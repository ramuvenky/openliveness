import XCTest
@testable import OpenLiveness

/// Phase 3 Step 1 — gesture-detector unit tests. These exercise the pure
/// numeric logic in Layer2ActiveLiveness.swift without any MediaPipe or
/// camera dependency. The sibling file android-sdk/.../Layer2ActiveLivenessTest.kt
/// covers the same scenarios on the Kotlin side; a drift between the two
/// trips its mirror test.
final class Layer2DetectorsTests: XCTestCase {

    // Build a 478-landmark neutral face. Only the indices the detectors
    // actually read are populated; the rest stay at (0, 0, 0) so a stray
    // detector that reads an unexpected index would produce an obviously
    // broken (0, 0) landmark instead of silently working on random data.
    private func neutralFace() -> [FaceLandmark] {
        var pts = Array(repeating: FaceLandmark(x: 0, y: 0), count: FaceLandmarks.expectedCount)
        pts[1]   = FaceLandmark(x: 0.50, y: 0.50) // nose tip
        pts[10]  = FaceLandmark(x: 0.50, y: 0.20) // forehead
        pts[33]  = FaceLandmark(x: 0.40, y: 0.50) // left eye outer
        pts[61]  = FaceLandmark(x: 0.40, y: 0.60) // left mouth corner
        pts[133] = FaceLandmark(x: 0.50, y: 0.50) // right eye outer (eye_width = 0.10)
        pts[145] = FaceLandmark(x: 0.45, y: 0.50) // right eye lower lid
        pts[152] = FaceLandmark(x: 0.50, y: 0.80) // chin
        pts[159] = FaceLandmark(x: 0.45, y: 0.45) // right eye upper lid (EAR = 0.5, open)
        pts[234] = FaceLandmark(x: 0.30, y: 0.50) // face left anchor
        pts[291] = FaceLandmark(x: 0.60, y: 0.60) // right mouth corner (mouth_width = 0.20)
        pts[454] = FaceLandmark(x: 0.70, y: 0.50) // face right anchor (face_width = 0.40)
        return pts
    }

    private func lm(_ pts: [FaceLandmark]) -> FaceLandmarks { try! FaceLandmarks(points: pts) }

    // MARK: - Model

    func testFaceLandmarksAccepts478() throws {
        let f = try FaceLandmarks(points: neutralFace())
        XCTAssertEqual(f.points.count, 478)
        XCTAssertEqual(f[1].x, 0.50)
    }

    // M-5: wrong-count input must throw (not trap) so XCTest + host apps
    // can catch a MediaPipe model-config regression rather than crash.
    func testFaceLandmarksThrowsOnWrongCount() {
        XCTAssertThrowsError(try FaceLandmarks(points: [])) { err in
            XCTAssertEqual(err as? FaceLandmarksError, .wrongCount(got: 0, expected: 478))
        }
        let short = Array(repeating: FaceLandmark(x: 0, y: 0), count: 477)
        XCTAssertThrowsError(try FaceLandmarks(points: short)) { err in
            XCTAssertEqual(err as? FaceLandmarksError, .wrongCount(got: 477, expected: 478))
        }
    }

    // R-1 safe factory — a face-not-detected tick (0 points) must return
    // nil so an adapter can simply drop the frame instead of trapping
    // the host process via the direct initializer's precondition.
    func testFaceLandmarksValidatingReturnsNilForEmptyOrPartial() {
        XCTAssertNil(FaceLandmarks.validating(points: []))
        XCTAssertNil(FaceLandmarks.validating(points: Array(repeating: FaceLandmark(x: 0, y: 0), count: 100)))
        XCTAssertNotNil(FaceLandmarks.validating(points: neutralFace()),
            "exact-count input must build successfully")
    }

    // MARK: - Blink

    func testBlinkDetectorFiresOnCloseThenReopen() {
        let d = BlinkDetector()
        // 2 frames neutral — eye open, no fire.
        for _ in 0..<2 { XCTAssertFalse(d.update(lm(neutralFace()))) }
        // Close eye for 2 frames: shrink EAR by pulling upper lid near lower.
        var closed = neutralFace()
        closed[159] = FaceLandmark(x: 0.45, y: 0.49) // vertical dist = 0.01, EAR = 0.1
        for _ in 0..<2 { XCTAssertFalse(d.update(lm(closed))) }
        // Reopen — fire on the first open frame after a qualifying close.
        XCTAssertTrue(d.update(lm(neutralFace())))
    }

