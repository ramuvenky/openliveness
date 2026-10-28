import SwiftUI
import OpenLiveness

/// SwiftUI wrapper around the SDK's UIKit CameraPreviewView. The SDK
/// deliberately ships a UIView (not a SwiftUI view) so a hosting app
/// using UIKit can drop it in without pulling SwiftUI; this sample
/// bridges it back into SwiftUI with UIViewRepresentable.
///
/// Keeping the bridge in the sample — not the SDK — avoids forcing a
/// SwiftUI dependency on integrators who still use UIKit.
struct CameraPreview: UIViewRepresentable {
    let capture: CameraCapture

    func makeUIView(context: Context) -> CameraPreviewView {
        let view = CameraPreviewView()
        view.attach(capture)
        return view
    }

    func updateUIView(_ uiView: CameraPreviewView, context: Context) {
        // No-op: the preview layer is wired once at makeUIView time and
        // the session reference is stable for the lifetime of this view.
    }
}
