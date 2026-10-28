import Foundation

// Review fix M-2: without LocalizedError conformance, `error.localizedDescription`
// on a plain Swift enum returns "The operation couldn't be completed. (OpenLiveness.CDLError
// error 0.)" — opaque Cocoa boilerplate the dev-harness UIs on both platforms would
// otherwise surface to users. Splitting the conformance into an extension (not folded
// into the enum declaration) keeps CDLError.swift as the single source of truth for the
// cases while isolating the human-facing strings here where they can be reviewed in
// isolation. Each message is deliberately short + actionable so the android-sample /
// ios-sample status label stays single-line.
extension CDLError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .hardwareAttestationUnsupported:
            return "Hardware attestation is unavailable on this device."
        case .keychainError:
            return "Keychain access failed — unlock the device and retry."
        case .qrParseError:
            return "QR payload was not valid base64 or did not decode to a CDL session."
        case .networkError(let detail):
            // Preserve the caller-supplied diagnostic; the inner string already
            // explains WHY (invalid URL, timeout, ...) so wrapping it in a prefix
            // keeps the error readable without losing the specifics.
            return "Network error: \(detail)"
        case .challengeTimeout:
            return "Liveness challenge timed out before all steps completed."
        case .sessionExpired:
            return "This CDL session has expired. Scan a fresh QR code."
        case .cameraPermissionDenied:
            return "Camera permission denied. Enable it in Settings and retry."
        case .stubModeDisallowed:
            // The error exists specifically so a Release integrator who forgets
            // to inject a landmark stream fails LOUDLY. The message points them
            // at the two legitimate escapes so triage doesn't require reading
            // CDLSession source.
            return "Liveness cannot run: no landmark stream was provided and " +
                "allowStubMode is false. Pass a LandmarkStream or set " +
                "allowStubMode: true (dev harnesses only)."
        }
    }
}
