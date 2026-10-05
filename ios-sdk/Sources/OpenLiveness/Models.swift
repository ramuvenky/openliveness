import Foundation

public struct QRPayload: Decodable {
    public let session_id: String
    public let challenge_id: String
    public let challenge_sequence: [String]
    public let relay_ws: String
    public let relay_http: String
    public let expires_at: String
    public let version: String
}

public struct LayerScores {
    public var layer2ChallengeCompleted: Bool = false
    public var layer2ChallengeScore: Float = 0.0
    public var layer3PulseDetected: Bool = false
    public var layer3BpmInRange: Bool = false
    public var layer3RppgConfidence: Float = 0.0
    public var layer4LbpScore: Float = 0.0
    public var layer4FourierScore: Float = 0.0
    public var layer5BehavioralScore: Float = 0.0
    public var layer5MicrosaccadeDetected: Bool = false

    public init() {}
}

public struct AmbientConditions {
    public var lowLightDetected: Bool = false
    public var motionDetected: Bool = false
    public var frontCameraConfirmed: Bool = true

    public init() {}
}

public struct LivenessResult {
    public let decision: String   // "pass" | "fail" | "inconclusive"
    public let assuranceLevel: String
    public let layerScores: LayerScores
}

/// Parses the base64-encoded JSON payload embedded in the CDL QR code.
public func parseQRPayload(_ rawValue: String) throws -> QRPayload {
    guard let data = Data(base64Encoded: rawValue),
          let payload = try? JSONDecoder().decode(QRPayload.self, from: data)
    else { throw CDLError.qrParseError }
    return payload
}
