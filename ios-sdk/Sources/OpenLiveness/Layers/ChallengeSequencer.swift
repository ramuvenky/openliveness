import Foundation

/// Wire strings for the five Layer 2 gestures. The raw values match
/// `relay/src/session/types.ts` CHALLENGE_STEPS exactly — the server
/// composes the sequence from these strings, and the SDK deserializes
/// them back into this enum. Renaming a case here without updating the
/// server (or vice versa) silently breaks the handshake.
public enum Challenge: String, CaseIterable {
    case blink
    case lookLeft  = "look_left"
    case lookRight = "look_right"
    case smile
    case nod
}

/// Terminal result of a challenge run. `completed` and `score` are the
/// two fields the orchestrator reads to populate layer_scores; the rest
/// are advisory (logging / UI). We keep them in the struct instead of
/// behind getters so a crash dump has the full picture of what the run
/// looked like without any method calls.
public struct ChallengeResult: Equatable {
    public let completed: Bool
    public let score: Float
    public let elapsedSeconds: TimeInterval
    public let stepsCompleted: Int
    public let totalSteps: Int
    /// Challenges in the exact order their detectors fired. Length ==
    /// `stepsCompleted`. The attestation builder joins the wire strings
    /// here into `challenge_response` so the server sees what the SDK
    /// actually detected, not just what the server prompted. On a
    /// `completed==true` run this equals the issued sequence; on a
    /// partial/timed-out run it's the prefix of steps the user got through.
    /// Empty on constructor-fabricated terminal results (empty stream).
    public let firedSteps: [Challenge]

    public init(
        completed: Bool,
        score: Float,
        elapsedSeconds: TimeInterval,
        stepsCompleted: Int,
        totalSteps: Int,
        firedSteps: [Challenge] = []
    ) {
        self.completed = completed
        self.score = score
        self.elapsedSeconds = elapsedSeconds
        self.stepsCompleted = stepsCompleted
        self.totalSteps = totalSteps
        self.firedSteps = firedSteps
    }
}

/// Events the sequencer emits as frames stream in. A fresh `.stepCompleted`
/// drives the on-screen "Blink now → Look left" prompt change; `.finished`
/// is terminal and tells the orchestrator Layer 2 is ready to score.
public enum ChallengeEvent: Equatable {
    case stepCompleted(step: Challenge, index: Int)
    case finished(ChallengeResult)
}

/// Thrown by `ChallengeSequencer.init` so a malformed wire payload fails
/// at construction instead of mid-session. Having the error cases as an
/// enum (not strings) means the UI layer can map each to a user message
/// without string-matching.
public enum ChallengeSequencerError: Error, Equatable {
    case emptySequence
    case unknownChallenge(String)
    /// Two adjacent steps resolve to the same Challenge. Blink/Gaze/Smile
    /// detectors self-reset on fire; a sustained gesture would silently
    /// satisfy both slots from one presentation. We reject the sequence
    /// at construction so the server can't ship an exploitable prompt
    /// order — detectors would need a cooldown-until-neutral gate before
    /// this could be lifted. Mirrors Kotlin
    /// `ChallengeSequencerException.ConsecutiveDuplicateSteps`.
    case consecutiveDuplicateSteps(step: Challenge, index: Int)
}

/// Drives the Layer 2 challenge run: holds one detector per gesture,
/// advances the active index when the current detector fires, and
/// enforces an 8-second total timeout from the first frame.
///
/// Timeout is measured from the *first* `update()` call (not from init)
/// so a sequencer that is allocated and warmed up before the UI prompt
/// appears doesn't eat the user's budget. After `.finished` fires, the
/// sequencer is idempotent — further `update` calls return nil instead
/// of re-emitting events or re-arming detectors.
///
/// Not thread-safe: `currentIndex`, `startTime`, and `finishedResult`
/// are plain mutable fields. The sequencer is driven exclusively by a
/// single Layer2Runner on one actor/queue; UI consumers should read
/// events via the runner's callback, not by polling `result` from
/// another queue.
public final class ChallengeSequencer {
    /// Total budget per Spec §1340. The three steps must *all* complete
    /// within this window; `timeScore` below buckets the actual elapsed
    /// time into the 1.0 / 0.9 / 0.7 / 0.0 score the orchestrator uses.
    public static let totalTimeoutSeconds: TimeInterval = 8.0

    public let sequence: [Challenge]
    private let detectors: [GestureDetector]
    private var currentIndex = 0
    private var startTime: TimeInterval?
    private var finishedResult: ChallengeResult?
    private var firedSteps: [Challenge] = []

    /// Build from the wire payload the relay sent. Each string must
    /// parse to a `Challenge`; unknown strings throw rather than silently
    /// skipping the step (a skipped step is a false pass).
    public convenience init(wire: [String]) throws {
        var mapped: [Challenge] = []
        for raw in wire {
            guard let c = Challenge(rawValue: raw) else {
                throw ChallengeSequencerError.unknownChallenge(raw)
            }
            mapped.append(c)
        }
        try self.init(sequence: mapped)
    }

