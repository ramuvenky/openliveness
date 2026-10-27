package dev.openliveness.layers

/**
 * Drives a ChallengeSequencer from a LandmarkStream and emits a
 * `processing_update` WS frame each time a step fires. Kept separate
 * from CDLSession so the frame-pump and the WS encode/transport live
 * in different files — the runner can be unit-tested with fakes while
 * CDLSession stays thin.
 *
 * Not thread-safe. `run()` consumes the stream serially from one
 * coroutine; the `emit` callback is invoked from that same coroutine,
 * so emit implementations that touch UI state must dispatch to main
 * themselves. One Layer2Runner per session; do not reuse.
 */
class Layer2Runner(
    val sequencer: ChallengeSequencer,
    private val stream: LandmarkStream,
    private val emit: ProcessingUpdateEmitter
) {

    companion object {
        /** Spec §880 wire label used in `processing_update.current_layer`
         *  for Layer-2 progress. Centralised so a server rename doesn't
         *  require grepping; the server slices this field to 40 chars so
         *  the exact string also has to match for the browser UI dispatch. */
        const val WIRE_LAYER_LABEL = "layer2_active_liveness"
    }

    /**
     * Consume frames until the sequencer finishes (step-by-step completion
     * OR 8s total timeout). Returns the terminal ChallengeResult. Also
     * returns on nextFrame() == null — a stream that ends before the
     * user finishes is treated the same as a timeout (completed=false,
     * score=0) so the orchestrator's hard-rule fail path kicks in.
     *
     * Progress emits are monotonic non-decreasing: the sequencer freezes
     * `currentIndex` at the completed step before firing a timeout
     * Finished, so the fallback progress `stepsCompleted/totalSteps`
     * equals the last stepCompleted emit's value — the browser UI never
     * sees progress jump backward.
     */
    suspend fun run(): ChallengeResult {
        var completedCount = 0
        var firstTimestamp: Double? = null
        var lastTimestamp = 0.0
        while (true) {
            val frame = stream.nextFrame() ?: break
            val (landmarks, timestamp) = frame
            if (firstTimestamp == null) firstTimestamp = timestamp
            lastTimestamp = timestamp
            val event = sequencer.update(landmarks, timestamp) ?: continue
            when (event) {
                is ChallengeEvent.StepCompleted -> {
                    // Progress is "steps completed so far / total steps".
                    // We emit AFTER increment, so index=0 for first step
                    // becomes progress=1/total — matches what the browser
                    // UI needs to render "1 of 3 done".
                    completedCount = event.index + 1
                    val progress = completedCount.toFloat() / sequencer.sequence.size.toFloat()
                    emit(progress, WIRE_LAYER_LABEL)
                }
                is ChallengeEvent.Finished -> {
                    // On finished, emit terminal 1.0 progress so the UI
                    // shows the final tick even if the last step and the
                    // finish event arrive on the same frame. For a timed-
                    // out (not completed) run, emit the fractional progress
                    // reached — don't lie about completion.
                    val r = event.result
                    val progress = if (r.completed) 1.0f
                        else r.stepsCompleted.toFloat() / r.totalSteps.toFloat()
                    emit(progress, WIRE_LAYER_LABEL)
                    return r
                }
            }
        }
        // Stream ended before the sequencer finished — fabricate a
        // not-completed result so the caller sees a terminal state
        // instead of hanging. Elapsed and stepsCompleted are tracked
        // locally in the runner (not read from sequencer.result, which
        // is only populated on Finished and would be null here) so the
        // caller sees how far the user actually got before the stream
        // died — critical for diagnosing camera disconnects vs timeouts.
        val elapsed = firstTimestamp?.let { lastTimestamp - it } ?: 0.0
        return ChallengeResult(
            completed = false,
            score = 0.0f,
            elapsedSeconds = elapsed,
            stepsCompleted = completedCount,
            totalSteps = sequencer.sequence.size,
            firedSteps = sequencer.firedStepsSoFar
        )
    }
}
