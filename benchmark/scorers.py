"""Scorer factories for the benchmark harness.

The harness takes any `Scorer = Callable[[np.ndarray], float]`, so this
file is where the plumbing between the Python benchmark path and the
shipped mobile artefacts lives. Each factory returns a closure that
owns a loaded model + preprocessing pipeline and renders one score per
frame.

**Preprocessing parity**: The TFLite scorer reuses
`core.algorithms.layer4_texture.lbp_histogram` verbatim — the Python
and mobile sides use the SAME 59-bin uniform-LBP extractor the
training script produced features from. A drift between benchmark
and production preprocessing is the single quietest way to publish
inflated paper numbers; routing both through one function is the
tripwire.

**Deferred TF import**: `tensorflow` is imported inside the factory,
not at module scope. The benchmark harness, dataset stubs, and
pytest cases must stay importable on machines where TF isn't
installed yet (Catalina + TF 2.17 is historically fragile per
project memory — same reason `core.algorithms.layer4_texture` guards
its `cv2` + `skimage` imports). A CLI user who skipped `pip install
-r core/requirements.txt` gets a clear RuntimeError at scorer-build
time with the install hint, not a bare ImportError from somewhere
deep in the tf stack.
"""

from __future__ import annotations

from pathlib import Path
from typing import Callable

import numpy as np

from .harness import Scorer, VideoSample, VideoScorer


# Canonical location of the TFLite artefact in the mono-repo. Exported
# so the CLI's --model help text, the scorer's error message, and any
# future dev-ex script that wants to default to the shipped weights
# all agree on one path. Relative to repo root; the CLI resolves it
# before passing it down.
DEFAULT_LBP_MODEL_PATH = Path("android-sdk/src/main/assets/lbp_classifier.tflite")

# Pure-sklearn mirror of the shipped TFLite classifier. Lives next to
# the training script in `core/models/`. Same training data + same
# feature pipeline, exported two ways: `.pkl` for the server and
# benchmark harness (sklearn-native), `.tflite` for the mobile SDKs
# (bundled into the Android APK + iOS app). Shipping both lets the
# benchmark CLI run on machines where TensorFlow isn't installable —
# relevant on Catalina x86_64 where TF 2.14+ wheels don't exist.
DEFAULT_LBP_SKLEARN_PATH = Path("core/models/lbp_classifier.pkl")

# Sentinel for the Fourier scorer — the FFT ring-energy detector is a
# closed-form function of the input frame (Spec §1424-1448), with no
# trained weights to load. The CLI's uniform `--model` lookup expects
# every registered scorer to have a default entry in DEFAULT_MODEL_PATHS,
# so we register this placeholder and the factory accepts-and-ignores
# whatever path the CLI resolves. Keeping the same factory signature
# across LBP + Fourier means `SCORER_FACTORIES` stays a single flat
# `dict[str, Callable[[Path | str], Scorer]]` without variance gymnastics.
DEFAULT_FOURIER_PATH = Path("<fourier-has-no-model>")

# Same sentinel pattern as Fourier — Layer 3 POS rPPG (Spec §1356-1405)
# is a closed-form function of the RGB signal; no weights to load. The
# CLI's `--model` lookup is uniform across frame-level and video-level
# scorers so the placeholder lives in DEFAULT_MODEL_PATHS too.
DEFAULT_POS_RPPG_PATH = Path("<pos-rppg-has-no-model>")


