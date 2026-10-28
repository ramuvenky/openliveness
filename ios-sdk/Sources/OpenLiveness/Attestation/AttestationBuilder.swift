import Foundation
import CryptoKit
#if canImport(UIKit)
import UIKit
#endif

/// Produces the signed CDLAttestation dictionary ready to POST to the relay.
/// Falls back to stub layer scores when the real detection layers are not
/// wired in, so the end-to-end relay handshake stays testable in isolation.
///
/// Platform asymmetry — KNOWN LIMITATION until Phase 4 Play Integrity
/// Standard migration. On iOS, `liveness_decision` is baked into the App
/// Attest assertion client data via `CDLSession.generateAssertion`, so
/// forging the field at the SDK layer breaks the device-signed envelope.
/// On Android, Play Integrity classic only signs the server-supplied
/// nonce; `liveness_decision` there is covered by the outer `signature`
/// but not by the hardware-backed assertion. A server policy that weighs
/// the decision more when the device also signed it will see different
/// trust levels for iOS and Android. Phase 4 should migrate Android to
/// Play Integrity Standard and bind the decision via `requestHash`;
/// until then, treat the two platforms as equal-trust from the server's
/// perspective.
struct AttestationBuilder {

    /// Builds the signed attestation and returns its canonical-JSON bytes
    /// ready to POST. Returning bytes (rather than a dictionary) is load-
    /// bearing: the server verifies the signature over canonical bytes, so
    /// sending anything else risks wire-vs-signed drift and makes raw-byte
    /// audit trails meaningless.
    /// Status of the hardware-attestation attempt. Mirrors the Kotlin
    /// enum of the same name — kept narrow (3 values + wire-string
    /// mapping) so a drift between the two sides shows up at compile
    /// time rather than as a server-side rejection.
    enum HwAttestationStatus: String {
        case ok = "ok"
        case unsupported = "unsupported"
        case failed = "failed"
    }

    enum BuildError: Error, Equatable {
        /// H-1: real detector output (`challengeResult != nil`) without
        /// an explicit `livenessDecision` is a caller error — the stub
        /// rule only inspects layer 2 and would silently emit "pass" on
        /// catastrophic layer-3/4/5 scores. Builder throws rather than
        /// paper over the mistake.
        case missingLivenessDecisionForRealChallengeResult
    }

