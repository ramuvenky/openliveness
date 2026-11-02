"""Deterministic cross-language parity fixtures.

Produces JSON files in `core/tests/golden/fixtures/`. Each fixture
captures (a) the inputs to a Python port function, (b) the function's
output as the ground truth, so the Kotlin and Swift mirrors can replay
the same inputs and assert bit-for-bit (or within an epsilon) parity.

**Why JSON instead of pickle**: Kotlin and Swift can read JSON out of
the box; pickle would require language-specific bindings. The size
cost is acceptable — the longest fixture (POS rPPG, 300 RGB triplets)
is ~15 KB.

**Fixture scope**:
- `orchestrator_*`: weight tables + decisions under all four ambient
  permutations plus the hard-fail-on-missed-challenge path.
- `layer3_rppg_*`: synthetic RGB streams that produce known bpm/SNR.
- `layer4_fourier_*`: 80×80 face crops → fourier score.
- `layer5_behavioral_*`: landmark sequences → score + saccade flag.

Layer 2 isn't fixtured here: its detectors are tight state machines
whose Python / Kotlin implementations are already covered by direct
threshold tests on both sides, and a landmark-sequence fixture would
balloon the JSON files without catching anything the two unit test
suites don't already catch independently.

Usage:
    python -m core.tests.golden.generate_golden_fixtures
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from core.algorithms.layer3_rppg_pos import MIN_SAMPLES_NORMAL, pos_rppg
from core.algorithms.layer4_texture import fourier_score
from core.algorithms.layer5_behavioral import (
    detect_microsaccades,
    micro_movement_score,
)
from core.models.export_tflite import (
    fused_predict_proba_live,
    fused_weights,
)
from core.orchestrator import (
    AmbientConditions,
    LayerScores,
    orchestrate,
    weights_for,
)


FIXTURE_DIR = Path(__file__).resolve().parent / "fixtures"


def _write(name: str, payload: dict) -> None:
    FIXTURE_DIR.mkdir(parents=True, exist_ok=True)
    out = FIXTURE_DIR / f"{name}.json"
    out.write_text(json.dumps(payload, indent=2, sort_keys=True))
    print(f"wrote {out.relative_to(FIXTURE_DIR.parent.parent.parent)}")


# ---- Orchestrator fixtures ----------------------------------------------

def _orchestrator_fixtures() -> None:
    cases = []
    # Perfect scores across all 4 ambient permutations, with and without
    # hw attestation. Covers IAL2 vs software_only + the weight-reweight.
    for low_light in (False, True):
        for motion in (False, True):
            for hw in (False, True):
                scores = LayerScores(
                    layer2_challenge_completed=True,
                    layer2_challenge_score=0.9,
                    layer3_rppg_confidence=0.8,
                    layer4_lbp_score=0.85,
                    layer4_fourier_score=0.9,
                    layer5_behavioral_score=0.7,
                )
                ambient = AmbientConditions(
                    low_light_detected=low_light,
                    motion_detected=motion,
                )
                result = orchestrate(scores, ambient, hardware_attestation_verified=hw)
                cases.append({
                    "name": f"ll={low_light},motion={motion},hw={hw}",
                    "inputs": {
                        "layer_scores": {
                            "layer2_challenge_completed": scores.layer2_challenge_completed,
                            "layer2_challenge_score": scores.layer2_challenge_score,
                            "layer3_rppg_confidence": scores.layer3_rppg_confidence,
                            "layer4_lbp_score": scores.layer4_lbp_score,
                            "layer4_fourier_score": scores.layer4_fourier_score,
                            "layer5_behavioral_score": scores.layer5_behavioral_score,
                        },
                        "ambient_conditions": {
                            "low_light_detected": ambient.low_light_detected,
                            "motion_detected": ambient.motion_detected,
                        },
                        "hardware_attestation_verified": hw,
                    },
                    "expected": {
                        "decision": result.decision,
                        "assurance_level": result.assurance_level,
                        "weighted_score": result.weighted_score,
                        "weights_applied": result.weights_applied,
                    },
                })
    # Hard-fail case.
    scores = LayerScores(
        layer2_challenge_completed=False,
        layer2_challenge_score=0.0,
        layer3_rppg_confidence=0.9,
        layer4_lbp_score=0.9,
        layer4_fourier_score=0.9,
        layer5_behavioral_score=0.9,
    )
    result = orchestrate(scores, AmbientConditions(), hardware_attestation_verified=True)
    cases.append({
        "name": "challenge_missed_hard_fail",
        "inputs": {
            "layer_scores": {
                "layer2_challenge_completed": False,
                "layer2_challenge_score": 0.0,
                "layer3_rppg_confidence": 0.9,
                "layer4_lbp_score": 0.9,
                "layer4_fourier_score": 0.9,
                "layer5_behavioral_score": 0.9,
            },
            "ambient_conditions": {"low_light_detected": False, "motion_detected": False},
            "hardware_attestation_verified": True,
        },
        "expected": {
            "decision": result.decision,
            "assurance_level": result.assurance_level,
            "weighted_score": result.weighted_score,
            "weights_applied": result.weights_applied,
        },
    })
    _write("orchestrator", {"cases": cases})


# ---- Layer 3 rPPG fixtures ----------------------------------------------

def _rppg_rgb(bpm: float, n: int, fs: float, seed: int) -> np.ndarray:
    rng = np.random.default_rng(seed)
    t = np.arange(n) / fs
    pulse = np.sin(2 * np.pi * (bpm / 60.0) * t)
    r = 150.0 + 0.5 * rng.standard_normal(n)
    g = 120.0 + 20.0 * pulse + 0.5 * rng.standard_normal(n)
    b = 100.0 + 0.5 * rng.standard_normal(n)
    return np.stack([r, g, b], axis=1)


def _layer3_fixtures() -> None:
    cases = []
    for name, bpm in [("resting_72bpm", 72.0), ("fast_100bpm", 100.0)]:
        rgb = _rppg_rgb(bpm, MIN_SAMPLES_NORMAL, fs=30.0, seed=hash(name) & 0xFFFF)
        result = pos_rppg(rgb, fs=30.0, low_light=False)
        assert result is not None
        cases.append({
            "name": name,
            "inputs": {
                "fs": 30.0,
                "low_light": False,
                "rgb": rgb.tolist(),
            },
            "expected": result,
        })
    _write("layer3_rppg", {"cases": cases})


# ---- Layer 4 Fourier fixtures -------------------------------------------

def _gaussian_blob(seed: int = 0) -> np.ndarray:
    rng = np.random.default_rng(seed)
    size = 80
    yy, xx = np.indices((size, size))
    sigma = size / 2.0
    blob = 255.0 * np.exp(-((yy - size / 2) ** 2 + (xx - size / 2) ** 2) / (2 * sigma ** 2))
    # A hint of noise so the fixture is less of a degenerate pattern.
    blob += rng.standard_normal((size, size)) * 2
    return np.clip(blob, 0, 255).astype(np.uint8)


def _screen_pattern(seed: int = 0) -> np.ndarray:
    size = 80
    yy, xx = np.indices((size, size))
    blob = _gaussian_blob(seed).astype(np.float32)
    grid = 80.0 * np.sin(2 * np.pi * xx * 23.0 / size) * np.sin(2 * np.pi * yy * 23.0 / size)
    return np.clip(blob + grid, 0, 255).astype(np.uint8)


def _layer4_fourier_fixtures() -> None:
    cases = []
    for name, img in [
        ("live_blob", _gaussian_blob(seed=1)),
        ("replay_grid", _screen_pattern(seed=1)),
    ]:
        # Convert to BGR 3-channel because fourier_score hits cv2.cvtColor.
        bgr = np.stack([img, img, img], axis=-1)
        score = fourier_score(bgr)
        cases.append({
            "name": name,
            "inputs": {"size": img.shape[0], "gray": img.flatten().tolist()},
            "expected": {"fourier_score": float(score)},
        })
    _write("layer4_fourier", {"cases": cases})


# ---- Layer 5 behavioral fixtures ----------------------------------------

def _landmark_sequence(n_frames: int, step: float, iris_step: float, seed: int):
    rng = np.random.default_rng(seed)
    frames = []
    for i in range(n_frames):
        dx = step * ((i % 2) * 2 - 1)
        iris_x = 0.3 + iris_step * i
        frame = [[0.0, 0.0]] * 478
        frame[6] = [0.5 + dx, 0.4]
        frame[8] = [0.5 + dx, 0.42]
        frame[9] = [0.5 + dx, 0.44]
        frame[10] = [0.5 + dx, 0.46]
        for lid in range(468, 473):
            frame[lid] = [iris_x, 0.5]
        frames.append(frame)
    _ = rng  # reserved for future noise injection
    return frames


# ---- Layer 4 LBP fixtures -----------------------------------------------
#
# The Kotlin and Swift mirrors need to reproduce `lbp_histogram` byte-for-
# byte, because any drift against skimage's `local_binary_pattern(P=8, R=1,
# method='uniform')` flows into the Dense(59→5→1) TFLite model as a
# different feature vector and the "probability of live" diverges from the
# server's re-run. The fixture captures:
#   - 80×80 uint8 face crops (same shape the inference path sees)
#   - the Python-ground-truth 59-bin L1-normalized histogram
#   - the TFLite model's live-probability output (via the pure-numpy fused
#     replay — the two agreed to 1.5e-8 at export time, see export_tflite.py)
#
# Fixture is skipped silently if the trained classifier isn't on disk:
# Phase 5 Step 3 shipped the .tflite into the android-sdk assets but the
# .pkl is a model-registry artifact and may not be present in every clone.
# The Kotlin test must also tolerate a missing fixture (same reason).

LBP_PKL = Path(__file__).resolve().parents[3] / "core" / "models" / "lbp_classifier.pkl"


def _random_face_crop(seed: int) -> np.ndarray:
    """80×80 uint8 image with a dim face-sized blob plus noise. The LBP
    output is dominated by the noise, which is the point — we want the
    fixture to exercise all 59 histogram bins (noise → mixed uniform +
    non-uniform codes) rather than a flat blob that collapses to a single
    bin."""
    rng = np.random.default_rng(seed)
    size = 80
    yy, xx = np.indices((size, size))
    blob = 180.0 * np.exp(-((yy - 40) ** 2 + (xx - 40) ** 2) / (2 * 25.0 ** 2))
    noise = rng.integers(-20, 20, size=(size, size))
    img = np.clip(blob + noise, 0, 255).astype(np.uint8)
    return img


def _layer4_lbp_fixtures() -> None:
    if not LBP_PKL.exists():
        print(f"skip layer4_lbp fixture — {LBP_PKL} not present")
        return
    import joblib  # local import so fixture gen w/o joblib still runs other sections
    from skimage.feature import local_binary_pattern

    model = joblib.load(LBP_PKL)
    w1, b1, w2, b2 = fused_weights(model)

    cases = []
    for seed in (1, 2, 3):
        img = _random_face_crop(seed)
        lbp = local_binary_pattern(img, P=8, R=1, method="uniform")
        hist, _ = np.histogram(lbp.ravel(), bins=59, range=(0, 59))
        total = int(hist.sum())
        hist_norm = (hist.astype(np.float64) / total) if total > 0 else hist.astype(np.float64)
        score = float(fused_predict_proba_live(hist_norm[None, :].astype(np.float32), w1, b1, w2, b2)[0])
        cases.append({
            "name": f"random_seed_{seed}",
            "inputs": {"size": int(img.shape[0]), "gray": img.flatten().tolist()},
            "expected": {
                "histogram": hist_norm.tolist(),
                "lbp_score": score,
            },
        })
    _write("layer4_lbp", {"cases": cases})


def _layer5_fixtures() -> None:
    cases = []
    scenarios = [
        ("natural_jitter_no_saccade", {"step": 0.005, "iris_step": 0.0001, "frames": 60}),
        ("still_face_no_saccade",      {"step": 0.0,   "iris_step": 0.0,     "frames": 60}),
        ("natural_with_saccade",       {"step": 0.005, "iris_step": 0.03,    "frames": 10}),
    ]
    for name, cfg in scenarios:
        frames = _landmark_sequence(cfg["frames"], cfg["step"], cfg["iris_step"], seed=1)
        score = micro_movement_score(frames)
        saccade = detect_microsaccades(frames)
        cases.append({
            "name": name,
            "inputs": {"landmarks": frames},
            "expected": {
                "behavioral_score": score,
                "microsaccade_detected": saccade,
            },
        })
    _write("layer5_behavioral", {"cases": cases})


def main() -> None:
    _orchestrator_fixtures()
    _layer3_fixtures()
    _layer4_fourier_fixtures()
    _layer4_lbp_fixtures()
    _layer5_fixtures()


if __name__ == "__main__":
    main()
