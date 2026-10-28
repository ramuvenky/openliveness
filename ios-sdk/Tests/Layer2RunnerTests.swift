import XCTest
@testable import OpenLiveness

/// Phase 3 Step 4 — Layer2Runner tests. The runner is the piece that
/// couples the pure sequencer (Step 2) to the camera-and-WebSocket world
/// in CDLSession. We fake both the landmark stream and the WS emitter so
/// the frame-pump + processing_update emission can be exercised without
/// a camera or a server. Sibling file lives under
/// android-sdk/.../Layer2RunnerTest.kt.
final class Layer2RunnerTests: XCTestCase {

    // MARK: - Fakes

    /// Yields a pre-recorded script of (landmarks, timestamp) frames,
    /// one per nextFrame() call, then returns nil. Represents a camera
    /// stream that ran out of frames mid-session.
    private final class ScriptedStream: LandmarkStream {
        var frames: [(FaceLandmarks, TimeInterval)]
        init(_ frames: [(FaceLandmarks, TimeInterval)]) { self.frames = frames }
        func nextFrame() async throws -> (FaceLandmarks, TimeInterval)? {
            frames.isEmpty ? nil : frames.removeFirst()
        }
    }

    /// Records every (progress, currentLayer) tuple the runner emits so
    /// the test can assert on the exact sequence of processing_update
    /// frames that would hit the WebSocket in production.
    private final class EmitRecorder {
        var calls: [(Float, String)] = []
        func emit(_ progress: Float, _ layer: String) {
            calls.append((progress, layer))
        }
    }

    private func neutralFace() -> [FaceLandmark] {
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
    private func lm(_ pts: [FaceLandmark]) -> FaceLandmarks { try! FaceLandmarks(points: pts) }
    private func closedEye() -> FaceLandmarks {
        var p = neutralFace()
        p[159] = FaceLandmark(x: 0.45, y: 0.49)
        return lm(p)
    }
    private func leftGaze() -> FaceLandmarks {
        var p = neutralFace()
        p[1] = FaceLandmark(x: 0.42, y: 0.50)
        return lm(p)
    }

    // MARK: - Happy path

    func testRunCompletesAndEmitsPerStepThenTerminalProgress() async throws {
        // One-step blink sequence. Script: 2 closed frames then 1 open
        // frame → blink fires → sequencer finished.
        let seq = try ChallengeSequencer(sequence: [.blink])
        let stream = ScriptedStream([
            (closedEye(), 0.0),
            (closedEye(), 0.033),
            (lm(neutralFace()), 0.066),
        ])
        let rec = EmitRecorder()
        let runner = Layer2Runner(sequencer: seq, stream: stream) { p, l in rec.emit(p, l) }

        let result = try await runner.run()

        XCTAssertTrue(result.completed)
        XCTAssertEqual(result.stepsCompleted, 1)
        XCTAssertEqual(result.totalSteps, 1)
        // One emit — the finished-and-completed emit — at progress 1.0.
        // The sequencer collapses step N completion with run completion
        // onto a single Finished event when N is the last step; the
        // runner must therefore not double-fire a stepCompleted emit.
        XCTAssertEqual(rec.calls.count, 1)
        XCTAssertEqual(rec.calls[0].0, 1.0)
        XCTAssertEqual(rec.calls[0].1, Layer2Runner.wireLayerLabel)
    }

    // MARK: - Stream drains before completion

    func testStreamEndingBeforeCompletionYieldsNotCompletedResult() async throws {
        // Script runs out mid-sequence. The runner must still return a
        // terminal ChallengeResult (completed=false, score=0) instead of
        // hanging — otherwise CDLSession.start would never submit the
        // attestation.
        let seq = try ChallengeSequencer(sequence: [.blink])
        let stream = ScriptedStream([(closedEye(), 0.0)]) // only 1 frame
        let rec = EmitRecorder()
        let runner = Layer2Runner(sequencer: seq, stream: stream) { p, l in rec.emit(p, l) }

        let result = try await runner.run()

        XCTAssertFalse(result.completed)
        XCTAssertEqual(result.score, 0.0)
        XCTAssertEqual(result.stepsCompleted, 0)
        // No emits — nothing completed and the sequencer never fired
        // Finished. We don't emit a fake progress line for the aborted
        // run; the server would misread it as "almost done".
        XCTAssertEqual(rec.calls.count, 0)
    }

    // R-6 — camera drops mid-run after the user already finished a step.
    // Previously the runner's stream-end fallback returned stepsCompleted=0
    // and elapsedSeconds=0 regardless of how far the user got, because
    // sequencer.result is only populated on .finished. Lock in the real
    // diagnostic values so a server-side regression investigation can
    // tell "camera died at step 1" from "user never started".
    func testStreamEndingAfterStepRecordsRealStepsAndElapsed() async throws {
        let seq = try ChallengeSequencer(sequence: [.blink, .lookLeft])
        // Complete the BLINK step, then stream dies before LOOK_LEFT.
        let stream = ScriptedStream([
            (closedEye(), 0.0),
            (closedEye(), 0.033),
            (lm(neutralFace()), 0.066),  // BLINK fires here
            (leftGaze(), 0.1)            // stream ends after this frame
        ])
        let rec = EmitRecorder()
        let runner = Layer2Runner(sequencer: seq, stream: stream) { p, l in rec.emit(p, l) }

        let result = try await runner.run()

        XCTAssertFalse(result.completed)
        XCTAssertEqual(result.score, 0.0)
        XCTAssertEqual(result.stepsCompleted, 1,
            "stream-end fallback must report real stepsCompleted, not 0")
        XCTAssertEqual(result.totalSteps, 2)
        // elapsedSeconds should be lastTimestamp - firstTimestamp = 0.1.
        XCTAssertEqual(result.elapsedSeconds, 0.1, accuracy: 1e-6,
            "stream-end fallback must report real elapsed, not 0")
        // The step-completed emit happened mid-stream, so progress 0.5
        // was sent to the WS before the stream died. The fallback
        // doesn't emit a trailing progress — that's a UI concern the
        // caller handles.
        XCTAssertEqual(rec.calls.count, 1)
        XCTAssertEqual(rec.calls[0].0, 0.5, accuracy: 1e-6)
    }

    // MARK: - Wire label

    func testEmitsStableWireLabel() {
        // The server slices current_layer to 40 chars and the browser
        // dispatches UI on this exact string. Guard against a quiet
        // rename of the constant that would silently break the browser.
        XCTAssertEqual(Layer2Runner.wireLayerLabel, "layer2_active_liveness")
    }
}
