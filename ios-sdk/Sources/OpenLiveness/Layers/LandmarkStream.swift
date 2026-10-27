import Foundation

/// Frames of (landmarks, timestamp) that Layer2Runner consumes. The real
/// production impl wraps MediaPipe Face Mesh (Phase 3 Step 5); test fakes
/// drive the same protocol so the runner can be exercised deterministically
/// without a camera. `timestamp` is seconds since an arbitrary epoch; the
/// sequencer only looks at deltas, so the epoch choice is irrelevant as
/// long as a single stream uses one monotonic reference.
public protocol LandmarkStream: AnyObject {
    /// Returns the next frame, or nil when the stream has ended. May
    /// throw on camera / inference failure — the runner treats a throw
    /// as a terminal error and bubbles it up rather than silently
    /// stopping, so a user sees "camera failed" rather than "challenge
    /// not completed".
    func nextFrame() async throws -> (FaceLandmarks, TimeInterval)?
}

/// Callback shape the runner uses to push a `processing_update` WS frame
/// each time a challenge step completes. Pulling this out of the runner
/// decouples WS encoding/transport from the sequencer-driving logic,
/// which lets tests observe progress without a WebSocket harness.
public typealias ProcessingUpdateEmitter = (_ progress: Float, _ currentLayer: String) -> Void
