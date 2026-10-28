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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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
    fun `stubLivenessDecision returns pass or fail based on layer2 completion`() {
        val scores = AttestationBuilder.stubLayerScores()
        assertEquals("pass", AttestationBuilder.stubLivenessDecision(scores))
        scores.layer2ChallengeCompleted = false
        assertEquals("fail", AttestationBuilder.stubLivenessDecision(scores))
    }

    // S-1 drift guard — the stub layer scores MUST compose with the real
    // LivenessOrchestrator to produce "pass" (not inconclusive/fail). If a
    // future tuning of weights or thresholds breaks that, the Phase-1 e2e
    // relay tests that still take the stub path would start failing with
    // the diagnostic buried in the server response. Locking this here
    // catches the drift at Android-SDK unit-test time, where the fix
    // (bump the stub scores OR adjust the orchestrator) is obvious.
    @Test
    fun `stub layer scores compose with real orchestrator to decision=pass`() {
        val stub = AttestationBuilder.stubLayerScores()
        val outcome = LivenessOrchestrator().orchestrate(stub, AmbientConditions())
        assertEquals("pass", outcome.decision,
            "stub scores drifted below pass threshold — stub e2e path would start failing")
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

    // S-NEW-2 (c) — the on-wire stub_mode marker. Defaults to false so a
    // caller that forgets to stamp it doesn't accidentally pass as "stub"
    // on the server. The CDLSession.start path flips this to true when
    // the Phase-1 stub liveness ran (challengeResult == null); the server
    // enforces policy on this field, so a regression that stops emitting
    // it (or hardcodes false) would silently let the stub path reach
    // production.
    @Test
    fun `builder stamps stub_mode=false by default`() {
        val bytes = AttestationBuilder.build(
            context = context,
            session = stubSession(),
            layerScores = AttestationBuilder.stubLayerScores(),
            ambient = AmbientConditions(),
        )
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        assertTrue(obj.has("stub_mode"), "stub_mode field must be present on every attestation")
        assertFalse(obj.getBoolean("stub_mode"),
            "default stub_mode must be false — opt-in only, never on by default")
    }

    @Test
    fun `builder stamps stub_mode=true when caller sets it`() {
        val bytes = AttestationBuilder.build(
            context = context,
            session = stubSession(),
            layerScores = AttestationBuilder.stubLayerScores(),
            ambient = AmbientConditions(),
            stubMode = true,
        )
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        assertTrue(obj.getBoolean("stub_mode"),
            "stub_mode must be true on the wire when the SDK ran the stub path")
    }

    // C-1 — assurance_level must downgrade to software_only when the
    // orchestrator decided `fail` or `inconclusive`, even if hw
    // attestation succeeded. Before the fix the builder emitted "IAL2"
    // off (deviceAttestation != null && hwOk) alone — i.e. an attestation
    // claiming the strongest assurance level while the SDK's own
    // liveness decision was `fail`. The relay's checkAssurance also
    // rejects that pair, but we belt-and-suspenders at the SDK layer
    // so direct callers can't emit the self-contradicting attestation.
    @Test
    fun `assurance_level downgrades to software_only when livenessDecision is fail even if hw ok`() {
        val bytes = AttestationBuilder.build(
            context = context,
            session = stubSession(),
            layerScores = AttestationBuilder.stubLayerScores(),
            ambient = AmbientConditions(),
            deviceAttestation = "stub-token",
            livenessDecision = "fail",
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
            session = stubSession(),
            layerScores = AttestationBuilder.stubLayerScores(),
            ambient = AmbientConditions(),
            deviceAttestation = "stub-token",
            livenessDecision = "inconclusive",
            hwAttestationStatus = CDLSession.HwStatus.OK,
        )
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("software_only", obj.getString("assurance_level"))
    }

    // H-1 — a caller that has a real `challengeResult` MUST also pass an
    // explicit `livenessDecision`. The old default routed through
    // `stubLivenessDecision` which only inspects layer 2; a catastrophic
    // layer-3/4/5 score would silently become "pass" on the wire.
    @Test
    fun `builder throws when challengeResult is provided without livenessDecision`() {
        val result = ChallengeResult(
            completed = true,
            score = 1.0f,
            elapsedSeconds = 0.1,
            stepsCompleted = 1,
            totalSteps = 1,
            firedSteps = listOf(Challenge.BLINK)
        )
        assertThrows(IllegalArgumentException::class.java) {
            AttestationBuilder.build(
                context = context,
                session = stubSession(),
                layerScores = AttestationBuilder.stubLayerScores(),
                ambient = AmbientConditions(),
                challengeResult = result,
                // livenessDecision deliberately omitted
            )
        }
    }

    // H-1 pair — null challengeResult + null livenessDecision keeps the
    // Phase-1 stub path alive (e2e CI still routes through this).
    @Test
    fun `builder falls back to stub decision when both challengeResult and livenessDecision are null`() {
        val bytes = AttestationBuilder.build(
            context = context,
            session = stubSession(),
            layerScores = AttestationBuilder.stubLayerScores(),
            ambient = AmbientConditions(),
        )
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("pass", obj.getString("liveness_decision"))
    }

    // M-3 — challenge_response reflects what the SDK actually detected
    // (`firedSteps`), not just what the server prompted
    // (`session.challenge_sequence`). On completed==true these are equal
    // by construction. The on-wire test fixes `firedSteps` to a known
    // order and asserts the comma-join matches.
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
            session = stubSession(),
            layerScores = AttestationBuilder.stubLayerScores(),
            ambient = AmbientConditions(),
            livenessDecision = "pass",
            challengeResult = result,
        )
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("blink,look_left,smile", obj.getString("challenge_response"))
    }

    // M-3 — timed-out run still sends empty string so relay's
    // checkChallenge rejects the half-done result (unchanged from
    // pre-fix). Keeping this test wires in the new field surface.
    @Test
    fun `challenge_response is empty string on timed-out run even with partial firedSteps`() {
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
            session = stubSession(),
            layerScores = AttestationBuilder.stubLayerScores(),
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
