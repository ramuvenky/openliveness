"""Phase 5 Step 2 — train the Layer 4 LBP classifier.

**Dataset gap (documented in `synthetic_dataset.py`)**: the Spec cites
MSU-MFSD and CASIA-SURF which are licensed. This script trains on the
synthetic generator for pipeline validation. Replace `build_dataset`
with a real-data loader (same (images, labels) contract) when the
licenses come through — the rest of this file does not need to change.

**Why a linear SVM**: LBP histograms are 59 bins — a linear decision
boundary in that space is enough for the attack types the Spec
targets (print, screen replay). A nonlinear kernel would raise two
problems: (1) it does not export cleanly to TFLite (we would need to
materialize a nonlinear surface), (2) it over-fits a 59-bin feature
space on the small datasets available. Linear + probability
calibration gives us the `predict_proba` interface the mobile
inference path expects.

**Why probability calibration**: `SVC(kernel="linear", probability=True)`
runs Platt scaling internally, which gives us a well-shaped
probability output instead of raw decision-function values. The
orchestrator weights `layer4_lbp_score` against other [0, 1] layer
scores, so a calibrated probability is a correctness requirement, not
just an ergonomic one.

Usage:
    python -m core.models.train_texture_model \\
        --output core/models/lbp_classifier.pkl \\
        --samples-per-class 500
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from sklearn.calibration import CalibratedClassifierCV
from sklearn.metrics import accuracy_score, classification_report
from sklearn.model_selection import train_test_split
from sklearn.svm import LinearSVC

from core.algorithms.layer4_texture import lbp_histogram
from core.models.synthetic_dataset import build_dataset


def extract_histograms(images: np.ndarray) -> np.ndarray:
    return np.stack([lbp_histogram(img) for img in images], axis=0)


def train(samples_per_class: int, seed: int) -> tuple[CalibratedClassifierCV, dict]:
    images, labels = build_dataset(n_per_class=samples_per_class, seed=seed)
    X = extract_histograms(images)
    X_train, X_test, y_train, y_test = train_test_split(
        X, labels, test_size=0.2, random_state=seed, stratify=labels
    )
    # LinearSVC trains fast on histograms; CalibratedClassifierCV wraps
    # it with 5-fold Platt scaling to get `predict_proba`. On the small
    # synthetic set, cv=5 is fine; on real datasets this should be
    # revisited to match class balance.
    base = LinearSVC(C=1.0, dual="auto", max_iter=5000)
    model = CalibratedClassifierCV(base, cv=5, method="sigmoid")
    model.fit(X_train, y_train)

    y_pred = model.predict(X_test)
    report = classification_report(
        y_test, y_pred, target_names=["spoof", "live"], output_dict=True, zero_division=0
    )
    acc = accuracy_score(y_test, y_pred)
    metrics = {
        "accuracy": float(acc),
        "n_train": int(len(y_train)),
        "n_test": int(len(y_test)),
        "report": report,
    }
    return model, metrics


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--metrics-output", type=Path, default=None)
    parser.add_argument("--samples-per-class", type=int, default=500)
    parser.add_argument("--seed", type=int, default=0)
    args = parser.parse_args()

    model, metrics = train(args.samples_per_class, args.seed)

    import joblib
    args.output.parent.mkdir(parents=True, exist_ok=True)
    joblib.dump(model, args.output)
    print(f"Saved classifier to {args.output}")

    metrics_path = args.metrics_output or args.output.with_suffix(".metrics.json")
    metrics_path.write_text(json.dumps(metrics, indent=2))
    print(f"Saved metrics to {metrics_path}")
    print(f"Test accuracy: {metrics['accuracy']:.4f}")


if __name__ == "__main__":
    main()
