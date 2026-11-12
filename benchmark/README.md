# openliveness benchmark

ISO/IEC 30107-3 presentation attack detection numbers for every
scorer shipped (or shipped-adjacent) in this repo. All numbers are
reproducible from source — clone, download the corpus, run one
command, and get the same report.

This is the system-level view across layers. The Android AAR's
`lbp_classifier.model-card.json` is the per-artefact view for the
one trained model the SDK ships; this doc covers the full scorer
suite, including closed-form detectors (Fourier, pos-rppg) that own
no weights and the equal-weighted fusion of all three.

---

## Why this exists

1. **Reproducibility matters for an open-source project.** Any
   reviewer can clone the repo, download the corpus, run `python -m
   benchmark`, and verify the numbers below. Commercial liveness
   vendors typically publish performance numbers from internal
   evaluations that aren't straightforward for an outside party to
   re-run; shipping the harness alongside the numbers is openliveness's
   alternative — the same claims, backed by the code that produced them.

2. **ISO/IEC 30107-3 is the referee standard** for PAD. Regulators,
   bank procurement, and academic reviewers ask for APCER/BPCER/HTER
   against a public corpus; the harness computes exactly those.

3. **Silent-regression tripwire.** The benchmark loads the same
   `lbp_classifier.tflite` the Android AAR ships. A future training
   run that produces a worse model — or a preprocessing refactor
   that drifts the Python and mobile pipelines apart — gets caught
   here before the APK hits JitPack.

4. **Deployer calibration.** APCER/BPCER are reported at both the
   HTER-minimising threshold (paper-table number) and the stock 0.5
   operating point (what the SDK with no tuning produces today).
   The gap between the two bounds the fair-tuning headroom a deployer
   would see after calibrating against their own population.

---

## How to reproduce

```bash
# 1. Install the Python dependencies (TensorFlow + opencv + scipy).
pip install -r core/requirements.txt

# 2. Download MSU-MFSD. The corpus is publicly available without
#    EULA as of late 2026; mirror from co-author Hu Han's homepage:
#    https://sites.google.com/site/huhanhomepage/datasetcode
#    Extract so the layout matches `benchmark/datasets/msu_mfsd.py`:
#      $DATASET_ROOT/
#        scene01/real/*.mp4 *.mov + *.face sidecars
#        scene01/attack/*.mp4 *.mov + *.face sidecars
#        train_sub_list.txt
#        test_sub_list.txt

# 3. Run each scorer. Outputs JSON on stdout.
python -m benchmark --dataset msu-mfsd --dataset-root $DATASET_ROOT \
    --scorer tflite-lbp --split test > benchmark/results/msu-mfsd/tflite-lbp.json

python -m benchmark --dataset msu-mfsd --dataset-root $DATASET_ROOT \
    --scorer fourier --split test > benchmark/results/msu-mfsd/fourier.json

python -m benchmark --dataset msu-mfsd --dataset-root $DATASET_ROOT \
    --scorer pos-rppg --split test > benchmark/results/msu-mfsd/pos-rppg.json

python -m benchmark --dataset msu-mfsd --dataset-root $DATASET_ROOT \
    --scorer combined --split test > benchmark/results/msu-mfsd/combined.json
```

The checked-in JSON reports under `benchmark/results/msu-mfsd/` are
the outputs from the current `main` HEAD. Re-running against the
same corpus + same model artefacts must reproduce them exactly.

---

## Dataset

**MSU-MFSD** (Wen, Han, Jain 2015, TIFS). 280 videos across 35
subjects. Each subject has 2 bona-fide access videos + 6 attack
videos (iPad replay, iPhone replay, printed photo — each captured
by both a MacBook Air built-in camera and a Google Nexus 5 front
camera). All in `scene01`.

- **Train split**: 15 clients (`train_sub_list.txt`) — used only to
  train the LBP classifier, never read by the test-split benchmark.
- **Test split**: 20 clients (`test_sub_list.txt`) — disjoint from
  train, used for every number below.

