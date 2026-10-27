package dev.openliveness.layers

/**
 * Wire strings for the five Layer 2 gestures. The `wire` values match
 * relay/src/session/types.ts CHALLENGE_STEPS exactly — the server composes
 * the sequence from these strings, and the SDK deserializes them back
 * into this enum. Renaming a wire value here without updating the server
 * (or vice versa) silently breaks the handshake.
 */
enum class Challenge(val wire: String) {
    BLINK("blink"),
    LOOK_LEFT("look_left"),
    LOOK_RIGHT("look_right"),
    SMILE("smile"),
    NOD("nod");

    companion object {
        fun fromWire(s: String): Challenge? = values().firstOrNull { it.wire == s }
    }
}

/**
 * Terminal result of a challenge run. `completed` and `score` are the
 * two fields the orchestrator reads to populate layer_scores; the rest
 * are advisory (logging / UI). We keep them as data-class fields instead
 * of behind getters so a crash dump has the full picture of what the run
 * looked like without any method calls.
 */
data class ChallengeResult(
    val completed: Boolean,
    val score: Float,
    val elapsedSeconds: Double,
    val stepsCompleted: Int,
    val totalSteps: Int,
    /**
     * Challenges in the exact order their detectors fired. Length ==
     * stepsCompleted. The attestation builder joins the wire strings
     * here into `challenge_response` so the server sees what the SDK
     * actually detected, not just what the server prompted. On a
     * `completed==true` run this equals the issued sequence; on a
     * partial/timed-out run it's the prefix of steps the user got through.
     * Empty list on constructor-fabricated terminal results (empty stream).
     */
    val firedSteps: List<Challenge> = emptyList()
)

/**
 * Events the sequencer emits as frames stream in. A fresh StepCompleted
 * drives the on-screen "Blink now → Look left" prompt change; Finished
 * is terminal and tells the orchestrator Layer 2 is ready to score.
 */
sealed class ChallengeEvent {
    data class StepCompleted(val step: Challenge, val index: Int) : ChallengeEvent()
    data class Finished(val result: ChallengeResult) : ChallengeEvent()
}

/**
 * Thrown by `ChallengeSequencer` construction so a malformed wire payload
 * fails at build time instead of mid-session. Having typed error cases
 * (not strings) means the UI layer can map each to a user message without
 * string-matching.
 */
sealed class ChallengeSequencerException(msg: String) : IllegalArgumentException(msg) {
    object EmptySequence : ChallengeSequencerException("challenge sequence must be non-empty")
    class UnknownChallenge(val raw: String) : ChallengeSequencerException("unknown challenge: $raw")

    /**
     * Two adjacent steps resolve to the same Challenge. Blink/Gaze/Smile
     * detectors self-reset on fire; a sustained gesture would silently
     * satisfy both slots from one presentation. We reject the sequence at
     * construction so the server can't ship an exploitable prompt order
     * — detectors would need a cooldown-until-neutral gate before this
     * could be lifted. Mirrors iOS ChallengeSequencerError.consecutiveDuplicateSteps.
     */
    class ConsecutiveDuplicateSteps(val step: Challenge, val index: Int) :
        ChallengeSequencerException("step[$index]=$step duplicates step[${index - 1}]; sustained-gesture replay")
}

/**
 * Drives the Layer 2 challenge run: holds one detector per gesture,
 * advances the active index when the current detector fires, and
 * enforces an 8-second total timeout from the first frame.
 *
 * Timeout is measured from the *first* update() call (not from init)
 * so a sequencer allocated and warmed up before the UI prompt appears
 * doesn't eat the user's budget. After Finished fires, the sequencer
 * is idempotent — further update calls return null instead of
 * re-emitting events or re-arming detectors.
 *
 * Not thread-safe: `currentIndex`, `startTime`, and `finishedResult`
 * are plain mutable fields. The sequencer is driven exclusively by a
 * single Layer2Runner on one coroutine; UI consumers should read
 * events via the runner's callback, not by polling `result` from
 * another thread.
 */
class ChallengeSequencer private constructor(val sequence: List<Challenge>) {

