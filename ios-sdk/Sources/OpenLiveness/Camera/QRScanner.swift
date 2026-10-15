import AVFoundation
import Foundation

/// AVFoundation-based QR scanner. The rear camera is the natural choice
/// for scanning a browser-displayed QR, so this is intentionally separate
/// from CameraCapture (which is front-only for liveness).
public final class QRScanner: NSObject, AVCaptureMetadataOutputObjectsDelegate {

    public enum ScannerError: Error {
        case cameraPermissionDenied
        case rearCameraUnavailable
    }

    public let session = AVCaptureSession()
    private let output = AVCaptureMetadataOutput()
    private let queue = DispatchQueue(label: "dev.openliveness.qrscanner")
    private var onPayload: ((QRPayload) -> Void)?

    public func start(onPayload: @escaping (QRPayload) -> Void) async throws {
        self.onPayload = onPayload
        try await requestPermission()

        session.beginConfiguration()
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device) else {
            session.commitConfiguration()
            throw ScannerError.rearCameraUnavailable
        }
        if session.canAddInput(input) { session.addInput(input) }
        if session.canAddOutput(output) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: queue)
            output.metadataObjectTypes = [.qr]
        }
        session.commitConfiguration()
        session.startRunning()
    }

    public func stop() {
        if session.isRunning { session.stopRunning() }
    }

    public func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        for object in metadataObjects {
            guard let readable = object as? AVMetadataMachineReadableCodeObject,
                  readable.type == .qr,
                  let raw = readable.stringValue else { continue }
            // One payload per scan session — stop immediately to avoid
            // repeated callbacks for the same code.
            if let payload = try? parseQRPayload(raw) {
                stop()
                onPayload?(payload)
                return
            }
        }
    }

    private func requestPermission() async throws {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return
        case .notDetermined:
            let granted = await AVCaptureDevice.requestAccess(for: .video)
            if !granted { throw ScannerError.cameraPermissionDenied }
        case .denied, .restricted:
            throw ScannerError.cameraPermissionDenied
        @unknown default:
            throw ScannerError.cameraPermissionDenied
        }
    }
}
