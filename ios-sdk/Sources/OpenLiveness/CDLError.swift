import Foundation

public enum CDLError: Error {
    case hardwareAttestationUnsupported
    case keychainError
    case qrParseError
    case networkError(String)
    case challengeTimeout
    case sessionExpired
    case cameraPermissionDenied
}
