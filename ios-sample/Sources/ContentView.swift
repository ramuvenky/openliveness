import SwiftUI
import OpenLiveness

/// Top-level screen of the dev harness. Mirrors the android-sample layout:
///  - full-screen camera preview behind everything
///  - persistent red "DEV HARNESS" banner at the top
///  - large centred prompt text
///  - QR-paste field + Start button pinned to the bottom
///
/// Deliberately built as a single SwiftUI view rather than split across
/// subviews — the sample is small, and keeping the layout in one place
/// makes it easy to compare side-by-side with MainActivity.kt during a
/// cross-platform code review.
struct ContentView: View {
    @StateObject private var runner = LivenessRunner()
    @State private var qrText: String = ""

    var body: some View {
        ZStack {
            // Preview sits behind every other layer. In stub mode (no
            // injected real LandmarkStream) the camera still runs so the
            // user sees themselves — mirrors the Android sample's choice
            // not to hide the preview just because the model asset is
            // missing. The banner above makes the "this isn't real
            // liveness" state unambiguous either way.
            CameraPreview(capture: runner.cameraCapture)
                .ignoresSafeArea()

            // Review fix H-5: if the user denied camera permission, the
            // preview below is a black rectangle with no explanation. The
            // overlay covers it with a tappable "Open Settings" affordance
            // that deep-links to this app's permission panel.
            if runner.cameraStatus == .denied {
                permissionDeniedOverlay
            }

            VStack {
                warningBanner
                Spacer()
                promptText
                Spacer()
                controls
            }
        }
        .task { await runner.prepareCamera() }
        // Review fix H-6: scene disappearance cancels any in-flight CDL
        // session. Without this a user backgrounding mid-run would leave
        // the WS + attestation pipeline racing against the app lifecycle.
        .onDisappear { runner.cancel() }
    }

    private var warningBanner: some View {
        Text("⚠ DEV HARNESS — do not point at a production relay")
            .font(.footnote.weight(.semibold))
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .padding(12)
            .background(Color(red: 0xB7/255, green: 0x1C/255, blue: 0x1C/255))
    }

    private var promptText: some View {
        Text(runner.prompt)
            .font(.system(size: 32, weight: .semibold))
            .foregroundColor(.white)
            .shadow(color: .black, radius: 6)
            .multilineTextAlignment(.center)
            .padding()
    }

    private var controls: some View {
        VStack(spacing: 12) {
            TextEditor(text: $qrText)
                .frame(height: 72)
                // Review fix M-1: a pasted base64 QR string without these
                // modifiers gets munged by iOS autocorrect + first-letter
                // capitalization. Base64 is case-sensitive; both have to
                // be off or every paste risks a silent "qrParseError".
                .disableAutocorrection(true)
                .autocapitalization(.none)
                .overlay(
                    // SwiftUI TextEditor has no `placeholder:`, so the
                    // "paste QR" hint is drawn as a disabled overlay
                    // that disappears once the user types anything.
                    Group {
                        if qrText.isEmpty {
                            Text("Paste QR payload (base64)")
                                .foregroundColor(.gray)
                                .padding(.leading, 6)
                                .allowsHitTesting(false)
                        }
                    }, alignment: .topLeading
                )
                .cornerRadius(6)
            Button(action: start) {
                Text(runner.isRunning ? "Running…" : "Start liveness")
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(runner.isRunning ? Color.gray : Color.blue)
                    .foregroundColor(.white)
                    .cornerRadius(6)
            }
            .disabled(runner.isRunning)
            Text(runner.status)
                .font(.callout)
                .foregroundColor(.white)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding()
        .background(Color.black.opacity(0.75))
    }

    private var permissionDeniedOverlay: some View {
        VStack(spacing: 16) {
            Text("Camera permission denied")
                .font(.title3.weight(.semibold))
                .foregroundColor(.white)
            Text("OpenLiveness needs camera access to prove a human is present. Open Settings and enable Camera for this app.")
                .multilineTextAlignment(.center)
                .foregroundColor(.white)
                .padding(.horizontal, 24)
            Button(action: runner.openSystemSettings) {
                Text("Open Settings")
                    .padding(.horizontal, 24)
                    .padding(.vertical, 10)
                    .background(Color.white)
                    .foregroundColor(.black)
                    .cornerRadius(6)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black.opacity(0.85))
    }

    private func start() {
        let trimmed = qrText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            runner.status = "Paste a QR payload first"
            return
        }
        runner.startRun(qrText: trimmed)
    }
}
