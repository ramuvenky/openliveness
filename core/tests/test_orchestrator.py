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


def test_default_weights_match_spec():
    assert DEFAULT_WEIGHTS == {
        "layer2": 0.30,
        "layer3": 0.25,
        "layer4_lbp": 0.20,
        "layer4_fourier": 0.15,
        "layer5": 0.10,
    }


def test_low_light_only_shifts_l3_to_l2_and_l5():
    w = weights_for(AmbientConditions(low_light_detected=True))
    assert w["layer3"] == 0.15
    assert w["layer2"] == 0.35
    assert w["layer5"] == 0.15


def test_motion_only_drains_l5_to_l3():
    w = weights_for(AmbientConditions(motion_detected=True))
    assert w["layer5"] == 0.05
    assert w["layer3"] == pytest.approx(0.25 + (0.10 - 0.05))


def test_both_flags_stack_sequentially():
    """low_light first (l5=0.15), motion stacks on top (l5=0.05, l3+=0.10)."""
    w = weights_for(
        AmbientConditions(low_light_detected=True, motion_detected=True)
    )
    assert w["layer5"] == 0.05
    assert w["layer3"] == pytest.approx(0.15 + 0.10)


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
