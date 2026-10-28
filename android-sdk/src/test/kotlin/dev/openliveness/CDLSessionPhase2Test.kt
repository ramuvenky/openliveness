package dev.openliveness

import android.content.Context
import com.google.android.play.core.integrity.IntegrityServiceException
import com.google.android.play.core.integrity.model.IntegrityErrorCode
import dev.openliveness.attestation.PlayIntegrityClient
import dev.openliveness.attestation.PlayIntegrityService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Phase 2 drift tests for CDLSession.requestDeviceAttestation and the
 * applicationContext capture. The behaviors locked in here are the ones
 * the security review flagged as load-bearing:
 *
 *  - C1 (critical): CancellationException MUST re-throw. A regression
 *    collapsing it into HwStatus.FAILED would silently break structured
 *    concurrency — parent scopes would believe the child completed
 *    normally and continue with a half-cancelled flow.
 *
 *  - H1 (high): the OK / FAILED split is the downgrade-attack signal.
 *    A failure must emit (null, FAILED) so the relay can distinguish
 *    "device cannot attest" from "device could attest but was prevented";
 *    the latter clustering on previously-ok devices is the signal.
 *
 *  - L3 (low): the session must pin applicationContext, not the passed
 *    Activity. PlayIntegrityService captures this reference and a leak
 *    here would retain the whole view hierarchy for the Play Integrity
 *    call's duration.
 *
 * MockWebServer stands in for the relay so requestIntegrityNonce routes
 * through real HTTP. PlayIntegrityService is mocked with coEvery because
 * requestIntegrityToken is suspend and the real impl talks to Google
 * Play Services (unavailable in JVM tests).
 */
class CDLSessionPhase2Test {

    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var playIntegrity: PlayIntegrityClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        context = mockk(relaxed = true)
        playIntegrity = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun session(): CDLSession {
        val payload = QRPayload(
            session_id = "s1",
            challenge_id = "c1",
            challenge_sequence = listOf("blink"),
            relay_ws = "wss://unused",
            relay_http = server.url("/").toString().trimEnd('/'),
            expires_at = "2099-01-01T00:00:00Z",
            version = "1.0",
        )
        return CDLSession(payload, context, playIntegrity = playIntegrity)
    }

