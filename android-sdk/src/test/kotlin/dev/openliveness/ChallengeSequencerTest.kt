package dev.openliveness

import dev.openliveness.layers.Challenge
import dev.openliveness.layers.ChallengeEvent
import dev.openliveness.layers.ChallengeSequencer
import dev.openliveness.layers.ChallengeSequencerException
import dev.openliveness.layers.FaceLandmark
import dev.openliveness.layers.FaceLandmarks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Phase 3 Step 2 — challenge-sequencer unit tests. Mirrors
 * ios-sdk/Tests/ChallengeSequencerTests.swift so a numeric or timing
 * drift between the two platforms trips its sibling test rather than
 * divergent server-side scoring.
 */
class ChallengeSequencerTest {

    private fun neutralFace(): MutableList<FaceLandmark> {
        val pts = MutableList(FaceLandmarks.EXPECTED_COUNT) { FaceLandmark(0f, 0f) }
        pts[1]   = FaceLandmark(0.50f, 0.50f)
        pts[10]  = FaceLandmark(0.50f, 0.20f)
        pts[33]  = FaceLandmark(0.40f, 0.50f)
        pts[61]  = FaceLandmark(0.40f, 0.60f)
        pts[133] = FaceLandmark(0.50f, 0.50f)
        pts[145] = FaceLandmark(0.45f, 0.50f)
        pts[152] = FaceLandmark(0.50f, 0.80f)
        pts[159] = FaceLandmark(0.45f, 0.45f)
        pts[234] = FaceLandmark(0.30f, 0.50f)
        pts[291] = FaceLandmark(0.60f, 0.60f)
        pts[454] = FaceLandmark(0.70f, 0.50f)
        return pts
    }
    private fun lm(pts: List<FaceLandmark>) = FaceLandmarks(pts)

    private fun closedEyeFrame(): FaceLandmarks {
        val p = neutralFace().also { it[159] = FaceLandmark(0.45f, 0.49f) }
        return lm(p)
    }
    private fun leftGazeFrame(): FaceLandmarks {
        val p = neutralFace().also { it[1] = FaceLandmark(0.42f, 0.50f) }
        return lm(p)
    }
    private fun rightGazeFrame(): FaceLandmarks {
        val p = neutralFace().also { it[1] = FaceLandmark(0.58f, 0.50f) }
        return lm(p)
    }

    // MARK: - Construction

    @Test
    fun `empty wire sequence throws`() {
        assertThrows<ChallengeSequencerException.EmptySequence> {
            ChallengeSequencer.fromWire(emptyList())
        }
    }

    @Test
    fun `unknown wire string throws`() {
        val ex = assertThrows<ChallengeSequencerException.UnknownChallenge> {
            ChallengeSequencer.fromWire(listOf("blink", "wink"))
        }
        assertEquals("wink", ex.raw)
    }

    @Test
    fun `wire strings match relay contract exactly`() {
        // These exact lowercase snake_case strings are the contract with
        // relay/src/session/types.ts CHALLENGE_STEPS — if someone renames
        // a case here the server will dispatch sequences the SDK can't
        // parse.
        val seq = ChallengeSequencer.fromWire(listOf("blink", "look_left", "look_right", "smile", "nod"))
        assertEquals(
            listOf(Challenge.BLINK, Challenge.LOOK_LEFT, Challenge.LOOK_RIGHT, Challenge.SMILE, Challenge.NOD),
            seq.sequence
        )
    }

    // MARK: - time_score buckets (Spec §1342)

    @Test
    fun `time_score buckets at exact boundaries`() {
        assertEquals(1.0f, ChallengeSequencer.timeScore(0.0))
        assertEquals(1.0f, ChallengeSequencer.timeScore(2.999))
        // 3.0 lands in the 0.9 bucket (upper-exclusive on lower bucket).
        assertEquals(0.9f, ChallengeSequencer.timeScore(3.0))
        assertEquals(0.9f, ChallengeSequencer.timeScore(4.999))
        assertEquals(0.7f, ChallengeSequencer.timeScore(5.0))
        assertEquals(0.7f, ChallengeSequencer.timeScore(8.0))
        assertEquals(0.0f, ChallengeSequencer.timeScore(8.001))
    }

    // MARK: - Happy path

