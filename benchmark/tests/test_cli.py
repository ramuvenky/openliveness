"""Tests for the `python -m benchmark` entry point.

Covers the argparse surface (choices, required flags) + the three
"missing input" exit paths (bad dataset root, bad model path, dataset
validator failure). The happy path is exercised with a fake dataset
+ fake scorer monkey-patched into the registries so the test doesn't
need tensorflow / cv2 / an actual video corpus.
"""

from __future__ import annotations

import io
import json
from pathlib import Path

import numpy as np
import pytest

from benchmark import __main__ as cli
from benchmark.harness import Sample


def test_main_rejects_missing_dataset_arg(capsys):
    # argparse exits 2 on missing required flags; this is the standard
    # shell contract. Pin it so a future refactor that swaps in a
    # bespoke parser can't accidentally change the exit code.
    with pytest.raises(SystemExit) as exc_info:
        cli.main(["--scorer", "tflite-lbp"])
    assert exc_info.value.code == 2


def test_main_rejects_unknown_dataset(capsys):
    # argparse enforces `choices=`; a typo in the dataset name is a
    # config error, not a runtime failure.
    with pytest.raises(SystemExit) as exc_info:
        cli.main([
            "--dataset", "no-such-corpus",
            "--dataset-root", "/tmp",
            "--scorer", "tflite-lbp",
        ])
    assert exc_info.value.code == 2


def test_main_exits_2_on_missing_dataset_root(tmp_path, capsys):
    # Loader constructor raises FileNotFoundError, main() must trap it
    # and return 2 — not re-raise, not exit 1. The error message must
    # name the dataset (so a user running three in a loop can tell
    # which one failed) and the download URL.
    bogus = tmp_path / "nope"
    rc = cli.main([
        "--dataset", "msu-mfsd",
        "--dataset-root", str(bogus),
        "--scorer", "tflite-lbp",
    ])
    assert rc == 2
    err = capsys.readouterr().err
    assert "MSU-MFSD" in err
    assert "Download from" in err


def test_main_exits_2_on_missing_model(tmp_path, capsys, monkeypatch):
    # Dataset root exists (real/ + attack/ subdirs so the loader
    # constructor succeeds), but the model path points at nothing.
    # The scorer factory's FileNotFoundError must land on the same
    # exit-2 branch as the dataset-missing case.
    pytest.importorskip("tensorflow")
    (tmp_path / "real").mkdir()
    (tmp_path / "attack").mkdir()
    rc = cli.main([
        "--dataset", "msu-mfsd",
        "--dataset-root", str(tmp_path),
        "--scorer", "tflite-lbp",
        "--model", str(tmp_path / "missing.tflite"),
    ])
    assert rc == 2
    err = capsys.readouterr().err
    assert "TFLite model not found" in err


def test_main_happy_path_emits_json(tmp_path, capsys, monkeypatch):
    # Inject a fake dataset + fake scorer so the test is independent
    # of tensorflow / cv2 / the actual research corpora. Verifies the
    # end-to-end wiring: argparse → loader lookup → scorer lookup →
    # harness.run → JSON on stdout.
    class _FakeDataset:
        name = "FAKE"

        def __init__(self, root: Path) -> None:
            self.root = root

        def __iter__(self):
            yield Sample(np.array([[0.9]]), 1, "live-A")
            yield Sample(np.array([[0.8]]), 1, "live-B")
            yield Sample(np.array([[0.2]]), 0, "atk-A")
            yield Sample(np.array([[0.1]]), 0, "atk-B")

        def __len__(self) -> int:
            return 4

    def _fake_scorer_factory(_model):
        # The CLI passes --model through unexamined; a scorer that
        # ignores it lets the happy-path test skip the "model file
        # exists" precondition without stubbing tensorflow.
        return lambda frame: float(frame.flat[0])

    monkeypatch.setitem(cli.DATASET_LOADERS, "fake", _FakeDataset)
    monkeypatch.setitem(cli.SCORER_FACTORIES, "fake", _fake_scorer_factory)
    # The CLI looks up DEFAULT_MODEL_PATHS[scorer] when --model is
    # omitted; register one for the injected scorer so the lookup
    # succeeds. The fake factory ignores whatever path it receives.
    monkeypatch.setitem(cli.DEFAULT_MODEL_PATHS, "fake", Path("/ignored"))
    # argparse's choices= is frozen at parser-build time; rebuild
    # it so our injected keys are accepted.
    monkeypatch.setattr(cli, "_build_parser", _patched_parser)

    rc = cli.main([
        "--dataset", "fake",
        "--dataset-root", str(tmp_path),
        "--scorer", "fake",
    ])
    assert rc == 0
    out = capsys.readouterr().out
    report = json.loads(out)
    assert report["dataset"] == "FAKE"
    assert report["n_samples"] == 4
    assert report["n_live"] == 2
    assert report["n_attack"] == 2
    # Perfect ranker on this split.
    assert report["auc"] == 1.0
    assert report["hter_at_optimal"] == 0.0


def _patched_parser():
    # Rebuilds the argparse parser *after* the test's monkeypatch has
    # inserted "fake" into DATASET_LOADERS / SCORER_FACTORIES, so the
    # choices= lists see the injected keys. Lives outside the test so
    # monkeypatch.setattr can swap it in cleanly.
    import argparse

    parser = argparse.ArgumentParser(prog="python -m benchmark")
    parser.add_argument("--dataset", required=True, choices=sorted(cli.DATASET_LOADERS.keys()))
    parser.add_argument("--dataset-root", required=True, type=Path)
    parser.add_argument("--scorer", required=True, choices=sorted(cli.SCORER_FACTORIES.keys()))
    parser.add_argument("--model", type=Path, default=None)
    return parser


def test_main_exits_cleanly_on_empty_dataset(tmp_path, capsys, monkeypatch):
    # The harness's ValueError (empty dataset) must map to exit 2,
    # same bucket as config errors — the user's input (an empty root)
    # is the root cause either way.
    class _EmptyDataset:
        name = "EMPTY"

        def __init__(self, root: Path) -> None:
            pass

        def __iter__(self):
            return iter(())

        def __len__(self) -> int:
            return 0

    monkeypatch.setitem(cli.DATASET_LOADERS, "empty", _EmptyDataset)
    monkeypatch.setitem(cli.SCORER_FACTORIES, "noop", lambda _m: (lambda f: 0.5))
    monkeypatch.setitem(cli.DEFAULT_MODEL_PATHS, "noop", Path("/ignored"))
    monkeypatch.setattr(cli, "_build_parser", _patched_parser)

    rc = cli.main([
        "--dataset", "empty",
        "--dataset-root", str(tmp_path),
        "--scorer", "noop",
    ])
    assert rc == 2
    assert "empty" in capsys.readouterr().err.lower()
