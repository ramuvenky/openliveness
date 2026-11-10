"""Dataset-agnostic benchmark driver.

Loops over an iterable of [`Sample`][benchmark.harness.Sample]s, calls
a user-supplied `Scorer` on each frame, and returns a
[`BenchmarkReport`][benchmark.harness.BenchmarkReport] containing the
HTER-minimising threshold plus the ISO/IEC 30107-3 numbers at both
that threshold and the stock 0.5 operating point. The 0.5-point report
is what a shipped SDK with default thresholds actually sees — the
swept-optimum is the paper table number. Reporting both prevents
cherry-picking.

The datasets referenced in the paper (MSU-MFSD, REPLAY-ATTACK, OULU-
NPU) each ship a loader implementing the `Dataset` protocol. See
`benchmark/datasets/*.py` — none of the raw video corpora are shipped
with this repo, so loader `__iter__`s raise FileNotFoundError until a
local copy is wired in. The protocol is deliberately narrow so a
fully in-memory fake can drive the harness in unit tests without
pulling in OpenCV or video files.
"""

from __future__ import annotations

from dataclasses import dataclass, asdict
from typing import Callable, Iterator, Protocol, runtime_checkable

import numpy as np

from .metrics import compute_metrics, sweep_threshold


# The reference decision point for a shipped SDK: liveness is called
# at exactly `score >= 0.5`. Making it a module constant keeps the
# "stock operating point" in exactly one place; the paper table
# footnote and the SDK's default threshold stay in lockstep.
DEFAULT_THRESHOLD: float = 0.5


@dataclass(frozen=True)
class Sample:
    """One PAD evaluation input.

    Attributes:
        frame: grayscale image handed to the scorer. The Layer-4 pipeline
            expects an 80×80 uint8 face crop, but the harness itself is
            agnostic — a scorer that wants a full-resolution bitmap
            can accept whatever shape its dataset emits.
        label: 1 for a bona-fide (live) presentation, 0 for an attack.
            Matches the sklearn / ISO 30107-3 convention; score threshold
            `score >= t` is the live-decision rule.
        source: provenance tag (video filename, subject id, …) kept so a
            failure cluster can be traced back to the raw asset. Not used
            by the harness; preserved for post-hoc analysis.
    """

    frame: np.ndarray
    label: int
    source: str = ""


Scorer = Callable[[np.ndarray], float]
"""`(frame) -> live-probability in [0, 1]`. The scorer is injected
rather than imported so a benchmark run can swap between the TFLite
LBP classifier, the Python reference `core.algorithms.layer4_texture.
lbp_score`, or an ablation that uses a different layer entirely —
without any changes to the harness."""


@dataclass(frozen=True)
class VideoSample:
    """One video-level PAD evaluation input — Layer 3 (rPPG) needs the
    full temporal signal from a single clip, not a single frame, so it
    gets its own Sample type to keep the frame-level `Scorer` contract
    unambiguous.

    Attributes:
        rgb_signal: (N, 3) float array of per-frame mean (R, G, B) over
            the combined skin ROIs (forehead + both cheeks). Byte-domain
            means (0..255), unscaled — matches the `RGBSample` units
            the Android `Layer3RgbTap.kt` ships and what the Python
            reference `pos_rppg(rgb_signal, …)` consumes directly.
        fps: capture frame rate. The dataset loader derives this from
            the container metadata; passed through so the scorer can
            pin the FFT frequency axis.
        label: 1 bona-fide / 0 attack, same convention as `Sample`.
        source: provenance tag (video filename) kept for post-hoc
            APCER-by-medium clustering.
    """

    rgb_signal: np.ndarray
    fps: float
    label: int
    source: str = ""


VideoScorer = Callable[["VideoSample"], float]
"""`(VideoSample) -> live-probability in [0, 1]`. Parallel to
[`Scorer`][benchmark.harness.Scorer] but consumes a whole-video RGB
signal — the smallest input that admits any pulse-detection scoring
at all. Register under `benchmark.scorers.VIDEO_SCORER_FACTORIES` so
the CLI can dispatch to [`run_video`][benchmark.harness.run_video]."""


@runtime_checkable
class Dataset(Protocol):
    """Iterable of [`Sample`][benchmark.harness.Sample]s plus bookkeeping.

    `len()` is required so the harness can pre-size progress reporting
    and refuse to run on empty corpora (the metrics side would also
    reject it, but failing before the first inference saves setup time
    on a mistaken config).
    """

    name: str
    def __iter__(self) -> Iterator[Sample]: ...
    def __len__(self) -> int: ...


