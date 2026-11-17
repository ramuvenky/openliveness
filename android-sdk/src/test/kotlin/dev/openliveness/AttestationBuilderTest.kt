package dev.openliveness

import android.content.Context
import dev.openliveness.attestation.AttestationBuilder
import dev.openliveness.attestation.SigningService
import dev.openliveness.layers.Challenge
import dev.openliveness.layers.ChallengeResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    // Infrastructure guard — proves the real org.json is on the test
    // classpath, not Android's stub. Under isReturnDefaultValues=true the
    // stub's JSONObject.getString returns null, which silently flips every
    // assertEquals("x", obj.getString(...)) in this file into a failure
    // with no indication the parser itself is the problem. If this test
    // trips, re-add `testImplementation("org.json:json:...")` to
    // build.gradle.kts rather than chasing the field-level failures.
    @Test
    fun `org dot json is the real impl on the test classpath`() {
        val obj = JSONObject("""{"k":"v"}""")
        assertEquals("v", obj.getString("k"))
    }

    private fun fakeSession(): CDLSession {
        val payload = QRPayload(
            session_id = "s1",
            challenge_id = "c1",
            challenge_sequence = listOf("blink"),
            relay_ws = "wss://x",
            relay_http = "https://x",
            expires_at = "2099-01-01T00:00:00Z",
            version = "1.0"
        )
        val (stream, layer3, layer4) = fakeWiring()
        return CDLSession(
            payload = payload,
            context = context,
            playIntegrity = mockk(relaxed = true),
            landmarkStream = stream,
            layer3 = layer3,
            layer4 = layer4,
        )
    }

    // Neutral score fixture. Replaces the historical stubLayerScores()
    // helper — tests that need custom values override individual fields
    // via .copy().
    private fun neutralScores(): LayerScores = LayerScores(
        layer2ChallengeCompleted = true,
        layer2ChallengeScore = 0.85f,
        layer3PulseDetected = true,
        layer3BpmInRange = true,
        layer3RppgConfidence = 0.85f,
        layer4LbpScore = 0.85f,
        layer4FourierScore = 0.85f,
        layer5BehavioralScore = 0.85f,
        layer5MicrosaccadeDetected = true,
    )

    private fun completedResult(): ChallengeResult = ChallengeResult(
        completed = true,
        score = 1.0f,
        elapsedSeconds = 0.1,
        stepsCompleted = 1,
        totalSteps = 1,
        firedSteps = listOf(Challenge.BLINK),
    )

    // H1 drift test — no device attestation → software_only + unsupported.
    @Test
    fun `builder stamps software_only and hw_attestation_status=unsupported when no deviceAttestation`() {
        val bytes = AttestationBuilder.build(
            context = context,
            session = fakeSession(),
            layerScores = neutralScores(),
            ambient = AmbientConditions(),
            livenessDecision = "pass",
            challengeResult = completedResult(),
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
            session = fakeSession(),
            layerScores = neutralScores(),
            ambient = AmbientConditions(),
            livenessDecision = "pass",
            challengeResult = completedResult(),
            deviceAttestation = "stub-play-integrity-token",
        )
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("IAL2", obj.getString("assurance_level"))
        assertEquals("ok", obj.getString("hw_attestation_status"))
        assertEquals("stub-play-integrity-token", obj.getString("device_attestation"))
    }

    // H1 drift test — explicit `failed` status survives into the output.
    @Test
    fun `builder preserves explicit hw_attestation_status=failed`() {
        val bytes = AttestationBuilder.build(
            context = context,
            session = fakeSession(),
            layerScores = neutralScores(),
            ambient = AmbientConditions(),
            livenessDecision = "pass",
            challengeResult = completedResult(),
            deviceAttestation = null,
            hwAttestationStatus = CDLSession.HwStatus.FAILED,
        )
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("software_only", obj.getString("assurance_level"))
        assertEquals("failed", obj.getString("hw_attestation_status"))
    }

    // The livenessDecision parameter must be echoed verbatim into the
    // output — load-bearing because any signed client-data envelope
    // (iOS App Attest, future Android remote attestation) is computed
    // over the same string.
    @Test
    fun `builder uses explicit livenessDecision verbatim`() {
        val bytes = AttestationBuilder.build(
            context = context,
            session = fakeSession(),
            layerScores = neutralScores(),
            ambient = AmbientConditions(),
            livenessDecision = "fail",
            challengeResult = completedResult(),
        )
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("fail", obj.getString("liveness_decision"))
    }

    // Mirror of iOS testAttestationBuilderReturnsCanonicalBytes: the first
    // alphabetical key in the output must be `ambient_conditions`, and the
    // bytes must round-trip through JSON parse + re-canonicalize unchanged.
    @Test
    fun `builder output is canonical JSON bytes`() {
        val bytes = AttestationBuilder.build(
            context = context,
            session = fakeSession(),
            layerScores = neutralScores(),
            ambient = AmbientConditions(),
            livenessDecision = "pass",
            challengeResult = completedResult(),
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
    // attestation the relay's checkAssurance invariant rejects.
    @Test
    fun `builder emits software_only when deviceAttestation present but status is FAILED`() {
        val bytes = AttestationBuilder.build(
            context = context,
            session = fakeSession(),
            layerScores = neutralScores(),
            ambient = AmbientConditions(),
            livenessDecision = "pass",
            challengeResult = completedResult(),
            deviceAttestation = "stub-play-integrity-token",
            hwAttestationStatus = CDLSession.HwStatus.FAILED,
        )
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("software_only", obj.getString("assurance_level"))
        assertEquals("failed", obj.getString("hw_attestation_status"))
    }

    // M-1 pessimism — status OK but no deviceAttestation to back it up.
    @Test
    fun `builder emits software_only when status is OK but deviceAttestation is null`() {
        val bytes = AttestationBuilder.build(
            context = context,
            session = fakeSession(),
            layerScores = neutralScores(),
            ambient = AmbientConditions(),
            livenessDecision = "pass",
            challengeResult = completedResult(),
            deviceAttestation = null,
            hwAttestationStatus = CDLSession.HwStatus.OK,
        )
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("software_only", obj.getString("assurance_level"))
        assertEquals("ok", obj.getString("hw_attestation_status"))
    }

    // C-1 — assurance_level must downgrade to software_only when the
    // orchestrator decided `fail` or `inconclusive`, even if hw
    // attestation succeeded. Belt-and-suspenders at the SDK layer so
    // direct callers can't emit the self-contradicting attestation.
    @Test
    fun `assurance_level downgrades to software_only when livenessDecision is fail even if hw ok`() {
        val bytes = AttestationBuilder.build(
            context = context,
            session = fakeSession(),
            layerScores = neutralScores(),
            ambient = AmbientConditions(),
            deviceAttestation = "stub-token",
            livenessDecision = "fail",
            challengeResult = completedResult(),
            hwAttestationStatus = CDLSession.HwStatus.OK,
        )
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("fail", obj.getString("liveness_decision"))
        assertEquals("software_only", obj.getString("assurance_level"))
        assertEquals("ok", obj.getString("hw_attestation_status"))
    }

    @Test
    fun `assurance_level downgrades to software_only when livenessDecision is inconclusive even if hw ok`() {
        val bytes = AttestationBuilder.build(
            context = context,
            session = fakeSession(),
            layerScores = neutralScores(),
            ambient = AmbientConditions(),
            deviceAttestation = "stub-token",
            livenessDecision = "inconclusive",
            challengeResult = completedResult(),
            hwAttestationStatus = CDLSession.HwStatus.OK,
        )
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("software_only", obj.getString("assurance_level"))
    }

    // M-3 — challenge_response reflects what the SDK actually detected
    // (`firedSteps`), not just what the server prompted. On completed==true
    // these are equal by construction; the test pins the on-wire join.
    @Test
    fun `challenge_response is firedSteps wire-joined when challengeResult is complete`() {
        val result = ChallengeResult(
            completed = true,
            score = 1.0f,
            elapsedSeconds = 0.1,
            stepsCompleted = 3,
            totalSteps = 3,
            firedSteps = listOf(Challenge.BLINK, Challenge.LOOK_LEFT, Challenge.SMILE)
        )
        val bytes = AttestationBuilder.build(
            context = context,
            session = fakeSession(),
            layerScores = neutralScores(),
            ambient = AmbientConditions(),
            livenessDecision = "pass",
            challengeResult = result,
        )
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("blink,look_left,smile", obj.getString("challenge_response"))
    }

    // v0.2.0 — on a timed-out run the builder now emits the partial
    // firedSteps prefix (not ""); the relay's checkChallenge still
    // rejects since the prefix won't equal the issued sequence, and the
    // wire error becomes ERR_CHALLENGE_MISMATCH (meaningful) instead of
    // ERR_SCHEMA_INVALID (opaque). Pre-fix this test pinned "" — the
    // stub path masked the schema trap; stub removal exposed it. The
    // relay has a sibling test
    // (`attestation.test.ts: rejects a partial challenge_response ...`)
    // that pins the semantic rejection of this exact "blink" payload.
    @Test
    fun `challenge_response carries partial firedSteps on timed-out run (no empty-string short-circuit)`() {
        val result = ChallengeResult(
            completed = false,
            score = 0.0f,
            elapsedSeconds = 8.1,
            stepsCompleted = 1,
            totalSteps = 3,
            firedSteps = listOf(Challenge.BLINK)
        )
        val bytes = AttestationBuilder.build(
            context = context,
            session = fakeSession(),
            layerScores = neutralScores(),
            ambient = AmbientConditions(),
            livenessDecision = "fail",
            challengeResult = result,
        )
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("blink", obj.getString("challenge_response"))
    }

    // Zero-progress run — user did literally nothing before timeout.
    // firedSteps is empty, challenge_response is "". The relay's zod
    // schema is now permissive (v0.2.0 drops .min(1)) so this payload
    // reaches checkChallenge, which rejects it with
    // ERR_CHALLENGE_MISMATCH instead of the pre-fix ERR_SCHEMA_INVALID.
    @Test
    fun `challenge_response is empty string on zero-progress timed-out run`() {
        val result = ChallengeResult(
            completed = false,
            score = 0.0f,
            elapsedSeconds = 8.1,
            stepsCompleted = 0,
            totalSteps = 3,
            firedSteps = emptyList()
        )
        val bytes = AttestationBuilder.build(
            context = context,
            session = fakeSession(),
            layerScores = neutralScores(),
            ambient = AmbientConditions(),
            livenessDecision = "fail",
            challengeResult = result,
        )
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("", obj.getString("challenge_response"))
    }

    // M-1 companion — explicit UNSUPPORTED with a non-null token (odd pair
    // a buggy caller might produce) also stays pessimistic.
    @Test
    fun `builder emits software_only when deviceAttestation present but status is UNSUPPORTED`() {
        val bytes = AttestationBuilder.build(
            context = context,
            session = fakeSession(),
            layerScores = neutralScores(),
            ambient = AmbientConditions(),
            livenessDecision = "pass",
            challengeResult = completedResult(),
            deviceAttestation = "stub-token",
            hwAttestationStatus = CDLSession.HwStatus.UNSUPPORTED,
        )
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("software_only", obj.getString("assurance_level"))
        assertEquals("unsupported", obj.getString("hw_attestation_status"))
    }

    // Guard: the stub_mode field must NOT appear on the wire. Prior
    // versions stamped a boolean here; relay schema no longer accepts it.
    @Test
    fun `builder output omits stub_mode field entirely`() {
        val bytes = AttestationBuilder.build(
            context = context,
            session = fakeSession(),
            layerScores = neutralScores(),
            ambient = AmbientConditions(),
            livenessDecision = "pass",
            challengeResult = completedResult(),
        )
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        assertFalse(obj.has("stub_mode"), "stub_mode must be removed from the wire contract")
    }
}
