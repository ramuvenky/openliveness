# Contributing

openliveness is maintained in the open and PRs are welcome. The
project has a lead maintainer plus outside contributors; everything
lands through review, not direct push.

The one thing to internalize before writing code is the invariant
the project is built around: biometric data never leaves the user's
device, and the server receives only a signed proof. Any change that
regresses an attacker-visible property of that contract is a bug,
even if every test passes. `protocol/ThreatModel.md` is the source
of truth for what "attacker-visible" means. Read it once before
your first PR.

## Where help is most useful right now

The iOS SDK. Android is done through v0.1.0 — Layers 1–5 are
implemented, the texture classifier is trained on MSU-MFSD and
exported to TFLite, the sample app has been exercised on a real
device, and CI is green. iOS has the skeleton (CDLSession, attestation,
QR scanner, Layer 2 active liveness, sample app with App Attest on
TestFlight) but Layers 3, 4, and 5 are all stub constants. Someone
comfortable with Swift + a bit of computer vision can meaningfully
move the project forward by picking any one of them.

Non-iOS areas that also want attention: adding scored benchmark runs
against REPLAY-ATTACK or OULU-NPU (the loaders in `benchmark/datasets/`
are already written), integration docs for specific KYC stacks, and
translations of the sample app's user-facing strings.

## iOS SDK: what works, what doesn't

Code lives in `ios-sdk/`. The sample app in `ios-sample/` is a dev
harness only — never meant for production — and ships with a permanent
red banner to make that obvious.

Working today: `CDLSession` (the full WS handshake, ack timeout, and
stub-mode gate), the App Attest + keychain + ES256 signing flow, QR
scanner, camera capture plumbing, the Layer 2 active liveness runner
with the challenge sequencer, canonical JSON, and the `LandmarkStream`
protocol used by Layer 2 tests. Unit tests + one integration test +
two CI workflows (Simulator for forks without an Apple Developer
account, TestFlight for signed builds).

Not working — these are the open contributions:

**MediaPipe FaceLandmarker binding.** The `LandmarkStream` protocol
has test fakes but no production implementation. Android ships
`MediaPipeLandmarkStream.kt` and bundles `face_landmarker.task` as an
asset. iOS needs the same wrapper and the same `.task` file bundled
through SwiftPM resources.

**Layer 3 (rPPG).** Currently a 6-line stub returning `0.85`. The
Python reference is `core/algorithms/layer3_rppg.py` (POS algorithm);
the Kotlin impl is `Layer3RPPG.kt` + `Layer3RgbTap.kt`. Port it, add
unit tests against the same golden fixtures the Android tests use.

**Layer 4 (LBP texture + TFLite).** 7-line stub. The classifier is
already trained and exported — `android-sdk/src/main/assets/lbp_classifier.tflite`,
2.8 KB. The same file should ship bit-identically in the iOS bundle;
add `TensorFlowLiteSwift` as a SwiftPM dep and write the thin wrapper
around it. Face crop extraction mirrors `FaceCropExtractor.kt`.

**Layer 5 (behavioral).** 7-line stub. Reads micro-movement signals
(iOS equivalent of what `Layer5Behavioral.kt` does). This is probably
the smallest of the three ports because `CMMotionManager` maps cleanly.

**Ambient conditions.** iOS has no ambient light sensor API available
to third-party apps. Android reads `TYPE_LIGHT`. Either find a proxy
(`AVCaptureDevice.exposureTargetBias` is one candidate) or make the
case for reporting `ambient: null` to the relay and documenting the
asymmetry.

**On-device integration tests for Layers 3/4/5.** The pattern to
mirror is `android-sdk/src/androidTest/kotlin/dev/openliveness/CDLSessionE2ETest.kt`.

Open an issue to claim one of these before you start, so two people
don't duplicate work.

## Running things locally

iOS: `swift build && swift test` in `ios-sdk/` for unit tests.
Integration tests expect the stub-relay running — see below. The
sample app is XcodeGen-driven; `ios-sample/README.md` has the setup.

Android: `./gradlew test` for unit tests, `./gradlew connectedDebugAndroidTest`
with a device or emulator attached for instrumented tests. If you're
on USB, run `adb reverse tcp:8787 tcp:8787` first so `localhost:8787`
inside the app points at the host stub-relay. (If you don't, the
instrumented tests still try to connect to the AVD-only address and
fail.)

Relay / verify / web-sdk: `pnpm install && pnpm test` at the repo
root. Node 20+ and pnpm 9+.

Core and benchmark: Python 3.11, `pip install -r core/requirements.txt`.
On macOS Catalina the TensorFlow install is non-obvious — if you hit
it, grep for `catalina` in the repo's existing notes before fighting
pip for an afternoon.

Stub-relay: `python tools/stub-relay/server.py` binds `127.0.0.1:8787`.
Flask app, no persistence, mirrors the real relay's WS + HTTP routes.
Several integration tests on both mobile sides depend on it.

## PRs

One concern per PR. If you're porting Layer 3 to iOS, don't also
refactor `RelayClient` — that's a second PR.

New production code wants tests. A ported layer wants both a unit
test that doesn't need a camera and at least one integration test
that hits the stub-relay. Where a port has a reference impl in
Python or Kotlin, state the parity bound you achieved in the PR
description — not because precision for its own sake, but because
when the next person debugs a regression they need to know whether
`0.84` and `0.86` on the same input is normal drift or a bug.

If the change could affect what an attacker sees or can forge, say
so and point at the specific line in `protocol/ThreatModel.md` your
change interacts with. Reviewers look for this.

Don't bypass the stub-mode gate. It exists because an earlier
version shipped stub scores to the relay in release builds; it is
not a code-smell to be refactored away.

## Reporting things

Bugs: GitHub issues. Include the component, repro steps, platform
versions.

Security issues: don't open a public issue — use GitHub's private
vulnerability reporting. See `SECURITY.md`.

## License

Apache 2.0. By opening a PR you're agreeing your contribution ships
under that license. No CLA.
