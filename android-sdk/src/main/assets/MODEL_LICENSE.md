# Model weights license — research only

**This file applies to `lbp_classifier.tflite` only.** The surrounding
OpenLiveness source code is licensed under Apache-2.0 (see `LICENSE`
at the repository root). The bundled TFLite classifier has a
different, more restrictive license, inherited from its training
corpus.

## Training corpus

`lbp_classifier.tflite` was trained on the **MSU Mobile Face Spoof
Database (MSU-MFSD)**:

> Di Wen, Hu Han, Anil K. Jain, *"Face Spoof Detection with Image
> Distortion Analysis"*, IEEE Transactions on Information Forensics
> and Security, 2015.

MSU-MFSD is released for **academic research use only**. Training a
model on the corpus produces a derivative work whose redistribution
is bound by the same restriction.

## What this means

This restriction applies **only to the bundled `.tflite` file**, not
to the rest of the OpenLiveness SDK. You can use the SDK itself —
code, orchestrator, relay, protocol — in commercial products under
Apache-2.0, today, without restriction.

- ✅ **Permitted**: using these bundled weights for research,
  benchmarking, reproducing the model-card numbers, and development
  or evaluation of integrations against the OpenLiveness SDK.

- ❌ **Not permitted**: shipping these bundled weights (or a
  commercial product that contains them) without first obtaining
  written permission from the MSU-MFSD authors / Michigan State
  University, or replacing `lbp_classifier.tflite` with a classifier
  trained on commercially-licensed data.

## For commercial deployers

The expected path is to retrain `lbp_classifier.tflite` on your own
consented dataset (a few hundred live videos plus print/replay
attacks on representative device models is typically enough) and
drop the resulting artifact into `android-sdk/src/main/assets/` in
your fork. The training and export scripts are included in this
repository — see `core/models/train_texture_model_msu.py` and
`core/models/export_tflite.py` for a reference pipeline.

Everything else in the OpenLiveness repository — the Android SDK
code, the orchestrator, the relay, the verification library, the
protocol specification — remains Apache-2.0 and is free to use
commercially.

## Attribution

If you use these weights in research, please cite:

```
@article{wen2015face,
  title={Face Spoof Detection with Image Distortion Analysis},
  author={Wen, Di and Han, Hu and Jain, Anil K},
  journal={IEEE Transactions on Information Forensics and Security},
  volume={10},
  number={4},
  pages={746--761},
  year={2015}
}
```
