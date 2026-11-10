"""Tests for the TFLite scorer factory.

Keeps the fast-feedback suite independent of tensorflow — the heavy
integration path is gated on `importorskip("tensorflow")` so a CI
box without TF installed still runs the lazy-import + missing-model
diagnostics, which are the two failure modes a user actually hits.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

from benchmark.scorers import (
    DEFAULT_FOURIER_PATH,
    DEFAULT_LBP_MODEL_PATH,
    DEFAULT_MODEL_PATHS,
    SCORER_FACTORIES,
    available_scorers,
    fourier_scorer,
    tflite_lbp_scorer,
)


def test_registry_exposes_tflite_lbp():
    # The CLI reads this registry to build `--scorer` choices. If a
    # future refactor renames the key, the CLI's argparse would reject
    # the user's old command line without warning; pin the public
    # string here.
    assert "tflite-lbp" in SCORER_FACTORIES
    assert SCORER_FACTORIES["tflite-lbp"] is tflite_lbp_scorer


def test_available_scorers_is_sorted():
    # argparse's `--help` iterates `choices=` in order — a sorted list
    # keeps the output stable across Python versions and makes the
    # user-facing help identical on every machine.
    names = available_scorers()
    assert names == sorted(names)
    assert "tflite-lbp" in names


def test_default_model_path_points_at_shipped_asset():
    # The default must land on the artefact the Android SDK actually
    # ships — if someone renames the asset without updating this
    # constant, the CLI's default would silently diverge from the
    # mobile deployment.
    assert DEFAULT_LBP_MODEL_PATH == Path(
        "android-sdk/src/main/assets/lbp_classifier.tflite"
    )


def test_missing_tensorflow_raises_runtime_error(monkeypatch):
    # Hide `tensorflow` from the import machinery for this test only.
    # The scorer factory does a lazy import so a user who skipped
    # `pip install -r core/requirements.txt` gets a clear hint rather
    # than a bare ImportError from somewhere deep in the TF stack.
    monkeypatch.setitem(sys.modules, "tensorflow", None)
    with pytest.raises(RuntimeError, match="requires tensorflow"):
        tflite_lbp_scorer("whatever.tflite")


def test_missing_model_file_raises_with_hint(tmp_path, monkeypatch):
    # When TF is present but the model artefact isn't on disk, the
    # error must name the canonical path + the regenerate command so
    # a user with a stale checkout can fix it in one line.
    tensorflow = pytest.importorskip("tensorflow")
    bogus = tmp_path / "missing.tflite"
    with pytest.raises(FileNotFoundError) as exc_info:
        tflite_lbp_scorer(bogus)
    msg = str(exc_info.value)
    assert str(DEFAULT_LBP_MODEL_PATH) in msg
    assert "export_tflite" in msg


def test_happy_path_scores_a_face_crop():
    # Full end-to-end: load the shipped .tflite, feed a synthetic
    # 80×80 grayscale crop through lbp_histogram, confirm the scorer
    # emits a Python float in [0, 1]. Gated on both tensorflow and
    # cv2 / skimage being importable — matches the production stack.
    pytest.importorskip("tensorflow")
    pytest.importorskip("cv2")
    pytest.importorskip("skimage")

    import numpy as np

    model_path = Path(__file__).resolve().parents[2] / DEFAULT_LBP_MODEL_PATH
    if not model_path.exists():
        pytest.skip(f"shipped model not found at {model_path}")

    scorer = tflite_lbp_scorer(model_path)
    # Deterministic non-trivial texture: a diagonal gradient. Keeps
    # the test insensitive to the exact classifier weights while
    # still giving the LBP a non-degenerate histogram.
    frame = np.tile(np.linspace(0, 255, 80, dtype=np.uint8), (80, 1))
    score = scorer(frame)
    assert isinstance(score, float)
    assert 0.0 <= score <= 1.0


def test_registry_exposes_fourier():
    # Same pin as the LBP entry — the CLI reads this registry to build
    # `--scorer` choices, so renaming the public string without updating
    # downstream call sites must break this test loudly, not the user's
    # shell command silently.
    assert "fourier" in SCORER_FACTORIES
    assert SCORER_FACTORIES["fourier"] is fourier_scorer


def test_fourier_registered_in_default_model_paths():
    # The CLI's uniform `--model` lookup does DEFAULT_MODEL_PATHS[scorer]
    # with no fallback; a missing entry would KeyError the user's run.
    # Fourier has no real model, but the sentinel keeps the lookup uniform.
    assert DEFAULT_MODEL_PATHS["fourier"] == DEFAULT_FOURIER_PATH


def test_fourier_scorer_ignores_model_path(tmp_path):
    # Interface parity: the factory accepts a path just like the LBP
    # scorers, but Fourier owns no weights so the path never gets
    # read. Pass a bogus path that would fail any file existence check
    # and confirm the scorer builds + scores anyway.
    pytest.importorskip("cv2")
    import numpy as np

    scorer = fourier_scorer(tmp_path / "not-a-real-file.pkl")
    frame = np.tile(np.linspace(0, 255, 80, dtype=np.uint8), (80, 1))
    score = scorer(frame)
    assert isinstance(score, float)
    assert 0.0 <= score <= 1.0


def test_fourier_scorer_missing_cv2_raises_runtime_error(monkeypatch):
    # Match the LBP scorers' lazy-import diagnostic: a user without cv2
    # installed gets a clear install hint instead of a bare ImportError.
    monkeypatch.setitem(sys.modules, "cv2", None)
    with pytest.raises(RuntimeError, match="requires opencv-python"):
        fourier_scorer("whatever.pkl")
