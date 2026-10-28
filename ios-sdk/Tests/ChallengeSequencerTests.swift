import XCTest
@testable import OpenLiveness

/// Phase 3 Step 2 — challenge-sequencer unit tests. These exercise the
/// pure sequencing + timing logic without any MediaPipe or camera
/// dependency. The sibling file android-sdk/.../ChallengeSequencerTest.kt
/// covers the same scenarios on the Kotlin side; a drift between the two
/// trips its mirror test.
final class ChallengeSequencerTests: XCTestCase {

    private func neutralFace() -> [FaceLandmark] {
        var pts = Array(repeating: FaceLandmark(x: 0, y: 0), count: FaceLandmarks.expectedCount)
        pts[1]   = FaceLandmark(x: 0.50, y: 0.50)
        pts[10]  = FaceLandmark(x: 0.50, y: 0.20)
        pts[33]  = FaceLandmark(x: 0.40, y: 0.50)
        pts[61]  = FaceLandmark(x: 0.40, y: 0.60)
        pts[133] = FaceLandmark(x: 0.50, y: 0.50)
        pts[145] = FaceLandmark(x: 0.45, y: 0.50)
        pts[152] = FaceLandmark(x: 0.50, y: 0.80)
        pts[159] = FaceLandmark(x: 0.45, y: 0.45)
        pts[234] = FaceLandmark(x: 0.30, y: 0.50)
        pts[291] = FaceLandmark(x: 0.60, y: 0.60)
        pts[454] = FaceLandmark(x: 0.70, y: 0.50)
        return pts
    }
    private func lm(_ pts: [FaceLandmark]) -> FaceLandmarks { try! FaceLandmarks(points: pts) }

    private func closedEyeFrame() -> FaceLandmarks {
        var p = neutralFace()
        p[159] = FaceLandmark(x: 0.45, y: 0.49) // EAR drops below 0.2
        return lm(p)
    }
    private func leftGazeFrame() -> FaceLandmarks {
        var p = neutralFace()
        p[1] = FaceLandmark(x: 0.42, y: 0.50) // offset = -0.2
        return lm(p)
    }
    private func rightGazeFrame() -> FaceLandmarks {
        var p = neutralFace()
        p[1] = FaceLandmark(x: 0.58, y: 0.50) // offset = +0.2
        return lm(p)
    }

    // MARK: - Construction

    func testEmptySequenceThrows() {
        XCTAssertThrowsError(try ChallengeSequencer(wire: [])) { err in
            XCTAssertEqual(err as? ChallengeSequencerError, .emptySequence)
        }
    }

    func testUnknownChallengeThrows() {
        XCTAssertThrowsError(try ChallengeSequencer(wire: ["blink", "wink"])) { err in
            XCTAssertEqual(err as? ChallengeSequencerError, .unknownChallenge("wink"))
        }
    }

    func testWireStringsMatchRelayContract() throws {
        // These exact lowercase snake_case strings are the contract with
        // relay/src/session/types.ts CHALLENGE_STEPS — if someone renames
        // a case here the server will dispatch sequences the SDK can't
        // parse.
        let seq = try ChallengeSequencer(wire: ["blink", "look_left", "look_right", "smile", "nod"])
        XCTAssertEqual(seq.sequence, [.blink, .lookLeft, .lookRight, .smile, .nod])
    }

    // MARK: - time_score buckets (Spec §1342)

    func testTimeScoreBuckets() {
        XCTAssertEqual(ChallengeSequencer.timeScore(elapsed: 0.0), 1.0)
        XCTAssertEqual(ChallengeSequencer.timeScore(elapsed: 2.999), 1.0)
        // 3.0 is the lower boundary of the 0.9 bucket (upper-exclusive).
        XCTAssertEqual(ChallengeSequencer.timeScore(elapsed: 3.0), 0.9)
        XCTAssertEqual(ChallengeSequencer.timeScore(elapsed: 4.999), 0.9)
        XCTAssertEqual(ChallengeSequencer.timeScore(elapsed: 5.0), 0.7)
        XCTAssertEqual(ChallengeSequencer.timeScore(elapsed: 8.0), 0.7)
        XCTAssertEqual(ChallengeSequencer.timeScore(elapsed: 8.001), 0.0)
    }

    // MARK: - Happy path

