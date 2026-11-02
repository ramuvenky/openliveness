"""Synthetic LBP-training data generator.

**Why synthetic**: the Spec references MSU-MFSD and CASIA-SURF, both of
which are gated behind academic license requests. This generator
produces a defensible stand-in that exercises the SAME feature path
(LBP histogram → classifier) so the training pipeline is validated
end-to-end before real data arrives.

**What makes a 'live' vs 'spoof' face in LBP space**:
Live skin photos have smooth, organic micro-texture — LBP produces a
histogram weighted toward the uniform codes (ends of the 59-bin range
in the P=8 uniform scheme).

Spoof captures (printed photos, screen replays) carry periodic
artefacts that produce more non-uniform LBP codes (center of the
histogram, bin ~58 which is the catch-all for non-uniform patterns).

The generator biases synthetic textures accordingly:
- live  → smooth Perlin-like fields + mild speckle
- spoof → periodic grid overlays + banding (print halftone / screen pitch)

A classifier trained on this won't be production-quality (real datasets
would catch dozens of attack modes we can't synthesize), but it WILL
produce a non-trivial decision boundary over LBP space — enough to
validate the training → TFLite → mobile pipeline.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np

try:
    import cv2  # type: ignore
except ImportError:  # pragma: no cover
    cv2 = None


FACE_CROP_SIZE = (80, 80)


def _smooth_noise(rng: np.random.Generator, size: tuple[int, int], scale: int) -> np.ndarray:
    """Low-frequency pseudo-Perlin: upsample a coarse random field."""
    coarse = rng.standard_normal((size[0] // scale + 2, size[1] // scale + 2))
    # Bilinear upsample via cv2 if present, else repeat+smooth as fallback.
    if cv2 is not None:
        up = cv2.resize(coarse, (size[1], size[0]), interpolation=cv2.INTER_CUBIC)
    else:  # pragma: no cover
        up = np.kron(coarse, np.ones((scale, scale)))[: size[0], : size[1]]
    return up


def generate_live_face(rng: np.random.Generator) -> np.ndarray:
    """Smooth gradient + 1/f-ish noise + mild speckle."""
    h, w = FACE_CROP_SIZE
    base = _smooth_noise(rng, (h, w), scale=16) * 25 + 128
    mid = _smooth_noise(rng, (h, w), scale=4) * 8
    fine = rng.standard_normal((h, w)) * 2  # low-amplitude noise
    img = np.clip(base + mid + fine, 0, 255).astype(np.uint8)
    return img


def generate_spoof_face(rng: np.random.Generator) -> np.ndarray:
    """Live-like base + periodic grid overlay — a stand-in for the
    pixel-pitch artefacts a screen replay carries."""
    h, w = FACE_CROP_SIZE
    live_base = generate_live_face(rng).astype(np.float32)
    yy, xx = np.indices((h, w))
    # Pick a random grid frequency in the pixel-pitch band.
    freq = rng.uniform(6.0, 20.0)
    phase_x = rng.uniform(0, 2 * np.pi)
    phase_y = rng.uniform(0, 2 * np.pi)
    grid_amp = rng.uniform(15.0, 35.0)
    grid = grid_amp * (
        np.sin(2 * np.pi * freq * xx / w + phase_x)
        * np.sin(2 * np.pi * freq * yy / h + phase_y)
    )
    # Light horizontal banding on top of the grid (common print artefact).
    band_freq = rng.uniform(10.0, 30.0)
    bands = rng.uniform(5.0, 15.0) * np.sin(2 * np.pi * band_freq * yy / h)
    img = np.clip(live_base + grid + bands, 0, 255).astype(np.uint8)
    return img


def build_dataset(n_per_class: int, seed: int = 0) -> tuple[np.ndarray, np.ndarray]:
    """Return `(images, labels)` with labels 1=live, 0=spoof."""
    rng = np.random.default_rng(seed)
    images: list[np.ndarray] = []
    labels: list[int] = []
    for _ in range(n_per_class):
        images.append(generate_live_face(rng))
        labels.append(1)
        images.append(generate_spoof_face(rng))
        labels.append(0)
    return np.stack(images, axis=0), np.array(labels, dtype=np.int64)


def save_sample_grid(out_path: Path, n: int = 8, seed: int = 0) -> None:
    """Dump an inspectable grid of synthetic samples. Useful for a
    reviewer who wants to eyeball what the classifier is learning
    from before concluding the pipeline is sound."""
    if cv2 is None:
        return
    rng = np.random.default_rng(seed)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    tiles: list[np.ndarray] = []
    for _ in range(n):
        tiles.append(generate_live_face(rng))
        tiles.append(generate_spoof_face(rng))
    grid_rows = [np.hstack(tiles[i : i + 4]) for i in range(0, len(tiles), 4)]
    grid = np.vstack(grid_rows)
    cv2.imwrite(str(out_path), grid)
