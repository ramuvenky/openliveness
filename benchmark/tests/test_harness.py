"""Tests for the dataset-agnostic benchmark harness.

An in-memory `FakeDataset` plays the role of a real research corpus
so we can exercise `run()` without downloading videos or depending on
OpenCV. The harness's contract is narrow — iterate samples, score
each, emit a report — so the fake covers the full behaviour.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest

from benchmark.datasets.msu_mfsd import MSUMFSD
from benchmark.datasets.oulu_npu import OuluNPU
from benchmark.datasets.replay_attack import ReplayAttack
from benchmark.harness import (
    BenchmarkReport,
    DEFAULT_THRESHOLD,
    Dataset,
    Sample,
    run,
)


class FakeDataset:
    """Smallest thing that satisfies the Dataset protocol — four
    samples, two per class, scores already baked in so the scorer
    is a trivial identity. Lets us assert exact HTER/AUC numbers."""

    name = "FakeCorpus"

    def __init__(self, samples: list[Sample]) -> None:
        self._samples = samples

    def __iter__(self):
        return iter(self._samples)

    def __len__(self) -> int:
        return len(self._samples)


def _frame(scalar: float) -> np.ndarray:
    """Build a 1×1 frame whose only pixel encodes the score the
    identity scorer will read back. Keeps the test independent of any
    real feature extractor. float64 — not float32 — so an exact
    equality check against the chosen threshold doesn't trip on the
    `0.8 → 0.800000011920929` cast."""
    return np.array([[scalar]], dtype=np.float64)


def _identity_scorer(frame: np.ndarray) -> float:
    return float(frame.flat[0])


def test_run_produces_full_report_on_perfect_scores():
    # Perfectly separable: all lives at 1.0, all attacks at 0.0. The
    # swept-optimal and default thresholds should both see zero error,
    # but the sweep picks the lowest-live score (1.0) as the threshold.
    dataset = FakeDataset([
        Sample(frame=_frame(1.0), label=1, source="live-A"),
        Sample(frame=_frame(1.0), label=1, source="live-B"),
        Sample(frame=_frame(0.0), label=0, source="atk-A"),
        Sample(frame=_frame(0.0), label=0, source="atk-B"),
    ])
    report = run(dataset, _identity_scorer)

    assert isinstance(report, BenchmarkReport)
    assert report.dataset == "FakeCorpus"
    assert report.n_samples == 4
    assert report.n_live == 2
    assert report.n_attack == 2

    # Perfect ranker → AUC == 1.
    assert report.auc == 1.0

    # Default-threshold operating point: 0.5 cleanly separates.
    assert report.default_threshold == DEFAULT_THRESHOLD
    assert report.apcer_at_default == 0.0
    assert report.bpcer_at_default == 0.0
    assert report.hter_at_default == 0.0

    # Swept-optimal — threshold snaps to a unique score and HTER=0.
    assert report.hter_at_optimal == 0.0
    assert report.optimal_threshold in (0.0, 1.0)


def test_run_distinguishes_optimal_from_default():
    # Scores are shifted so the stock 0.5 threshold is wrong but a
    # sweep can find a better operating point. Pins the "report both"
    # contract — the hter_at_default must be strictly worse than
    # hter_at_optimal in this configuration.
    dataset = FakeDataset([
        Sample(frame=_frame(0.9), label=1, source="live-A"),
        Sample(frame=_frame(0.8), label=1, source="live-B"),
        # Attack at 0.6 — above the default 0.5 threshold, so the
        # stock SDK would wrongly accept it. The sweep should prefer
        # a threshold at or above 0.7 to catch this.
        Sample(frame=_frame(0.6), label=0, source="atk-high"),
        Sample(frame=_frame(0.1), label=0, source="atk-low"),
    ])
    report = run(dataset, _identity_scorer)

    # Stock 0.5 accepts the 0.6-scored attack: APCER = 0.5.
    assert report.apcer_at_default == 0.5
    assert report.bpcer_at_default == 0.0
    assert report.hter_at_default == 0.25

    # Sweep finds a threshold > 0.6 that catches the attack.
    assert report.hter_at_optimal == 0.0
    assert report.optimal_threshold == 0.8


def test_run_rejects_empty_dataset():
    with pytest.raises(ValueError, match="is empty"):
        run(FakeDataset([]), _identity_scorer)


def test_run_propagates_single_class_error():
    # Harness delegates to compute_metrics / sweep_threshold; the
    # validator's loud-fail contract must reach the caller.
    dataset = FakeDataset([
        Sample(frame=_frame(0.1), label=1, source="A"),
        Sample(frame=_frame(0.9), label=1, source="B"),
    ])
    with pytest.raises(ValueError, match="both classes"):
        run(dataset, _identity_scorer)


def test_fakedataset_satisfies_dataset_protocol():
    # Keeps us honest: a change to the Protocol that breaks the fake
    # also breaks the real loaders. runtime_checkable makes
    # isinstance work here.
    dataset = FakeDataset([Sample(frame=_frame(0.5), label=1)])
    assert isinstance(dataset, Dataset)


def test_scorer_invoked_once_per_sample():
    # Pins the "collect scores + labels then evaluate twice" shape —
    # if a future refactor accidentally re-scored the dataset for the
    # default-threshold pass, a model with per-call cost would double
    # the benchmark runtime. The counter here is a cheap tripwire.
    calls = {"n": 0}

    def counting_scorer(frame: np.ndarray) -> float:
        calls["n"] += 1
        return float(frame.flat[0])

    dataset = FakeDataset([
        Sample(frame=_frame(0.9), label=1),
        Sample(frame=_frame(0.2), label=0),
    ])
    run(dataset, counting_scorer)
    assert calls["n"] == 2


@pytest.mark.parametrize(
    "cls, expected_name",
    [
        (MSUMFSD, "MSU-MFSD"),
        (ReplayAttack, "REPLAY-ATTACK"),
        (OuluNPU, "OULU-NPU"),
    ],
)
def test_dataset_loader_errors_cleanly_on_missing_root(
    cls, expected_name, tmp_path: Path
):
    # The loaders are documented stubs: constructing against a non-
    # existent root must raise with the paper citation + download URL
    # so a user who typo'd the path sees exactly one actionable error.
    bogus = tmp_path / "does-not-exist"
    with pytest.raises(FileNotFoundError) as exc_info:
        cls(bogus)
    msg = str(exc_info.value)
    assert expected_name in msg
    assert "Download from" in msg


@pytest.mark.parametrize(
    "cls",
    # MSUMFSD intentionally omitted — its __iter__ is wired against
    # the real MSU-MFSD layout + .face sidecars (see
    # test_msu_mfsd_loader.py for the synthetic-video coverage).
    # ReplayAttack and OuluNPU still raise until a local corpus is
    # available for them.
    [ReplayAttack, OuluNPU],
)
def test_dataset_loader_iter_defers_until_decoder_is_wired(
    cls, tmp_path: Path
):
    # The root exists but the frame-extraction loop is explicitly
    # deferred — iterating must raise NotImplementedError, not
    # silently emit zero samples. Protects against a future dev
    # thinking the loader "works" because len() returned 0.
    (tmp_path / "real").mkdir()
    (tmp_path / "attack").mkdir()
    (tmp_path / "Train_files").mkdir()  # for OULU
    dataset = cls(tmp_path)
    with pytest.raises(NotImplementedError, match="frame-extraction"):
        next(iter(dataset))