    static func build(
        session: CDLSession,
        layerScores: LayerScores,
        ambient: AmbientConditions,
        deviceAttestation: String? = nil,
        /// Pre-computed decision. Callers that also sign client data (App
        /// Attest assertion) MUST pass the same string they fed into the
        /// device-signed payload — otherwise the posted attestation and
        /// the assertion disagree on a load-bearing field.
        ///
        /// H-1: `nil` only falls back to the stub rule when
        /// `challengeResult` is also nil (the Phase-1 e2e stub path). A
        /// caller that passes a real `challengeResult` without an explicit
        /// decision is in a real-detector flow where the stub rule — which
        /// only inspects layer 2 — would silently emit "pass" on
        /// catastrophic layer-3/4/5 scores. We throw at build time rather
        /// than ship a lying attestation.
        livenessDecision: String? = nil,
        /// How hardware attestation ended. Lets the relay tell "device
        /// cannot attest" (expected) apart from "device tried and failed"
        /// (downgrade-attack signal). Defaults to unsupported when the
        /// device attestation blob is nil, ok when it's present — the
        /// explicit arg wins for callers that know better.
        hwAttestationStatus: HwAttestationStatus? = nil,
        /// Real Layer-2 outcome. When present, `challenge_response` is
        /// gated on the sequencer's `completed` flag — a timed-out run
        /// sends an empty string that relay checkChallenge rejects, so
        /// an SDK caller that reaches `.build(...)` without actually
        /// running the sequencer to completion cannot submit a passing-
        /// looking payload. This is tamper-defense against SDK-internal
        /// bugs or confused integrators, NOT replay defense (the
        /// enclosing payload is signed; replay is defended at the
        /// signature layer). When nil, falls back to echoing the
        /// sequence for the Phase 1 stub path still used by relay e2e
        /// tests.
        challengeResult: ChallengeResult? = nil,
        /// S-NEW-2(c) — on-wire marker that the submitting SDK took the
        /// Phase-1 stub path (no landmark stream wired, layer 3/4/5
        /// scores fabricated from `stubLayerScores()`). The relay
        /// enforces policy — a production environment is expected to
        /// refuse `stub_mode: true` outside allow-listed CI session
        /// IDs. Stamping this on the client is defense in depth: a
        /// hostile fork that compiles with `OPENLIVENESS_STUB_ALLOWED`
        /// can still be caught by the server-side policy because the
        /// wire bytes carry the truth. Default `false` so a caller that
        /// forgets to stamp it doesn't accidentally pass as "stub" on
        /// the server — opt-in only.
        stubMode: Bool = false
    ) throws -> Data {

        // H-1: real detector output without an orchestrated decision is a
        // caller error — the stub rule only inspects layer 2 and would
        // silently emit "pass" when layers 3/4/5 are catastrophic. Fail
        // loud rather than ship a lying attestation.
        if challengeResult != nil && livenessDecision == nil {
            throw BuildError.missingLivenessDecisionForRealChallengeResult
        }
        let decision = livenessDecision ?? self.stubLivenessDecision(layerScores: layerScores)
        let effectiveHwStatus = hwAttestationStatus
            ?? (deviceAttestation == nil ? HwAttestationStatus.unsupported : HwAttestationStatus.ok)
        // C-1 + M-1: derive assurance_level from THREE signals — device
        // attestation blob present, hw status .ok, AND the orchestrator's
        // decision is "pass". Dropping the decision check (as the pre-fix
        // code did) could stamp "IAL2" onto a `fail` liveness result when
        // hw attestation happened to succeed — a hostile combination the
        // relay's checkAssurance also rejects, but we belt-and-suspenders
        // here so a direct caller of AttestationBuilder.build cannot emit
        // a self-contradicting attestation. "pass" is the only decision
        // string that unlocks IAL2; `inconclusive` + hw-ok stays
        // software_only to match the orchestrator's own output.
        let assuranceLevel =
            (deviceAttestation != nil && effectiveHwStatus == .ok && decision == "pass")
                ? "IAL2" : "software_only"
        // M-3: `challenge_response` reflects what the SDK actually
        // detected (`firedSteps`), not just what the server prompted
        // (`session.challengeSequence`). On a `completed==true` run
        // these are equal by construction; on a timed-out run
        // `firedSteps` is the prefix the user actually got through, and
        // the relay's checkChallenge rejects the mismatched (partial)
        // string — same tamper-defense goal as before, but now the field
        // doesn't fabricate the server's prompt. When `challengeResult`
        // is nil the Phase-1 stub path keeps echoing the server
        // sequence so e2e CI stays unchanged.
        let challengeResponse: String
        if let r = challengeResult {
            challengeResponse = r.completed
                ? r.firedSteps.map { $0.rawValue }.joined(separator: ",")
                : ""
        } else {
            challengeResponse = session.challengeSequence.joined(separator: ",")
        }
        var attestation: [String: Any] = [
            "version": "1.0",
            "session_id": session.sessionId,
            "challenge_id": session.challengeId,
            "challenge_response": challengeResponse,
            "timestamp": iso8601Now(),
            "liveness_decision": decision,
            "assurance_level": assuranceLevel,
            "hw_attestation_status": effectiveHwStatus.rawValue,
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
            "stub_mode": stubMode,
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

    /// STUB-only decision used by Phase 1 callers that haven't wired a
    /// real `LivenessOrchestrator` yet. Public so CDLSession can feed
    /// the same string into the App Attest assertion client data,
    /// keeping the signed assertion and the posted attestation in
    /// agreement when the stub path is active.
    ///
    /// NOT a replacement for `LivenessOrchestrator.orchestrate(...)`:
    /// this rule only inspects `layer2ChallengeCompleted` and will emit
    /// "pass" even when layer-3/4/5 scores are catastrophic, because
    /// stub scores from `stubLayerScores()` are hardcoded to 0.85 and
    /// inspecting them would make the stub e2e tests inconsistent. Any
    /// caller with real detector output must route through
    /// `LivenessOrchestrator` — the renamed `stub` prefix is a tripwire
    /// against importing this into real paths. Changes here are part
    /// of the attestation wire contract — review carefully.
    static func stubLivenessDecision(layerScores: LayerScores) -> String {
        if !layerScores.layer2ChallengeCompleted { return "fail" }
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
