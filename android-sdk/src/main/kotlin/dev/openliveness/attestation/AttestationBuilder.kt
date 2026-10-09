package dev.openliveness.attestation

import android.content.Context
import android.provider.Settings
import dev.openliveness.AmbientConditions
import dev.openliveness.CDLSession
import dev.openliveness.CanonicalJson
import dev.openliveness.LayerScores
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Builds the signed CDLAttestation map. Falls back to stub layer scores
 * so the end-to-end relay handshake stays exercisable before the real
 * detection layers are wired in.
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
        deviceAttestation: String? = null
    ): ByteArray {

        // TODO(layer2): `challenge_response` currently echoes the challenge
        // sequence that the relay already issued, which is trivially replayable.
        // Replace with the user-observed response captured by Layer2 before
        // treating attestation submission as evidence of a live challenge.
        val attestation = linkedMapOf<String, Any?>(
            "version" to "1.0",
            "session_id" to session.payload.session_id,
            "challenge_id" to session.payload.challenge_id,
            "challenge_response" to session.payload.challenge_sequence.joinToString(","),
            "timestamp" to iso8601Now(),
            "liveness_decision" to decision(layerScores),
            "assurance_level" to if (deviceAttestation == null) "software_only" else "IAL2",
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

    private fun decision(layerScores: LayerScores): String =
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