    func testBlinkDetectorDoesNotFireWithoutReopen() {
        // A static closed-eye photo (never reopens) must not count. This is
        // the whole point of the reopen gate — a 2D presentation attack
        // can hold "closed" indefinitely but can't produce the transition.
        let d = BlinkDetector()
        var closed = neutralFace()
        closed[159] = FaceLandmark(x: 0.45, y: 0.49)
        for _ in 0..<10 { XCTAssertFalse(d.update(lm(closed))) }
    }

    // R-2 presentation-attack guard — a sustained closed-eye photo
    // briefly nudged off-camera (eye_width == 0 → face lost) must NOT
    // fire on reacquisition. Without the latch reset, the hostile path
    // would have been: closed, closed, LOST, open → fire. We lock the
    // reset here so a regression that drops the reset re-opens the
    // trivially-exploitable path.
    func testBlinkDetectorDoesNotFireAfterFaceLossGap() {
        let d = BlinkDetector()
        var closed = neutralFace()
        closed[159] = FaceLandmark(x: 0.45, y: 0.49)
        // Latch arms (closed for ≥2 frames).
        XCTAssertFalse(d.update(lm(closed)))
        XCTAssertFalse(d.update(lm(closed)))
        // Face-tracking loss — zero eye width simulates camera off-face.
        var lost = neutralFace()
        lost[33]  = FaceLandmark(x: 0.50, y: 0.50)
        lost[133] = FaceLandmark(x: 0.50, y: 0.50)
        XCTAssertFalse(d.update(lm(lost)))
        // Reacquisition on an open eye: must NOT fire because the latch
        // reset when the face was lost. A real blink needs a fresh
        // closed→open transition after reacquisition.
        XCTAssertFalse(d.update(lm(neutralFace())))
    }

    func testBlinkDetectorIgnoresSingleFrameNoise() {
        // One below-threshold frame flanked by open frames should not arm
        // the reopen state — would otherwise fire on sensor flicker.
        let d = BlinkDetector()
        var dip = neutralFace()
        dip[159] = FaceLandmark(x: 0.45, y: 0.49)
        XCTAssertFalse(d.update(lm(dip)))
        XCTAssertFalse(d.update(lm(neutralFace())))
        XCTAssertFalse(d.update(lm(neutralFace())))
    }

    // MARK: - Gaze

    func testGazeLeftFiresAfterThreeConsecutiveFrames() {
        let d = GazeDetector(.left)
        var left = neutralFace()
        left[1] = FaceLandmark(x: 0.42, y: 0.50) // offset = (0.42-0.50)/0.40 = -0.20
        XCTAssertFalse(d.update(lm(left)))
        XCTAssertFalse(d.update(lm(left)))
        XCTAssertTrue(d.update(lm(left)))
    }

    func testGazeRightFiresAfterThreeConsecutiveFrames() {
        let d = GazeDetector(.right)
        var right = neutralFace()
        right[1] = FaceLandmark(x: 0.58, y: 0.50) // offset = +0.20
        XCTAssertFalse(d.update(lm(right)))
        XCTAssertFalse(d.update(lm(right)))
        XCTAssertTrue(d.update(lm(right)))
    }

    func testGazeLeftRejectsRightwardOffset() {
        // Reusing a LOOK_LEFT detector in a LOOK_RIGHT challenge is a
        // sequencer bug this guards against — directional detectors must
        // not cross-fire.
        let d = GazeDetector(.left)
        var right = neutralFace()
        right[1] = FaceLandmark(x: 0.58, y: 0.50)
        for _ in 0..<10 { XCTAssertFalse(d.update(lm(right))) }
    }

    func testGazeConsecutiveFrameCounterResets() {
        // A non-matching frame mid-sequence must zero the counter — otherwise
        // random head motion over many frames would eventually clear the
        // threshold cumulatively.
        let d = GazeDetector(.left)
        var left = neutralFace()
        left[1] = FaceLandmark(x: 0.42, y: 0.50)
        XCTAssertFalse(d.update(lm(left)))
        XCTAssertFalse(d.update(lm(neutralFace()))) // reset
        XCTAssertFalse(d.update(lm(left)))
        XCTAssertFalse(d.update(lm(left)))
        XCTAssertTrue(d.update(lm(left)))
    }

    // MARK: - Smile

    func testSmileDetectorRequiresCalibrationThenFires() throws {
        let d = SmileDetector()
        // First 10 frames establish neutral mouth_width = 0.20.
        for _ in 0..<10 { XCTAssertFalse(d.update(lm(neutralFace()))) }
        let neutral = try XCTUnwrap(d.neutralMouthWidth)
        XCTAssertEqual(neutral, 0.20, accuracy: 1e-6)
        // Widen mouth: new width = 0.25 > 0.20 * 1.2 = 0.24.
        var smile = neutralFace()
        smile[291] = FaceLandmark(x: 0.65, y: 0.60)
        for _ in 0..<4 { XCTAssertFalse(d.update(lm(smile))) }
        XCTAssertTrue(d.update(lm(smile)))
    }

