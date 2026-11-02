"""Layer 4 — Texture analysis (LBP + Fourier).

Reference implementation per Spec §1404-1462. The Android/iOS mirrors
run the same math via TFLite + CoreML; the server re-runs this module
from `layer_scores` before issuing a JWT. Keep numeric parity.

LBP branch: histogram of a uniform P=8,R=1 LBP code is fed to a trained
SVM; the probability-of-live is the score. The classifier is injected
so this module stays dependency-light for unit tests (which pass a
stub classifier) while production code wires in the sklearn SVM loaded
from `core/models/`.

Fourier branch: fully self-contained. Measures the ratio between the
energy ring at 1/4..1/3 of the image size vs. the organic baseline
estimated from a lower-frequency ring. Screen replay bumps the HF ring
(pixel-pitch harmonics); real skin follows 1/f rolloff so the ring is
quiet.
"""

from __future__ import annotations

from typing import Any, Protocol

import numpy as np

try:  # Optional at import time — texture scoring is a frame-level op and
      # the dev workflow (tests, Phase-5 CI) runs without cv2 installed.
    import cv2  # type: ignore
except ImportError:  # pragma: no cover
    cv2 = None

try:
    from skimage.feature import local_binary_pattern  # type: ignore
except ImportError:  # pragma: no cover
    local_binary_pattern = None


class LBPClassifier(Protocol):
    """Minimal protocol the LBP branch needs. Matches sklearn's
    `predict_proba` shape so a trained `SVC(probability=True)` or
    `CalibratedClassifierCV` drops in with no adapter."""

    def predict_proba(self, X) -> np.ndarray: ...


def lbp_histogram(face_frame: np.ndarray) -> np.ndarray:
    """Resize → gray → uniform LBP (P=8, R=1) → 59-bin histogram.

    Exposed separately from `lbp_score` so training code can reuse the
    exact feature extractor the inference path uses — a mismatch between
    train-time and inference-time preprocessing is one of the quietest
    ways to tank a biometric classifier.
    """
    if cv2 is None or local_binary_pattern is None:
        raise RuntimeError(
            "lbp_histogram requires opencv-python and scikit-image; "
            "install core/requirements.txt before running on real frames."
        )
    face = cv2.resize(face_frame, (80, 80))
    if face.ndim == 3:
        gray = cv2.cvtColor(face, cv2.COLOR_BGR2GRAY)
    else:
        gray = face
    lbp = local_binary_pattern(gray, P=8, R=1, method="uniform")
    hist, _ = np.histogram(lbp.ravel(), bins=59, range=(0, 59))
    total = hist.sum()
    if total == 0:
        return hist.astype(np.float64)
    return hist.astype(np.float64) / total


def lbp_score(face_frame: np.ndarray, classifier: LBPClassifier) -> float:
    """Probability-of-live from a trained LBP + SVM pipeline.

    1.0 = confident live texture; 0.0 = confident spoof. The classifier
    is injected rather than loaded here so tests can pin exact return
    values without needing a 2MB `.pkl` on disk, and so the production
    load path can live wherever the app bootstraps its models.
    """
    hist = lbp_histogram(face_frame)
    probs = classifier.predict_proba([hist])
    return float(probs[0][1])


def _ring_mask(shape, inner_frac_denom: int, outer_frac_denom: int) -> np.ndarray:
    """Pixel distance from image center, 1 inside `(rows/outer, rows/inner)`.

    Matches the spec's inner < d < outer with d measured in pixels and
    the fractions keyed off `rows` (not min(rows, cols)) — the spec
    assumes a square 80x80 face crop upstream, so keeping it keyed to
    rows preserves parity on that path. Vectorized vs. the spec's
    nested-for because the inner loop runs on 80² pixels per frame and
    the server re-runs it per attestation.
    """
    rows, cols = shape
    cr, cc = rows // 2, cols // 2
    yy, xx = np.indices((rows, cols))
    d = np.sqrt((yy - cr) ** 2 + (xx - cc) ** 2)
    inner = rows / outer_frac_denom
    outer = rows / inner_frac_denom
    return ((d > inner) & (d < outer)).astype(np.float64)


def estimate_organic_hf(magnitude: np.ndarray) -> float:
    """Baseline HF energy from the mid-frequency ring (Spec §1450).

    Real skin has 1/f rolloff so the mid ring is a reasonable proxy for
    the "quiet" organic HF level. Dividing by 4.0 matches the Python
    reference literally — the constant is what the server re-runs.
    """
    lf_mask = _ring_mask(magnitude.shape, inner_frac_denom=4, outer_frac_denom=8)
    mask_sum = lf_mask.sum()
    if mask_sum <= 0:
        return 0.0
    lf_energy = float((magnitude * lf_mask).sum() / mask_sum)
    return lf_energy / 4.0


def fourier_score(face_frame: np.ndarray) -> float:
    """Screen-replay detector via FFT ring-energy ratio (Spec §1424-1448).

    1.0 → no screen artefacts (live); 0.0 → strong artefacts (replay).
    """
    if cv2 is None:
        raise RuntimeError(
            "fourier_score requires opencv-python; "
            "install core/requirements.txt before running on real frames."
        )
    if face_frame.ndim == 3:
        gray = cv2.cvtColor(face_frame, cv2.COLOR_BGR2GRAY)
    else:
        gray = face_frame
    dft_shift = np.fft.fftshift(np.fft.fft2(gray))
    magnitude = np.log(np.abs(dft_shift) + 1)

    hf_mask = _ring_mask(magnitude.shape, inner_frac_denom=3, outer_frac_denom=4)
    mask_sum = hf_mask.sum()
    if mask_sum <= 0:
        return 1.0
    hf_energy = float((magnitude * hf_mask).sum() / mask_sum)

    expected = estimate_organic_hf(magnitude)
    artifact_ratio = hf_energy / max(expected, 1e-10)

    # Clamp ratio-above-1 to zero so a quieter-than-organic ring doesn't
    # push the score above 1.0. The *2 coefficient and 1/(1+x) shape are
    # straight from the spec — the sharpness of the knee matters because
    # it decides where the "borderline" (0.6-0.8) scores land.
    return float(1.0 / (1.0 + max(artifact_ratio - 1.0, 0.0) * 2.0))
