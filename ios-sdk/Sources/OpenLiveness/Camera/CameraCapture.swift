import AVFoundation
#if canImport(UIKit)
import UIKit
#endif

/// AVFoundation front-facing camera session with a drop-in preview view.
/// Enforces the front camera (per spec: `front_camera_confirmed` is
/// always true) and surfaces low-light / permission state so the
/// orchestrator can set `ambient_conditions.low_light_detected`.
public final class CameraCapture: NSObject {

    public enum CaptureError: Error {
        case cameraPermissionDenied
        case frontCameraUnavailable
    }

    public let session = AVCaptureSession()
    private var videoDevice: AVCaptureDevice?
    private let output = AVCaptureVideoDataOutput()
    private let queue = DispatchQueue(label: "dev.openliveness.camera")

    public private(set) var frontCameraConfirmed: Bool = false

    public var lowLightDetected: Bool {
        guard let device = videoDevice else { return false }
        return device.iso > 800
    }

    /// Request camera permission and configure the front-facing camera.
    /// Returns once the capture session is configured; call `start()` to
    /// begin streaming frames.
    public func configure() async throws {
        try await requestCameraPermission()

        session.beginConfiguration()
        session.sessionPreset = .high

        let discovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInWideAngleCamera],
            mediaType: .video,
            position: .front
        )
        guard let front = discovery.devices.first else {
            session.commitConfiguration()
            throw CaptureError.frontCameraUnavailable
        }
        videoDevice = front
        frontCameraConfirmed = true

        let input = try AVCaptureDeviceInput(device: front)
        if session.canAddInput(input) { session.addInput(input) }

        output.setSampleBufferDelegate(nil, queue: queue)
        if session.canAddOutput(output) { session.addOutput(output) }

        session.commitConfiguration()
    }

    public func start() {
        if !session.isRunning { session.startRunning() }
    }

    public func stop() {
        if session.isRunning { session.stopRunning() }
    }

    private func requestCameraPermission() async throws {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            return
        case .notDetermined:
            let granted = await AVCaptureDevice.requestAccess(for: .video)
            if !granted { throw CaptureError.cameraPermissionDenied }
        case .denied, .restricted:
            throw CaptureError.cameraPermissionDenied
        @unknown default:
            throw CaptureError.cameraPermissionDenied
        }
    }
}

#if canImport(UIKit)
/// UIView wrapper around AVCaptureVideoPreviewLayer so hosting apps can
/// drop the preview into any UIViewController.
public final class CameraPreviewView: UIView {
    public override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    public var previewLayer: AVCaptureVideoPreviewLayer {
        layer as! AVCaptureVideoPreviewLayer
    }

    public func attach(_ capture: CameraCapture) {
        previewLayer.session = capture.session
        previewLayer.videoGravity = .resizeAspectFill
    }
}
#endif