    // R-3 calibration-corruption guard — zero-width frames (face lost
    // during the warmup) MUST NOT be sampled into the neutral baseline.
    // Previous behavior would have averaged 0.0 into the mean, dragging
    // the trigger (neutral * 1.2) toward 0 and firing on any normal
    // mouth motion — a false-pass path we want to lock closed.
    func testSmileDetectorSkipsZeroWidthCalibrationSamples() {
        let d = SmileDetector()
        // 5 real neutral frames + 5 face-lost frames: calibration still
        // needs 5 more real frames before fire path is live, and the
        // baseline must equal 0.20 (clean), not an average that
        // includes 0.0s.
        for _ in 0..<5 { _ = d.update(lm(neutralFace())) }
        var lost = neutralFace()
        lost[61]  = FaceLandmark(x: 0.50, y: 0.50)
        lost[291] = FaceLandmark(x: 0.50, y: 0.50) // mouth_width = 0
        for _ in 0..<5 { _ = d.update(lm(lost)) } // dropped — must not advance calibration
        // neutralMouthWidth is still nil (only 5 real samples, need 10).
        XCTAssertNil(d.neutralMouthWidth)
        for _ in 0..<5 { _ = d.update(lm(neutralFace())) }
        XCTAssertEqual(d.neutralMouthWidth, 0.20, accuracy: 1e-6)
    }

    func testSmileDetectorDoesNotFireBelowThreshold() {
        let d = SmileDetector()
        for _ in 0..<10 { _ = d.update(lm(neutralFace())) }
        // 0.23 is just below the 1.2× threshold — should not fire.
        var mild = neutralFace()
        mild[291] = FaceLandmark(x: 0.63, y: 0.60)
        for _ in 0..<10 { XCTAssertFalse(d.update(lm(mild))) }
    }

    // H-4 — SmileDetector baseline uses the MEDIAN of calibration samples,
    // not the mean. An eager user who smiles for a minority of the warmup
    // window (e.g., 3 of 10 frames) would drag the mean up and lock
    // themselves out of ever clearing the 1.2× trigger. Median shrugs off
    // minority outliers and stays at the user's real neutral width.
    //
    // Scenario: 7 neutral frames at mouth_width 0.20 + 3 early-smile
    // frames at 0.30 during warmup.
    //   mean baseline   = (0.20*7 + 0.30*3) / 10 = 0.23 → trigger 0.276
    //   median baseline = 0.20                        → trigger 0.240
    // A legitimate 5-frame smile at mouth_width 0.26 clears median
    // (fires), does NOT clear mean (locked out).
    func testSmileUsesMedianBaselineThatIgnoresMinorityOutliers() throws {
        let d = SmileDetector()
        // 7 neutral calibration frames.
        for _ in 0..<7 { _ = d.update(lm(neutralFace())) }
        // 3 early-smile calibration frames (user eager; mouth_width=0.30).
        var early = neutralFace()
        early[291] = FaceLandmark(x: 0.70, y: 0.60)
        for _ in 0..<3 { _ = d.update(lm(early)) }
        let neutral = try XCTUnwrap(d.neutralMouthWidth)
        XCTAssertEqual(neutral, 0.20, accuracy: 1e-6)

        // A legitimate smile at mouth_width 0.26 (0.20 * 1.3 — above the
        // median trigger of 0.24, below the mean trigger of 0.276).
        var smile = neutralFace()
        smile[291] = FaceLandmark(x: 0.66, y: 0.60)
        for _ in 0..<4 { XCTAssertFalse(d.update(lm(smile))) }
        XCTAssertTrue(d.update(lm(smile)),
            "smile at 1.3× must fire with median baseline (would be locked out by mean)")
    }

    // MARK: - Nod

    func testNodDetectorFiresWhenPitchRangeExceedsThresholdWithinOneSecond() {
        let d = NodDetector()
        // t=0.0: pitch proxy = 0.20 - 0.80 = -0.60
        XCTAssertFalse(d.update(lm(neutralFace()), at: 0.0))
        // t=0.3: head down — forehead y = 0.40 → pitch = -0.40.
        // Range = -0.40 - (-0.60) = 0.20 > 0.15 → fire.
        var down = neutralFace()
        down[10] = FaceLandmark(x: 0.50, y: 0.40)
        XCTAssertTrue(d.update(lm(down), at: 0.3))
    }