    public init(sequence: [Challenge]) throws {
        guard !sequence.isEmpty else { throw ChallengeSequencerError.emptySequence }
        for i in 1..<sequence.count {
            if sequence[i] == sequence[i - 1] {
                throw ChallengeSequencerError.consecutiveDuplicateSteps(step: sequence[i], index: i)
            }
        }
        self.sequence = sequence
        self.detectors = sequence.map { ChallengeSequencer.makeDetector(for: $0) }
    }

    /// Factory kept internal and injectable in tests via `init(sequence:)`.
    /// Each call returns a fresh detector so repeating a gesture in a
    /// sequence (e.g. two blinks) doesn't share state across steps.
    private static func makeDetector(for c: Challenge) -> GestureDetector {
        switch c {
        case .blink:     return BlinkDetector()
        case .lookLeft:  return GazeDetector(.left)
        case .lookRight: return GazeDetector(.right)
        case .smile:     return SmileDetector()
        case .nod:       return NodDetector()
        }
    }

    /// Feed one frame. Returns nil for frames that neither complete a
    /// step nor end the run; returns `.stepCompleted` the frame a
    /// detector fires; returns `.finished` on the frame that completes
    /// the last step OR on the first frame past the 8s budget. Also
    /// returns nil after a terminal `.finished` has already been
    /// emitted — use `isFinished` to disambiguate "waiting for
    /// progress" from "already done".
    ///
    /// Elapsed is floored at 0: a backward-moving timestamp (resumed
    /// app, clock jump) can't produce a negative elapsed that bypasses
    /// the timeout check and hangs the sequencer indefinitely.
    ///
    /// R-NEW-1: a non-finite timestamp (NaN / Infinity from a buggy
    /// upstream clock conversion — e.g., ns/sec divide-by-zero in a
    /// camera adapter) finalizes the sequencer immediately with a
    /// timed-out result. Without this, `NaN > 8.0` is false and the
    /// timeout check never fires, hanging the session on the UI side.
    @discardableResult
    public func update(_ landmarks: FaceLandmarks, at timestamp: TimeInterval) -> ChallengeEvent? {
        if let done = finishedResult {
            _ = done
            return nil
        }
        if startTime == nil { startTime = timestamp }
        let raw = timestamp - (startTime ?? timestamp)
        // Swift's `max(0, .nan)` returns 0 (loses NaN), unlike Kotlin's
        // `kotlin.math.max` which propagates NaN. Preserve non-finite
        // values here so the isFinite guard below catches them — this is
        // what makes R-NEW-1 fire on the first NaN/Infinity frame.
        let elapsed = raw.isFinite ? max(0, raw) : raw

        if !elapsed.isFinite || elapsed > ChallengeSequencer.totalTimeoutSeconds {
            let result = ChallengeResult(
                completed: false,
                score: 0.0,
                elapsedSeconds: elapsed,
                stepsCompleted: currentIndex,
                totalSteps: sequence.count,
                firedSteps: firedSteps
            )
            finishedResult = result
            return .finished(result)
        }

        let fired = detectors[currentIndex].update(landmarks, at: timestamp)
        guard fired else { return nil }

        let completedStep = sequence[currentIndex]
        let completedIndex = currentIndex
        firedSteps.append(completedStep)
        currentIndex += 1

        if currentIndex >= sequence.count {
            let result = ChallengeResult(
                completed: true,
                score: ChallengeSequencer.timeScore(elapsed: elapsed),
                elapsedSeconds: elapsed,
                stepsCompleted: sequence.count,
                totalSteps: sequence.count,
                firedSteps: firedSteps
            )
            finishedResult = result
            return .finished(result)
        }
        return .stepCompleted(step: completedStep, index: completedIndex)
    }

    /// Spec §1342 bucketing. Boundaries land in the lower bucket (3.0s
    /// scores 0.9, not 1.0; 5.0s scores 0.7, not 0.9) because the spec
    /// uses "< 3s" and "3-5s" meaning the upper boundary is exclusive.
    /// 8.0s exactly still passes at 0.7 — only > 8s times out.
    public static func timeScore(elapsed: TimeInterval) -> Float {
        if elapsed < 3.0 { return 1.0 }
        if elapsed < 5.0 { return 0.9 }
        if elapsed <= 8.0 { return 0.7 }
        return 0.0
    }

    public var isFinished: Bool { finishedResult != nil }
    public var result: ChallengeResult? { finishedResult }

    /// Snapshot of challenges whose detector fired so far. Visible so
    /// Layer2Runner's stream-end fallback can report the real prefix of
    /// completed challenges instead of an empty list — the attestation
    /// builder joins these into `challenge_response`.
    public var firedStepsSoFar: [Challenge] { firedSteps }
}
