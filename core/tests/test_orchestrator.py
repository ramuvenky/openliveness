"""Orchestrator parity tests.

These tests pin the weight sums and decision thresholds that the server
re-runs from `layer_scores`. A failure here means the Python side has
drifted from the Kotlin/Swift mirrors and attestations will fail
server-side verification.
"""

from __future__ import annotations

import pytest

from core.orchestrator import (
    AmbientConditions,
    DEFAULT_WEIGHTS,
    INCONCLUSIVE_THRESHOLD,
    LayerScores,
    PASS_THRESHOLD,
    orchestrate,
    weights_for,
)


def _perfect_scores() -> LayerScores:
    return LayerScores(
        layer2_challenge_completed=True,
        layer2_challenge_score=1.0,
        layer3_rppg_confidence=1.0,
        layer4_lbp_score=1.0,
        layer4_fourier_score=1.0,
        layer5_behavioral_score=1.0,
    )


# ---- Weight-sum invariant ------------------------------------------------

@pytest.mark.parametrize(
    "low_light,motion",
    [(False, False), (True, False), (False, True), (True, True)],
)
def test_weights_sum_to_one(low_light, motion):
    """C-2 fix: sum MUST be 1.0 in all four permutations.

    Pre-fix, (low_light=True, motion=True) summed to 1.05 and the
    weighted score could exceed 1.0, shifting the pass threshold.
    """
    ambient = AmbientConditions(low_light_detected=low_light, motion_detected=motion)
    w = weights_for(ambient)
    total = sum(w.values())
    assert abs(total - 1.0) < 1e-6, (ambient, w, total)


def test_default_weights_match_calibration():
    """2026-11 (v0.2.0) calibration. See core/orchestrator.py's
    DEFAULT_WEIGHTS comment for the on-device-regression rationale that
    drove dropping Fourier to 0 and reducing LBP. Must match Kotlin/
    Swift byte-for-byte so the golden fixtures round-trip cleanly."""
    assert DEFAULT_WEIGHTS == {
        "layer2": 0.50,
        "layer3": 0.10,
        "layer4_lbp": 0.15,
        "layer4_fourier": 0.00,
        "layer5": 0.25,
    }


def test_low_light_only_shifts_lbp_to_l5():
    """Low-light shifts 0.05 off L4 LBP into L5 (behavioral is ambient-
    invariant; LBP texture is noisier under poor illumination). L3 is
    already floored at 0.10 by the default, so low-light doesn't cut
    it further."""
    w = weights_for(AmbientConditions(low_light_detected=True))
    assert w["layer2"] == pytest.approx(0.50)
    assert w["layer3"] == pytest.approx(0.10)
    assert w["layer4_lbp"] == pytest.approx(0.10)
    assert w["layer4_fourier"] == pytest.approx(0.00)
    assert w["layer5"] == pytest.approx(0.30)


def test_motion_only_drains_l5_to_l3():
    w = weights_for(AmbientConditions(motion_detected=True))
    assert w["layer5"] == pytest.approx(0.05)
    assert w["layer3"] == pytest.approx(0.10 + (0.25 - 0.05))


def test_both_flags_stack_sequentially():
    """low_light first (l5=0.30), motion stacks on top (l5=0.05, l3+=0.25)."""
    w = weights_for(
        AmbientConditions(low_light_detected=True, motion_detected=True)
    )
    assert w["layer5"] == pytest.approx(0.05)
    assert w["layer3"] == pytest.approx(0.10 + 0.25)
    assert w["layer4_lbp"] == pytest.approx(0.10)


# ---- Decision logic ------------------------------------------------------

def test_missed_challenge_hard_fails_even_with_perfect_other_scores():
    scores = _perfect_scores()
    scores.layer2_challenge_completed = False
    result = orchestrate(scores, AmbientConditions())
    assert result.decision == "fail"
    assert result.assurance_level == "software_only"
    assert result.weighted_score == 0.0


