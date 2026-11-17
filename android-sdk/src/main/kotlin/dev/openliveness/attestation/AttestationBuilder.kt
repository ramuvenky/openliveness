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
 * Builds the signed CDLAttestation map.
 *
 * Platform parity (post-Standard migration): on iOS, `liveness_decision`
 * is baked into the App Attest assertion client data; on Android, it is
 * bound into the Play Integrity Standard verdict via `requestHash`
 * (SHA-256 of `nonce|decision|session_id|challenge_id`). Forging the
 * decision at the SDK layer now breaks the hardware-signed envelope on
 * both platforms — the server policy no longer needs to assign asymmetric
 * trust weights to iOS vs. Android.
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
         * field. Required: the orchestrator must produce this before the
         * attestation is built.
         */
        livenessDecision: String,
        /**
         * How hardware attestation ended. `null` is accepted for callers
         * that pre-date the field; defaults to `UNSUPPORTED` so a missing
         * `deviceAttestation` is tagged correctly. The relay reads this to
         * tell "no Play Services" (expected) apart from "Play Integrity
         * threw" (potential downgrade attack).
         */
        hwAttestationStatus: CDLSession.HwStatus? = null,
        /**
         * Real Layer-2 outcome. `challenge_response` is gated on the
         * sequencer's `completed` flag — a timed-out run sends an empty
         * string that relay checkChallenge rejects, so an SDK caller that
         * reaches `.build(...)` without actually running the sequencer to
         * completion cannot submit a passing-looking payload. This is
         * tamper-defense against SDK-internal bugs or confused integrators,
         * NOT replay defense (the enclosing payload is signed; replay is
         * defended at the signature layer).
         */
        challengeResult: ChallengeResult,
    ): ByteArray {

        val decision = livenessDecision
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
        // `challenge_response` reflects what the SDK actually detected
        // (`firedSteps`), not what the server prompted — the relay's
        // checkChallenge compares them. Emitted unconditionally so a
        // timed-out run still carries the partial prefix (e.g. "blink"
        // when the user got 1 of 3 steps done). On a `completed==true`
        // run this equals the issued sequence by construction; on a
        // zero-progress run it's the empty string, which the relay's
        // zod schema accepts and checkChallenge rejects with the
        // intended ERR_CHALLENGE_MISMATCH — the pre-fix "always "" on
        // incomplete" short-circuited into ERR_SCHEMA_INVALID at the
        // zod boundary and lost the partial-progress signal.
        val challengeResponse = challengeResult.firedSteps.joinToString(",") { it.wire }
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
