import Foundation

/// Normalized MediaPipe Face Mesh landmark. x and y are in [0, 1] (image
/// space); z is pseudo-depth and unused by the current detectors — kept so
/// future detectors (e.g., a true 3D pitch estimate for NOD) can read it
/// without a landmark-model change.
public struct FaceLandmark: Equatable {
    public let x: Float
    public let y: Float
    public let z: Float
    public init(x: Float, y: Float, z: Float = 0) {
        self.x = x; self.y = y; self.z = z
    }
}

/// Thrown by `FaceLandmarks.init` when the landmark count doesn't match
/// MediaPipe's 478-point output. Converting from a trapping precondition
/// (M-5) means the rejection path is reachable in XCTest and a MediaPipe
/// model-config regression surfaces as a loud, catchable error rather
/// than a process crash in the host app.
public enum FaceLandmarksError: Error, Equatable {
    case wrongCount(got: Int, expected: Int)
}

/// 478-point MediaPipe Face Mesh output for a single frame. The count is
/// enforced because every detector in this file reads specific indices
/// (e.g., 159, 145, 1, 234, 454) and silently clipping to fewer landmarks
/// would make the gesture detectors produce correlated garbage instead of
/// failing loudly.
///
/// The initializer throws on bad input (M-5). Camera / MediaPipe adapters
/// that may legitimately see empty-face frames should prefer
/// `validating(points:)` so a face-not-detected tick becomes a nil
/// (dropped frame) rather than a thrown error that unwinds the capture
/// pipeline. The throwing init stays for callers that have already
/// filtered and want the model-mismatch to surface as a clear error.
public struct FaceLandmarks {
    public static let expectedCount = 478
    public let points: [FaceLandmark]

    public init(points: [FaceLandmark]) throws {
        guard points.count == FaceLandmarks.expectedCount else {
            throw FaceLandmarksError.wrongCount(got: points.count, expected: FaceLandmarks.expectedCount)
        }
        self.points = points
    }

    /// Safe factory for stream adapters: returns nil when the input
    /// doesn't look like a MediaPipe face mesh output (empty, partial,
    /// or wrong model). Mirrors Android `FaceLandmarks.tryFrom`.
    public static func validating(points: [FaceLandmark]) -> FaceLandmarks? {
        try? FaceLandmarks(points: points)
    }

    public subscript(i: Int) -> FaceLandmark { points[i] }
}

private func distance2D(_ a: FaceLandmark, _ b: FaceLandmark) -> Float {
    let dx = a.x - b.x, dy = a.y - b.y
    return (dx * dx + dy * dy).squareRoot()
}

/// Common surface for every gesture detector so ChallengeSequencer can
/// drive them polymorphically. The timestamp arg is required by the
/// protocol so the sequencer can pass it uniformly; frame-counted
/// detectors (blink, gaze, smile) ignore it. Keeping a single signature
/// avoids the alternative of a type-switch inside the sequencer, which
/// would scatter per-detector knowledge.
///
/// Not thread-safe by design: each detector carries mutable state (frame
/// counters, buffers) and is only ever driven by a single Layer2Runner
/// instance on one actor/queue. Sharing a detector across runners races.
public protocol GestureDetector: AnyObject {
    @discardableResult
    func update(_ landmarks: FaceLandmarks, at timestamp: TimeInterval) -> Bool
    func reset()
}

/// BLINK — EAR = distance(159, 145) / distance(33, 133). Fires when EAR
/// drops below 0.2 for 2+ consecutive frames (eye closed) and then rises
/// back above 0.25 (eye reopened). The reopen gate is what makes this
/// resistant to a static closed-eyes photo: a replay never triggers the
/// closed→open transition.
///
/// Note on repeated firing: `closedFrames` and `waitingForReopen` both
/// reset to 0/false after a fire, so a sustained blink-blink-blink stream
/// can satisfy a *second* identical BLINK step after another closed→open
/// cycle. The sequencer never puts two BLINK steps in a row today, so
/// this is dormant; a future gesture list that does must add a cooldown
/// or require an intermediate neutral frame.
public final class BlinkDetector: GestureDetector {
    public static let closedThreshold: Float = 0.2
    public static let openThreshold: Float = 0.25
    public static let minClosedFrames = 2

    private var closedFrames = 0
    private var waitingForReopen = false

    public init() {}

