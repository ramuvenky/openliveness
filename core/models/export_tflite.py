"""Phase 5 Step 3 — export the trained LBP classifier to TFLite.

Loads the CalibratedClassifierCV from `train_texture_model.py`, fuses
the five calibrated LinearSVC branches into a single Keras model that
converts cleanly to TFLite, and verifies the exported model's
`predict_proba` output matches sklearn's to within 1e-5 across a
probe set.

**Why fused, not wrapped**: a naive Keras export would represent the
`CalibratedClassifierCV` as 5 parallel branches joined by a mean —
TFLite's converter rejects Python `Lambda` layers in strict mode and
produces an unoptimizable graph in flex mode. Fusing collapses the
whole pipeline into two `Dense` layers (feature → calibrated-sigmoid
→ ensemble-mean) that TFLite can lower to two matmul + add + sigmoid
ops. Final `.tflite` is ~2 KB regardless of training set size.

**Math behind the fuse**:
    p_live_i = 1 / (1 + exp(a_i * decision_i + b_i))
             = sigmoid(-a_i * (coef_i · x + intercept_i) - b_i)
             = sigmoid((-a_i * coef_i) · x + (-a_i * intercept_i - b_i))

So each calibrated branch is literally `Dense(1, sigmoid)` with a
transformed kernel and bias. Stacking 5 of them is `Dense(5, sigmoid)`,
and the ensemble mean is `Dense(1, linear)` with kernel=[1/5]*5, bias=0.

Usage:
    python -m core.models.export_tflite \\
        --input  core/models/lbp_classifier.pkl \\
        --output android-sdk/src/main/assets/lbp_classifier.tflite
"""

from __future__ import annotations

import argparse
from pathlib import Path

import joblib
import numpy as np


def fused_weights(model) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """Collapse a sklearn CalibratedClassifierCV(cv=K, method='sigmoid',
    base_estimator=LinearSVC) into two Dense-layer weight tensors.

    Returns:
        kernel_1: shape (n_features, K) — stacked -a_i * coef_i.
        bias_1:   shape (K,)            — -a_i * intercept_i - b_i.
        kernel_2: shape (K, 1)          — 1/K each (ensemble mean).
        bias_2:   shape (1,)            — 0.
    """
    ccs = model.calibrated_classifiers_
    k = len(ccs)
    n_features = ccs[0].estimator.coef_.shape[1]

    kernel_1 = np.zeros((n_features, k), dtype=np.float32)
    bias_1 = np.zeros((k,), dtype=np.float32)
    for i, cc in enumerate(ccs):
        coef = cc.estimator.coef_[0]           # shape (n_features,)
        intercept = cc.estimator.intercept_[0] # scalar
        cal = cc.calibrators[0]                # _SigmoidCalibration
        a, b = float(cal.a_), float(cal.b_)
        kernel_1[:, i] = (-a * coef).astype(np.float32)
        bias_1[i] = float(-a * intercept - b)

    kernel_2 = np.full((k, 1), 1.0 / k, dtype=np.float32)
    bias_2 = np.zeros((1,), dtype=np.float32)
    return kernel_1, bias_1, kernel_2, bias_2


def fused_predict_proba_live(x: np.ndarray, w1, b1, w2, b2) -> np.ndarray:
    """Pure-numpy replay of the fused Keras forward pass. Used by the
    parity check both before (sanity against sklearn) and after
    (sanity against the TFLite converter)."""
    h = 1.0 / (1.0 + np.exp(-(x @ w1 + b1)))  # (n, K)
    return (h @ w2 + b2).reshape(-1)          # (n,)


def build_tflite(w1, b1, w2, b2) -> bytes:
    """Build a tf.keras model from the fused weights and run it through
    TFLiteConverter. Imports tensorflow lazily so the rest of this file
    stays importable in environments where TF isn't installed (e.g.
    the Phase 5 pytest run, which doesn't need the exporter)."""
    import tensorflow as tf  # noqa: E402

    n_features, k = w1.shape
    inputs = tf.keras.Input(shape=(n_features,), name="lbp_hist", dtype=tf.float32)
    h = tf.keras.layers.Dense(
        k,
        activation="sigmoid",
        use_bias=True,
        name="calibrated_decisions",
    )(inputs)
    outputs = tf.keras.layers.Dense(
        1,
        activation=None,
        use_bias=True,
        name="ensemble_mean",
    )(h)
    km = tf.keras.Model(inputs, outputs, name="lbp_classifier")

    # Keras Dense expects kernel shape (in, out) which already matches
    # our (n_features, K) and (K, 1) layouts.
    km.get_layer("calibrated_decisions").set_weights([w1, b1])
    km.get_layer("ensemble_mean").set_weights([w2, b2])

    converter = tf.lite.TFLiteConverter.from_keras_model(km)
    # No quantization — the model is 311 params; the ~1.5x shrink
    # isn't worth the quant/dequant op overhead at inference time.
    return converter.convert()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--parity-samples",
        type=int,
        default=200,
        help="Random 59-bin histograms to sanity-check sklearn ↔ fused ↔ tflite parity.",
    )
    parser.add_argument("--seed", type=int, default=0)
    args = parser.parse_args()

    model = joblib.load(args.input)
    w1, b1, w2, b2 = fused_weights(model)

    # Parity 1: fused numpy forward pass vs. sklearn predict_proba[:, 1].
    rng = np.random.default_rng(args.seed)
    probe = rng.random((args.parity_samples, w1.shape[0])).astype(np.float32)
    probe = probe / probe.sum(axis=1, keepdims=True)  # normalize to histograms
    sk_live = model.predict_proba(probe)[:, 1]
    fused_live = fused_predict_proba_live(probe, w1, b1, w2, b2)
    sk_delta = float(np.max(np.abs(sk_live - fused_live)))
    print(f"sklearn <-> fused max |Δ|: {sk_delta:.3e}")
    if sk_delta > 1e-5:
        raise SystemExit(
            f"Fused weights disagree with sklearn by {sk_delta:.3e} (> 1e-5). "
            "Something changed in CalibratedClassifierCV's internal layout — "
            "re-derive the fuse math before shipping this TFLite."
        )

    # Parity 2: tflite inference vs. fused numpy forward pass.
    tflite_bytes = build_tflite(w1, b1, w2, b2)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(tflite_bytes)
    print(f"Wrote {args.output} ({len(tflite_bytes)} bytes)")

    import tensorflow as tf  # noqa: E402
    interp = tf.lite.Interpreter(model_content=tflite_bytes)
    interp.resize_tensor_input(interp.get_input_details()[0]["index"], list(probe.shape))
    interp.allocate_tensors()
    in_idx = interp.get_input_details()[0]["index"]
    out_idx = interp.get_output_details()[0]["index"]
    interp.set_tensor(in_idx, probe)
    interp.invoke()
    tflite_out = interp.get_tensor(out_idx).reshape(-1)
    tflite_delta = float(np.max(np.abs(tflite_out - fused_live)))
    print(f"fused <-> tflite max |Δ|: {tflite_delta:.3e}")
    if tflite_delta > 1e-4:
        raise SystemExit(
            f"TFLite output disagrees with fused numpy by {tflite_delta:.3e} (> 1e-4). "
            "The converter likely inlined an unexpected op; inspect the "
            "generated graph before shipping."
        )
    print("TFLite parity OK — safe to bundle.")


if __name__ == "__main__":
    main()
