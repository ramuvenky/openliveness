package dev.openliveness

import android.content.Context
import dev.openliveness.attestation.AttestationBuilder
import dev.openliveness.attestation.SigningService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.json.JSONObject

/**
 * Phase 2 drift tests for AttestationBuilder on Android. Mirrors
 * ios-sdk/Tests/OpenLivenessTests.swift so a change on one platform
 * without an equivalent change on the other trips its sibling test.
 *
 * SigningService is mocked out because it talks to AndroidKeyStore,
 * which is unavailable in JVM unit tests. The builder's own output
 * (field names, values, ordering) is what we're asserting here.
 */
class AttestationBuilderTest {

    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        context = mockk(relaxed = true)
        mockkObject(SigningService)
        every { SigningService.publicKeyBase64() } returns "stub-pub-key"
        every { SigningService.sign(any()) } returns "stub-sig"
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(SigningService)
    }

    private fun stubSession(): CDLSession {
        val payload = QRPayload(
            session_id = "s1",
            challenge_id = "c1",
            challenge_sequence = listOf("blink"),
            relay_ws = "wss://x",
            relay_http = "https://x",
            expires_at = "2099-01-01T00:00:00Z",
            version = "1.0"
        )
        return CDLSession(payload, context, playIntegrity = mockk(relaxed = true))
    }

    // H1 drift test — no device attestation → software_only + unsupported.
    @Test
    fun `builder stamps software_only and hw_attestation_status=unsupported when no deviceAttestation`() {
        val bytes = AttestationBuilder.build(
            context = context,
            session = stubSession(),
            layerScores = AttestationBuilder.stubLayerScores(),
            ambient = AmbientConditions(),
            deviceAttestation = null,
        )
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("software_only", obj.getString("assurance_level"))
        assertEquals("unsupported", obj.getString("hw_attestation_status"))
        assertFalse(obj.has("device_attestation"))
    }

    // H1 drift test — device attestation present → IAL2 + ok.
    @Test
    fun `builder stamps IAL2 and hw_attestation_status=ok when deviceAttestation present`() {
        val bytes = AttestationBuilder.build(
            context = context,
            session = stubSession(),
            layerScores = AttestationBuilder.stubLayerScores(),
            ambient = AmbientConditions(),
            deviceAttestation = "stub-play-integrity-token",
        )
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("IAL2", obj.getString("assurance_level"))
        assertEquals("ok", obj.getString("hw_attestation_status"))
        assertEquals("stub-play-integrity-token", obj.getString("device_attestation"))
    }

    // H1 drift test — explicit `failed` status survives into the output.
    // This is the downgrade-attack signal; a regression collapsing it back
    // into `unsupported` or dropping it entirely would mask forced
    // downgrades on the server side.
    @Test
    fun `builder preserves explicit hw_attestation_status=failed`() {
        val bytes = AttestationBuilder.build(
            context = context,
            session = stubSession(),
            layerScores = AttestationBuilder.stubLayerScores(),
            ambient = AmbientConditions(),
            deviceAttestation = null,
            hwAttestationStatus = CDLSession.HwStatus.FAILED,
        )
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("software_only", obj.getString("assurance_level"))
        assertEquals("failed", obj.getString("hw_attestation_status"))
    }

    // H3 drift test — explicit livenessDecision wins over the computed stub.
    // Load-bearing because a signed client data (future Phase 2+ platforms,
    // iOS App Attest) uses the same string; the builder must not recompute.
    @Test
    fun `builder uses explicit livenessDecision when provided`() {
        val bytes = AttestationBuilder.build(
            context = context,
            session = stubSession(),
            layerScores = AttestationBuilder.stubLayerScores(), // would compute "pass"
            ambient = AmbientConditions(),
            livenessDecision = "fail",
        )
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("fail", obj.getString("liveness_decision"))
    }

    // H3 drift test — pure helper. Flipping layer2ChallengeCompleted flips
    // the decision; locking this prevents a silent rule change that would
    // leave the device-signed assertion and the posted attestation
    // disagreeing on the same input.
    @Test
    fun `livenessDecision returns pass or fail based on layer2 completion`() {
        val scores = AttestationBuilder.stubLayerScores()
        assertEquals("pass", AttestationBuilder.livenessDecision(scores))
        scores.layer2ChallengeCompleted = false
        assertEquals("fail", AttestationBuilder.livenessDecision(scores))
    }

    // Mirror of iOS testAttestationBuilderReturnsCanonicalBytes: the first
    // alphabetical key in the output must be `ambient_conditions`, and the
    // bytes must round-trip through JSON parse + re-canonicalize unchanged.
    @Test
    fun `builder output is canonical JSON bytes`() {
        val bytes = AttestationBuilder.build(
            context = context,
            session = stubSession(),
            layerScores = AttestationBuilder.stubLayerScores(),
            ambient = AmbientConditions(),
        )
        val str = bytes.toString(Charsets.UTF_8)
        assertTrue(str.startsWith("""{"ambient_conditions":"""),
            "canonical bytes must open with the first alphabetical key, got: ${str.take(60)}")
        assertFalse(str.contains(" "), "canonical bytes must not contain whitespace")
        assertTrue(str.contains(""""signature":"""), "signature field missing")
    }

    // Drift guard — the HwStatus enum wire values are part of the
    // server-visible contract. A rename on this side without the
    // relay-side Zod enum would silently 400 every attestation.
    @Test
    fun `HwStatus wire values are the server-visible contract strings`() {
        assertEquals("ok", CDLSession.HwStatus.OK.wire)
        assertEquals("unsupported", CDLSession.HwStatus.UNSUPPORTED.wire)
        assertEquals("failed", CDLSession.HwStatus.FAILED.wire)
    }

    // M-1 pessimism — token present but status explicitly FAILED must NOT
    // emit IAL2. The real CDLSession.start flow cannot produce this pair,
    // but direct AttestationBuilder.build callers (tests, future
    // orchestrators) could; without this guard the SDK would hand out an
    // attestation the relay's checkAssurance invariant rejects. Pessimistic
    // defaulting to software_only keeps the on-wire output honest.
    @Test
    fun `builder emits software_only when deviceAttestation present but status is FAILED`() {
        val bytes = AttestationBuilder.build(
            context = context,
            session = stubSession(),
            layerScores = AttestationBuilder.stubLayerScores(),
            ambient = AmbientConditions(),
            deviceAttestation = "stub-play-integrity-token",
            hwAttestationStatus = CDLSession.HwStatus.FAILED,
        )
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("software_only", obj.getString("assurance_level"))
        assertEquals("failed", obj.getString("hw_attestation_status"))
    }

    // M-1 pessimism — status OK but no deviceAttestation to back it up.
    // The token-less OK claim is nonsense (the server has nothing to verify)
    // so the builder downgrades to software_only here rather than passing
    // the inconsistency through for the server to reject at checkAssurance.
    @Test
    fun `builder emits software_only when status is OK but deviceAttestation is null`() {
        val bytes = AttestationBuilder.build(
            context = context,
            session = stubSession(),
            layerScores = AttestationBuilder.stubLayerScores(),
            ambient = AmbientConditions(),
            deviceAttestation = null,
            hwAttestationStatus = CDLSession.HwStatus.OK,
        )
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("software_only", obj.getString("assurance_level"))
        // The status field itself is preserved verbatim — the pessimism only
        // kicks in on assurance_level. Preserving the raw status keeps the
        // caller-reported value visible on the wire for triage.
        assertEquals("ok", obj.getString("hw_attestation_status"))
    }

    // M-1 companion — explicit UNSUPPORTED with a non-null token (odd pair
    // a buggy caller might produce) also stays pessimistic.
    @Test
    fun `builder emits software_only when deviceAttestation present but status is UNSUPPORTED`() {
        val bytes = AttestationBuilder.build(
            context = context,
            session = stubSession(),
            layerScores = AttestationBuilder.stubLayerScores(),
            ambient = AmbientConditions(),
            deviceAttestation = "stub-token",
            hwAttestationStatus = CDLSession.HwStatus.UNSUPPORTED,
        )
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("software_only", obj.getString("assurance_level"))
        assertEquals("unsupported", obj.getString("hw_attestation_status"))
    }
}
