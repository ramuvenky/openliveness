"""ISO/IEC 30107-3 metrics: APCER, BPCER, HTER, AUC + HTER-min sweep.

The three error rates follow the ISO definitions used throughout the
anti-spoofing literature:

  APCER = Attack Presentation Classification Error Rate
        = fraction of attack samples the detector wrongly accepts as live
  BPCER = Bona Fide Presentation Classification Error Rate
        = fraction of live samples the detector wrongly rejects as attack
  HTER  = Half Total Error Rate = (APCER + BPCER) / 2

Score convention: higher means more likely live. A sample is called
live iff `score >= threshold`. This matches the Python spec at
`docs/Person2Prompt.md:886-897` and the Layer-4 classifier output
contract.
"""

from __future__ import annotations

from typing import Mapping, Sequence

import numpy as np
from sklearn.metrics import roc_auc_score


# Public dict schema. Pinned so downstream (paper table, CI
# regression gate) can rely on exact key names.
_METRIC_KEYS = ("apcer", "bpcer", "hter", "auc")


def compute_metrics(
    scores: Sequence[float],
    labels: Sequence[int],
    threshold: float = 0.5,
) -> Mapping[str, float]:
    """Return APCER/BPCER/HTER/AUC at `threshold`.

    Args:
        scores: iterable of floats in [0.0, 1.0]; higher = more likely live.
        labels: iterable of ints; 1 = live (bona fide), 0 = attack.
        threshold: decision threshold applied via `score >= threshold`.

    Returns:
        Mapping with keys `apcer`, `bpcer`, `hter`, `auc`.

    Raises:
        ValueError: on length mismatch, out-of-domain labels, or when
            only one class is present (AUC + per-class rates are
            undefined). We fail loud rather than silently degrading
            to a half-correct number that would land in a paper table.
    """
    scores_arr = np.asarray(scores, dtype=np.float64)
    labels_arr = np.asarray(labels, dtype=np.int64)
    _validate(scores_arr, labels_arr)

    predictions = (scores_arr >= threshold).astype(np.int64)
    attack_mask = labels_arr == 0
    live_mask = labels_arr == 1

    apcer = float(predictions[attack_mask].mean())  # mean of (pred==1) on attacks
    bpcer = float((1 - predictions[live_mask]).mean())  # mean of (pred==0) on lives
    hter = 0.5 * (apcer + bpcer)
    # roc_auc_score requires both classes present; _validate ensured that.
    auc = float(roc_auc_score(labels_arr, scores_arr))

    return {"apcer": apcer, "bpcer": bpcer, "hter": hter, "auc": auc}


def sweep_threshold(
    scores: Sequence[float],
    labels: Sequence[int],
) -> Mapping[str, float]:
    """Return the HTER-minimising threshold and its metrics.

    Enumerates every unique score as a candidate threshold — the
    HTER surface is piecewise-constant between adjacent sorted scores
    (crossing one sample flips a single prediction), so sweeping unique
    values is exact and avoids the aliasing of a fixed-step linspace.
    Ties go to the lowest-threshold candidate so a monotone score
    distribution yields a stable, reproducible pick across runs.

    AUC is threshold-independent and comes out of the per-threshold
    `compute_metrics` call unchanged — included in the return for
    callers that want the full ISO 30107-3 report in one shot.

    Returns:
        Same keys as `compute_metrics` plus `threshold`: the chosen
        decision point. The returned threshold is one of the input
        scores — rounding/snapping is the caller's job.
    """
    scores_arr = np.asarray(scores, dtype=np.float64)
    labels_arr = np.asarray(labels, dtype=np.int64)
    _validate(scores_arr, labels_arr)

    best: dict[str, float] | None = None
    # Sorted so that on exact HTER ties the FIRST (smallest) threshold
    # wins — gives a deterministic pick and biases toward lower-FRR
    # operating points, which is what deployment tends to prefer.
    for threshold in np.unique(scores_arr):
        row = compute_metrics(scores_arr, labels_arr, threshold=float(threshold))
        if best is None or row["hter"] < best["hter"]:
            best = {**row, "threshold": float(threshold)}

    # _validate guarantees at least one sample per class, hence at
    # least one unique score — `best` is non-None by construction.
    assert best is not None
    return best


def _validate(scores: np.ndarray, labels: np.ndarray) -> None:
    """Shared precondition check for both entry points. Pulled out so
    the error messages stay identical regardless of which function the
    caller landed in — a benchmark run flipping between the two
    shouldn't see cosmetically-different diagnostics."""
    if scores.shape != labels.shape:
        raise ValueError(
            f"scores and labels must have the same shape; "
            f"got {scores.shape} vs {labels.shape}"
        )
    if scores.ndim != 1:
        raise ValueError(f"scores must be 1-D; got {scores.ndim}-D")
    if scores.size == 0:
        raise ValueError("scores/labels must be non-empty")
    unique_labels = np.unique(labels)
    if not np.all(np.isin(unique_labels, (0, 1))):
        raise ValueError(
            f"labels must be in {{0, 1}}; got {sorted(unique_labels.tolist())}"
        )
    if unique_labels.size < 2:
        raise ValueError(
            "both classes must be represented; "
            "APCER/BPCER/AUC are undefined on a single-class input"
        )
