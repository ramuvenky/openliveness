package dev.openliveness

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Locks in the Medium-severity fixes for RelayClient:
 *  - malformed /complete responses must throw CDLException, not silently
 *    coerce to accepted=false (`parseCompleteResponse`).
 *  - `submitAttestation` must take canonical bytes and send them verbatim
 *    on the wire, so the server verifies the signature over exactly what
 *    the client signed (no Moshi re-serialization in between).
 */
class RelayClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: RelayClient

    private val attestationBytes = """{"ping":"pong"}""".toByteArray(Charsets.UTF_8)

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = RelayClient(server.url("/").toString().trimEnd('/'))
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    // MARK: - submitAttestation (integration: HTTP + parse)

    @Test
    fun `submitAttestation returns parsed response on success`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"accepted":true,"attestation_token":"tok-xyz"}""")
        )
        val resp = client.submitAttestation("s1", attestationBytes)
        assertEquals(true, resp.accepted)
        assertEquals("tok-xyz", resp.attestationToken)
    }

    @Test
    fun `submitAttestation accepts null attestation_token`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"accepted":false}"""))
        val resp = client.submitAttestation("s1", attestationBytes)
        assertEquals(false, resp.accepted)
        assertNull(resp.attestationToken)
    }

    @Test
    fun `submitAttestation sends the attestation bytes verbatim on the wire`() = runBlocking {
        // The audit trail requires the request body to be byte-identical to
        // what AttestationBuilder produced: raw-byte logs on the server must
        // match the bytes the client committed to. Any Moshi/JSONEncoder
        // re-serialization in RelayClient would break that guarantee even
        // though the signature itself would still verify (the server strips
        // `signature` and re-canonicalizes).
        server.enqueue(MockResponse().setBody("""{"accepted":true}"""))
        client.submitAttestation("s1", attestationBytes)
        val recorded = server.takeRequest()
        assertArrayEquals(attestationBytes, recorded.body.readByteArray())
        assertEquals("application/json", recorded.getHeader("Content-Type"))
    }

    @Test
    fun `submitAttestation throws when accepted is missing`() {
        server.enqueue(MockResponse().setBody("""{"attestation_token":"tok"}"""))
        val ex = assertThrows(CDLException::class.java) {
            runBlocking { client.submitAttestation("s1", attestationBytes) }
        }
        assertTrue(ex.message!!.contains("accepted"))
    }

    @Test
    fun `submitAttestation throws when accepted is wrong type`() {
        server.enqueue(MockResponse().setBody("""{"accepted":"yes"}"""))
        val ex = assertThrows(CDLException::class.java) {
            runBlocking { client.submitAttestation("s1", attestationBytes) }
        }
        assertTrue(ex.message!!.contains("accepted"))
    }

    @Test
    fun `submitAttestation throws when attestation_token is wrong type`() {
        server.enqueue(
            MockResponse().setBody("""{"accepted":true,"attestation_token":123}""")
        )
        val ex = assertThrows(CDLException::class.java) {
            runBlocking { client.submitAttestation("s1", attestationBytes) }
        }
        assertTrue(ex.message!!.contains("attestation_token"))
    }

    @Test
    fun `submitAttestation surfaces non-2xx with body`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val ex = assertThrows(CDLException::class.java) {
            runBlocking { client.submitAttestation("s1", attestationBytes) }
        }
        assertTrue(ex.message!!.contains("500"))
        assertTrue(ex.message!!.contains("boom"))
    }

    // MARK: - parseCompleteResponse (unit)
    //
    // Covers the pure-parse half without the HTTP round-trip so each error
    // branch is pinned individually. The integration tests above exercise
    // the glue; these exercise the branches.

    @Test
    fun `parseCompleteResponse rejects empty body`() {
        val ex = assertThrows(CDLException::class.java) {
            client.parseCompleteResponse("")
        }
        assertTrue(ex.message!!.contains("empty"))
    }

    @Test
    fun `parseCompleteResponse rejects non-JSON body`() {
        val ex = assertThrows(CDLException::class.java) {
            client.parseCompleteResponse("not json at all")
        }
        assertTrue(ex.message!!.contains("malformed"))
    }

    // MARK: - Phase 2 — Play Integrity nonce round-trip
    //
    // CDLSession.requestDeviceAttestation() POSTs to the per-session
    // integrity-nonce endpoint and feeds the returned nonce into Play
    // Integrity. These pin the client half of that round-trip so wiring
    // regressions (wrong path, missing nonce, HTTP error) surface here
    // instead of silently downgrading every session to software_only.

    @Test
    fun `requestIntegrityNonce returns parsed nonce on success`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"nonce":"nonce-abc"}"""))
        val nonce = client.requestIntegrityNonce("sess-1")
        assertEquals("nonce-abc", nonce)
        val recorded = server.takeRequest()
        assertEquals("/cdl/session/sess-1/integrity-nonce", recorded.path)
        assertEquals("POST", recorded.method)
        // Body must be an empty JSON object: the relay keys the nonce on
        // session_id (from the URL), not on anything in the body, so a
        // regression that smuggled fields in here would mask a routing
        // bug. Content-Type must also match what the relay expects.
        assertEquals("{}", recorded.body.readUtf8())
        assertTrue(recorded.getHeader("Content-Type")?.contains("application/json") == true)
    }

    @Test
    fun `requestIntegrityNonce throws when nonce field is missing`() {
        server.enqueue(MockResponse().setBody("""{}"""))
        val ex = assertThrows(CDLException::class.java) {
            runBlocking { client.requestIntegrityNonce("sess-1") }
        }
        assertTrue(ex.message!!.contains("nonce"))
    }

    @Test
    fun `requestIntegrityNonce surfaces non-2xx with body`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate-limited"))
        val ex = assertThrows(CDLException::class.java) {
            runBlocking { client.requestIntegrityNonce("sess-1") }
        }
        assertTrue(ex.message!!.contains("429"))
    }
}