    func testNodDetectorDoesNotFireIfSpanOutsideWindow() {
        // Two samples 1.5s apart are not a nod — the window drops the
        // older sample so only the current pitch is in the buffer.
        let d = NodDetector()
        XCTAssertFalse(d.update(lm(neutralFace()), at: 0.0))
        var down = neutralFace()
        down[10] = FaceLandmark(x: 0.50, y: 0.40)
        XCTAssertFalse(d.update(lm(down), at: 1.5))
    }

    // R-4 unbounded-buffer guard (same-timestamp storm). A hostile or
    // buggy stream repeating the same timestamp would keep every sample
    // inside the 1-second window forever (cutoff = t - 1 < t). The
    // maxSamples=240 cap must trim regardless. Push 1000 frames and
    // confirm the buffer never exceeds the cap.
    func testNodBufferStaysCappedUnderSameTimestampStorm() {
        let d = NodDetector()
        for _ in 0..<1000 { _ = d.update(lm(neutralFace()), at: 5.0) }
        XCTAssertLessThanOrEqual(d.bufferSizeForTest, 240,
            "nod buffer must respect maxSamples cap, got \(d.bufferSizeForTest)")
    }

    // H-3 — pitch is normalized by face_width so detection is distance-
    // invariant. A "far user" (small face in frame) has a tiny raw y
    // delta between neutral and nod positions; without normalization the
    // raw delta fails to clear the 0.15 threshold and the detector
    // becomes unusable at normal arm's-length distances. After the fix,
    // the same relative nod magnitude fires at any face scale.
    //
    // Scenario: shrink the whole face uniformly so face_width = 0.10
    // (quarter-size relative to the standard 0.40 fixture). A quarter-
    // scale nod has a raw y delta of ~0.025 — well under the 0.15
    // threshold if interpreted as raw y units. Normalized by
    // face_width=0.10 it becomes 0.25 → fires.
    func testNodNormalizationMakesDetectionDistanceInvariant() {
        let d = NodDetector()
        var farNeutral = Array(repeating: FaceLandmark(x: 0, y: 0), count: FaceLandmarks.expectedCount)
        farNeutral[10]  = FaceLandmark(x: 0.50, y: 0.45) // forehead
        farNeutral[152] = FaceLandmark(x: 0.50, y: 0.55) // chin
        farNeutral[234] = FaceLandmark(x: 0.45, y: 0.50) // face left anchor
        farNeutral[454] = FaceLandmark(x: 0.55, y: 0.50) // face right (face_width=0.10)
        XCTAssertFalse(d.update(lm(farNeutral), at: 0.0))
        var farDown = Array(repeating: FaceLandmark(x: 0, y: 0), count: FaceLandmarks.expectedCount)
        farDown[10]  = FaceLandmark(x: 0.50, y: 0.475) // nod moves forehead down 0.025 raw-y
        farDown[152] = FaceLandmark(x: 0.50, y: 0.55)
        farDown[234] = FaceLandmark(x: 0.45, y: 0.50)
        farDown[454] = FaceLandmark(x: 0.55, y: 0.50)
        XCTAssertTrue(d.update(lm(farDown), at: 0.3),
            "small face (face_width=0.10) with raw-y delta 0.025 must fire post-H3 (normalized=0.25 > 0.15)")
    }

    // H-3 companion — degenerate frames (face lost, zero face_width) are
    // dropped. Without the guard we'd divide by zero and either NaN-up
    // the buffer or inject bogus pitch samples that could prematurely
    // trigger the window-span check.
    func testNodDropsZeroFaceWidthFrames() {
        let d = NodDetector()
        var lost = neutralFace()
        lost[234] = FaceLandmark(x: 0.50, y: 0.50)
        lost[454] = FaceLandmark(x: 0.50, y: 0.50) // face_width = 0
        for _ in 0..<20 { _ = d.update(lm(lost), at: 0.0) }
        XCTAssertEqual(d.bufferSizeForTest, 0,
            "zero-face-width frames must not be sampled into the pitch buffer")
    }

    // R-4 companion — backward-moving timestamps (resumed app, clock
    // jump). cutoff = t_latest - 1s < any earlier t, so every older
    // sample stays "fresh" by window check. maxSamples must still trim.
    func testNodBufferStaysCappedUnderBackwardTimestamps() {
        let d = NodDetector()
        // Start at t=1000, walk back to t=0 across 1000 frames.
        var t: TimeInterval = 1000
        for _ in 0..<1000 {
            _ = d.update(lm(neutralFace()), at: t)
            t -= 1.0
        }
        XCTAssertLessThanOrEqual(d.bufferSizeForTest, 240,
            "nod buffer must respect maxSamples under backward timestamps, got \(d.bufferSizeForTest)")
    }
}