@runtime_checkable
class VideoDataset(Protocol):
    """Iterable of [`VideoSample`][benchmark.harness.VideoSample]s. Mirror
    of [`Dataset`][benchmark.harness.Dataset] for the video-level path —
    one `VideoSample` per clip rather than one `Sample` per frame.
    """

    name: str
    def __iter__(self) -> Iterator["VideoSample"]: ...
    def __len__(self) -> int: ...


@dataclass(frozen=True)
class BenchmarkReport:
    """Full ISO/IEC 30107-3 report for one dataset + one scorer pair.

    `*_at_optimal` are the paper-table numbers taken at the HTER-
    minimising threshold a sweep found on this exact split; `*_at_default`
    are what the shipped SDK produces today with no tuning. The gap
    between the two bounds the "fair-tuning" headroom a deployer would
    see if they calibrated against their own population.
    """

    dataset: str
    n_samples: int
    n_live: int
    n_attack: int
    optimal_threshold: float
    apcer_at_optimal: float
    bpcer_at_optimal: float
    hter_at_optimal: float
    auc: float
    default_threshold: float
    apcer_at_default: float
    bpcer_at_default: float
    hter_at_default: float

    def to_dict(self) -> dict:
        """Convenience for JSON dumps / CSV rows / paper tables."""
        return asdict(self)


def run(dataset: Dataset, scorer: Scorer) -> BenchmarkReport:
    """Score every sample in `dataset`, return the full report.

    The two threshold evaluations (swept + 0.5) run on the same score/
    label arrays, so the scorer is invoked exactly once per sample —
    important when the scorer wraps a TFLite model whose per-call cost
    dominates the loop.

    Raises:
        ValueError: when the dataset is empty, or the collected labels
            are single-class (propagated from `compute_metrics` /
            `sweep_threshold`).
    """
    if len(dataset) == 0:
        raise ValueError(f"dataset {dataset.name!r} is empty — nothing to score")

    scores: list[float] = []
    labels: list[int] = []
    for sample in dataset:
        scores.append(float(scorer(sample.frame)))
        labels.append(int(sample.label))

    return _build_report(dataset.name, scores, labels)


def run_video(dataset: VideoDataset, scorer: VideoScorer) -> BenchmarkReport:
    """Video-level twin of [`run`][benchmark.harness.run].

    One `scorer()` invocation per clip (not per frame) — necessary for
    Layer 3 rPPG, where the pulse FFT only makes sense over a temporal
    window. Report shape is identical to the frame-level path so
    downstream tooling (JSON dumps, paper tables) doesn't need to
    discriminate.

    Raises:
        ValueError: when the dataset is empty, or the collected labels
            are single-class (propagated from `compute_metrics` /
            `sweep_threshold`).
    """
    if len(dataset) == 0:
        raise ValueError(f"dataset {dataset.name!r} is empty — nothing to score")

    scores: list[float] = []
    labels: list[int] = []
    for sample in dataset:
        scores.append(float(scorer(sample)))
        labels.append(int(sample.label))

    return _build_report(dataset.name, scores, labels)


def _build_report(name: str, scores: list[float], labels: list[int]) -> BenchmarkReport:
    # Shared assembly used by both run() and run_video(). Lives here
    # so a future change to the report fields (e.g. adding EER)
    # doesn't need two parallel edits that could drift.
    swept = sweep_threshold(scores, labels)
    default = compute_metrics(scores, labels, threshold=DEFAULT_THRESHOLD)
    return BenchmarkReport(
        dataset=name,
        n_samples=len(scores),
        n_live=sum(1 for lbl in labels if lbl == 1),
        n_attack=sum(1 for lbl in labels if lbl == 0),
        optimal_threshold=swept["threshold"],
        apcer_at_optimal=swept["apcer"],
        bpcer_at_optimal=swept["bpcer"],
        hter_at_optimal=swept["hter"],
        # AUC is threshold-independent — identical in `swept` and
        # `default`. Reading it off the swept row is arbitrary.
        auc=swept["auc"],
        default_threshold=DEFAULT_THRESHOLD,
        apcer_at_default=default["apcer"],
        bpcer_at_default=default["bpcer"],
        hter_at_default=default["hter"],
    )
