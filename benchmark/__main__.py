"""`python -m benchmark` entry point.

Glue between the dataset loaders, the scorer factories, and the
harness. Nothing lives here that isn't argparse plumbing — the
scientific contract (metrics, sweep, report shape) is in
`benchmark.metrics` + `benchmark.harness` so a notebook user can
import the pieces directly without going through the CLI.

**Exit codes**: 0 on a successful benchmark run; 2 for a usage error
(unknown dataset, missing root, missing model). The non-zero path is
load-bearing — CI jobs and release scripts should fail loudly if the
TFLite artefact drifts out of the expected path, not emit a silent
empty report.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Callable

from .datasets.msu_mfsd import MSUMFSD, MSUMFSDVideo
from .datasets.oulu_npu import OuluNPU
from .datasets.replay_attack import ReplayAttack
from .harness import Dataset, VideoDataset, run, run_video
from .scorers import (
    DEFAULT_MODEL_PATHS,
    SCORER_FACTORIES,
    VIDEO_SCORER_FACTORIES,
    available_scorers,
    is_video_scorer,
)


# Registry keyed by the string a user types for `--dataset`. Values
# are callables that take a Path and return a Dataset instance — the
# loader constructors already match that signature, so this is a
# zero-wrapping registry. Adding a loader is one entry here plus the
# usual class in `datasets/`.
DATASET_LOADERS: dict[str, Callable[[Path], Dataset]] = {
    "msu-mfsd": MSUMFSD,
    "replay-attack": ReplayAttack,
    "oulu-npu": OuluNPU,
}


# Parallel registry for video-level loaders. Only MSU-MFSD is wired
# today because it's the only corpus with per-frame bbox sidecars that
# make the ROI-averaging deterministic without re-running face
# detection in Python. REPLAY-ATTACK and OULU-NPU stay frame-only
# until a loader lands that owns the bbox/landmark extraction.
VIDEO_DATASET_LOADERS: dict[str, Callable[[Path], VideoDataset]] = {
    "msu-mfsd": MSUMFSDVideo,
}


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m benchmark",
        description=(
            "Run the ISO/IEC 30107-3 benchmark on one of the research "
            "datasets using a pluggable scorer. Prints the report as JSON "
            "on stdout; non-zero exit on any missing-input failure."
        ),
    )
    parser.add_argument(
        "--dataset",
        required=True,
        choices=sorted(DATASET_LOADERS.keys()),
        help="Which research corpus to score against.",
    )
    parser.add_argument(
        "--dataset-root",
        required=True,
        type=Path,
        help="Filesystem path to the dataset root (see per-loader docstring for layout).",
    )
    parser.add_argument(
        "--scorer",
        required=True,
        choices=available_scorers(),
        help="Which scorer factory to instantiate.",
    )
    parser.add_argument(
        "--model",
        type=Path,
        default=None,
        help=(
            "Path to the scorer's model artefact. When omitted, picks the "
            "canonical default for the chosen --scorer (see "
            "`DEFAULT_MODEL_PATHS` in benchmark.scorers)."
        ),
    )
    parser.add_argument(
        "--split",
        default="all",
        choices=["all", "train", "test"],
        help=(
            "Dataset-specific train/test partition filter. For MSU-MFSD, "
            "uses the shipped train_sub_list.txt / test_sub_list.txt. "
            "Other datasets ignore this flag today."
        ),
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    """CLI entry. Returns the shell exit code so tests can call this
    directly without `sys.exit`-trapping. argparse itself raises
    SystemExit(2) on unknown args; we match that for our own
    missing-input failures below."""
    parser = _build_parser()
    args = parser.parse_args(argv)

    # Resolve --model lazily: the per-scorer default lets a user run
    # `python -m benchmark --scorer sklearn-lbp` with no --model and
    # get the sklearn pkl rather than the TFLite path (and vice versa).
    model_path = args.model if args.model is not None else DEFAULT_MODEL_PATHS[args.scorer]

    try:
        # Dispatch on scorer kind: video-level scorers (Layer 3 rPPG)
        # consume one VideoSample per clip, frame-level (Layer 4) one
        # Sample per frame. Dataset loader and harness runner pair up
        # accordingly so the harness never sees a type it can't score.
        if is_video_scorer(args.scorer):
            video_loader = VIDEO_DATASET_LOADERS.get(args.dataset)
            if video_loader is None:
                # Explicit error over KeyError — tells the user *why*
                # the combo is disallowed, not just which lookup missed.
                raise ValueError(
                    f"scorer {args.scorer!r} is video-level but dataset "
                    f"{args.dataset!r} has no video loader. "
                    f"Video loaders available for: "
                    f"{sorted(VIDEO_DATASET_LOADERS.keys())}."
                )
            if args.dataset == "msu-mfsd":
                dataset = video_loader(args.dataset_root, split=args.split)
            else:
                dataset = video_loader(args.dataset_root)
            scorer = VIDEO_SCORER_FACTORIES[args.scorer](model_path)
            report = run_video(dataset, scorer)
        else:
            # --split only means something to MSU-MFSD today; other
            # loaders ignore the kwarg. Pass it through conditionally
            # so we don't force the other two constructors to accept a
            # param they don't honour.
            if args.dataset == "msu-mfsd":
                dataset = DATASET_LOADERS[args.dataset](args.dataset_root, split=args.split)
            else:
                dataset = DATASET_LOADERS[args.dataset](args.dataset_root)
            scorer = SCORER_FACTORIES[args.scorer](model_path)
            report = run(dataset, scorer)
    except (FileNotFoundError, RuntimeError, ValueError) as e:
        # One exit path for every "config is wrong / corpus is missing"
        # failure. Stderr so a `--help | less`-style user doesn't see
        # it mixed with the JSON stdout contract.
        print(f"benchmark: {e}", file=sys.stderr)
        return 2

    json.dump(report.to_dict(), sys.stdout, indent=2, sort_keys=True)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":  # pragma: no cover - exercised via subprocess test
    sys.exit(main())
