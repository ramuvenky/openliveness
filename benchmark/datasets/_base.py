"""Shared base for the three research-dataset loaders.

Each concrete loader (MSU-MFSD, REPLAY-ATTACK, OULU-NPU) describes
its root layout as a `_RootSpec` — the live subdir, the attack subdir,
and the glob patterns used to find video files inside each. The base
class handles `len()` + a clear FileNotFoundError when the raw corpus
isn't present on disk, leaving the actual video→frame→Sample
conversion as per-dataset `__iter__` logic (NotImplementedError here;
see docstrings on each subclass for the gating reason).

Why not inline this per file: the three error messages — "dataset
not found, download at <URL>, expected layout …" — are identical in
shape and would drift the moment one got rephrased. Pulling the
layout into a shared struct keeps the user-facing diagnostics
consistent and makes it a 10-line change to add a fourth dataset.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Iterator, Sequence

from benchmark.harness import Sample


@dataclass(frozen=True)
class _RootSpec:
    """Describes where a dataset's live/attack videos live under `root`.

    Kept module-private because the three dataset loaders are the only
    things that should ever construct one; downstream code only reads
    `Dataset.name` and iterates samples.
    """

    live_subdir: str
    attack_subdir: str
    video_globs: Sequence[str]  # e.g., ("*.mp4", "*.mov", "*.avi")
    download_url: str
    citation: str


class _ResearchDataset:
    """Base for the three research video datasets.

    Subclasses override `name` + `_SPEC` + (eventually) `__iter__`.
    The default `__iter__` raises `NotImplementedError` because the
    raw video decoding path is deferred — we don't ship the videos
    and haven't confirmed OpenCV is on the target machine. Enabling
    a loader is a two-line change (import cv2, implement the
    frame-extraction loop), gated on a user confirming availability
    rather than installed-by-default to keep `benchmark.*` importable
    on a laptop that doesn't have cv2 yet.
    """

    name: str = "unset"
    _SPEC: _RootSpec

    def __init__(self, root: Path | str) -> None:
        root_path = Path(root)
        if not root_path.exists():
            # Fail fast with the download pointer so a user who typo'd
            # the path or forgot to download the corpus sees exactly
            # one actionable error rather than a confusing empty
            # iteration later. Not raising inside __iter__ because
            # callers often len() a dataset for sizing before scoring,
            # and we want both paths to produce the same diagnostic.
            raise FileNotFoundError(
                f"{self.name}: dataset root {root_path!r} does not exist. "
                f"Download from {self._SPEC.download_url} ({self._SPEC.citation})."
            )
        self.root = root_path
        self._live_dir = root_path / self._SPEC.live_subdir
        self._attack_dir = root_path / self._SPEC.attack_subdir

    def _video_paths(self, subdir: Path) -> list[Path]:
        """Enumerate videos matching any of `_SPEC.video_globs`.

        Returned sorted so iteration order is deterministic across runs
        on the same machine — load-bearing for reproducible paper tables.
        Also lets the harness pre-count samples via `__len__` without
        buffering per-video frames.
        """
        if not subdir.exists():
            return []
        paths: list[Path] = []
        for pattern in self._SPEC.video_globs:
            paths.extend(subdir.glob(pattern))
        return sorted(paths)

    def __len__(self) -> int:
        """Video count (not frame count) — matches what the paper
        tables report as `n_samples` for each corpus. A subclass that
        extracts multiple frames per video should override."""
        return len(self._video_paths(self._live_dir)) + len(
            self._video_paths(self._attack_dir)
        )

    def __iter__(self) -> Iterator[Sample]:
        # Deferred: see class docstring. The subclass docstrings include
        # the exact pip install + OpenCV API this would use once a
        # local corpus is available.
        raise NotImplementedError(
            f"{self.name}: frame-extraction loop is not wired yet. "
            "Enable by installing opencv-python and adding a cv2.VideoCapture "
            "loop that yields one 80×80 grayscale face crop per sampled frame. "
            "See the subclass docstring for the per-dataset directory map."
        )
