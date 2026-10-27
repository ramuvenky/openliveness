import Foundation
import SwiftUI
import OpenLiveness
#if canImport(UIKit)
import UIKit
#endif

/// Owns the CDLSession lifecycle and surfaces UI-visible state. All
/// published properties are mutated on the main actor so SwiftUI's
/// diffing stays on one dispatcher — long-running work (CDLSession.start)
/// runs on background queues via Swift concurrency but every write to
/// `status` / `prompt` / `isRunning` / `cameraStatus` is marshalled back
/// here.
///
/// Parity with android-sample/MainActivity.kt: the sample — not the SDK —
/// is responsible for owning the camera + stream lifecycle. That mirrors
/// M3/M4 from the Android review (sample owns stream+camera lifecycle),
/// so a developer reading both platforms sees the same ownership model.
@MainActor
final class LivenessRunner: ObservableObject {

    /// Camera-permission state surfaced to the UI. Review fix H-5: without
    /// a distinct `denied` case ContentView had no signal to show a
    /// recovery affordance when the user denied the system prompt.
    enum CameraStatus: Equatable {
        case initial
        case authorized
        case denied
        case failed(String)
    }

    @Published var status: String = "Ready. Paste QR payload."
    @Published var prompt: String = ""
    @Published var isRunning: Bool = false
    @Published var cameraStatus: CameraStatus = .initial

    /// Camera capture is kept alive across sessions so the preview stays
    /// up even when the user is just typing in the QR field. Reusing one
    /// instance also avoids repeatedly asking for camera permission on
    /// each Start tap.
    let cameraCapture = CameraCapture()

    /// Review fix H-6: hold the in-flight Task so a scene disappear or
    /// explicit cancel can tear it down. Without this a user backgrounding
    /// mid-session leaves the WebSocket + attestation flow racing against
    /// the app's lifecycle — exactly the kind of orphaned coroutine the
    /// Android M3/M4 review fix on the SDK side addressed.
    private var runTask: Task<Void, Never>?

    /// Prepare the camera on first appearance. Idempotent — subsequent
    /// calls short-circuit inside AVCaptureSession because the inputs are
    /// already attached. Review fix H-5: we now distinguish "denied" from
    /// generic failure so the view can offer an "Open Settings" affordance
    /// instead of silently showing a black rectangle.
    func prepareCamera() async {
        do {
            try await cameraCapture.configure()
            cameraCapture.start()
            cameraStatus = .authorized
        } catch CameraCapture.CaptureError.cameraPermissionDenied {
            cameraStatus = .denied
            status = "Camera permission denied — enable it in Settings."
        } catch {
            // Use `String(describing:)` not `localizedDescription` because
            // CaptureError is a plain enum (same reasoning as the CDLError
            // M-2 fix); localizedDescription would return Cocoa boilerplate.
            cameraStatus = .failed(String(describing: error))
            status = "Camera unavailable: \(String(describing: error))"
        }
    }

    /// Open the system Settings app at this app's permission panel. The
    /// H-5 recovery path: user denied the mic/camera prompt → they tap
    /// this → iOS deep-links straight to our Settings page. Without this
    /// the only recovery is uninstall/reinstall.
    func openSystemSettings() {
        #if canImport(UIKit)
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
        #endif
    }

    /// Run the full CDL handshake. The method is intentionally short — the
    /// SDK's CDLSession.start() does the heavy lifting; the runner just
    /// toggles UI state and renders the terminal outcome.
    func run(qrText: String) async {
        isRunning = true
        status = "Parsing QR…"
        prompt = ""

        do {
            let payload = try parseQRPayload(qrText)
            // Show the first prompt BEFORE the WS handshake starts so the
            // user has something to read while camera warm-up + relay
            // round-trip burn through their first second. The iOS CDLSession
            // doesn't currently expose a progress callback (unlike the
            // Android overload), so the prompt stays static for the duration
            // of the session. Phase 3 Step 5 iOS follow-up: wire a progress
            // emitter through CDLSession.start() to match Android's live-
            // updating prompt sequence.
            prompt = Self.promptLabel(for: payload.challenge_sequence.first)
            status = "Running liveness…"

            // Review fix C-2: pass `allowStubMode: true` explicitly rather
            // than relying on the SDK's debug-only default to resolve
            // across the SwiftPM module boundary (it doesn't — the compile
            // flag never propagates from the sample module to the SDK
            // module). The sample is a documented dev harness protected
            // by the red banner above AND the server-side STUB_MODE
            // allow-list, so the explicit opt-in is correct here. Phase 3
            // Step 5 iOS will swap this for an injected real LandmarkStream
            // (Vision adapter) at which point the flag can drop to false.
            let session = CDLSession(from: payload, allowStubMode: true)
            let response = try await session.start()
            status = "accepted=\(response.accepted)"
            prompt = ""
        } catch {
            // `String(describing:)` + `type(of:)` instead of
            // `error.localizedDescription` so a CDLError that didn't get the
            // LocalizedError conformance (SDK older than this fix, or a
            // future case someone forgot to list) still produces readable
            // text — matches the android-sample's "${t.javaClass.simpleName}:
            // ${t.message}" idiom.
            status = "error: \(type(of: error)): \(String(describing: error))"
            prompt = ""
        }
        isRunning = false
    }

    /// Cancel the in-flight session, if any. Idempotent; safe to call from
    /// `.onDisappear` without checking state first. Review fix H-6.
    func cancel() {
        runTask?.cancel()
        runTask = nil
        if isRunning {
            isRunning = false
            status = "Cancelled"
            prompt = ""
        }
    }

    /// Wraps `run(qrText:)` in a tracked Task so `cancel()` can tear it
    /// down. ContentView calls this from the Start button; direct `run`
    /// callers (tests) can still call the async method without a task.
    func startRun(qrText: String) {
        runTask?.cancel()
        runTask = Task { [weak self] in
            await self?.run(qrText: qrText)
        }
    }

    /// Map a wire string to the single-line instruction shown on screen.
    /// Mirror of MainActivity.promptLabel in android-sample so a dev
    /// comparing the two reads the same strings. Exposed as `static` for
    /// unit testing — the mapping is pure and belongs to the sample
    /// module's public-to-tests surface, not a per-instance detail.
    static func promptLabel(for wire: String?) -> String {
        switch wire {
        case "blink": return "Blink now"
        case "look_left": return "Look left"
        case "look_right": return "Look right"
        case "smile": return "Smile"
        case "nod": return "Nod"
        case .none: return ""
        case .some(let other):
            // Unknown wire values render verbatim so a dev using a new
            // sequence sees the string rather than a blank prompt. The
            // server has already validated the string set, so an unknown
            // here means the SDK is out of sync with the relay enum —
            // which is diagnostic signal, not something to hide.
            return other
        }
    }
}
