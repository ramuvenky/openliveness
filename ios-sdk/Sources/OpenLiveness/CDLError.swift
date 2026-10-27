import Foundation

public enum CDLError: Error {
    case hardwareAttestationUnsupported
    case keychainError
    case qrParseError
    case networkError(String)
    case challengeTimeout
    case sessionExpired
    case cameraPermissionDenied
    /// S-NEW-1(b) — thrown by `CDLSession.start()` when no landmark stream
    /// was supplied AND the explicit `allowStubMode` opt-in was not set.
    /// The stub-liveness path exists only for Phase-1 CI harnesses; a
    /// production integrator who forgets to wire a stream gets a loud
    /// failure here instead of a silently-passing liveness check.
    case stubModeDisallowed
}