    func testTwoStepSequenceCompletesAndScores() throws {
        // Blink then look-left. Both complete well under 3s → score 1.0.
        let seq = try ChallengeSequencer(sequence: [.blink, .lookLeft])
        var events: [ChallengeEvent] = []
        var t = 0.0

        // Blink: 2 closed frames then 1 open frame fires.
        if let e = seq.update(closedEyeFrame(), at: t) { events.append(e) }; t += 0.033
        if let e = seq.update(closedEyeFrame(), at: t) { events.append(e) }; t += 0.033
        if let e = seq.update(lm(neutralFace()), at: t) { events.append(e) }; t += 0.033

        // Expected: one .stepCompleted(.blink, 0).
        XCTAssertEqual(events, [.stepCompleted(step: .blink, index: 0)])

        // Look-left: fires on the 3rd consecutive matching frame.
        if let e = seq.update(leftGazeFrame(), at: t) { events.append(e) }; t += 0.033
        if let e = seq.update(leftGazeFrame(), at: t) { events.append(e) }; t += 0.033
        let last = seq.update(leftGazeFrame(), at: t)

        guard case .finished(let r)? = last else {
            return XCTFail("expected .finished, got \(String(describing: last))")
        }
        XCTAssertTrue(r.completed)
        XCTAssertEqual(r.score, 1.0, accuracy: 1e-6)
        XCTAssertEqual(r.stepsCompleted, 2)
        XCTAssertEqual(r.totalSteps, 2)
        XCTAssertTrue(seq.isFinished)
    }

    // MARK: - Timeout

    func testTimeoutFiresFinishedWithZeroScore() throws {
        let seq = try ChallengeSequencer(sequence: [.blink])
        // First frame at t=0 starts the clock; next frame past 8s times out.
        XCTAssertNil(seq.update(lm(neutralFace()), at: 0.0))
        guard case .finished(let r)? = seq.update(lm(neutralFace()), at: 8.001) else {
            return XCTFail("expected timeout .finished")
        }
        XCTAssertFalse(r.completed)
        XCTAssertEqual(r.score, 0.0)
        XCTAssertEqual(r.stepsCompleted, 0)
        XCTAssertEqual(r.totalSteps, 1)
    }

    // MARK: - Direction isolation

    func testWrongDirectionDoesNotAdvanceLookLeftStep() throws {
        // Guards against a sequencer bug where the wrong detector is
        // picked for a step: a right-gaze stream must never satisfy a
        // LOOK_LEFT step.
        let seq = try ChallengeSequencer(sequence: [.lookLeft])
        var t = 0.0
        for _ in 0..<10 {
            XCTAssertNil(seq.update(rightGazeFrame(), at: t))
            t += 0.033
        }
        XCTAssertFalse(seq.isFinished)
    }

    // MARK: - Idempotence

    func testUpdateAfterFinishedReturnsNil() throws {
        let seq = try ChallengeSequencer(sequence: [.blink])
        // Drive to completion.
        _ = seq.update(closedEyeFrame(), at: 0.0)
        _ = seq.update(closedEyeFrame(), at: 0.033)
        guard case .finished? = seq.update(lm(neutralFace()), at: 0.066) else {
            return XCTFail("expected .finished")
        }
        // Further updates must not re-fire events; the sequencer is sealed.
        XCTAssertNil(seq.update(lm(neutralFace()), at: 0.1))
        XCTAssertNil(seq.update(closedEyeFrame(), at: 0.2))
    }

    // R-5 — a resumed app or clock jump can hand the sequencer a frame
    // with a timestamp earlier than the first-frame baseline. Without
    // the elapsed floor, (ts - startTime) is negative, `elapsed > 8s`
    // is false, the sequencer never times out, and the session hangs
    // the UI forever. Lock in that backward timestamps don't bypass
    // the timeout path.
    func testBackwardTimestampDoesNotBypassTimeout() throws {
        let seq = try ChallengeSequencer(sequence: [.blink])
        // First frame establishes baseline at t=1000.
        XCTAssertNil(seq.update(lm(neutralFace()), at: 1000.0))
        // Jump backward by 100s — raw elapsed would be -100 (looks fine
        // to the timeout check). The clock-floor keeps elapsed at 0 so
        // the sequencer stays live.
        let ev1 = seq.update(lm(neutralFace()), at: 900.0)
        XCTAssertNil(ev1)
        XCTAssertFalse(seq.isFinished)
        // Forward jump past the 8s budget triggers timeout cleanly.
        guard case .finished? = seq.update(lm(neutralFace()), at: 1008.001) else {
            return XCTFail("forward jump past 8s should finalize")
        }
    }

