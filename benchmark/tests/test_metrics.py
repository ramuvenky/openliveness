"""Tests for ISO/IEC 30107-3 metrics + HTER-minimising threshold sweep.

Hand-crafted score/label vectors cover:
  - the four corners of (APCER, BPCER) (perfect detector / inverted
    detector / all-attack / all-live predictions)
  - AUC on a perfectly-separable set
  - the sweep's optimality + tie-break policy
  - the shared validator's loud-failure contract
"""

from __future__ import annotations

import pytest

from benchmark.metrics import compute_metrics, sweep_threshold


def test_perfect_detector_at_midpoint_threshold():
    # Lives all scored 1.0, attacks all scored 0.0 — any threshold in
    # (0, 1] produces zero error. AUC on a cleanly-separated set is 1.0.
    scores = [1.0, 1.0, 1.0, 0.0, 0.0, 0.0]
    labels = [1, 1, 1, 0, 0, 0]
    out = compute_metrics(scores, labels, threshold=0.5)
    assert out["apcer"] == 0.0
    assert out["bpcer"] == 0.0
    assert out["hter"] == 0.0
    assert out["auc"] == 1.0


def test_inverted_detector_floods_both_rates():
    # Scores inverted vs labels — every attack is called live, every
    # live is called attack. AUC = 0 because the ranking is flipped;
    # HTER = 1.0 (worst possible symmetric error).
    scores = [0.0, 0.0, 1.0, 1.0]
    labels = [1, 1, 0, 0]
    out = compute_metrics(scores, labels, threshold=0.5)
    assert out["apcer"] == 1.0
    assert out["bpcer"] == 1.0
    assert out["hter"] == 1.0
    assert out["auc"] == 0.0


def test_threshold_at_zero_passes_everything_as_live():
    # score >= 0 is tautological for scores in [0, 1] — every sample
    # is predicted live, so APCER=1 (all attacks accepted) and
    # BPCER=0 (zero lives rejected).
    scores = [0.0, 0.5, 1.0, 0.0, 0.5, 1.0]
    labels = [1, 1, 1, 0, 0, 0]
    out = compute_metrics(scores, labels, threshold=0.0)
    assert out["apcer"] == 1.0
    assert out["bpcer"] == 0.0
    assert out["hter"] == 0.5


def test_threshold_above_max_rejects_everything_as_attack():
    # No sample >= 1.5 → every prediction is attack. APCER=0 (no
    # attacks accepted), BPCER=1 (all lives wrongly rejected).
    scores = [0.0, 0.5, 1.0, 0.0, 0.5, 1.0]
    labels = [1, 1, 1, 0, 0, 0]
    out = compute_metrics(scores, labels, threshold=1.5)
    assert out["apcer"] == 0.0
    assert out["bpcer"] == 1.0
    assert out["hter"] == 0.5


def test_threshold_equality_counts_as_live():
    # ISO 30107-3 + spec: `score >= threshold` is the live decision.
    # A score exactly equal to the threshold must count as live, so
    # the attack at score=0.5 is wrongly accepted → APCER=1.
    out = compute_metrics(
        scores=[0.5, 0.5],
        labels=[1, 0],
        threshold=0.5,
    )
    assert out["apcer"] == 1.0  # attack accepted at the boundary
    assert out["bpcer"] == 0.0  # live correctly accepted


def test_sweep_picks_hter_minimum():
    # Hand-constructed so HTER has a single clear minimum at
    # threshold = 0.6: that cuts cleanly between the two attack scores
    # (0.4, 0.55) and the two live scores (0.65, 0.9).
    scores = [0.9, 0.65, 0.55, 0.4]
    labels = [1, 1, 0, 0]
    out = sweep_threshold(scores, labels)
    assert out["hter"] == 0.0
    # The best threshold is the smallest score that still separates —
    # the sweep enumerates unique scores and the first HTER-minimum
    # wins, so the chosen threshold is 0.65 (lowest live score).
    assert out["threshold"] == 0.65


def test_sweep_tie_break_takes_lowest_threshold():
    # Fully-mixed scores where every candidate threshold produces the
    # same HTER (0.5). The documented tie-break — "first threshold
    # wins" — must then land on the lowest unique score (0.1).
    #
    #   t=0.1: all predicted live → APCER=1, BPCER=0, HTER=0.5
    #   t=0.9: predictions=[1,1,0,0] vs labels=[1,0,1,0]
    #          → APCER=0.5, BPCER=0.5, HTER=0.5
    scores = [0.9, 0.9, 0.1, 0.1]
    labels = [1, 0, 1, 0]
    out = sweep_threshold(scores, labels)
    assert out["threshold"] == 0.1
    assert out["hter"] == 0.5


def test_sweep_auc_matches_compute_metrics():
    # AUC is threshold-independent; the sweep must return the same
    # value `compute_metrics` would produce at any single threshold.
    scores = [0.2, 0.4, 0.6, 0.8, 0.3, 0.7]
    labels = [0, 0, 1, 1, 0, 1]
    swept = sweep_threshold(scores, labels)
    direct = compute_metrics(scores, labels, threshold=0.5)
    assert swept["auc"] == direct["auc"]


def test_validator_rejects_length_mismatch():
    with pytest.raises(ValueError, match="same shape"):
        compute_metrics(scores=[0.1, 0.9], labels=[1], threshold=0.5)


def test_validator_rejects_out_of_domain_labels():
    with pytest.raises(ValueError, match=r"labels must be in \{0, 1\}"):
        compute_metrics(scores=[0.1, 0.9], labels=[1, 2], threshold=0.5)


def test_validator_rejects_single_class_input():
    # AUC is undefined without both classes; APCER/BPCER would also
    # degrade to NaN via empty-slice means. Fail loud.
    with pytest.raises(ValueError, match="both classes"):
        compute_metrics(scores=[0.1, 0.4, 0.9], labels=[1, 1, 1], threshold=0.5)


def test_validator_rejects_empty_input():
    with pytest.raises(ValueError, match="non-empty"):
        compute_metrics(scores=[], labels=[], threshold=0.5)


def test_sweep_shares_validator_errors():
    # Same validator, same error text — proves the shared helper is
    # the single source of truth for diagnostics.
    with pytest.raises(ValueError, match="both classes"):
        sweep_threshold(scores=[0.1, 0.4], labels=[1, 1])