    // H1 drift test — happy path. Both calls succeed → (token, OK).
    @Test
    fun `requestDeviceAttestation returns OK with token on success`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"nonce":"n1"}"""))
        coEvery { playIntegrity.requestIntegrityToken("n1") } returns "integrity-tok"

        val result = session().requestDeviceAttestation()

        assertEquals("integrity-tok", result.token)
        assertEquals(CDLSession.HwStatus.OK, result.status)
    }

    // H1 drift test — Play Integrity failure path. Non-cancellation
    // exception must collapse to (null, FAILED), not propagate: start()
    // relies on this to keep the session alive and let the server see the
    // downgrade signal.
    @Test
    fun `requestDeviceAttestation returns FAILED when Play Integrity throws`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"nonce":"n1"}"""))
        coEvery { playIntegrity.requestIntegrityToken(any()) } throws RuntimeException("device not Play-certified")

        val result = session().requestDeviceAttestation()

        assertNull(result.token)
        assertEquals(CDLSession.HwStatus.FAILED, result.status)
    }

    // H1 drift test — relay nonce failure path. If the relay itself can't
    // issue a nonce (HTTP 500, network error, whatever), the method must
    // still return FAILED rather than throw — same reason as above.
    @Test
    fun `requestDeviceAttestation returns FAILED when integrity-nonce call fails`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val result = session().requestDeviceAttestation()

        assertNull(result.token)
        assertEquals(CDLSession.HwStatus.FAILED, result.status)
    }

    // C1 drift test (critical) — CancellationException must re-throw.
    // Catching Exception without a prior catch for CancellationException
    // would break structured concurrency: the parent coroutine would see
    // the child "complete" with FAILED instead of unwinding. The explicit
    // ordering of catch blocks in requestDeviceAttestation is what this
    // test pins down.
    @Test
    fun `requestDeviceAttestation re-throws CancellationException`() {
        server.enqueue(MockResponse().setBody("""{"nonce":"n1"}"""))
        coEvery { playIntegrity.requestIntegrityToken(any()) } throws CancellationException("cancelled")

        assertThrows(CancellationException::class.java) {
            runBlocking { session().requestDeviceAttestation() }
        }
    }

    // H1 drift test — a Play Integrity failure whose underlying error code
    // means "device cannot attest at all" must bucket as UNSUPPORTED, not
    // FAILED. Without this, every non-Play-Services Android device (AOSP,
    // Huawei, GrapheneOS, enterprise ROMs) deterministically lands in the
    // bucket the relay clusters on as a downgrade-attack signal — making
    // the signal useless. Pin the mapping so a regression that collapsed
    // the two branches shows up here.
    @Test
    fun `requestDeviceAttestation returns UNSUPPORTED when Play Integrity classifies as unavailable`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"nonce":"n1"}"""))
        // PlayIntegrityUnavailableException is what PlayIntegrityService.classify
        // emits for IntegrityErrorCode.PLAY_SERVICES_NOT_FOUND et al. Simulate
        // the already-classified exception here; the service-level mapping is
        // pinned separately in PlayIntegrityServiceTest.
        coEvery { playIntegrity.requestIntegrityToken(any()) } throws
            PlayIntegrityUnavailableException("Play Integrity unavailable (code=-9)")

        val result = session().requestDeviceAttestation()

        assertNull(result.token)
        assertEquals(CDLSession.HwStatus.UNSUPPORTED, result.status)
    }

    // H1 drift test — a transient / API-side failure must stay in FAILED
    // so sustained patterns still reach the clustering heuristic. The
    // UNSUPPORTED bucket is reserved for environmental impossibility only.
    @Test
    fun `requestDeviceAttestation keeps FAILED for non-unavailable classifications`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"nonce":"n1"}"""))
        coEvery { playIntegrity.requestIntegrityToken(any()) } throws
            CDLException("Play Integrity failed (code=-100): internal")

        val result = session().requestDeviceAttestation()

        assertNull(result.token)
        assertEquals(CDLSession.HwStatus.FAILED, result.status)
    }

    // H1 service-level drift — PlayIntegrityService.classify is the hinge
    // between "environmental, UNSUPPORTED" and "failed, FAILED". The exact
    // code set is load-bearing: adding NETWORK_ERROR here would mute the
    // signal, dropping PLAY_SERVICES_NOT_FOUND would re-break the AOSP case.
    @Test
    fun `classify maps PLAY_SERVICES_NOT_FOUND to PlayIntegrityUnavailableException`() {
        val e = fakeIntegrityException(IntegrityErrorCode.PLAY_SERVICES_NOT_FOUND)
        val classified = PlayIntegrityService.classify(e)
        assert(classified is PlayIntegrityUnavailableException) {
            "expected PlayIntegrityUnavailableException, got ${classified.javaClass.name}"
        }
    }

    @Test
    fun `classify maps CANNOT_BIND_TO_SERVICE to PlayIntegrityUnavailableException`() {
        val e = fakeIntegrityException(IntegrityErrorCode.CANNOT_BIND_TO_SERVICE)
        assert(PlayIntegrityService.classify(e) is PlayIntegrityUnavailableException)
    }

    @Test
    fun `classify maps API_NOT_AVAILABLE to PlayIntegrityUnavailableException`() {
        val e = fakeIntegrityException(IntegrityErrorCode.API_NOT_AVAILABLE)
        assert(PlayIntegrityService.classify(e) is PlayIntegrityUnavailableException)
    }

    // Transient / adversarial-adjacent codes stay in the FAILED bucket.
    // NETWORK_ERROR could mask a hostile network and INTERNAL_ERROR could
    // mask SDK-level failure — both deserve to reach the clustering heuristic.
    @Test
    fun `classify keeps NETWORK_ERROR in CDLException (FAILED bucket)`() {
        val e = fakeIntegrityException(IntegrityErrorCode.NETWORK_ERROR)
        val classified = PlayIntegrityService.classify(e)
        assert(classified is CDLException && classified !is PlayIntegrityUnavailableException) {
            "expected plain CDLException, got ${classified.javaClass.name}"
        }
    }

    @Test
    fun `classify falls back to CDLException for non-IntegrityServiceException errors`() {
        val classified = PlayIntegrityService.classify(RuntimeException("generic"))
        assert(classified is CDLException && classified !is PlayIntegrityUnavailableException)
    }

    private fun fakeIntegrityException(code: Int): IntegrityServiceException =
        mockk(relaxed = true) {
            every { errorCode } returns code
            every { message } returns "test-code-$code"
        }

    // L3 drift test — the session must pin applicationContext. Verifies
    // the constructor reads applicationContext off the passed Context; a
    // regression that stored the raw Context would leak an Activity for
    // the lifetime of the (potentially multi-second) Play Integrity call.
    @Test
    fun `session captures applicationContext not the passed context`() {
        val activityCtx = mockk<Context>(relaxed = true)
        val appCtx = mockk<Context>(relaxed = true)
        every { activityCtx.applicationContext } returns appCtx

        val payload = QRPayload(
            session_id = "s1",
            challenge_id = "c1",
            challenge_sequence = listOf("blink"),
            relay_ws = "wss://unused",
            relay_http = server.url("/").toString().trimEnd('/'),
            expires_at = "2099-01-01T00:00:00Z",
            version = "1.0",
        )
        CDLSession(payload, activityCtx, playIntegrity = playIntegrity)

        verify { activityCtx.applicationContext }
    }
}