**Why only MSU-MFSD for v0.1.** It's the only presentation-attack
corpus we've confirmed is freely downloadable without an EULA as
of late 2026. REPLAY-ATTACK (Chingovska 2012) and OULU-NPU (Boulkenafet
2017) loaders exist as scaffolding in `benchmark/datasets/` but
raise `NotImplementedError` until a wired-in copy is available.

---

## Results (MSU-MFSD test split)

|                        | LBP        | Fourier    | pos-rppg   | combined  |
|------------------------|------------|------------|------------|-----------|
| sample unit            | frames     | frames     | clips      | clips     |
| n_samples              | 1969       | 1969       | 160        | 160       |
| AUC                    | **0.7797** | 0.6167     | 0.4844     | 0.7123    |
| optimal threshold      | 0.3084     | 0.1656     | 0.3595     | 0.2644    |
| APCER @ optimal        | 0.2711     | 0.1993     | 0.3000     | 0.2333    |
| BPCER @ optimal        | 0.3078     | 0.5738     | 0.6250     | 0.3750    |
| **HTER @ optimal**     | **0.2895** | 0.3865     | 0.4625     | 0.3042    |
| APCER @ default (0.5)  | 0.1070     | 0.0000     | 0.1417     | 0.0000    |
| BPCER @ default (0.5)  | 0.5446     | 1.0000     | 0.9250     | 1.0000    |
| HTER @ default (0.5)   | 0.3258     | 0.5000     | 0.5333     | 0.5000    |

**Bold** = best column.

### Per-scorer interpretation

**LBP (`tflite-lbp`)** — Local Binary Pattern texture classifier.
The one scorer with trained weights (LinearSVC + Platt calibration
on the 15-client train split, then exported to TFLite). Best of
the four on both AUC and HTER. This is the number quoted in the
Android AAR's model card.

**Fourier (`fourier`)** — FFT ring-energy ratio (Spec §1424-1448).
Closed-form detector; no weights. Lower AUC than LBP (0.617 vs
0.780) is expected — a hand-tuned frequency-band rule can't match
a corpus-trained classifier on a corpus-matched test set. The
value is **orthogonality**, not raw accuracy: the rule depends on
different image statistics than LBP, so attacks that defeat one
will still trip the other.

**pos-rppg (`pos-rppg`)** — Plane-Orthogonal-to-Skin rPPG per Wang
et al. 2015 (Spec §1356-1405). Closed-form detector; no weights.
**AUC 0.484 is below chance** on this corpus. Three known drivers:

1. MSU-MFSD clips are ~12 seconds — near the lower bound of the
   `MIN_SAMPLES_NORMAL = 300` window the POS algorithm needs for a
   confident SNR estimate. Shorter clips produce noisier bpm peaks.
2. The benchmark approximates the mobile pipeline's MediaPipe-landmark
   ROIs with bbox-fractional ROIs (the corpus ships PittPatt bboxes,
   not landmarks). Live signal-to-noise degrades under the
   approximation; attacks don't change.
3. MSU-MFSD's LCD-screen replays carry display-refresh and
   backlight-modulation artefacts that fall inside the physiological
   band — the POS detector can register these as a "pulse", which
   actively hurts the live-vs-attack ranking.

The literature on this is consistent: rPPG earns its keep on fresh
captures against synthetic/static faces, not on pre-recorded LCD
replays in short clips. Shipping it is still correct — against a
deepfake video injected via a virtual camera (the attack class
openliveness primarily targets), rPPG sees no pulse and flags.

**combined (`combined`)** — Equal-weighted mean of LBP's per-clip
frame mean, Fourier's per-clip frame mean, and pos-rppg's per-clip
confidence. Beats the two weaker single layers (Fourier and pos-rppg)
on both AUC and HTER. Does **not** beat LBP alone (0.712 vs 0.780
AUC) — the below-chance pos-rppg component drags the equal-weighted
fusion down. This is textbook ensemble-with-weak-learner behaviour:
naive uniform weights are the honest no-tuning baseline, not the
operating point you'd ship.

