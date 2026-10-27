package dev.openliveness.attestation

import android.content.Context
import android.provider.Settings
import dev.openliveness.AmbientConditions
import dev.openliveness.CDLSession
import dev.openliveness.CanonicalJson
import dev.openliveness.LayerScores
import dev.openliveness.layers.ChallengeResult
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Builds the signed CDLAttestation map. Falls back to stub layer scores
 * so the end-to-end relay handshake stays exercisable before the real
 * detection layers are wired in.
 *
 * Platform asymmetry — KNOWN LIMITATION until Phase 4 Play Integrity
 * Standard migration. On iOS, `liveness_decision` is baked into the App
 * Attest assertion client data via `CDLSession.generateAssertion`, so
 * forging the field at the SDK layer breaks the device-signed envelope.
 * On Android, Play Integrity classic only signs the server-supplied
 * nonce; `liveness_decision` here is covered by the outer `signature`
 * but not by the hardware-backed assertion. A server policy that
 * weighs the decision more when the device also signed it will see
 * different trust levels for iOS and Android. Phase 4 should migrate
 * Android to Play Integrity Standard and bind the decision via
 * `requestHash`; until then, treat the two platforms as equal-trust
 * from the server's perspective.
 */
object AttestationBuilder {

    /**
     * Builds the signed attestation and returns its canonical-JSON bytes
     * ready to POST. Returning bytes (rather than a Map) is load-bearing:
     * the server verifies the signature over canonical bytes, so sending
     * anything else risks wire-vs-signed drift and makes raw-byte audit
     * trails meaningless. Mirrors iOS AttestationBuilder.build.
     */
    fun build(
        context: Context,
        session: CDLSession,
        layerScores: LayerScores,
        ambient: AmbientConditions,
        deviceAttestation: String? = null,
        /**
         * Pre-computed decision. Callers that also sign client data (iOS App
         * Attest, future Android remote attestation) MUST pass the same string
         * they fed into the device-signed payload — otherwise the posted
         * attestation and the device assertion disagree on a load-bearing
         * field.
         *
         * H-1: `null` only falls back to the stub rule when `challengeResult`
         * is also null (the Phase-1 e2e stub path). A caller that passes a
         * real `challengeResult` without an explicit decision is in a real-
         * detector flow where the stub rule — which only inspects layer 2 —
         * would silently emit "pass" on catastrophic layer-3/4/5 scores.
         * Rather than paper over that mistake we throw at build time.
         */
        livenessDecision: String? = null,
        /**
         * How hardware attestation ended. `null` is accepted for callers
         * that pre-date the field; defaults to `UNSUPPORTED` so a missing
         * `deviceAttestation` is tagged correctly. The relay reads this to
         * tell "no Play Services" (expected) apart from "Play Integrity
         * threw" (potential downgrade attack).
         */
        hwAttestationStatus: CDLSession.HwStatus? = null,
        /**
         * Real Layer-2 outcome. When present, `challenge_response` is
         * gated on the sequencer's `completed` flag — a timed-out run
         * sends an empty string that relay checkChallenge rejects, so an
         * SDK caller that reaches `.build(...)` without actually running
         * the sequencer to completion cannot submit a passing-looking
         * payload. This is tamper-defense against SDK-internal bugs or
         * confused integrators, NOT replay defense (the enclosing payload
         * is signed; replay is defended at the signature layer). When
         * null, falls back to echoing the sequence for the Phase 1 stub
         * path still used by relay e2e tests.
         */
        challengeResult: ChallengeResult? = null,
        /**
         * On-wire marker that the submitting SDK took the Phase-1 stub
         * path (no landmark stream wired, layer 3/4/5 scores fabricated
         * from `stubLayerScores()`). The relay enforces policy — a
         * production environment is expected to refuse `stub_mode=true`
         * outside allow-listed CI session IDs. Stamping this on the
         * client is defense in depth: a hostile fork that rips out the
         * `allowStubMode` throw in CDLSession can still be caught by the
         * server-side policy because the wire bytes carry the truth.
         */
        stubMode: Boolean = false,
    ): ByteArray {

        // H-1: real detector output without an orchestrated decision is a
        // caller error — the stub rule only inspects layer 2 and would
        // silently emit "pass" when layers 3/4/5 are catastrophic. Fail
        // loud rather than ship a lying attestation.
        if (challengeResult != null && livenessDecision == null) {
            throw IllegalArgumentException(
                "AttestationBuilder.build: livenessDecision is required when challengeResult is provided; " +
                "route the result through LivenessOrchestrator.orchestrate() first."
            )
        }
        val decision = livenessDecision ?: stubLivenessDecision(layerScores)
        val effectiveHwStatus = hwAttestationStatus
            ?: if (deviceAttestation == null) CDLSession.HwStatus.UNSUPPORTED else CDLSession.HwStatus.OK
        // C-1 + M-1: derive assurance_level from THREE signals — device
        // attestation blob present, hw status OK, AND the orchestrator's
        // decision is "pass". Dropping the decision check (as the pre-fix
        // code did) could stamp "IAL2" onto a `fail` liveness result when
        // hw attestation happened to succeed — a hostile combination the
        // relay's checkAssurance also rejects, but we belt-and-suspenders
        // here so a direct caller of AttestationBuilder.build cannot emit
        // a self-contradicting attestation. "pass" is the only decision
        // string that unlocks IAL2; `inconclusive` + hw-ok stays
        // software_only to match the orchestrator's own output.
        val assuranceLevel =
            if (deviceAttestation != null
                && effectiveHwStatus == CDLSession.HwStatus.OK
                && decision == "pass"
            ) "IAL2" else "software_only"
        // M-3: `challenge_response` reflects what the SDK actually
        // detected (`firedSteps`), not just what the server prompted
        // (`session.payload.challenge_sequence`). On a `completed==true`
        // run these are equal by construction; on a timed-out run
        // `firedSteps` is the prefix the user actually got through, and
        // the relay's checkChallenge rejects the mismatched (partial)
        // string — same tamper-defense goal as before, but now the field
        // doesn't fabricate the server's prompt. When `challengeResult`
        // is null the Phase-1 stub path keeps echoing the server
        // sequence so e2e CI unchanged.
        val challengeResponse = when {
            challengeResult == null -> session.payload.challenge_sequence.joinToString(",")
            challengeResult.completed -> challengeResult.firedSteps.joinToString(",") { it.wire }
            else -> ""
        }
        val attestation = linkedMapOf<String, Any?>(
            "version" to "1.0",
            "session_id" to session.payload.session_id,
            "challenge_id" to session.payload.challenge_id,
            "challenge_response" to challengeResponse,
            "timestamp" to iso8601Now(),
            "liveness_decision" to decision,
            "assurance_level" to assuranceLevel,
            "hw_attestation_status" to effectiveHwStatus.wire,
            "layer_scores" to mapOf(
                "layer2_challenge_completed" to layerScores.layer2ChallengeCompleted,
                "layer2_challenge_score" to layerScores.layer2ChallengeScore.toDouble(),
                "layer3_pulse_detected" to layerScores.layer3PulseDetected,
                "layer3_bpm_in_range" to layerScores.layer3BpmInRange,
                "layer3_rppg_confidence" to layerScores.layer3RppgConfidence.toDouble(),
                "layer4_lbp_score" to layerScores.layer4LbpScore.toDouble(),
                "layer4_fourier_score" to layerScores.layer4FourierScore.toDouble(),
                "layer5_behavioral_score" to layerScores.layer5BehavioralScore.toDouble(),
                "layer5_microsaccade_detected" to layerScores.layer5MicrosaccadeDetected
            ),
            "device_platform" to "android",
            "stub_mode" to stubMode,
            "device_id_hash" to computeDeviceIdHash(context),
            "ambient_conditions" to mapOf(
                "low_light_detected" to ambient.lowLightDetected,
                "motion_detected" to ambient.motionDetected,
                "front_camera_confirmed" to ambient.frontCameraConfirmed
            ),
            "public_key" to SigningService.publicKeyBase64()
        )
        if (deviceAttestation != null) {
            attestation["device_attestation"] = deviceAttestation
        }

        // Sign the canonical bytes of the map without the signature field,
        // then re-canonicalize once the signature is installed so the wire
        // bytes are exactly what the server will re-canonicalize to verify
        // (strip signature → re-canonicalize → check against signed bytes).
        val canonicalForSigning = CanonicalJson.encode(attestation).toByteArray(Charsets.UTF_8)
        attestation["signature"] = SigningService.sign(canonicalForSigning)
        return CanonicalJson.encode(attestation).toByteArray(Charsets.UTF_8)
    }