    @Test
    fun `two-step sequence completes and scores`() {
        val seq = ChallengeSequencer.of(listOf(Challenge.BLINK, Challenge.LOOK_LEFT))
        val events = mutableListOf<ChallengeEvent>()
        var t = 0.0

        seq.update(closedEyeFrame(), t)?.let { events += it }; t += 0.033
        seq.update(closedEyeFrame(), t)?.let { events += it }; t += 0.033
        seq.update(lm(neutralFace()), t)?.let { events += it }; t += 0.033

        assertEquals(listOf(ChallengeEvent.StepCompleted(Challenge.BLINK, 0)), events)

        seq.update(leftGazeFrame(), t)?.let { events += it }; t += 0.033
        seq.update(leftGazeFrame(), t)?.let { events += it }; t += 0.033
        val last = seq.update(leftGazeFrame(), t)

        assertTrue(last is ChallengeEvent.Finished, "expected Finished, got $last")
        val r = (last as ChallengeEvent.Finished).result
        assertTrue(r.completed)
        assertEquals(1.0f, r.score, 1e-6f)
        assertEquals(2, r.stepsCompleted)
        assertEquals(2, r.totalSteps)
        assertTrue(seq.isFinished)
    }

    // MARK: - Timeout

    @Test
    fun `timeout fires Finished with zero score`() {
        val seq = ChallengeSequencer.of(listOf(Challenge.BLINK))
        assertNull(seq.update(lm(neutralFace()), 0.0))
        val ev = seq.update(lm(neutralFace()), 8.001)
        assertTrue(ev is ChallengeEvent.Finished, "expected timeout Finished, got $ev")
        val r = (ev as ChallengeEvent.Finished).result
        assertFalse(r.completed)
        assertEquals(0.0f, r.score)
        assertEquals(0, r.stepsCompleted)
        assertEquals(1, r.totalSteps)
    }

    // MARK: - Direction isolation

    @Test
    fun `wrong-direction gaze does not advance LOOK_LEFT step`() {
        // Guards against a sequencer bug where the wrong detector is
        // picked for a step: a right-gaze stream must never satisfy a
        // LOOK_LEFT step.
        val seq = ChallengeSequencer.of(listOf(Challenge.LOOK_LEFT))
        var t = 0.0
        repeat(10) {
            assertNull(seq.update(rightGazeFrame(), t))
            t += 0.033
        }
        assertFalse(seq.isFinished)
    }

    // MARK: - Idempotence

    @Test
    fun `update after Finished returns null`() {
        val seq = ChallengeSequencer.of(listOf(Challenge.BLINK))
        seq.update(closedEyeFrame(), 0.0)
        seq.update(closedEyeFrame(), 0.033)
        val fin = seq.update(lm(neutralFace()), 0.066)
        assertTrue(fin is ChallengeEvent.Finished)
        // Sealed after Finished — further updates must not re-fire events.
        assertNull(seq.update(lm(neutralFace()), 0.1))
        assertNull(seq.update(closedEyeFrame(), 0.2))
    }

    // MARK: - Elapsed clock start

    // R-5 — a resumed app or clock jump can hand the sequencer a frame
    // with a timestamp earlier than the first-frame baseline. Without the
    // elapsed floor, (ts - startTime) is negative, `elapsed > 8s` is
    // false, the sequencer never times out, and the session hangs the
    // UI forever. Lock in that backward timestamps don't bypass the
    // timeout path.
    @Test
    fun `backward timestamp does not bypass timeout`() {
        val seq = ChallengeSequencer.of(listOf(Challenge.BLINK))
        // First frame establishes baseline at t=1000.
        assertNull(seq.update(lm(neutralFace()), 1000.0))
        // Jump backward by 100s — raw elapsed would be -100 (looks fine
        // to the timeout check). But a frame way past the budget should
        // never resuscitate: here we're asserting the clock-floor
        // doesn't negatively advance anything.
        val ev1 = seq.update(lm(neutralFace()), 900.0)
        // The frame doesn't fire a step (neutral face is not a blink)
        // and must not fire a stale Finished either — elapsed is
        // floored at 0 so the sequencer stays live.
        assertNull(ev1)
        assertFalse(seq.isFinished)
        // Forward jump past the 8s budget triggers timeout cleanly.
        val ev2 = seq.update(lm(neutralFace()), 1008.001)
        assertTrue(ev2 is ChallengeEvent.Finished, "forward jump past 8s should finalize")
    }

