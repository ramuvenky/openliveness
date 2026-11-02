"""Train the Layer-4 LBP classifier on MSU-MFSD's train split.

Sibling to `train_texture_model.py` — same classifier (LinearSVC +
Platt-calibrated via CalibratedClassifierCV, 5-fold) and same output
shape (`predict_proba([hist])[0][1]`), but features come from the
real MSU-MFSD training videos rather than the synthetic LBP
generator in `synthetic_dataset.py`. Keeping the two scripts
separate rather than branching inside one script because:

1. The synthetic path has to stay runnable without a licensed corpus
   on disk (CI, fresh clone, no internet). Guarding every line in a
   single script with a `--source {synthetic|msu-mfsd}` flag would
   buy only mild DRY while putting the easier path at the mercy of
   the harder one's import chain.
2. The real-data path reuses `benchmark.datasets.msu_mfsd.MSUMFSD` —
   not just a convenience; it's the SAME frame-decoding + face-crop
   pipeline the benchmark CLI uses, so train/benchmark feature
   extraction can't drift out of parity. That dependency doesn't
   belong in `train_texture_model.py`.

**Protocol**: fits on `split="train"` (15 clients). Does not touch
`split="test"` so an honest APCER/BPCER number comes out of the
benchmark CLI run that follows. MSU's convention is that train and
test clients are disjoint — this script relies on that; a copy of
MSU-MFSD with modified sub-list files would silently leak.

Usage:
    python -m core.models.train_texture_model_msu \\
        --dataset-root /path/to/MSU-MFSD \\
        --output core/models/lbp_classifier.pkl
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

from benchmark.datasets.msu_mfsd import MSUMFSD
from core.algorithms.layer4_texture import lbp_histogram


def collect_features(dataset: MSUMFSD) -> tuple[np.ndarray, np.ndarray]:
    """Iterate the loader, extract LBP histograms, return (X, y).

    Holds all features in memory — on a 1,500-sample train split
    with 59-float64 bins that's ~700 KB, so the simplicity is worth
    more than a streaming classifier interface.
    """
    histograms: list[np.ndarray] = []
    labels: list[int] = []
    for sample in dataset:
        histograms.append(lbp_histogram(sample.frame))
        labels.append(sample.label)
    if not histograms:
        raise RuntimeError(
            "No samples extracted from dataset — check the split filter "
            "and that `.face` sidecars exist alongside the videos."
        )
    return np.stack(histograms, axis=0), np.asarray(labels, dtype=np.int64)


def train(
    X: np.ndarray, y: np.ndarray, seed: int
) -> tuple[CalibratedClassifierCV, dict]:
    """Fit the same LinearSVC + Platt-calibration stack as the
    synthetic trainer. The MSU train split has roughly 3× more
    attacks than lives (6 attack videos per client vs 2 real), so
    we stratify on `y` to keep both classes represented in the
    inner validation holdout."""
    X_train, X_val, y_train, y_val = train_test_split(
        X, y, test_size=0.2, random_state=seed, stratify=y
    )
    base = LinearSVC(C=1.0, dual="auto", max_iter=5000)
    model = CalibratedClassifierCV(base, cv=5, method="sigmoid")
    model.fit(X_train, y_train)

    y_pred = model.predict(X_val)
    report = classification_report(
        y_val, y_pred, target_names=["spoof", "live"], output_dict=True, zero_division=0
    )
    acc = accuracy_score(y_val, y_pred)
    metrics = {
        "accuracy": float(acc),
        "n_train": int(len(y_train)),
        "n_val": int(len(y_val)),
        "n_features": int(X.shape[1]),
        "report": report,
        # The in-script validation split is drawn from MSU-train, NOT
        # MSU-test — a "0.95 accuracy" here is on held-out training
        # clients, not the real paper-protocol test. Pin that so a
        # reviewer reading the metrics file can't confuse the two.
        "validation_source": "inner holdout of MSU-MFSD train split",
    }
    return model, metrics


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Train Layer-4 LBP classifier on MSU-MFSD train split."
    )
    parser.add_argument(
        "--dataset-root",
        type=Path,
        required=True,
        help="Path to MSU-MFSD root (containing scene01/ + sub-list files).",
    )
    parser.add_argument(
        "--output",
        type=Path,
        required=True,
        help="Where to save the pickled classifier (joblib format).",
    )
    parser.add_argument(
        "--metrics-output",
        type=Path,
        default=None,
        help="Metrics JSON path. Defaults to <output>.metrics.json.",
    )
    parser.add_argument("--seed", type=int, default=0)
    args = parser.parse_args()

    dataset = MSUMFSD(args.dataset_root, split="train")
    print(f"Extracting LBP features from {len(dataset)} MSU-MFSD train videos…")
    X, y = collect_features(dataset)
    print(f"  Collected {len(y)} samples; {int((y == 1).sum())} live, {int((y == 0).sum())} attack")

    model, metrics = train(X, y, args.seed)

    import joblib
    args.output.parent.mkdir(parents=True, exist_ok=True)
    joblib.dump(model, args.output)
    print(f"Saved classifier to {args.output}")

    metrics_path = args.metrics_output or args.output.with_suffix(".metrics.json")
    # Add the training provenance up front so the file is self-explanatory
    # when a reviewer finds it in the repo six months later.
    metrics["training_corpus"] = "MSU-MFSD (Wen, Han, Jain 2015 TIFS)"
    metrics["training_split"] = "train (15 clients per train_sub_list.txt)"
    metrics_path.write_text(json.dumps(metrics, indent=2))
    print(f"Saved metrics to {metrics_path}")
    print(f"Inner-validation accuracy: {metrics['accuracy']:.4f}")


if __name__ == "__main__":
    main()