    companion object {
        /** Spec §1340 — total budget for all steps combined. */
        const val TOTAL_TIMEOUT_SECONDS: Double = 8.0

        /**
         * Build from the wire payload the relay sent. Each string must
         * map to a Challenge; unknown strings throw rather than silently
         * skipping the step (a skipped step is a false pass).
         */
        fun fromWire(wire: List<String>): ChallengeSequencer {
            if (wire.isEmpty()) throw ChallengeSequencerException.EmptySequence
            val mapped = wire.map { raw ->
                Challenge.fromWire(raw) ?: throw ChallengeSequencerException.UnknownChallenge(raw)
            }
            rejectConsecutiveDuplicates(mapped)
            return ChallengeSequencer(mapped)
        }

        fun of(sequence: List<Challenge>): ChallengeSequencer {
            if (sequence.isEmpty()) throw ChallengeSequencerException.EmptySequence
            rejectConsecutiveDuplicates(sequence)
            return ChallengeSequencer(sequence)
        }

        private fun rejectConsecutiveDuplicates(sequence: List<Challenge>) {
            for (i in 1 until sequence.size) {
                if (sequence[i] == sequence[i - 1]) {
                    throw ChallengeSequencerException.ConsecutiveDuplicateSteps(sequence[i], i)
                }
            }
        }

        /**
         * Spec §1342 bucketing. Boundaries land in the lower bucket (3.0s
         * scores 0.9, not 1.0; 5.0s scores 0.7, not 0.9) because the spec
         * uses "< 3s" and "3-5s" meaning the upper boundary is exclusive.
         * 8.0s exactly still passes at 0.7 — only > 8s times out.
         */
        fun timeScore(elapsed: Double): Float = when {
            elapsed < 3.0 -> 1.0f
            elapsed < 5.0 -> 0.9f
            elapsed <= 8.0 -> 0.7f
            else -> 0.0f
        }
    }

    private val detectors: List<GestureDetector> = sequence.map { makeDetector(it) }
    private var currentIndex = 0
    private var startTime: Double? = null
    private var finishedResult: ChallengeResult? = null
    private val firedSteps: MutableList<Challenge> = mutableListOf()

    /**
     * Fresh detector per step so repeating a gesture in a sequence
     * (e.g. two blinks) doesn't share state across steps.
     */
    private fun makeDetector(c: Challenge): GestureDetector = when (c) {
        Challenge.BLINK      -> BlinkDetector()
        Challenge.LOOK_LEFT  -> GazeDetector(GazeDirection.LEFT)
        Challenge.LOOK_RIGHT -> GazeDetector(GazeDirection.RIGHT)
        Challenge.SMILE      -> SmileDetector()
        Challenge.NOD        -> NodDetector()
    }

    val isFinished: Boolean get() = finishedResult != null
    val result: ChallengeResult? get() = finishedResult

    /**
     * Snapshot of challenges whose detector fired so far. Visible so
     * Layer2Runner's stream-end fallback can report the real prefix of
     * completed challenges instead of an empty list — the attestation
     * builder joins these into `challenge_response`.
     */
    val firedStepsSoFar: List<Challenge> get() = firedSteps.toList()

    /**
     * Feed one frame. Returns null for frames that neither complete a
     * step nor end the run; returns StepCompleted the frame a detector
     * fires; returns Finished on the frame that completes the last step
     * OR on the first frame past the 8s budget. Also returns null after
     * terminal Finished has already been emitted — use `isFinished` to
     * disambiguate "waiting for progress" from "already done".
     *
     * Elapsed is floored at 0: a backward-moving timestamp (resumed app,
     * clock jump) can't produce a negative elapsed that bypasses the
     * timeout check and hangs the sequencer indefinitely. A non-finite
     * timestamp (NaN or ±Infinity from a buggy upstream clock conversion)
     * also fails safe to timeout rather than letting the comparison
     * swallow the frame — `NaN > 8.0` is false and would otherwise mean
     * the sequencer waits forever.
     */
    fun update(landmarks: FaceLandmarks, timestamp: Double): ChallengeEvent? {
        finishedResult?.let { return null }
        if (startTime == null) startTime = timestamp
        val elapsed = kotlin.math.max(0.0, timestamp - (startTime ?: timestamp))

        if (!elapsed.isFinite() || elapsed > TOTAL_TIMEOUT_SECONDS) {
            val r = ChallengeResult(
                completed = false,
                score = 0.0f,
                elapsedSeconds = elapsed,
                stepsCompleted = currentIndex,
                totalSteps = sequence.size,
                firedSteps = firedSteps.toList()
            )
            finishedResult = r
            return ChallengeEvent.Finished(r)
        }

        val fired = detectors[currentIndex].update(landmarks, timestamp)
        if (!fired) return null

        val completedStep = sequence[currentIndex]
        val completedIndex = currentIndex
        firedSteps.add(completedStep)
        currentIndex++

        if (currentIndex >= sequence.size) {
            val r = ChallengeResult(
                completed = true,
                score = timeScore(elapsed),
                elapsedSeconds = elapsed,
                stepsCompleted = sequence.size,
                totalSteps = sequence.size,
                firedSteps = firedSteps.toList()
            )
            finishedResult = r
            return ChallengeEvent.Finished(r)
        }
        return ChallengeEvent.StepCompleted(completedStep, completedIndex)
    }
}