def test_perfect_scores_without_hw_attestation_pass_software_only():
    result = orchestrate(_perfect_scores(), AmbientConditions())
    assert result.decision == "pass"
    assert result.assurance_level == "software_only"


def test_perfect_scores_with_hw_attestation_reach_ial2():
    result = orchestrate(
        _perfect_scores(),
        AmbientConditions(),
        hardware_attestation_verified=True,
    )
    assert result.decision == "pass"
    assert result.assurance_level == "IAL2"


def test_borderline_score_is_inconclusive():
    """Craft a score matrix landing between INCONCLUSIVE_THRESHOLD and PASS_THRESHOLD."""
    scores = LayerScores(
        layer2_challenge_completed=True,
        layer2_challenge_score=0.7,
        layer3_rppg_confidence=0.7,
        layer4_lbp_score=0.7,
        layer4_fourier_score=0.7,
        layer5_behavioral_score=0.7,
    )
    result = orchestrate(scores, AmbientConditions())
    assert INCONCLUSIVE_THRESHOLD <= result.weighted_score < PASS_THRESHOLD
    assert result.decision == "inconclusive"
    # Even a hardware-verified inconclusive stays software_only.
    assert (
        orchestrate(scores, AmbientConditions(), hardware_attestation_verified=True)
        .assurance_level
        == "software_only"
    )


def test_low_scores_fail():
    scores = LayerScores(
        layer2_challenge_completed=True,
        layer2_challenge_score=0.2,
        layer3_rppg_confidence=0.2,
        layer4_lbp_score=0.2,
        layer4_fourier_score=0.2,
        layer5_behavioral_score=0.2,
    )
    result = orchestrate(scores, AmbientConditions())
    assert result.decision == "fail"


# ---- Layer-3 abstention renormalization ---------------------------------

def test_layer3_abstention_renormalizes_remaining_weights():
    """L3 confidence=0 → is_layer3_abstaining → remaining four weights
    renormalize to sum to 1.0. Pins the user-visible fix for the 2026-11
    on-device regression where honest POS-rPPG "no lock" output on short
    clips pulled live users below 0.60."""
    scores = LayerScores(
        layer2_challenge_completed=True,
        layer2_challenge_score=0.9,
        layer3_rppg_confidence=0.0,
        layer4_lbp_score=0.5,
        layer4_fourier_score=0.5,
        layer5_behavioral_score=1.0,
    )
    result = orchestrate(scores, AmbientConditions())
    # remaining = 0.50 + 0.15 + 0.00 + 0.25 = 0.90
    # weighted  = 0.9*(0.50/0.90) + 0.5*(0.15/0.90) + 0.5*(0.00/0.90)
    #           + 1.0*(0.25/0.90) ≈ 0.8611
    assert result.weighted_score == pytest.approx(0.8611, abs=1e-3)
    assert result.decision == "pass"
    # Weights published in the result reflect the renormalized set so
    # a downstream auditor (benchmark, server-side mirror) can replay.
    assert result.weights_applied["layer3"] == 0.0
    assert sum(result.weights_applied.values()) == pytest.approx(1.0)


def test_layer3_abstention_does_not_rescue_photo_attack():
    """Companion to the live-user case: a pure photo attack that
    somehow moved through the challenge (L2=0.9) with no micro-saccade
    (L5=0) and spoof-looking texture (L4=0.15) must still fail. The
    v0.2.0 reweight (L4 down, L5 up) specifically preserves this
    invariant — rebalanced so L2 alone can't carry the sum above
    INCONCLUSIVE when L5 is flat. Under v0.1.0 weights this was
    protected by L4=0.25; under v0.2.0 that mass moved into L5.
    """
    scores = LayerScores(
        layer2_challenge_completed=True,
        layer2_challenge_score=0.9,
        layer3_rppg_confidence=0.0,
        layer4_lbp_score=0.15,
        layer4_fourier_score=0.0,
        layer5_behavioral_score=0.0,
    )
    result = orchestrate(scores, AmbientConditions())
    assert result.decision == "fail"
