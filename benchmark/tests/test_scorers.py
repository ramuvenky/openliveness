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
    DEFAULT_POS_RPPG_PATH,
    SCORER_FACTORIES,
    VIDEO_SCORER_FACTORIES,
    available_scorers,
    fourier_scorer,
    is_video_scorer,
    pos_rppg_scorer,
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


def test_registry_exposes_pos_rppg():
    # Same pin as the frame-level scorers, but the video registry is
    # the correct one to check — a rename landing here without updating
    # the CLI dispatch would break `--scorer pos-rppg` silently.
    assert "pos-rppg" in VIDEO_SCORER_FACTORIES
    assert VIDEO_SCORER_FACTORIES["pos-rppg"] is pos_rppg_scorer


def test_pos_rppg_registered_in_default_model_paths():
    # Default-path lookup is shared across frame and video registries
    # so the CLI can resolve `--model` uniformly. Missing entry would
    # KeyError the user's run.
    assert DEFAULT_MODEL_PATHS["pos-rppg"] == DEFAULT_POS_RPPG_PATH


def test_is_video_scorer_distinguishes_registries():
    # CLI dispatch hinges on this; a false negative on a video scorer
    # name would route a VideoSample into the frame-level `run` and
    # blow up at scorer-call time with a cryptic signature mismatch.
    assert is_video_scorer("pos-rppg") is True
    assert is_video_scorer("fourier") is False
    assert is_video_scorer("tflite-lbp") is False


def test_available_scorers_merges_both_registries():
    # argparse's `--help` needs to see every registered scorer, not
    # just the frame-level ones — a user who omits pos-rppg from the
    # choices wouldn't be able to invoke it even though the factory
    # exists.
    names = available_scorers()
    assert "pos-rppg" in names
    assert "tflite-lbp" in names
    assert "fourier" in names
    assert names == sorted(names)


def test_pos_rppg_scorer_missing_scipy_raises_runtime_error(monkeypatch):
    # Mirrors the TF / cv2 lazy-import guards: a user without scipy
    # installed gets a clean install hint instead of a bare ImportError
    # from somewhere deep in the Butterworth filter path.
    monkeypatch.setitem(sys.modules, "scipy", None)
    with pytest.raises(RuntimeError, match="requires scipy"):
        pos_rppg_scorer("whatever.pkl")


def test_pos_rppg_scorer_short_signal_returns_zero():
    # Below MIN_SAMPLES_NORMAL (300) → pos_rppg returns None → scorer
    # maps to 0.0. This is the "no pulse detected → attack" convention
    # the mobile Layer 3 enforces; a NaN leak here would silently
    # corrupt the AUC calculation.
    pytest.importorskip("scipy")
    from benchmark.harness import VideoSample

    import numpy as np

    scorer = pos_rppg_scorer("ignored.pkl")
    rgb = np.full((60, 3), 128.0)  # 2s at 30fps — way under MIN_SAMPLES
    sample = VideoSample(rgb_signal=rgb, fps=30.0, label=1, source="short.mov")
    assert scorer(sample) == 0.0


def test_pos_rppg_scorer_synthetic_pulse_produces_nonzero_score():
    # Inject a clean 1.2 Hz (72 bpm) sinusoid on the green channel —
    # the dominant pulse channel in rPPG. The signal is well above the
    # SNR_THRESHOLD_DB_NORMAL floor, so the scorer must return a
    # strictly positive confidence. Catches regressions where the
    # dict→scalar mapping (e.g., reading the wrong key) silently
    # returns 0 on bona-fide clips.
    pytest.importorskip("scipy")
    from benchmark.harness import VideoSample

    import numpy as np

    fs = 30.0
    n = 450  # 15s at 30fps — safely over MIN_SAMPLES_NORMAL
    t = np.arange(n) / fs
    # Byte-domain means, same unit as the real dataset tap (0..255).
    # Pulse amplitude ~2 counts on a 128 baseline mimics a strong
    # bona-fide signal without clipping the normalisation step.
    pulse = 2.0 * np.sin(2.0 * np.pi * 1.2 * t)
    rgb = np.stack(
        [
            np.full(n, 128.0),
            128.0 + pulse,  # pulse on green
            np.full(n, 128.0),
        ],
        axis=1,
    )
    scorer = pos_rppg_scorer("ignored.pkl")
    sample = VideoSample(rgb_signal=rgb, fps=fs, label=1, source="synthetic.mov")
    score = scorer(sample)
    assert isinstance(score, float)
    assert 0.0 < score <= 1.0


def test_pos_rppg_scorer_degenerate_signal_returns_zero():
    # Zero-mean on any channel → pos_rppg can't normalise → None →
    # scorer returns 0.0. Protects against a black-frame capture or
    # tracking failure injecting NaNs downstream.
    pytest.importorskip("scipy")
    from benchmark.harness import VideoSample

    import numpy as np

    scorer = pos_rppg_scorer("ignored.pkl")
    rgb = np.zeros((450, 3))  # degenerate: zero means across every channel
    sample = VideoSample(rgb_signal=rgb, fps=30.0, label=0, source="degenerate.mov")
    assert scorer(sample) == 0.0
