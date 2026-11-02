"""Python-side golden-fixture sanity test.

Replays each fixture through the Python port and asserts the output
matches the fixture's `expected` block. This is the first line of
defense against unintentional Python-side drift: if someone edits a
threshold without regenerating the fixtures, this test flips red
before the Kotlin / Swift mirrors even run.

To regenerate (after an intentional threshold change):
    python -m core.tests.golden.generate_golden_fixtures
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

from core.algorithms.layer3_rppg_pos import pos_rppg
from core.algorithms.layer4_texture import fourier_score
from core.algorithms.layer5_behavioral import (
    detect_microsaccades,
    micro_movement_score,
)
from core.orchestrator import (
    AmbientConditions,
    LayerScores,
    orchestrate,
)


FIXTURE_DIR = Path(__file__).parent / "golden" / "fixtures"


def _load(name: str) -> list[dict]:
    data = json.loads((FIXTURE_DIR / f"{name}.json").read_text())
    return data["cases"]


def test_orchestrator_fixtures_match():
    for case in _load("orchestrator"):
        ins = case["inputs"]
        scores = LayerScores(**ins["layer_scores"])
        ambient = AmbientConditions(**ins["ambient_conditions"])
        result = orchestrate(
            scores, ambient, hardware_attestation_verified=ins["hardware_attestation_verified"]
        )
        expected = case["expected"]
        assert result.decision == expected["decision"], case["name"]
        assert result.assurance_level == expected["assurance_level"], case["name"]
        assert result.weighted_score == pytest.approx(expected["weighted_score"]), case["name"]
        for k, v in expected["weights_applied"].items():
            assert result.weights_applied[k] == pytest.approx(v), (case["name"], k)


def test_layer3_rppg_fixtures_match():
    for case in _load("layer3_rppg"):
        rgb = np.array(case["inputs"]["rgb"])
        result = pos_rppg(rgb, fs=case["inputs"]["fs"], low_light=case["inputs"]["low_light"])
        assert result is not None, case["name"]
        for k, v in case["expected"].items():
            if isinstance(v, bool):
                assert result[k] == v, (case["name"], k)
            else:
                assert result[k] == pytest.approx(v, abs=1e-5), (case["name"], k)


def test_layer4_fourier_fixtures_match():
    for case in _load("layer4_fourier"):
        size = case["inputs"]["size"]
        gray = np.array(case["inputs"]["gray"], dtype=np.uint8).reshape((size, size))
        # fourier_score accepts the 2D path (ndim != 3 skips cvtColor).
        score = fourier_score(gray)
        assert score == pytest.approx(case["expected"]["fourier_score"], abs=1e-5), case["name"]


def test_layer5_behavioral_fixtures_match():
    for case in _load("layer5_behavioral"):
        frames = case["inputs"]["landmarks"]
        score = micro_movement_score(frames)
        saccade = detect_microsaccades(frames)
        assert score == pytest.approx(case["expected"]["behavioral_score"]), case["name"]
        assert saccade == case["expected"]["microsaccade_detected"], case["name"]