    fun stubLayerScores(): LayerScores = LayerScores(
        layer2ChallengeCompleted = true,
        layer2ChallengeScore = 0.85f,
        layer3PulseDetected = true,
        layer3BpmInRange = true,
        layer3RppgConfidence = 0.85f,
        layer4LbpScore = 0.85f,
        layer4FourierScore = 0.85f,
        layer5BehavioralScore = 0.85f,
        layer5MicrosaccadeDetected = true
    )

    /**
     * STUB-only decision used by Phase 1 callers that haven't wired a real
     * LivenessOrchestrator yet. Public so CDLSession can feed the same
     * string into the signed assertion the device attestation covers,
     * keeping the posted attestation and the device-signed client data in
     * agreement when the stub path is active.
     *
     * NOT a replacement for LivenessOrchestrator.orchestrate(): this rule
     * only inspects `layer2ChallengeCompleted` and will emit "pass" even
     * when layer-3/4/5 scores are catastrophic, because stub scores from
     * `stubLayerScores()` are hardcoded to 0.85 and inspecting them would
     * make the stub e2e tests inconsistent. Any caller with real detector
     * output must route through LivenessOrchestrator — the renamed
     * `stub` prefix is a tripwire against importing this into real paths.
     * Changes here are part of the attestation wire contract — review
     * carefully.
     */
    fun stubLivenessDecision(layerScores: LayerScores): String =
        if (!layerScores.layer2ChallengeCompleted) "fail" else "pass"

    private fun iso8601Now(): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC))

    private fun computeDeviceIdHash(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(androidId.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
