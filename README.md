# openliveness

**Open-source liveness verification that never sends biometric data across the network.**

Proves that a real, live human is in front of the camera — not a
photo, replay, deepfake, or AI-generated video. Designed to drop
into identity verification, authentication, and onboarding flows
as the liveness layer; this project doesn't do identity matching,
document verification, or PII handling.

Built on the Cross-Device Liveness (CDL) protocol: the user's phone
runs all biometric computation locally, and the server receives only
a signed cryptographic proof. Never video, never images, never face
embeddings.

Designed for banks, KYC providers, healthcare, government services,
and any application where proving human presence must not compromise
biometric privacy.

---

## The Problem

Web browsers have no way to verify that a camera feed came from real
hardware. An attacker installs virtual camera software (OBS, ManyCam),
feeds AI-generated video, and the liveness algorithm never knows the
physical camera was never involved. This is called an **injection attack**
— the dominant fraud vector against remote liveness checks today.

Every commercial liveness service solves this by sending your video
to their cloud. Your face — one of the most sensitive categories of
personal data — travels across the network and is processed on
someone else's servers.

## The Solution

openliveness takes a different approach. Instead of sending video to
a server, it routes liveness verification to the user's **phone** —
which can cryptographically prove its camera is genuine using hardware
attestation (Apple App Attest / Google Play Integrity).

```
Browser shows QR → User scans with phone → Phone runs liveness locally
→ Phone signs result with hardware key → Server verifies proof
→ Browser session complete
```

The server receives a signed attestation object — not video, not a face
image, not biometric features. Only numerical scores and a cryptographic
proof that a real human was verified on a real device.

**No biometric data crosses the network. Ever.**

---

## Why openliveness

| Problem | How openliveness solves it |
|---------|--------------------------|
| Injection attacks bypass the camera entirely | Hardware attestation (App Attest / Play Integrity) cryptographically proves the video came from a real physical camera |
| Commercial liveness sends your face to their cloud | All biometric computation runs on the user's device. Server receives only scores + proof. |
| Proprietary algorithms cannot be audited | All algorithms are open source (Apache 2.0). MediaPipe, POS rPPG, LBP, Fourier — all inspectable. |
| GDPR Article 9 burden for biometric processing | Server never processes biometric data. No special category data under GDPR. |
| Vendor lock-in and pricing | Self-hostable on any infrastructure. No usage limits. No API key. |
| Single point of failure in detection | Five independent detection layers. Attacker must defeat all simultaneously. |

---

## Design Principles

1. **On-device processing**: all biometric computation runs on the
   user's device. No biometric data crosses the network.

2. **Source-auditable**: all algorithms are open source and inspectable.

3. **GDPR Article 9 by design**: because biometric data never reaches
   your server, you do not process special category data under GDPR.

4. **Graceful degradation**: devices without hardware attestation
   return `software_only` assurance. The system does not hard-fail —
   it reports what it can prove.

---

## Five Detection Layers

| Layer | Method | Stops |
|-------|--------|-------|
| 1 | Hardware attestation (Play Integrity / App Attest) | Virtual camera injection |
| 2 | Active liveness challenge (MediaPipe Face Mesh) | Photo and replay attacks |
| 3 | Physiological signal rPPG (POS algorithm) | Synthetic video, no pulse |
| 4 | Texture analysis (LBP + Fourier) | Print attacks, screen replay |
| 5 | Behavioral biometrics (micro-movements) | Static synthetic faces |

---

## Packages

| Package | Description | Published as |
|---------|-------------|--------------|
| `relay/` | Node.js WebSocket relay server | self-host (`docker compose`) |
| `verify/` | Attestation JWT verification | `npm i @openliveness/verify` |
| `web-sdk/` | Browser QR display + session polling | `npm i @openliveness/web` |
| `android-sdk/` | Kotlin SDK for Android phones | JitPack: `com.github.ramuvenky.openliveness:android-sdk:v0.1.0` |
| `ios-sdk/` | Swift SDK for iPhones | Not yet published (blocked on Apple Developer enrollment) |
| `core/` | Python reference algorithm implementations | In-repo only (research / contributor tooling) |
| `benchmark/` | ISO/IEC 30107-3 evaluation harness | In-repo only (research / contributor tooling) |