def tflite_lbp_scorer(model_path: Path | str) -> Scorer:
    """Load `lbp_classifier.tflite` and return a per-frame Scorer closure.

    The returned callable:
        1. Runs `lbp_histogram(frame)` — same extractor the training
           script `core/models/train_texture_model.py` used.
        2. Reshapes the 59-bin histogram to the Interpreter's input
           shape (typically `(1, 59)` float32).
        3. Invokes the Interpreter and returns the single scalar
           output as a Python float in [0, 1].

    The Interpreter is loaded exactly ONCE per factory call and
    captured in the closure. TFLite Interpreters are not thread-safe,
    which matches the harness's single-threaded `run()` loop; do not
    hand the returned Scorer to a thread pool without a fresh factory
    call per worker.

    Raises:
        RuntimeError: when tensorflow is not importable — points the
            user at `core/requirements.txt`.
        FileNotFoundError: when `model_path` doesn't exist. The error
            message names the canonical path + the regenerate command
            so a user with a stale checkout can fix it in one line.
    """
    try:
        import tensorflow as tf  # type: ignore
    except ImportError as e:  # pragma: no cover - exercised via a test shim
        raise RuntimeError(
            "tflite_lbp_scorer requires tensorflow; "
            "install core/requirements.txt before running the benchmark CLI."
        ) from e
    # Local import so a missing cv2/skimage at scorer-build time
    # produces the same clear RuntimeError lbp_histogram raises —
    # avoids importing the Layer-4 module at package load.
    from core.algorithms.layer4_texture import lbp_histogram

    resolved = Path(model_path)
    if not resolved.exists():
        raise FileNotFoundError(
            f"TFLite model not found at {resolved!r}. "
            f"The canonical artefact lives at {DEFAULT_LBP_MODEL_PATH} "
            "and is generated by `python -m core.models.export_tflite`."
        )

    interpreter = tf.lite.Interpreter(model_path=str(resolved))
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()[0]
    output_details = interpreter.get_output_details()[0]
    input_index = input_details["index"]
    output_index = output_details["index"]
    input_shape = tuple(int(d) for d in input_details["shape"])
    input_dtype = input_details["dtype"]

    def score(frame: np.ndarray) -> float:
        # lbp_histogram returns float64; the exported model's input is
        # float32 — convert explicitly rather than relying on numpy's
        # ambiguous auto-cast. .reshape allocates a view when strides
        # permit, so this is one copy total (the astype).
        hist = lbp_histogram(frame).astype(input_dtype, copy=False)
        batched = hist.reshape(input_shape)
        interpreter.set_tensor(input_index, batched)
        interpreter.invoke()
        out = interpreter.get_tensor(output_index)
        return float(out.reshape(-1)[0])

    return score


def sklearn_lbp_scorer(model_path: Path | str) -> Scorer:
    """Load `lbp_classifier.pkl` and return a per-frame Scorer closure.

    Mirror of `tflite_lbp_scorer` but using the sklearn-native pickle
    instead of the TFLite export. Same `lbp_histogram` preprocessing,
    same training data, same model architecture — the two scorers
    should produce near-identical scores on the same frame. Reporting
    both lets the benchmark CLI run on Catalina (no TF wheel) and
    cross-check mobile-vs-server numeric parity.

    The pickle is `joblib.load`-ed exactly once per factory call;
    sklearn classifiers are thread-safe for `predict_proba`, so the
    returned Scorer can in principle be shared across workers — the
    harness is single-threaded regardless.

    Raises:
        RuntimeError: scikit-learn or joblib not importable.
        FileNotFoundError: `model_path` missing, with a pointer to the
            canonical path + regenerate command.
    """
    try:
        import joblib  # type: ignore
        import sklearn  # noqa: F401  type: ignore (presence check only)
    except ImportError as e:  # pragma: no cover
        raise RuntimeError(
            "sklearn_lbp_scorer requires scikit-learn + joblib; "
            "install core/requirements.txt before running the benchmark CLI."
        ) from e
    from core.algorithms.layer4_texture import lbp_histogram, lbp_score

    resolved = Path(model_path)
    if not resolved.exists():
        raise FileNotFoundError(
            f"sklearn LBP classifier not found at {resolved!r}. "
            f"The canonical artefact lives at {DEFAULT_LBP_SKLEARN_PATH} "
            "and is generated by `python -m core.models.train_texture_model`."
        )

    classifier = joblib.load(str(resolved))

    def score(frame: np.ndarray) -> float:
        # Delegate to `lbp_score` so the benchmark path and the server
        # attestation path share the exact scoring function. If we
        # inlined `predict_proba` here, a future refactor that changes
        # the "which class index is live" convention in `lbp_score`
        # would silently drift benchmark numbers.
        _ = lbp_histogram  # explicit: `lbp_score` uses it internally
        return lbp_score(frame, classifier)

    return score


def fourier_scorer(model_path: Path | str) -> Scorer:
    """Per-frame Scorer wrapping `core.algorithms.layer4_texture.fourier_score`.

    Unlike the LBP scorers, this one owns no trained weights — the FFT
    ring-energy detector is a closed-form function (Spec §1424-1448).
    `model_path` is accepted for signature parity with the LBP factories
    and is ignored; the CLI resolves it to `DEFAULT_FOURIER_PATH` when
    the user omits `--model`, and the function below never reads it.

    Raises:
        RuntimeError: when opencv-python isn't importable — `fourier_score`
            needs cv2 for the grayscale conversion path, so we surface
            the same clear hint the LBP scorers emit.
    """
    try:
        import cv2  # type: ignore  # noqa: F401 — presence check only
    except ImportError as e:  # pragma: no cover
        raise RuntimeError(
            "fourier_scorer requires opencv-python; "
            "install core/requirements.txt before running the benchmark CLI."
        ) from e
    # Local import mirrors the LBP factories: a user without the Layer-4
    # module's dependencies gets the RuntimeError at scorer-build time,
    # not at package import.
    from core.algorithms.layer4_texture import fourier_score

    del model_path  # explicit: Fourier has no artefact to load

    def score(frame: np.ndarray) -> float:
        return fourier_score(frame)

    return score


