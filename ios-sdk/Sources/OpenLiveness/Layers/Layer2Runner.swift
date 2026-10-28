import Foundation

/// Drives a ChallengeSequencer from a LandmarkStream and emits a
/// `processing_update` WS frame each time a step fires. Kept separate
/// from CDLSession so the frame-pump and the WS encode/transport live
/// in different files — the runner can be unit-tested with fakes while
/// CDLSession stays thin.
///
/// Not thread-safe. `run()` consumes the stream serially from one task;
/// the `emit` callback is invoked from that same task, so emit
/// implementations that touch UI state must dispatch to the main actor
/// themselves. One Layer2Runner per session; do not reuse.
public final class Layer2Runner {

    public let sequencer: ChallengeSequencer
    private let stream: LandmarkStream
    private let emit: ProcessingUpdateEmitter

    /// Spec §880 wire label used in `processing_update.current_layer` for
    /// Layer-2 progress. Centralised so a server rename doesn't require
    /// grepping; the server slices this field to 40 chars so the exact
    /// string also has to match for the browser UI dispatch.
    public static let wireLayerLabel = "layer2_active_liveness"

    public init(sequencer: ChallengeSequencer, stream: LandmarkStream, emit: @escaping ProcessingUpdateEmitter) {
        self.sequencer = sequencer
        self.stream = stream
        self.emit = emit
    }

    /// Consume frames until the sequencer finishes (step-by-step completion
    /// OR 8s total timeout). Returns the terminal `ChallengeResult`. Also
    /// returns on `nextFrame() == nil` — a stream that ends before the
    /// user finishes is treated the same as a timeout (completed=false,
    /// score=0) so the orchestrator's hard-rule fail path kicks in.
    ///
    /// R-NEW-4: progress emitted on the `.finished` frame is numerically
    /// identical to the last `.stepCompleted` emit when the final step
    /// also completes the sequence (both reduce to `stepsCompleted /
    /// totalSteps`). This is intentional — the browser UI treats a
    /// repeated identical progress value as a no-op, so a terminal
    /// re-emit never causes the progress bar to jump backward or
    /// double-tick.
    public func run() async throws -> ChallengeResult {
        var completedCount = 0
        var firstTimestamp: TimeInterval?
        var lastTimestamp: TimeInterval = 0
        while let frame = try await stream.nextFrame() {
            let (landmarks, timestamp) = frame
            if firstTimestamp == nil { firstTimestamp = timestamp }
            lastTimestamp = timestamp
            if let event = sequencer.update(landmarks, at: timestamp) {
                switch event {
                case .stepCompleted(_, let index):
                    // Progress is "steps completed so far / total steps".
                    // We emit AFTER increment, so index=0 for first step
                    // becomes progress=1/total — matches what the browser
                    // UI needs to render "1 of 3 done".
                    completedCount = index + 1
                    let progress = Float(completedCount) / Float(sequencer.sequence.count)
                    emit(progress, Layer2Runner.wireLayerLabel)
                case .finished(let result):
                    // On finished, emit a terminal 1.0 progress so the UI
                    // shows the final tick even if the last step and the
                    // finish event arrive on the same frame. For a timed-
                    // out (not completed) run, emit the fractional progress
                    // reached — don't lie about completion.
                    let progress = result.completed
                        ? 1.0
                        : Float(result.stepsCompleted) / Float(result.totalSteps)
                    emit(progress, Layer2Runner.wireLayerLabel)
                    return result
                }
            }
        }
        // Stream ended before the sequencer finished — fabricate a
        // not-completed result so the caller sees a terminal state
        // instead of hanging. Elapsed and stepsCompleted are tracked
        // locally in the runner (not read from `sequencer.result`,
        // which is only populated on `.finished` and would be nil here)
        // so the caller sees how far the user actually got before the
        // stream died — critical for diagnosing camera disconnects vs
        // timeouts.
        let elapsed = firstTimestamp.map { lastTimestamp - $0 } ?? 0
        return ChallengeResult(
            completed: false,
            score: 0.0,
            elapsedSeconds: elapsed,
            stepsCompleted: completedCount,
            totalSteps: sequencer.sequence.count,
            firedSteps: sequencer.firedStepsSoFar
        )
    }
}