    // R-NEW-1 — a NaN timestamp from a buggy upstream clock conversion
    // (e.g., ns/sec divide-by-zero in a camera adapter) must not hang
    // the sequencer. `NaN > 8.0` is false, so without the isFinite
    // guard the sequencer would keep waiting for a frame that'll never
    // pass the timeout check. Lock in: NaN/Infinity → immediate
    // Finished.
    func testNonFiniteTimestampTriggersImmediateTimeout() throws {
        let seqNaN = try ChallengeSequencer(sequence: [.blink])
        let evNaN = seqNaN.update(lm(neutralFace()), at: .nan)
        guard case .finished(let r)? = evNaN else {
            return XCTFail("NaN timestamp must finalize immediately, got \(String(describing: evNaN))")
        }
        XCTAssertFalse(r.completed)

        let seqInf = try ChallengeSequencer(sequence: [.blink])
        let evInf = seqInf.update(lm(neutralFace()), at: .infinity)
        guard case .finished = evInf else {
            return XCTFail("Infinity timestamp must finalize immediately, got \(String(describing: evInf))")
        }
    }

    // H-2 — consecutive duplicate steps must be rejected at construction.
    // Blink/Gaze/Smile detectors self-reset on fire; a sustained gesture
    // would silently satisfy both slots from one presentation. The server
    // isn't supposed to issue such a sequence, but a hostile fork of the
    // relay could — rejecting at the SDK boundary means a client can't be
    // tricked into accepting an exploitable prompt order.
    func testConsecutiveDuplicateStepsRejectedViaWire() {
        XCTAssertThrowsError(try ChallengeSequencer(wire: ["blink", "blink"])) { err in
            XCTAssertEqual(err as? ChallengeSequencerError,
                           .consecutiveDuplicateSteps(step: .blink, index: 1))
        }
    }

    func testConsecutiveDuplicateStepsRejectedViaSequenceInit() {
        XCTAssertThrowsError(try ChallengeSequencer(sequence: [.lookLeft, .lookLeft])) { err in
            XCTAssertEqual(err as? ChallengeSequencerError,
                           .consecutiveDuplicateSteps(step: .lookLeft, index: 1))
        }
    }

    func testNonAdjacentDuplicateStepsStillAllowed() throws {
        // A → B → A is a legit sequence — the B step forces the user back
        // to a neutral state between the two A's.
        let seq = try ChallengeSequencer(sequence: [.blink, .lookLeft, .blink])
        XCTAssertEqual(seq.sequence.count, 3)
    }

    // M-3 — the sequencer tracks firedSteps so AttestationBuilder can emit
    // the real detected prefix on the wire. Timed-out run keeps the
    // partial list; completed run has all steps.
    func testFiredStepsCarriesDetectedPrefixOnTimeout() throws {
        let seq = try ChallengeSequencer(sequence: [.blink, .lookLeft])
        // Fire blink.
        _ = seq.update(closedEyeFrame(), at: 0.0)
        _ = seq.update(closedEyeFrame(), at: 0.033)
        _ = seq.update(lm(neutralFace()), at: 0.066) // completes blink, index→1
        // Now time out before look-left fires.
        guard case .finished(let r)? = seq.update(lm(neutralFace()), at: 8.5) else {
            return XCTFail("expected .finished on timeout")
        }
        XCTAssertFalse(r.completed)
        XCTAssertEqual(r.firedSteps, [.blink])
        XCTAssertEqual(seq.firedStepsSoFar, [.blink])
    }

    func testFiredStepsEqualsFullSequenceOnCompletedRun() throws {
        let seq = try ChallengeSequencer(sequence: [.blink, .lookLeft])
        _ = seq.update(closedEyeFrame(), at: 0.0)
        _ = seq.update(closedEyeFrame(), at: 0.033)
        _ = seq.update(lm(neutralFace()), at: 0.066)
        _ = seq.update(leftGazeFrame(), at: 0.1)
        _ = seq.update(leftGazeFrame(), at: 0.133)
        guard case .finished(let r)? = seq.update(leftGazeFrame(), at: 0.166) else {
            return XCTFail("expected .finished")
        }
        XCTAssertTrue(r.completed)
        XCTAssertEqual(r.firedSteps, [.blink, .lookLeft])
    }

    // MARK: - Elapsed clock start

    func testElapsedMeasuredFromFirstUpdateNotInit() throws {
        // The sequencer may be allocated well before the UI prompt
        // appears. Elapsed must be relative to the first frame so the
        // user gets a full 8s budget regardless of allocation time.
        let seq = try ChallengeSequencer(sequence: [.blink])
        let t0 = 100.0 // arbitrary wall clock
        _ = seq.update(closedEyeFrame(), at: t0)
        _ = seq.update(closedEyeFrame(), at: t0 + 0.033)
        guard case .finished(let r)? = seq.update(lm(neutralFace()), at: t0 + 0.066) else {
            return XCTFail("expected .finished")
        }
        XCTAssertEqual(r.elapsedSeconds, 0.066, accuracy: 1e-6)
        XCTAssertEqual(r.score, 1.0) // well under 3s
    }
}