**Default-threshold 0.5 is degenerate for the two closed-form
scorers and the combined scorer.** Fourier/pos-rppg/combined tend
to produce scores well below 0.5 (BPCER = 100%: every clip,
including bona-fide ones, scores below threshold and gets called
attack). A real deployment would calibrate the threshold on
held-out data; the swept-optimal column is the number that matters.

---

## How to read these numbers

- **APCER** (Attack Presentation Classification Error Rate) — attacks
  misclassified as live. Lower is better. Security-first.
- **BPCER** (Bona-fide Presentation Classification Error Rate) — live
  presentations misclassified as attack. Lower is better. UX-first
  (false rejections).
- **HTER** (Half Total Error Rate) — `(APCER + BPCER) / 2`. The
  single-number operating-point summary.
- **AUC** (Area under the ROC curve) — threshold-independent
  separability. 0.5 is random; 1.0 is perfect. Reported alongside
  HTER because HTER depends on the chosen threshold and AUC does
  not.
- **@ optimal** — swept threshold minimising HTER on the test
  split. Paper-table number; represents the best fair tuning a
  deployer could achieve on data matched to this corpus.
- **@ default (0.5)** — the operating point the shipped SDK uses
  today with no calibration. The gap between @ optimal and @ default
  shows the tuning headroom.

See the ISO/IEC 30107-3 standard for the definitive metric
definitions.

---

## Limitations

1. **Single corpus.** MSU-MFSD is 35 subjects captured on 2015-era
   devices. Expect degraded performance on modern high-DPI OLED
   displays, deepfake-style attacks, and demographics underrepresented
   in MSU-MFSD. Wiring REPLAY-ATTACK and OULU-NPU loaders is the
   single highest-value contribution for closing this gap.

2. **Clip-length tension for pos-rppg.** The MSU-MFSD ~12s clips
   are at the lower bound of the POS detector's capture window. A
   corpus with longer clips (e.g. OULU-NPU) would give a fairer read
   on Layer 3's upside.

3. **Benchmark ROI vs mobile ROI.** The benchmark approximates
   Layer 3's skin ROIs from PittPatt bboxes; the mobile pipeline
   uses MediaPipe FaceMesh landmarks (indices 10, 101, 330). The
   RGB signals won't bit-match. Direction and sign of the effect
   is unknown; could be wiring a MediaPipe path in the benchmark
   as a separate initiative.

4. **No Layer 2 (challenge) or Layer 5 (behavioral) numbers.** Both
   layers need interactive data — a user actively performing
   challenges and producing micro-movements — which public corpora
   don't capture. These layers get their own test suites in the
   Android `src/test/kotlin/` tree instead.

5. **The combined scorer's equal weights are arbitrary.** A future
   pass should swap in AUC-proportional or logistic-regression
   weights fit on a held-out calibration split. The infrastructure
   (`combined_scorer` factory) stays the same; only the fusion rule
   inside changes.

---

## Scope

**What this harness does.** Compute ISO/IEC 30107-3 metrics for
any `Scorer` or `VideoScorer` against any registered dataset loader.
Dataset-agnostic + scorer-agnostic — adding a new scorer or a new
corpus is one registry entry.

**What this harness does not do.** Train models (see
`core/models/train_texture_model_msu.py`). Serve attestations (see
`relay/`). Replace the per-layer Kotlin/Swift unit tests that pin
numeric parity with the Python reference (see `android-sdk/src/test/`).

---

## Citation

Wen, D., Han, H., & Jain, A. K. (2015). Face Spoof Detection with
Image Distortion Analysis. *IEEE Transactions on Information
Forensics and Security*, 10(4), 746-761.

MSU-MFSD is used here under the terms Hu Han publishes on the
corpus's public download page. openliveness does not redistribute
the dataset.
