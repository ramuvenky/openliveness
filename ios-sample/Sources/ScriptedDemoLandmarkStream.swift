import Foundation
import OpenLiveness

/// Dev-harness `LandmarkStream` that emits a blink-firing three-frame
/// window (closed → closed → open). The iOS SDK does not yet ship a real
/// Vision-based landmark adapter — Phase 3 Step 5 iOS follow-up — so the
/// sample has to inject *something* for the Layer-2 sequencer to see.
///
/// This is intentionally duplicated from the ios-sdk integration-test
/// fixture `ScriptedBlinkStream` rather than exported from the SDK: the
/// SDK carries no stub path by design (v0.2.0), and a scripted stream in
/// the production surface would re-introduce one. Keeping it in the
/// sample module confines the "fake frames" concept to the dev harness,
/// paired with the permanent red "DEV HARNESS" banner.
///
/// Parity with android-sample's MainActivity which also drives the Android
/// SDK with the bundled face_landmarker.task against a live camera —
/// until Vision lands here, this scripted fallback is what keeps the iOS
/// sample end-to-end exercisable.
final class ScriptedDemoLandmarkStream: LandmarkStream {

    private var frames: [(FaceLandmarks, TimeInterval)]

    init() {
        self.frames = [
            (Self.closedEye(), 0.0),
            (Self.closedEye(), 0.033),
            (Self.openEye(),   0.066),
        ]
    }

    func nextFrame() async throws -> (FaceLandmarks, TimeInterval)? {
        frames.isEmpty ? nil : frames.removeFirst()
    }

    private static func neutralFace() -> [FaceLandmark] {
        var pts = Array(repeating: FaceLandmark(x: 0, y: 0), count: FaceLandmarks.expectedCount)
        pts[1]   = FaceLandmark(x: 0.50, y: 0.50)
        pts[33]  = FaceLandmark(x: 0.40, y: 0.50)
        pts[133] = FaceLandmark(x: 0.50, y: 0.50)
        pts[145] = FaceLandmark(x: 0.45, y: 0.50)
        pts[159] = FaceLandmark(x: 0.45, y: 0.45)
        pts[234] = FaceLandmark(x: 0.30, y: 0.50)
        pts[454] = FaceLandmark(x: 0.70, y: 0.50)
        return pts
    }

    private static func closedEye() -> FaceLandmarks {
        var p = neutralFace()
        p[159] = FaceLandmark(x: 0.45, y: 0.49)
        return try! FaceLandmarks(points: p)
    }

    private static func openEye() -> FaceLandmarks {
        try! FaceLandmarks(points: neutralFace())
    }
}
