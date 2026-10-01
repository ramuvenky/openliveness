# openliveness

**Open-source liveness verification that never sends biometric data across the network.**

Proves a real live human is present during identity verification using
the Cross-Device Liveness (CDL) protocol. The user's phone runs all
biometric computation locally — the server receives only a signed
cryptographic proof, never video, never images, never face embeddings.

Designed for banks, KYC providers, healthcare, government services,
and any application where proving human presence must not compromise
biometric privacy.

---

## The Problem

Web browsers have no way to verify that a camera feed came from real
hardware. An attacker installs virtual camera software (OBS, ManyCam),
feeds AI-generated video, and the liveness algorithm never knows the
physical camera was never involved. This is called an **injection attack**
— the dominant fraud vector in identity verification today.

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

| Package | Description |
|---------|-------------|
| `relay/` | Node.js WebSocket relay server |
| `verify/` (`@openliveness/verify`) | Attestation JWT verification library |
| `web-sdk/` (`@openliveness/web`) | Browser QR display + session polling |
| `ios-sdk/` | Swift SDK for iPhone liveness + signing |
| `android-sdk/` | Kotlin SDK for Android liveness + signing |
| `core/` | Python reference algorithm implementations |
| `benchmark/` | ISO/IEC 30107-3 evaluation harness |

---

## Quick Start

```typescript
// Browser
import { verify } from '@openliveness/web';

const result = await verify({
  relayUrl: 'relay.openliveness.dev',
  assuranceRequired: 'IAL2',
  onQRReady: (dataUrl) => { document.getElementById('qr').src = dataUrl; }
});

// result.decision === 'pass'
// result.assuranceLevel === 'IAL2'
// result.token === JWT for your backend
```

```typescript
// Your backend
import { verifyAttestationJWT } from '@openliveness/verify';

const result = await verifyAttestationJWT(token, {
  relayUrl: 'relay.openliveness.dev',
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

- **Banks & financial institutions** — KYC onboarding that meets IAL2 (NIST SP 800-63-3) without cloud biometric processing
- **Government & public sector** — remote identity proofing with auditable, open-source algorithms
- **Healthcare** — patient verification where biometric data must stay on-device (HIPAA, GDPR)
- **KYC/identity platforms** — add liveness without becoming a biometric data processor
- **Researchers** — open-source reference implementation for presentation attack detection
- **Any application** — where proving human presence must not compromise biometric privacy

---

## Documentation

- [Full Technical Specification](docs/spec.md) — complete implementation guide (28 sections)
- [Project Background](docs/background.md) — why this exists, what problem it solves
- [Threat Model](protocol/threat-model.md) — what this protects against and what it does not
- [Attestation Schema](protocol/attestation-schema.json) — the shared interface contract

---

## Status

Active development. Version 0.1.0 target: end of Oct 2026.

This is a new project. There is no production deployment yet.
Early adoption partnerships are actively sought.

---

## License

Apache 2.0