def pos_rppg_scorer(model_path: Path | str) -> VideoScorer:
    """Per-video Scorer wrapping `core.algorithms.layer3_rppg_pos.pos_rppg`.

    Layer 3 operates on a temporal window (Spec §1356-1405) — a single
    frame can't carry a pulse, so the scorer takes a `VideoSample` and
    reads its pre-extracted `rgb_signal`. Maps the POS result dict to
    a single `[0, 1]` score via the `confidence` field, which is
    already in that range and anchored to the detection threshold the
    mobile pipeline uses.

    A `None` result from `pos_rppg` (clip too short for the minimum
    capture window, degenerate signal, filter blow-up) maps to 0.0 —
    matches the mobile Layer 3 "no pulse detected → attack" decision
    rather than silently coercing to NaN or dropping the clip.

    `model_path` is accepted for signature parity with the frame-level
    factories and is ignored; POS owns no artefact to load. Raises
    RuntimeError if scipy isn't importable — same lazy-import pattern
    as the texture scorers.
    """
    try:
        import scipy  # type: ignore  # noqa: F401 — presence check only
    except ImportError as e:  # pragma: no cover
        raise RuntimeError(
            "pos_rppg_scorer requires scipy; "
            "install core/requirements.txt before running the benchmark CLI."
        ) from e
    # Local import mirrors the Layer-4 scorers: a user without the
    # Layer-3 module's dependencies gets the RuntimeError at scorer-build
    # time, not at package import.
    from core.algorithms.layer3_rppg_pos import pos_rppg

    del model_path  # explicit: POS has no artefact to load

    def score(sample: VideoSample) -> float:
        # low_light=False matches the benchmark context — MSU-MFSD and
        # the other research corpora are recorded under normal indoor
        # lighting. Exposing the flag to the CLI is a future
        # extension; the mobile side reads it from
        # AmbientConditions.low_light_detected per frame.
        result = pos_rppg(sample.rgb_signal, fs=sample.fps, low_light=False)
        if result is None:
            return 0.0
        return float(result["confidence"])

    return score


def combined_scorer(model_path: Path | str) -> VideoScorer:
    """Fuses Layer 3 (pos-rppg) + Layer 4 (LBP + Fourier) into one score.

    Mirror of the mobile orchestrator's job: each layer is a weak,
    independent signal; the deployed decision comes from fusing them.
    For the benchmark we fuse *per clip* so a single VideoSample
    produces a single score — directly comparable with pos-rppg and
    with an aggregated per-frame scorer, and the number that answers
    "what does the layered system actually score on this corpus?".

    Fusion rule: equal-weighted mean of three per-clip terms
        1. mean of per-frame LBP scores across the clip's `frames`
        2. mean of per-frame Fourier scores across the clip's `frames`
        3. pos-rppg `confidence` on the clip's `rgb_signal`
    Equal weights (1/3 each) because we have no held-out calibration
    split on MSU-MFSD for v0.1 — the paper table reports this fusion
    as the honest "no-tuning" baseline. A future calibration pass can
    swap in AUC-weighted or logistic-regression weights without
    changing the harness contract.

    `model_path` is the LBP TFLite artefact — Fourier and pos-rppg own
    no weights so they ignore it. Defaults via DEFAULT_MODEL_PATHS to
    the shipped `android-sdk/src/main/assets/lbp_classifier.tflite`.

    Raises:
        RuntimeError: any of the underlying lazy imports (tensorflow,
            opencv-python, scipy) are missing — the failing factory's
            diagnostic bubbles up unchanged so the install hint matches
            the actual missing package.
        ValueError: when a VideoSample arrives with `frames=None` —
            the combined scorer needs frame crops and silently falling
            back to rPPG-only would hide a dataset-config bug behind
            degraded numbers.
    """
    # Build the three underlying scorers eagerly — any missing-dep /
    # missing-model failure surfaces here, before the harness starts
    # churning through clips. Matches the LBP/Fourier/pos-rppg factory
    # contract: setup errors at scorer-build time, scoring errors at
    # invoke time.
    lbp = tflite_lbp_scorer(model_path)
    fourier = fourier_scorer(model_path)  # model_path ignored by Fourier
    rppg = pos_rppg_scorer(model_path)  # model_path ignored by pos-rppg

    def score(sample: VideoSample) -> float:
        if sample.frames is None or len(sample.frames) == 0:
            raise ValueError(
                "combined_scorer requires VideoSample.frames to be populated "
                "(got None or empty). The dataset loader should emit a 1 Hz "
                "stream of 80×80 face crops alongside rgb_signal — see "
                "MSUMFSDVideo for the reference implementation."
            )
        # np.mean over a Python list of floats is fine; the frame loop
        # dominates per-clip cost (TFLite invoke × N frames) so a
        # numpy-array detour wouldn't move the needle.
        lbp_scores = [lbp(frame) for frame in sample.frames]
        fourier_scores = [fourier(frame) for frame in sample.frames]
        rppg_score = rppg(sample)
        return (
            float(np.mean(lbp_scores))
            + float(np.mean(fourier_scores))
            + rppg_score
        ) / 3.0

    return score