    @discardableResult
    public func update(_ landmarks: FaceLandmarks, at _: TimeInterval = 0) -> Bool {
        let eyeWidth = distance2D(landmarks[33], landmarks[133])
        guard eyeWidth > 0 else {
            // Face-tracking loss (0-width eye = degenerate landmarks).
            // Clear the latch: holding `waitingForReopen` across lost
            // frames would let a lost-then-reacquired tracking gap fire
            // a false blink on the next open eye, which a sustained-
            // closed-eye presentation attack could exploit by nudging
            // the camera off the face briefly.
            closedFrames = 0
            waitingForReopen = false
            return false
        }
        let ear = distance2D(landmarks[159], landmarks[145]) / eyeWidth

        if waitingForReopen {
            if ear > BlinkDetector.openThreshold {
                waitingForReopen = false
                closedFrames = 0
                return true
            }
            return false
        }

        if ear < BlinkDetector.closedThreshold {
            closedFrames += 1
            if closedFrames >= BlinkDetector.minClosedFrames {
                waitingForReopen = true
            }
        } else {
            closedFrames = 0
        }
        return false
    }

    public func reset() {
        closedFrames = 0
        waitingForReopen = false
    }
}

public enum GazeDirection {
    case left
    case right
}

/// LOOK_LEFT / LOOK_RIGHT — offset = (landmark[1].x − face_center_x) / face_width.
/// Face anchors are landmarks[234] and [454]; nose tip is landmark[1].
/// Fires after 3+ consecutive frames with offset past ±0.15 in the chosen
/// direction. 3 frames (≈100 ms at 30fps) filters out micro-head-sway
/// without rejecting an actual deliberate turn.
///
/// Note on repeated firing: `consecutiveFrames` resets to 0 after a fire,
/// so holding a sustained turn can satisfy a *second* identical step
/// after another 3 frames. The sequencer never puts two same-direction
/// steps in a row, so this is dormant today; a future gesture list that
/// does must add a cooldown or require a neutral intermediate frame.
public final class GazeDetector: GestureDetector {
    public static let offsetThreshold: Float = 0.15
    public static let minConsecutiveFrames = 3

    public let direction: GazeDirection
    private var consecutiveFrames = 0

    public init(_ direction: GazeDirection) {
        self.direction = direction
    }

    @discardableResult
    public func update(_ landmarks: FaceLandmarks, at _: TimeInterval = 0) -> Bool {
        let faceCenterX = (landmarks[234].x + landmarks[454].x) / 2
        let faceWidth = abs(landmarks[454].x - landmarks[234].x)
        guard faceWidth > 0 else { return false }
        let offset = (landmarks[1].x - faceCenterX) / faceWidth

        let meets: Bool
        switch direction {
        case .left:  meets = offset < -GazeDetector.offsetThreshold
        case .right: meets = offset >  GazeDetector.offsetThreshold
        }

        if meets {
            consecutiveFrames += 1
            if consecutiveFrames >= GazeDetector.minConsecutiveFrames {
                consecutiveFrames = 0
                return true
            }
        } else {
            consecutiveFrames = 0
        }
        return false
    }

    public func reset() { consecutiveFrames = 0 }
}

/// SMILE — current_mouth_width > neutral_mouth_width × 1.2 for 5+ frames.
/// mouth_width = distance(landmark[61], landmark[291]) (mouth corners).
/// neutral is calibrated as the MEDIAN mouth_width across the first 10
/// frames. Median instead of mean (H-4) so an eager user who smiles for
/// a minority of the warmup window can't drag the baseline up and lock
/// themselves out of ever firing — the median picks the relaxed middle
/// value and ignores the smile outliers. Callers should still prompt
/// users to relax during warmup; this is a hardening, not a license to
/// skip the UX cue.
public final class SmileDetector: GestureDetector {
    public static let widthMultiplier: Float = 1.2
    public static let minConsecutiveFrames = 5
    public static let calibrationFrames = 10

    private var neutralSamples: [Float] = []
    private var consecutiveFrames = 0

    public init() {}