    // R-NEW-1 — a NaN timestamp from a buggy upstream clock conversion
    // (e.g., ns/sec divide-by-zero on a Camera2 adapter) must not hang
    // the sequencer. `NaN > 8.0` is false, so without the isFinite guard
    // the sequencer would keep waiting for a frame that'll never pass
    // the timeout check. Lock in: NaN/Infinity → immediate Finished.
    @Test
    fun `non-finite timestamp triggers immediate timeout`() {
        val seqNaN = ChallengeSequencer.of(listOf(Challenge.BLINK))
        val evNaN = seqNaN.update(lm(neutralFace()), Double.NaN)
        assertTrue(evNaN is ChallengeEvent.Finished,
            "NaN timestamp must finalize immediately, got $evNaN")
        assertFalse((evNaN as ChallengeEvent.Finished).result.completed)

        val seqInf = ChallengeSequencer.of(listOf(Challenge.BLINK))
        val evInf = seqInf.update(lm(neutralFace()), Double.POSITIVE_INFINITY)
        assertTrue(evInf is ChallengeEvent.Finished,
            "Infinity timestamp must finalize immediately, got $evInf")
    }

    // H-2 — consecutive duplicate steps must be rejected at construction.
    // Blink/Gaze/Smile detectors self-reset on fire; a sustained gesture
    // would silently satisfy both slots from one presentation. The server
    // isn't supposed to issue such a sequence, but a hostile fork of the
    // relay could — rejecting at the SDK boundary means a client can't be
    // tricked into accepting an exploitable prompt order.
    @Test
    fun `consecutive duplicate steps rejected at construction via wire`() {
        val ex = assertThrows<ChallengeSequencerException.ConsecutiveDuplicateSteps> {
            ChallengeSequencer.fromWire(listOf("blink", "blink"))
        }
        assertEquals(Challenge.BLINK, ex.step)
        assertEquals(1, ex.index)
    }

    @Test
    fun `consecutive duplicate steps rejected at construction via of`() {
        val ex = assertThrows<ChallengeSequencerException.ConsecutiveDuplicateSteps> {
            ChallengeSequencer.of(listOf(Challenge.LOOK_LEFT, Challenge.LOOK_LEFT))
        }
        assertEquals(Challenge.LOOK_LEFT, ex.step)
        assertEquals(1, ex.index)
    }

    @Test
    fun `non-adjacent duplicate steps still allowed`() {
        // A → B → A is a legit sequence — the B step forces the user
        // back to a neutral state between the two A's.
        val seq = ChallengeSequencer.of(
            listOf(Challenge.BLINK, Challenge.LOOK_LEFT, Challenge.BLINK)
        )
        assertEquals(3, seq.sequence.size)
    }

    // M-3 — the sequencer tracks firedSteps so AttestationBuilder can emit
    // the real detected prefix on the wire. Timed-out run keeps the
    // partial list; completed run has all steps.
    @Test
    fun `firedSteps carries the detected prefix on timeout`() {
        val seq = ChallengeSequencer.of(listOf(Challenge.BLINK, Challenge.LOOK_LEFT))
        // Fire blink.
        seq.update(closedEyeFrame(), 0.0)
        seq.update(closedEyeFrame(), 0.033)
        seq.update(lm(neutralFace()), 0.066) // completes blink, index→1
        // Now time out before look-left fires.
        val fin = seq.update(lm(neutralFace()), 8.5) as ChallengeEvent.Finished
        assertFalse(fin.result.completed)
        assertEquals(listOf(Challenge.BLINK), fin.result.firedSteps)
        assertEquals(listOf(Challenge.BLINK), seq.firedStepsSoFar)
    }

    @Test
    fun `firedSteps equals full sequence on completed run`() {
        val seq = ChallengeSequencer.of(listOf(Challenge.BLINK, Challenge.LOOK_LEFT))
        seq.update(closedEyeFrame(), 0.0)
        seq.update(closedEyeFrame(), 0.033)
        seq.update(lm(neutralFace()), 0.066)
        seq.update(leftGazeFrame(), 0.1)
        seq.update(leftGazeFrame(), 0.133)
        val fin = seq.update(leftGazeFrame(), 0.166) as ChallengeEvent.Finished
        assertTrue(fin.result.completed)
        assertEquals(listOf(Challenge.BLINK, Challenge.LOOK_LEFT), fin.result.firedSteps)
    }

    @Test
    fun `elapsed is measured from first update not from construction`() {
        // The sequencer may be allocated well before the UI prompt
        // appears. Elapsed must be relative to the first frame so the
        // user gets a full 8s budget regardless of allocation time.
        val seq = ChallengeSequencer.of(listOf(Challenge.BLINK))
        val t0 = 100.0
        seq.update(closedEyeFrame(), t0)
        seq.update(closedEyeFrame(), t0 + 0.033)
        val fin = seq.update(lm(neutralFace()), t0 + 0.066)
        assertTrue(fin is ChallengeEvent.Finished)
        val r = (fin as ChallengeEvent.Finished).result
        assertEquals(0.066, r.elapsedSeconds, 1e-6)
        assertEquals(1.0f, r.score)
    }
}
