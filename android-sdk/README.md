# openliveness Android SDK

Phone side of [openliveness](https://github.com/ramuvenky/openliveness) for
Android. Scans the QR shown in the browser, runs the five-layer liveness
pipeline locally, signs the result with a hardware-backed key, and POSTs
the signed attestation to the relay.

**Nothing leaves the device except scores and a cryptographic proof.**
No video, no landmarks, no face embeddings.

## Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.ramuvenky:openliveness:v0.1.1")
}
```

Minimum SDK: API 26 (Android 8.0). Requires the `CAMERA` permission.

## Quick start

```kotlin
import dev.openliveness.CDLSession
import dev.openliveness.parseQRPayload

// 1. You scan the QR with any camera / barcode library. The raw value
//    is a base64-encoded JSON payload.
val payload = parseQRPayload(scannedRawValue)

// 2. Hand it to CDLSession. cloudProjectNumber comes from
//    Google Cloud Console → Project Settings → Project number.
//    Pass null in dev / on AOSP devices to skip hardware attestation.
val session = CDLSession(
    payload = payload,
    context = context,
    cloudProjectNumber = 123456789L,
)

// 3. start() runs the full handshake: opens the WebSocket, waits for
//    session_acknowledged, runs liveness (challenge + rPPG + texture +
//    behavioral), signs with the hardware-backed EC key, POSTs to
//    /cdl/session/{id}/complete, returns the relay's response.
val response = session.start()

if (response.accepted && response.attestationToken != null) {
    // Phone's part is done. The browser sees the JWT via its own
    // WebSocket connection and forwards it to the relying party's
    // backend for verification with @openliveness/verify.
}
```

`start()` is a `suspend` function — call it from a coroutine.

## What this SDK ships

| Layer | Implementation |
|-------|----------------|
| 1 — Hardware attestation | Google Play Integrity **Standard** (`liveness_decision` bound into `requestHash`) |
| 2 — Active challenge     | MediaPipe Face Mesh landmark stream + sequencer |
| 3 — rPPG pulse           | POS algorithm over face-region luminance |
| 4 — Texture              | On-device TFLite classifier (uniform LBP(8,1) + Fourier features) |
| 5 — Behavioral           | Micro-movement + microsaccade detection |

The Layer 4 TFLite model lives at `assets/lbp_classifier.tflite` inside
the AAR. Training provenance, MSU-MFSD benchmark numbers (AUC 0.780,
HTER 0.289), and known limitations are documented in the model card
bundled right next to it: `assets/lbp_classifier.model-card.json`.

## Hardware attestation

With a valid `cloudProjectNumber` on a Play-Services device, Play
Integrity Standard binds the full liveness decision into the hardware
verdict — forging `"pass"` at the SDK layer breaks the signature.
The relay maps these to assurance levels:

| Device | SDK result | Relay assurance |
|--------|------------|-----------------|
| Play Integrity OK                           | `HwStatus.OK`           | `IAL2` (when decision == `pass`) |
| AOSP / Huawei / `cloudProjectNumber = null` | `HwStatus.UNSUPPORTED`  | `software_only` |
| Play Services present but attestation failed | `HwStatus.FAILED`       | `software_only`, logged for downgrade-attack review |

The SDK never hard-fails on missing Play Services — it reports what
the device can actually prove.

## Running the relay

The SDK talks to a relay server that this repo also ships (`relay/`,
Node + Fastify). You run it yourself:

```bash
git clone https://github.com/ramuvenky/openliveness.git
cd openliveness/relay
docker compose up -d
# Relay listens on http://localhost:3000; the QR payload's relay_ws /
# relay_http fields point the Android SDK at it automatically.
```

## Status

v0.1.1 — first public cut (v0.1.0 shipped under a mistaken coordinate
and is superseded). API surface is small and intentionally stable;
breaking changes before 1.0 will be flagged in release notes.

## License

Apache 2.0