# Sentinel reuse: combined_scorer's "model" is the LBP TFLite. The
# lookup in DEFAULT_MODEL_PATHS resolves to the same canonical path
# as `tflite-lbp`, so a user who runs `--scorer combined` without
# `--model` gets the shipped weights automatically.
# No new constant needed — just register the lookup below.


# Registry consumed by the CLI's `--scorer` choices for FRAME-level
# scorers. Keyed by the string a user types; values are factories that
# accept a model path and return a Scorer. Adding a new frame scorer
# means one entry here — the CLI's argparse `choices=` picks it up
# automatically (unioned with VIDEO_SCORER_FACTORIES).
SCORER_FACTORIES: dict[str, Callable[[Path | str], Scorer]] = {
    "tflite-lbp": tflite_lbp_scorer,
    "sklearn-lbp": sklearn_lbp_scorer,
    "fourier": fourier_scorer,
}


# VIDEO-level scorer registry. Kept separate from `SCORER_FACTORIES`
# because the input type differs (VideoSample vs np.ndarray frame) —
# merging would force a union at the call site and lose the static
# signature guarantee. The CLI knows which registry a `--scorer` name
# lives in and dispatches to `run` vs `run_video` accordingly.
VIDEO_SCORER_FACTORIES: dict[str, Callable[[Path | str], VideoScorer]] = {
    "pos-rppg": pos_rppg_scorer,
    "combined": combined_scorer,
}


# Default model path per scorer — looked up by the CLI when `--model`
# is omitted. Covers both registries so the resolution rule is
# uniform; sentinel paths for Fourier and pos-rppg reflect that
# those scorers own no artefact. The dict is flat (not per-registry)
# so a future refactor that moves a scorer between frame and video
# modes doesn't need the CLI to re-point its lookup.
DEFAULT_MODEL_PATHS: dict[str, Path] = {
    "tflite-lbp": DEFAULT_LBP_MODEL_PATH,
    "sklearn-lbp": DEFAULT_LBP_SKLEARN_PATH,
    "fourier": DEFAULT_FOURIER_PATH,
    "pos-rppg": DEFAULT_POS_RPPG_PATH,
    # Reuses the LBP path: combined_scorer's only trained artefact is
    # the TFLite classifier; Fourier + pos-rppg own no weights so the
    # single path covers both frame-level model-loading needs.
    "combined": DEFAULT_LBP_MODEL_PATH,
}


def available_scorers() -> list[str]:
    """Sorted scorer names from BOTH registries for argparse `choices=`.
    Sorted so `--help` output is stable across Python versions (dict
    insertion order is guaranteed since 3.7, but a sorted list is one
    less thing to argue about in a reviewer's head)."""
    return sorted(SCORER_FACTORIES.keys() | VIDEO_SCORER_FACTORIES.keys())


def is_video_scorer(name: str) -> bool:
    """True when `name` is registered as a video-level scorer.

    CLI dispatch uses this to choose between the frame-level `run` and
    the video-level `run_video` + matching dataset loader. Kept as a
    tiny helper so the CLI doesn't have to reach into the registry
    internals."""
    return name in VIDEO_SCORER_FACTORIES