    public var neutralMouthWidth: Float? {
        guard neutralSamples.count >= SmileDetector.calibrationFrames else { return nil }
        let sorted = neutralSamples.sorted()
        let n = sorted.count
        if n % 2 == 1 { return sorted[n / 2] }
        return (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    @discardableResult
    public func update(_ landmarks: FaceLandmarks, at _: TimeInterval = 0) -> Bool {
        let mouthWidth = distance2D(landmarks[61], landmarks[291])

        if neutralSamples.count < SmileDetector.calibrationFrames {
            // Skip degenerate frames (face lost, zero-width mouth).
            // Including them would drag the baseline toward 0, after
            // which the 1.2× trigger would fire on any normal mouth
            // motion — a false pass.
            guard mouthWidth > 0 else { return false }
            neutralSamples.append(mouthWidth)
            return false
        }

        guard let neutral = neutralMouthWidth, neutral > 0 else { return false }
        if mouthWidth > neutral * SmileDetector.widthMultiplier {
            consecutiveFrames += 1
            if consecutiveFrames >= SmileDetector.minConsecutiveFrames {
                consecutiveFrames = 0
                return true
            }
        } else {
            consecutiveFrames = 0
        }
        return false
    }

    public func reset() {
        neutralSamples.removeAll()
        consecutiveFrames = 0
    }
}

/// NOD — pitch proxy = (chin.y − forehead.y) / face_width. Fires when
/// (max − min) of the proxy within a 1-second window exceeds the threshold.
/// We use a vertical-delta proxy rather than a true atan2 pitch because
/// MediaPipe's z coordinate is a weak depth estimate on 2D camera feeds.
///
/// Face-width normalization (H-3): the raw y-delta scales with the user's
/// distance from the camera — a close user's face spans ~0.6 of image
/// height, a far user's ~0.2, so a fixed-threshold-in-raw-coords clears
/// trivially for close users and may never clear for far ones. Dividing
/// by face_width (horizontal anchor-to-anchor) cancels the distance
/// factor because both numerator and denominator shrink together as the
/// user moves away. The threshold is now in units of face-widths;
/// frames with a degenerate face_width (<=0) are dropped so we don't
/// divide by zero.
public final class NodDetector: GestureDetector {
    /// Threshold in units of face-widths. ~0.15 face-widths of normalized
    /// pitch-proxy change inside the 1s window ≈ a deliberate head nod
    /// across user-camera distances.
    public static let pitchDeltaThreshold: Float = 0.15
    public static let windowSeconds: TimeInterval = 1.0
    /// Hard cap on buffer size. The window filter alone is not enough:
    /// non-monotonic or backward-moving timestamps (resumed app, clock
    /// jump, same-timestamp frame storm) can keep every sample under
    /// the cutoff forever. 240 ≈ 4s at 60fps, comfortably past the 1s
    /// window; large enough that normal operation never trims.
    public static let maxSamples = 240

    private var buffer: [(timestamp: TimeInterval, pitch: Float)] = []

    public init() {}

    @discardableResult
    public func update(_ landmarks: FaceLandmarks, at timestamp: TimeInterval) -> Bool {
        // Face width = horizontal distance between the two face anchors
        // (same reference GazeDetector uses). Drop degenerate frames
        // (face lost) so a zero-width frame doesn't divide by zero or
        // inject a bogus sample into the window.
        let faceWidth = abs(landmarks[454].x - landmarks[234].x)
        guard faceWidth > 0 else { return false }
        let pitch = (landmarks[152].y - landmarks[10].y) / faceWidth
        buffer.append((timestamp, pitch))
        let cutoff = timestamp - NodDetector.windowSeconds
        buffer.removeAll { $0.timestamp < cutoff }
        // R-NEW-2: belt-and-suspenders bound after the window filter so a
        // hostile or buggy stream can't retain unbounded samples (see
        // maxSamples). Batched `removeFirst(k)` is a single shift of the
        // underlying storage; repeated `removeFirst()` would be O(n²) in
        // the overflow count.
        if buffer.count > NodDetector.maxSamples {
            buffer.removeFirst(buffer.count - NodDetector.maxSamples)
        }

        guard let minP = buffer.map({ $0.pitch }).min(),
              let maxP = buffer.map({ $0.pitch }).max() else {
            return false
        }
        if maxP - minP > NodDetector.pitchDeltaThreshold {
            buffer.removeAll()
            return true
        }
        return false
    }

    public func reset() { buffer.removeAll() }

    /// Internal test hook: tests shouldn't poke at mutable collections
    /// directly, but need to pin that the cap holds under adversarial
    /// timestamp inputs. Keep this out of the public interface.
    internal var bufferSizeForTest: Int { buffer.count }
}
