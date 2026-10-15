import Foundation
import CryptoKit
#if canImport(UIKit)
import UIKit
#endif

/// Produces the signed CDLAttestation dictionary ready to POST to the relay.
/// Falls back to stub layer scores when the real detection layers are not
/// wired in, so the end-to-end relay handshake stays testable in isolation.
struct AttestationBuilder {

    /// Builds the signed attestation and returns its canonical-JSON bytes
    /// ready to POST. Returning bytes (rather than a dictionary) is load-
    /// bearing: the server verifies the signature over canonical bytes, so
    /// sending anything else risks wire-vs-signed drift and makes raw-byte
    /// audit trails meaningless.
    static func build(
        session: CDLSession,
        layerScores: LayerScores,
        ambient: AmbientConditions,
        deviceAttestation: String? = nil
    ) throws -> Data {

        // TODO(layer2): `challenge_response` currently echoes the challenge
        // sequence that the relay already issued, which is trivially replayable.
        // Replace with the user-observed response captured by Layer2 before
        // treating attestation submission as evidence of a live challenge.
        var attestation: [String: Any] = [
            "version": "1.0",
            "session_id": session.sessionId,
            "challenge_id": session.challengeId,
            "challenge_response": session.challengeSequence.joined(separator: ","),
            "timestamp": iso8601Now(),
            "liveness_decision": decision(layerScores: layerScores),
            "assurance_level": deviceAttestation == nil ? "software_only" : "IAL2",
            "layer_scores": [
                "layer2_challenge_completed": layerScores.layer2ChallengeCompleted,
                "layer2_challenge_score": Double(layerScores.layer2ChallengeScore),
                "layer3_pulse_detected": layerScores.layer3PulseDetected,
                "layer3_bpm_in_range": layerScores.layer3BpmInRange,
                "layer3_rppg_confidence": Double(layerScores.layer3RppgConfidence),
                "layer4_lbp_score": Double(layerScores.layer4LbpScore),
                "layer4_fourier_score": Double(layerScores.layer4FourierScore),
                "layer5_behavioral_score": Double(layerScores.layer5BehavioralScore),
                "layer5_microsaccade_detected": layerScores.layer5MicrosaccadeDetected
            ],
            "device_platform": "ios",
            "device_id_hash": computeDeviceIdHash(),
            "ambient_conditions": [
                "low_light_detected": ambient.lowLightDetected,
                "motion_detected": ambient.motionDetected,
                "front_camera_confirmed": ambient.frontCameraConfirmed
            ],
            "public_key": try SigningService.publicKeyBase64()
        ]
        if let deviceAttestation = deviceAttestation {
            attestation["device_attestation"] = deviceAttestation
        }

        // Sign the canonical bytes of the dict without the signature field,
        // then re-canonicalize once the signature is installed so the wire
        // bytes are exactly what the server will re-canonicalize to verify.
        attestation["signature"] = try SigningService.sign(attestation)
        return try CanonicalJSON.encode(attestation)
    }

    /// Stub layer scores used before the real detection layers are wired in.
    static func stubLayerScores() -> LayerScores {
        var s = LayerScores()
        s.layer2ChallengeCompleted = true
        s.layer2ChallengeScore = 0.85
        s.layer3PulseDetected = true
        s.layer3BpmInRange = true
        s.layer3RppgConfidence = 0.85
        s.layer4LbpScore = 0.85
        s.layer4FourierScore = 0.85
        s.layer5BehavioralScore = 0.85
        s.layer5MicrosaccadeDetected = true
        return s
    }

    // MARK: - helpers

    private static func iso8601Now() -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f.string(from: Date())
    }

    private static func decision(layerScores: LayerScores) -> String {
        if !layerScores.layer2ChallengeCompleted { return "fail" }
        // Weighted-score logic lives in LivenessOrchestrator; until that
        // lands we trust the stub and emit pass.
        return "pass"
    }

    private static func computeDeviceIdHash() -> String {
        #if canImport(UIKit)
        let vendorId = UIDevice.current.identifierForVendor?.uuidString ?? "unknown"
        #else
        let vendorId = "unknown"
        #endif
        let data = Data(vendorId.utf8)
        let hash = SHA256.hash(data: data)
        return hash.compactMap { String(format: "%02x", $0) }.joined()
    }
}