---

## Quick Start

First, run the relay yourself — it's a Node server in this repo:

```bash
cd relay && docker compose up -d
# relay now listening on http://localhost:3000
```

Then integrate the SDKs into your app:

```typescript
// Browser
import { verify } from '@openliveness/web';

const result = await verify({
  relayUrl: 'localhost:3000',              // point at your deployed relay
  assuranceRequired: 'IAL2',
  onQRReady: (dataUrl) => { document.getElementById('qr').src = dataUrl; }
});

// result.decision === 'pass'
// result.assuranceLevel === 'IAL2'
// result.token === JWT for your backend
```

```kotlin
// Your Android app — the phone side that scans the QR and runs liveness
//
// settings.gradle.kts:
//   dependencyResolutionManagement {
//     repositories { maven { url = uri("https://jitpack.io") } }
//   }
// app/build.gradle.kts:
//   dependencies {
//     implementation("com.github.ramuvenky.openliveness:android-sdk:v0.1.0")
//   }

import dev.openliveness.CDLSession
import dev.openliveness.parseQRPayload

val session = CDLSession(
    payload = parseQRPayload(scannedQR),
    context = context,
    cloudProjectNumber = 123456789L,     // from Google Cloud Console; pass null to skip HW attestation
)
val response = session.start()           // suspend fun — call from a coroutine
// response.accepted + response.attestationToken
```

```typescript
// Your backend — verifies the JWT the browser forwards to you
import { verifyAttestationJWT } from '@openliveness/verify';

const result = await verifyAttestationJWT(token, {
  relayUrl: 'localhost:3000',              // same relay as above
  audience: 'your-domain.com',
  requiredAssurance: 'IAL2'
});
```

---

## What Never Leaves the Device

```
Raw video frames      — never transmitted
Face images           — never transmitted
Face embeddings       — never transmitted
Biometric features    — never transmitted
```

What crosses the network:
```json
{
  "liveness_decision": "pass",
  "layer_scores": { "layer2": 0.97, "layer3": 0.88, "layer4_lbp": 0.94 },
  "hardware_attested": true,
  "signature": "ES256 cryptographic proof"
}
```

These scores cannot reconstruct a face. They are not biometric data
in any legal or technical sense.

---

## Who This Is For

- **Banks & financial institutions** — IAL2-grade liveness for KYC onboarding (NIST SP 800-63-3) without cloud biometric processing
- **Government & public sector** — remote liveness proofing with auditable, open-source algorithms
- **Healthcare** — patient verification where biometric data must stay on-device (HIPAA, GDPR)
- **KYC/identity platforms** — add liveness without becoming a biometric data processor
- **Researchers** — open-source reference implementation for presentation attack detection
- **Any application** — where proving human presence must not compromise biometric privacy

---

## Documentation

- [Full Technical Specification](docs/Spec.md) — complete implementation guide (28 sections)
- [Project Background](docs/background.md) — why this exists, what problem it solves
- [Threat Model](protocol/ThreatModel.md) — what this protects against and what it does not
- [Attestation Schema](protocol/AttestationSchema.json) — the shared interface contract
- [Contributing](CONTRIBUTING.md) — how to help, with iOS SDK as the highest-value onramp
- [Security](SECURITY.md) — how to report vulnerabilities privately

---

## Status

Active development. **v0.1.0 shipping now** (Android SDK via JitPack, `@openliveness/web` and `@openliveness/verify` on npm).

iOS SDK sits in the repo but is not yet published — it's blocked on
Apple Developer enrollment. Help on finishing that port is the
highest-value contribution right now; see [CONTRIBUTING](CONTRIBUTING.md).

This is a new project. There is no production deployment yet.
Early adoption partnerships are actively sought.

---

## License

Apache 2.0
