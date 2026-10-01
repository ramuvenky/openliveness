# openliveness — Complete Technical Specification
# Cross-Device Liveness (CDL) Protocol v1.0
# Status: Active Development
# License: Apache 2.0

---

## TABLE OF CONTENTS

1.  Overview
2.  Design Principles
3.  Repository Structure
4.  Technology Stack
5.  Attestation Object — Shared Interface Contract
6.  Session Lifecycle
7.  HTTP API Reference
8.  WebSocket Message Protocol
9.  Apple App Attest Integration
10. Google Play Integrity Integration
11. Attestation JWT
12. Algorithm Specifications
13. Orchestrator
14. Redis Key Schema
15. Rate Limiting
16. Error Codes
17. Environment Variables
18. Docker and Local Development
19. Web SDK
20. Verify Package
21. Security Properties
22. Threat Model
23. Benchmark Harness
24. Testing Strategy
25. Algorithm Choices
26. Glossary

---

## 1. OVERVIEW

### What This Is

openliveness is an open-source liveness verification system that proves
a real live human is present during identity verification — without
sending any biometric data (video, images, face embeddings) across
the network.

It solves one specific problem: web browsers have no hardware camera
attestation API. This makes browser-based liveness verification
vulnerable to injection attacks where an attacker bypasses the physical
camera entirely by routing synthetic or pre-recorded video through
virtual camera software. The liveness algorithm never sees a fake
person — it sees what appears to be a genuine camera stream.

The solution is the Cross-Device Liveness (CDL) protocol. When a web
session needs liveness verification, the browser initiates a handoff
to the user's phone. The phone runs full liveness verification locally,
with hardware-backed proof that the video came from a real physical
camera. The phone sends only a signed attestation object to the server.
The browser session completes based on that proof.

### What This Is NOT

- Not a face recognition or face matching system
- Not a deepfake detection tool for existing video files
- Not a cloud biometric processing service
- Does not store, transmit, or process raw biometric data on any server

### Core Principle

```
The phone verifies the human.
The server verifies the phone verified the human.
Biometric data never leaves the device.
```

### Why Cross-Device

Mobile phones — iPhones and Android devices — have a capability that
web browsers fundamentally lack: they can cryptographically prove that
a video frame came from their physical hardware camera.

Apple provides this through App Attest. Google provides it through
the Play Integrity API. A virtual camera driver cannot forge these
proofs. They require the device's secure hardware chip — present in
every modern smartphone.

CDL applies the same cross-device handoff pattern used by passkeys
(FIDO2 Cross-Device Authentication) to liveness verification.

---

## 2. DESIGN PRINCIPLES

1. **On-device processing**: all biometric computation runs on the
   user's device. No biometric data crosses the network.

2. **Self-hostable**: the relay server and verification library
   can be deployed on any infrastructure.

3. **Source-auditable**: all algorithms are open source and
   inspectable by any party.

4. **GDPR Article 9 by design**: because biometric data never
   reaches the server, the server does not process special
   category data under GDPR. The user's device processes their
   own data.

5. **Assurance transparency**: the system reports an assurance level
   (IAL2, IAL1, software_only) with every result. The relying party
   enforces their own policy threshold. The system does not hide
   limitations.

6. **Graceful degradation**: devices without hardware attestation
   support return software_only assurance. The system does not
   hard-fail — it reports what it can prove.

---

## 3. REPOSITORY STRUCTURE

```
openliveness/
├── protocol/
│   ├── spec.md                      # This document
│   ├── attestation-schema.json      # Shared interface contract
│   ├── threat-model.md              # Security properties
│   └── session-lifecycle.md         # Sequence diagrams
│
├── relay/                           # Node.js + TypeScript relay server
│   ├── src/
│   │   ├── server.ts
│   │   ├── routes/
│   │   │   ├── session.ts
│   │   │   ├── device.ts
│   │   │   └── health.ts
│   │   ├── websocket/
│   │   │   ├── handler.ts
│   │   │   └── messages.ts
│   │   ├── attestation/
│   │   │   ├── verify.ts
│   │   │   ├── apple.ts
│   │   │   ├── google.ts
│   │   │   └── jwt.ts
│   │   ├── session/
│   │   │   ├── store.ts
│   │   │   ├── challenge.ts
│   │   │   └── types.ts
│   │   └── middleware/
│   │       ├── rateLimit.ts
│   │       └── security.ts
│   ├── tests/
│   ├── Dockerfile
│   ├── docker-compose.yml
│   ├── .env.example
│   └── package.json
│
├── verify/                          # npm: @openliveness/verify
│   ├── src/
│   │   ├── index.ts
│   │   ├── signature.ts
│   │   ├── schema.ts
│   │   ├── freshness.ts
│   │   └── jwt.ts
│   ├── tests/
│   └── package.json
│
├── web-sdk/                         # npm: @openliveness/web
│   ├── src/
│   │   ├── index.ts
│   │   ├── session.ts
│   │   ├── qr.ts
│   │   ├── websocket.ts
│   │   └── types.ts
│   ├── react/
│   │   └── LivenessWidget.tsx
│   ├── tests/
│   └── package.json
│
├── ios-sdk/                         # Swift Package
│   ├── Sources/
│   │   └── OpenLiveness/
│   │       ├── CDLSession.swift
│   │       ├── LivenessOrchestrator.swift
│   │       ├── Attestation/
│   │       │   ├── AttestationBuilder.swift
│   │       │   ├── AppAttestService.swift
│   │       │   └── SigningService.swift
│   │       ├── Layers/
│   │       │   ├── Layer2ActiveLiveness.swift
│   │       │   ├── Layer3RPPG.swift
│   │       │   ├── Layer4Texture.swift
│   │       │   └── Layer5Behavioral.swift
│   │       └── Camera/
│   │           └── CameraCapture.swift
│   ├── Tests/
│   └── Package.swift
│
├── android-sdk/                     # Kotlin + Gradle
│   ├── src/main/kotlin/dev/openliveness/
│   │   ├── CDLSession.kt
│   │   ├── LivenessOrchestrator.kt
│   │   ├── attestation/
│   │   │   ├── AttestationBuilder.kt
│   │   │   ├── PlayIntegrityService.kt
│   │   │   └── SigningService.kt
│   │   ├── layers/
│   │   │   ├── Layer2ActiveLiveness.kt
│   │   │   ├── Layer3RPPG.kt
│   │   │   ├── Layer4Texture.kt
│   │   │   └── Layer5Behavioral.kt
│   │   └── camera/
│   │       └── CameraCapture.kt
│   ├── src/test/
│   └── build.gradle.kts
│
├── core/                            # Python algorithm implementations
│   ├── algorithms/
│   │   ├── layer2_active_liveness.py
│   │   ├── layer3_rppg_pos.py
│   │   ├── layer4_texture.py
│   │   └── layer5_behavioral.py
│   ├── orchestrator.py
│   ├── models/
│   │   ├── train_texture_model.py
│   │   └── export_tflite.py
│   └── requirements.txt
│
├── benchmark/                       # ISO/IEC 30107-3 harness
│   ├── harness.py
│   ├── metrics.py
│   └── datasets/
│       ├── msu_mfsd.py
│       ├── replay_attack.py
│       └── oulu_npu.py
│
├── examples/
│   └── bank-kyc-demo/
│       ├── server.ts
│       ├── public/
│       │   ├── index.html
│       │   └── app.ts
│       └── package.json
│
├── docs/
│   ├── spec.md                      # This document
│   ├── background.md                # Project background and rationale
│   ├── quickstart.md
│   ├── ios-integration.md
│   ├── android-integration.md
│   ├── self-hosting.md
│   └── compliance.md
│
├── turbo.json
├── pnpm-workspace.yaml
├── package.json
├── LICENSE
├── README.md
└── CHANGELOG.md
```

---

## 4. TECHNOLOGY STACK

### relay/ — Server

| Technology | Version | Purpose | License |
|-----------|---------|---------|---------|
| Node.js | 20 LTS | Runtime | MIT |
| TypeScript | 5.x | Language | Apache 2.0 |
| Fastify | 4.x | HTTP server | MIT |
| ws | 8.x | WebSocket server | MIT |
| ioredis | 5.x | Redis client | MIT |
| jose | 5.x | JWT + JWK (WebCrypto) | MIT |
| zod | 3.x | Schema validation | MIT |
| pino | 8.x | Structured logging | MIT |
| @fastify/rate-limit | 9.x | Rate limiting | MIT |
| @fastify/helmet | 11.x | Security headers | MIT |
| uuid | 9.x | UUID generation | MIT |
| cbor | 9.x | CBOR decoding (App Attest) | MIT |
| googleapis | 140.x | Play Integrity verification | Apache 2.0 |
| Vitest | 1.x | Unit testing | MIT |
| Supertest | 6.x | HTTP integration tests | MIT |
| Redis | 7.x | Session storage | BSD |
| Docker | 24.x | Containerization | Apache 2.0 |

### verify/ — @openliveness/verify

| Technology | Version | Purpose | License |
|-----------|---------|---------|---------|
| TypeScript | 5.x | Language | Apache 2.0 |
| jose | 5.x | JWT + ES256 verification | MIT |
| zod | 3.x | Schema validation | MIT |

Zero runtime server dependencies. Runs in Node.js and browser.

### web-sdk/ — @openliveness/web

| Technology | Version | Purpose | License |
|-----------|---------|---------|---------|
| TypeScript | 5.x | Language | Apache 2.0 |
| qrcode | 1.x | QR code generation | MIT |
| React 18 (optional) | 18.x | React wrapper | MIT |

Core has no framework dependency. React wrapper is a separate export.

### ios-sdk/

| Technology | Version | Purpose | License |
|-----------|---------|---------|---------|
| Swift | 5.9+ | Language | Apache 2.0 |
| iOS target | 14.0+ | Minimum OS | - |
| MediaPipe iOS | 0.10.x | Face landmarks (478 points) | Apache 2.0 |
| AVFoundation | OS | Camera capture | Apple |
| DeviceCheck | OS | App Attest | Apple |
| CryptoKit | OS | ES256 signing | Apple |
| Swift Package Manager | - | Distribution | Apache 2.0 |

### android-sdk/

| Technology | Version | Purpose | License |
|-----------|---------|---------|---------|
| Kotlin | 1.9+ | Language | Apache 2.0 |
| Android | API 26+ (8.0) | Minimum OS | - |
| MediaPipe Android | 0.10.x | Face landmarks | Apache 2.0 |
| CameraX | 1.3.x | Camera capture | Apache 2.0 |
| Play Integrity API | - | Hardware attestation | Google |
| Android Keystore | OS | ES256 signing | Google |
| ML Kit Barcode | - | QR scanning | Apache 2.0 |
| OkHttp | 4.x | HTTP + WebSocket | Apache 2.0 |
| Moshi | 1.x | JSON serialization | Apache 2.0 |
| JUnit 5 | 5.x | Testing | EPL 2.0 |

### core/ + benchmark/ — Python

| Technology | Version | Purpose | License |
|-----------|---------|---------|---------|
| Python | 3.11+ | Language | PSF |
| MediaPipe Python | 0.10.x | Face landmarks | Apache 2.0 |
| OpenCV | 4.x | Frame processing | BSD |
| NumPy | 1.x | Numerical computation | BSD |
| SciPy | 1.x | Signal processing | BSD |
| TensorFlow | 2.x | Model training | Apache 2.0 |
| TensorFlow Lite | 2.x | Mobile model format | Apache 2.0 |
| Silent-Face-Anti-Spoofing | - | LBP + Fourier baseline | Apache 2.0 |
| scikit-learn | 1.x | LBP SVM classifier | BSD |
| pytest | 7.x | Testing | MIT |

### Monorepo

| Technology | Purpose |
|-----------|---------|
| pnpm 8.x | Node.js package manager |
| Turborepo | Monorepo task orchestration |

pnpm-workspace.yaml:
```yaml
packages:
  - 'relay'
  - 'verify'
  - 'web-sdk'
  - 'examples/*'
```

turbo.json:
```json
{
  "$schema": "https://turborepo.org/schema.json",
  "pipeline": {
    "build": { "dependsOn": ["^build"], "outputs": ["dist/**"] },
    "test": { "dependsOn": ["build"] },
    "dev": { "cache": false, "persistent": true }
  }
}
```

---

## 5. ATTESTATION OBJECT — SHARED INTERFACE CONTRACT

This is the ONLY JSON object the phone sends to the server after
liveness verification. It contains NO raw biometric data.

Both the mobile SDKs (producers) and the relay server (consumer)
implement against this schema. Changes require consensus from all
maintainers.

### Full Schema

```json
{
  "$schema": "http://json-schema.org/draft-07/schema",
  "title": "CDLAttestation",
  "type": "object",
  "required": [
    "version", "session_id", "challenge_id", "challenge_response",
    "timestamp", "liveness_decision", "assurance_level",
    "layer_scores", "device_platform", "public_key", "signature"
  ],
  "properties": {
    "version": {
      "type": "string", "enum": ["1.0"]
    },
    "session_id": {
      "type": "string", "format": "uuid",
      "description": "UUID from relay server. Links attestation to browser session."
    },
    "challenge_id": {
      "type": "string", "format": "uuid",
      "description": "Single-use challenge UUID. Server rejects reuse."
    },
    "challenge_response": {
      "type": "string",
      "description": "Ordered comma-separated steps completed. Example: 'blink,look_left,smile'"
    },
    "timestamp": {
      "type": "string", "format": "date-time",
      "description": "ISO 8601 UTC. Server rejects if older than 120 seconds."
    },
    "liveness_decision": {
      "type": "string", "enum": ["pass", "fail", "inconclusive"]
    },
    "assurance_level": {
      "type": "string", "enum": ["IAL2", "IAL1", "software_only"],
      "description": "IAL2: hardware attestation present + all layers pass. software_only: no hardware attestation."
    },
    "layer_scores": {
      "type": "object",
      "required": [
        "layer2_challenge_completed", "layer2_challenge_score",
        "layer3_pulse_detected", "layer3_bpm_in_range",
        "layer3_rppg_confidence", "layer4_lbp_score",
        "layer4_fourier_score", "layer5_behavioral_score",
        "layer5_microsaccade_detected"
      ],
      "properties": {
        "layer2_challenge_completed": { "type": "boolean" },
        "layer2_challenge_score":     { "type": "number", "minimum": 0.0, "maximum": 1.0 },
        "layer3_pulse_detected":      { "type": "boolean" },
        "layer3_bpm_in_range":        { "type": "boolean" },
        "layer3_rppg_confidence":     { "type": "number", "minimum": 0.0, "maximum": 1.0 },
        "layer4_lbp_score":           { "type": "number", "minimum": 0.0, "maximum": 1.0 },
        "layer4_fourier_score":       { "type": "number", "minimum": 0.0, "maximum": 1.0 },
        "layer5_behavioral_score":    { "type": "number", "minimum": 0.0, "maximum": 1.0 },
        "layer5_microsaccade_detected": { "type": "boolean" }
      }
    },
    "device_platform": {
      "type": "string", "enum": ["ios", "android"]
    },
    "device_attestation": {
      "type": "string",
      "description": "Base64 hardware attestation token. iOS: App Attest assertion. Android: Play Integrity token. Omit if hardware attestation unavailable."
    },
    "device_id_hash": {
      "type": "string",
      "description": "SHA-256 of device identifier. Hash only — raw ID never transmitted."
    },
    "ambient_conditions": {
      "type": "object",
      "properties": {
        "low_light_detected":    { "type": "boolean" },
        "motion_detected":       { "type": "boolean" },
        "front_camera_confirmed": { "type": "boolean" }
      }
    },
    "public_key": {
      "type": "string",
      "description": "Base64-encoded DER EC P-256 public key. Used to verify signature."
    },
    "signature": {
      "type": "string",
      "description": "Base64-encoded ES256 signature over canonical JSON of all fields except this one. Keys sorted alphabetically, no whitespace."
    }
  },
  "additionalProperties": false
}
```

### Computing the Signature (Mobile SDK)

```
1. Build attestation object with all fields EXCEPT signature
2. Serialize to canonical JSON:
   - Keys sorted alphabetically (recursive)
   - No whitespace
   - UTF-8 encoding
3. Hash: SHA-256(canonical_json_bytes)
4. Sign: ECDSA-P256(private_key, sha256_hash)
5. Encode: base64url(signature_bytes)
6. Set signature field to this value
```

iOS (CryptoKit):
```swift
import CryptoKit
let privateKey = try SecureEnclave.P256.Signing.PrivateKey()
let data = canonicalJSON.data(using: .utf8)!
let hash = SHA256.hash(data: data)
let signature = try privateKey.signature(for: hash)
let base64Sig = signature.rawRepresentation.base64EncodedString()
```

Android (Hardware Keystore):
```kotlin
val keyPairGenerator = KeyPairGenerator.getInstance(
    KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
)
keyPairGenerator.initialize(
    KeyGenParameterSpec.Builder("openliveness_key", KeyProperties.PURPOSE_SIGN)
        .setDigests(KeyProperties.DIGEST_SHA256)
        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
        .build()
)
val keyPair = keyPairGenerator.generateKeyPair()
val sig = Signature.getInstance("SHA256withECDSA")
sig.initSign(keyPair.private)
sig.update(canonicalJsonBytes)
val signatureBytes = sig.sign()
```

### Verifying the Signature (Server)

```typescript
async function verifyAttestationSignature(
  attestation: CDLAttestation
): Promise<boolean> {
  const { signature, ...rest } = attestation;
  const canonicalJSON = canonicalize(rest);
  const data = new TextEncoder().encode(canonicalJSON);
  const hash = await crypto.subtle.digest('SHA-256', data);

  const pubKey = await crypto.subtle.importKey(
    'spki',
    Buffer.from(attestation.public_key, 'base64'),
    { name: 'ECDSA', namedCurve: 'P-256' },
    false,
    ['verify']
  );

  return crypto.subtle.verify(
    { name: 'ECDSA', hash: 'SHA-256' },
    pubKey,
    Buffer.from(signature, 'base64'),
    hash
  );
}

function canonicalize(obj: unknown): string {
  if (typeof obj !== 'object' || obj === null) return JSON.stringify(obj);
  if (Array.isArray(obj)) return '[' + obj.map(canonicalize).join(',') + ']';
  const keys = Object.keys(obj as object).sort();
  const pairs = keys.map(k =>
    JSON.stringify(k) + ':' +
    canonicalize((obj as Record<string, unknown>)[k])
  );
  return '{' + pairs.join(',') + '}';
}
```

---

## 6. SESSION LIFECYCLE

### State Machine

```
initiated → phone_connected → processing → complete
                                         → failed
          → expired (phone did not connect within 5 minutes)
```

State transitions are one-way. Server rejects invalid transitions.

### Sequence

```
Browser                    Relay Server                 Phone
  |                             |                          |
  |-- POST /initiate ---------->|                          |
  |<-- session_id + QR ---------|                          |
  |                             |                          |
  |-- open WebSocket ---------->|                          |
  |                             |                          |
  |-- display QR code           |                          |
  |                             |                          |
  |                             |<-- scan QR --------------|
  |                             |                          |
  |<-- WS: phone_connected -----|-- WS: session_acknowledged ->|
  |                             |                          |
  |                             |    [run liveness layers] |
  |                             |    [on device only]      |
  |                             |                          |
  |<-- WS: processing_update ---|<-- WS: processing_update-|
  |                             |                          |
  |                             |<-- POST /complete -------|
  |                             |    (signed attestation)  |
  |                             |                          |
  |                             |-- verify all checks      |
  |                             |-- issue JWT              |
  |                             |                          |
  |<-- WS: session_complete ----|-- WS: attestation_received ->|
```

### Timeouts

| Event | Timeout |
|-------|---------|
| Phone must scan QR | 5 minutes from initiation |
| Phone must complete liveness | 3 minutes from QR scan |
| Attestation timestamp freshness | 120 seconds at receipt |
| Completed session in Redis | 24 hours |
| Failed session in Redis | 1 hour |

---

## 7. HTTP API REFERENCE

Base URL: `localhost` (dev) or your self-hosted relay URL.

---

### POST /cdl/session/initiate

Called by browser to start a CDL session.

Request body:
```json
{
  "relying_party": "example-bank.com",
  "assurance_required": "IAL2",
  "webhook_url": "example-bank.com/kyc/callback"
}
```

Fields:
- `relying_party` (required): domain of the service initiating KYC
- `assurance_required` (required): `"IAL2"` | `"IAL1"` | `"any"`
- `webhook_url` (optional): POST callback when session completes

Processing:
1. Generate `session_id` (UUID v4)
2. Generate `challenge_id` (UUID v4)
3. Generate `challenge_sequence` — 3 random non-repeating steps from:
   `["blink", "look_left", "look_right", "smile", "nod"]`
4. Generate `poll_token` (32 random bytes, base64url)
5. Store session in Redis with 5-minute TTL
6. Return response

Response 200:
```json
{
  "session_id": "uuid-v4",
  "challenge_id": "uuid-v4",
  "challenge_sequence": ["blink", "look_left", "smile"],
  "qr_payload": "base64-encoded JSON string",
  "poll_token": "base64url string",
  "expires_at": "ISO8601",
  "ws_url": "wss://relay.openliveness.dev/cdl/session/{id}/ws"
}
```

`qr_payload` decodes to:
```json
{
  "session_id": "uuid",
  "challenge_id": "uuid",
  "challenge_sequence": ["blink", "look_left", "smile"],
  "relay_ws": "wss://relay.openliveness.dev/cdl/session/{id}/device",
  "relay_http": "relay.openliveness.dev",
  "expires_at": "ISO8601",
  "version": "1.0"
}
```

**QR encoding**: `qr_payload` is `base64(JSON.stringify(qr_object))`.
The QR image encodes that base64 string as its text content.
Mobile SDK scans QR → gets base64 string → `base64Decode()` → `JSON.parse()` → QRPayload.
---

### GET /cdl/session/{session_id}/status

Called by browser to poll session state.

Headers: `Authorization: Bearer {poll_token}`

Response 200:
```json
{
  "session_id": "uuid",
  "state": "initiated|phone_connected|processing|complete|failed|expired",
  "liveness_decision": "pass|fail|inconclusive|null",
  "assurance_level": "IAL2|IAL1|software_only|null",
  "attestation_token": "JWT string (only when state=complete)",
  "created_at": "ISO8601",
  "updated_at": "ISO8601",
  "expires_at": "ISO8601"
}
```

---

### POST /cdl/session/{session_id}/complete

Called by phone SDK after liveness completes.
Body: full CDLAttestation object.

Verification order (reject immediately on any failure):
```
1.  Schema validation — object matches attestation-schema.json
2.  Session binding — session_id in body matches URL param
3.  Session state — exists, not expired, state is
                    phone_connected or processing
4.  Challenge binding — challenge_id matches stored value,
                        not already used,
                        challenge_response matches sequence
5.  Timestamp freshness — not > 120s old, not > 30s future
6.  Signature — ES256 verification using public_key field
7.  Hardware attestation — iOS: App Attest (Section 9)
                           Android: Play Integrity (Section 10)
8.  Assurance check — if IAL2 required and no hardware: reject
9.  Mark challenge_id as used in Redis
10. Issue attestation JWT (Section 11)
11. Update session state to complete
12. Broadcast session_complete via WebSocket
13. Call webhook if configured (POST body defined in Section 7a below)
```

Response 200:
```json
{ "accepted": true, "attestation_token": "JWT string" }
```

---

### POST /cdl/device/register (iOS only — App Attest Phase 1 Step 1)

```json
Request:  { "platform": "ios", "key_id": "string" }
Response: { "challenge": "base64-encoded 32 random bytes" }
```

---

### POST /cdl/device/attest (iOS only — App Attest Phase 1 Step 2)

```json
Request:  { "platform": "ios", "key_id": "string", "attestation": "base64" }
Response: { "device_token": "opaque string" }
```

---

### POST /cdl/session/{session_id}/integrity-nonce (Android only)

Response: `{ "nonce": "base64url string" }`

```typescript
import { sha256base64url } from '../utils/crypto';
const nonce = sha256base64url(
  sessionId + challengeId + livenessDecision + process.env.INTEGRITY_NONCE_SECRET!
);
```

---

### Section 7a — Webhook POST Body

When `webhook_url` is provided at session initiation and the session completes, the relay POSTs:

```json
{
  "event": "cdl.session.complete",
  "session_id": "uuid",
  "relying_party": "example-bank.com",
  "liveness_decision": "pass",
  "assurance_level": "IAL2",
  "attestation_token": "JWT string",
  "hardware_attested": true,
  "device_platform": "ios",
  "timestamp": "ISO8601"
}
```

Webhook delivery failures are logged but do not affect session outcome.

---

### GET /cdl/.well-known/jwks.json

Returns relay server EC P-256 public key for JWT verification.

```json
{
  "keys": [{
    "kty": "EC", "crv": "P-256",
    "kid": "relay-signing-key-v1",
    "use": "sig", "x": "...", "y": "..."
  }]
}
```

---

### GET /health

```json
{ "status": "ok", "redis": "connected", "version": "1.0.0" }
```

---

## 8. WEBSOCKET MESSAGE PROTOCOL

```
Browser connects: wss://{relay}/cdl/session/{session_id}/ws
Phone connects:   wss://{relay}/cdl/session/{session_id}/device
```

Authentication:
- Browser: `poll_token` as `Sec-WebSocket-Protocol` header
- Phone: `session_id` in URL (shared secret from QR code)

### Server → Browser

```json
{ "type": "phone_connected", "timestamp": "ISO8601" }
{ "type": "liveness_started", "timestamp": "ISO8601" }
{ "type": "processing_update", "progress": 0.5,
  "current_layer": "layer3_rppg", "timestamp": "ISO8601" }
{ "type": "session_complete", "liveness_decision": "pass",
  "assurance_level": "IAL2", "attestation_token": "JWT",
  "timestamp": "ISO8601" }
{ "type": "session_failed", "reason": "liveness_check_failed",
  "liveness_decision": "fail", "retry_allowed": true,
  "timestamp": "ISO8601" }
{ "type": "session_expired",
  "reason": "phone_did_not_connect|liveness_timeout",
  "timestamp": "ISO8601" }
{ "type": "error", "error_code": "ERR_*",
  "message": "string", "timestamp": "ISO8601" }
```

### Server → Phone

```json
{ "type": "session_acknowledged",
  "challenge_sequence": ["blink", "look_left", "smile"],
  "challenge_id": "uuid", "session_id": "uuid",
  "expires_at": "ISO8601", "timestamp": "ISO8601" }
{ "type": "session_invalid",
  "reason": "expired|not_found|already_complete",
  "timestamp": "ISO8601" }
{ "type": "attestation_received", "timestamp": "ISO8601" }
{ "type": "attestation_rejected",
  "error_code": "ERR_*", "timestamp": "ISO8601" }
```

### Phone → Server

```json
{ "type": "liveness_started", "timestamp": "ISO8601" }
{ "type": "processing_update", "progress": 0.4,
  "current_layer": "layer2_active_liveness", "timestamp": "ISO8601" }
```

### Browser → Server

```json
{ "type": "get_status" }
{ "type": "cancel_session" }
```

### WebSocket reconnect behaviour

The phone is permitted up to 3 WebSocket connections per session (to handle network drops).
On each new phone connection the server checks a per-session counter in Redis.
If the session is already in `phone_connected` or `processing` state the server re-sends
`session_acknowledged` so the phone can resume without the browser re-generating a QR.
If the counter exceeds 3 the server sends `session_invalid` with `reason: "too_many_reconnects"`
and closes the connection.

### retry_allowed semantics

`retry_allowed: true` in `session_failed` means the **user may try again** — it does NOT
mean the same session_id can be reused. The browser must call `POST /cdl/session/initiate`
to obtain a fresh session and display a new QR code. The failed session is discarded.
---

## 9. APPLE APP ATTEST INTEGRATION

App Attest runs in two phases.

### Phase 1 — Key Registration (Once Per Device Install)

```
iOS App                          Relay Server               Apple
  |                                   |                       |
  |-- generateKey() ----------------->|                       |
  |   returns keyId                   |                       |
  |                                   |                       |
  |-- POST /cdl/device/register ----->|                       |
  |   { platform: "ios", key_id }     |                       |
  |<-- { challenge: "base64" } -------|                       |
  |                                   |                       |
  |-- attestKey(keyId, challenge) --->                        |
  |   returns attestationObject       |                       |
  |                                   |                       |
  |-- POST /cdl/device/attest ------->|                       |
  |   { key_id, attestation }         |-- verify with Apple ->|
  |                                   |<-- result ------------|
  |<-- { device_token } --------------|                       |
  |   Store in Keychain               |                       |
```

iOS Implementation:
```swift
import DeviceCheck

class AppAttestService {
    private let service = DCAppAttestService.shared

    func registerKey() async throws -> String {
        guard service.isSupported else {
            throw CDLError.hardwareAttestationUnsupported
        }
        let keyId = try await service.generateKey()
        let challenge = try await relayClient.requestKeyChallenge(keyId: keyId)
        let challengeData = Data(base64Encoded: challenge)!
        let attestation = try await service.attestKey(
            keyId,
            clientDataHash: Data(SHA256.hash(data: challengeData))
        )
        let deviceToken = try await relayClient.registerDevice(
            keyId: keyId,
            attestation: attestation.base64EncodedString()
        )
        try KeychainService.store(keyId: keyId, deviceToken: deviceToken)
        return keyId
    }
}
```

### Phase 2 — Per-Session Assertion (Every Liveness Session)

iOS Implementation:
```swift
func generateAssertion(
    sessionId: String, challengeId: String, livenessDecision: String
) async throws -> String {
    let keyId = try KeychainService.retrieveKeyId()
    let clientData = (sessionId + challengeId + livenessDecision)
        .data(using: .utf8)!
    let clientDataHash = Data(SHA256.hash(data: clientData))
    let assertion = try await service.generateAssertion(
        keyId, clientDataHash: clientDataHash
    )
    return assertion.base64EncodedString()
    // Place in device_attestation field of attestation object
}
```

Server Verification:
```typescript
import * as cbor from 'cbor';
import { createHash, createVerify } from 'crypto';

async function verifyAppAttestAssertion(
  deviceAttestation: string,
  sessionId: string, challengeId: string, livenessDecision: string,
  keyId: string
): Promise<boolean> {

  const decoded = cbor.decodeFirstSync(
    Buffer.from(deviceAttestation, 'base64')
  );

  // Verify counter (replay prevention)
  const storedCounter = parseInt(
    await redis.get(`cdl:device:${keyId}:counter`) || '0'
  );
  const newCounter = decoded.authenticatorData.readUInt32BE(33);
  if (newCounter <= storedCounter) {
    throw new CDLError('ERR_APPLE_ATTEST_COUNTER');
  }

  // Verify clientDataHash binding
  const expectedHash = createHash('sha256')
    .update(sessionId + challengeId + livenessDecision)
    .digest();

  const signedData = Buffer.concat([decoded.authenticatorData, expectedHash]);
  const pubKey = await getStoredPublicKey(keyId);
  const verify = createVerify('SHA256');
  verify.update(signedData);
  if (!verify.verify(pubKey, decoded.signature)) {
    throw new CDLError('ERR_APPLE_ATTEST_INVALID');
  }

  await redis.set(`cdl:device:${keyId}:counter`, newCounter.toString());
  return true;
}
```

Full server-side Phase 1 verification follows Apple's documentation:
https://developer.apple.com/documentation/devicecheck/validating_apps_that_connect_to_your_server

During Phase 1 server verification, extract and store the leaf certificate public key:
```typescript
// After verifying certificate chain, store public key keyed by keyId:
await redis.set(`cdl:device:${keyId}:pubkey`, leafCertPublicKeyPEM, 'EX', 365*24*3600);
```

`getStoredPublicKey` implementation:
```typescript
export async function getStoredPublicKey(keyId: string): Promise<string> {
  const pubKey = await redis.get(`cdl:device:${keyId}:pubkey`);
  if (!pubKey) throw new CDLError('ERR_APPLE_ATTEST_INVALID');
  return pubKey;
}
```

When `/complete` is called for iOS, derive `keyId` from the attestation's `public_key` field:
```typescript
import { createHash } from 'crypto';
const keyId = createHash('sha256')
  .update(Buffer.from(attestation.public_key, 'base64'))
  .digest('hex');
```

### If App Attest Unavailable

```swift
guard DCAppAttestService.shared.isSupported else {
    // assurance_level = "software_only"
    // omit device_attestation field
    return
}
```
---

## 10. GOOGLE PLAY INTEGRITY INTEGRATION

### Flow

```
Android App                      Relay Server
  |                                   |
  |-- POST /integrity-nonce --------->|
  |<-- { nonce: "base64url" } --------|
  |                                   |
  |-- requestIntegrityToken(nonce) -->|
  |<-- integrityToken --------------  |
  |                                   |
  |  Place token in device_attestation|
  |  field of attestation object      |
```

Android Implementation:
```kotlin
import com.google.android.play.core.integrity.IntegrityManagerFactory

class PlayIntegrityService(private val context: Context) {

    suspend fun requestIntegrityToken(nonce: String): String {
        val integrityManager = IntegrityManagerFactory.create(context)
        val request = IntegrityTokenRequest.builder().setNonce(nonce).build()

        return suspendCoroutine { continuation ->
            integrityManager.requestIntegrityToken(request)
                .addOnSuccessListener { continuation.resume(it.token()) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
    }
}
```

Server Verification:
```typescript
import { google } from 'googleapis';

async function verifyPlayIntegrity(
  deviceAttestation: string,
  sessionId: string, challengeId: string, livenessDecision: string
): Promise<PlayIntegrityResult> {

  const auth = new google.auth.GoogleAuth({
    scopes: ['googleapis.com/auth/playintegrity']
  });
  const playintegrity = google.playintegrity({ version: 'v1', auth });

  const response = await playintegrity.v1.decodeIntegrityToken({
    packageName: process.env.GOOGLE_PLAY_PACKAGE_NAME!,
    requestBody: { integrityToken: deviceAttestation }
  });

  const verdict = response.data.tokenPayloadExternal!;

  // Verify nonce binding (replay prevention)
  const expectedNonce = base64url(sha256(
    sessionId + challengeId + livenessDecision +
    process.env.INTEGRITY_NONCE_SECRET!
  ));
  if (verdict.requestDetails?.nonce !== expectedNonce) {
    throw new CDLError('ERR_GOOGLE_INTEGRITY_NONCE');
  }

  // Require MEETS_DEVICE_INTEGRITY for IAL2
  const deviceVerdicts =
    verdict.deviceIntegrity?.deviceRecognitionVerdict || [];
  if (!deviceVerdicts.includes('MEETS_DEVICE_INTEGRITY')) {
    throw new CDLError('ERR_GOOGLE_INTEGRITY_LOW');
  }

  return {
    valid: true,
    meetsDevice: deviceVerdicts.includes('MEETS_DEVICE_INTEGRITY'),
    meetsStrong: deviceVerdicts.includes('MEETS_STRONG_INTEGRITY'),
    appRecognized: verdict.appIntegrity?.appRecognitionVerdict === 'PLAY_RECOGNIZED'
  };
}
```

### If Play Integrity Unavailable

```kotlin
try {
    val token = requestIntegrityToken(nonce)
    attestation.deviceAttestation = token
    attestation.assuranceLevel = "IAL2"
} catch (e: IntegrityServiceException) {
    attestation.assuranceLevel = "software_only"
    // omit device_attestation field
}
```

### Google Service Account Setup

1. Create service account in Google Cloud Console
2. Grant role: Service Account Token Creator
3. Enable Play Integrity API for the project
4. Download service account JSON key
5. Set `GOOGLE_APPLICATION_CREDENTIALS=/path/to/key.json`
6. Add key file to `.gitignore` — never commit it

---

## 11. ATTESTATION JWT

After verifying the attestation object, the relay server issues a JWT.
This is what the browser receives and what the relying party verifies.

### Generate Relay Signing Key

```bash
mkdir keys
openssl ecparam -name prime256v1 -genkey -noout -out keys/relay-private.pem
openssl ec -in keys/relay-private.pem -pubout -out keys/relay-public.pem
```

Never commit `keys/`. Add to `.gitignore`.

### JWT Header

```json
{ "alg": "ES256", "typ": "JWT", "kid": "relay-signing-key-v1" }
```

### JWT Claims

```json
{
  "iss": "relay.openliveness.dev",
  "sub": "{session_id}",
  "aud": "{relying_party}",
  "iat": 1234567890,
  "exp": 1234567890,
  "cdl_version": "1.0",
  "cdl_decision": "pass",
  "cdl_assurance": "IAL2",
  "cdl_device_platform": "ios",
  "cdl_challenge_id": "{challenge_id}",
  "cdl_layer_scores": {
    "layer2": 0.97,
    "layer3": 0.91,
    "layer4_lbp": 0.94,
    "layer4_fourier": 0.89,
    "layer5": 0.91
  },
  "cdl_hardware_attested": true,
  "cdl_attestation_hash": "{sha256 hex of full attestation object}",
  "cdl_low_light": false,
  "cdl_motion": false
}
```

JWT expires 15 minutes after issuance.

### Helper functions (relay/utils/crypto.ts)

```typescript
import { createHash } from 'crypto';

export function sha256hex(input: string): string {
  return createHash('sha256').update(input, 'utf8').digest('hex');
}

export function sha256bytes(input: string): Buffer {
  return createHash('sha256').update(input, 'utf8').digest();
}

export function base64url(buf: Buffer): string {
  return buf.toString('base64')
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
}

export function sha256base64url(input: string): string {
  return base64url(sha256bytes(input));
}
```
### Issuing the JWT

```typescript
import { SignJWT, importPKCS8 } from 'jose';

export async function issueAttestationJWT(
  attestation: CDLAttestation,
  session: CDLSession
): Promise<string> {
  const privateKey = await importPKCS8(
    readFileSync(process.env.JWT_PRIVATE_KEY_PATH!, 'utf8'),
    'ES256'
  );

  return new SignJWT({
    cdl_version: '1.0',
    cdl_decision: attestation.liveness_decision,
    cdl_assurance: attestation.assurance_level,
    cdl_device_platform: attestation.device_platform,
    cdl_challenge_id: attestation.challenge_id,
    cdl_layer_scores: {
      layer2: attestation.layer_scores.layer2_challenge_score,
      layer3: attestation.layer_scores.layer3_rppg_confidence,
      layer4_lbp: attestation.layer_scores.layer4_lbp_score,
      layer4_fourier: attestation.layer_scores.layer4_fourier_score,
      layer5: attestation.layer_scores.layer5_behavioral_score
    },
    cdl_hardware_attested: !!attestation.device_attestation,
    cdl_attestation_hash: sha256hex(JSON.stringify(attestation)),
    cdl_low_light: attestation.ambient_conditions?.low_light_detected ?? false,
    cdl_motion: attestation.ambient_conditions?.motion_detected ?? false
  })
  .setProtectedHeader({ alg: 'ES256', kid: 'relay-signing-key-v1' })
  .setIssuedAt()
  .setIssuer(process.env.RELAY_BASE_URL!)
  .setAudience(session.relying_party)
  .setSubject(session.session_id)
  .setExpirationTime('15m')
  .sign(privateKey);
}
```

---

## 12. ALGORITHM SPECIFICATIONS

### Layer 1 — Hardware Attestation

Not an algorithm. An OS-level API call.
- iOS: App Attest (Section 9)
- Android: Play Integrity (Section 10)

Output: `hardware_attested: true/false` and `assurance_level`

This runs after Layers 2-5 complete. Its output binds the hardware
proof to the liveness result.

---

### Layer 2 — Active Liveness (MediaPipe Face Mesh)

Library: MediaPipe Face Mesh, Apache 2.0
Landmarks: 478 3D landmarks per frame. Target: 30fps minimum.

**BLINK detection:**
```
EAR = distance(landmark[159], landmark[145]) / eye_width
  where eye_width = distance(landmark[33], landmark[133])

Blink detected when:
  EAR < 0.2 for 2+ consecutive frames
  followed by EAR > 0.25 (eye reopened)
```

**LOOK_LEFT detection:**
```
offset = (landmark[1].x - face_center_x) / face_width
  where face_center_x = (landmark[234].x + landmark[454].x) / 2

Look left = offset < -0.15 for 3+ consecutive frames
```

**LOOK_RIGHT:** offset > +0.15 for 3+ consecutive frames

**SMILE detection:**
```
neutral_mouth_width = calibrated from first 10 frames

Smile detected when:
  current_mouth_width > neutral_mouth_width * 1.2
  for 5+ consecutive frames
```

**NOD detection:**
```
Estimate pitch from top (landmark[10]) to bottom (landmark[152])

Nod detected when pitch changes by > 0.15 within 1 second
```

**Challenge scoring:**
```
challenge_sequence: 3 random non-repeating steps
must complete in order within 8 seconds

time_score:
  completed in < 3s:  1.0
  completed in 3-5s:  0.9
  completed in 5-8s:  0.7
  not completed:      0.0

layer2_challenge_score = all_completed ? time_score : 0.0
```

**Hard rule**: if challenge not completed → `liveness_decision = "fail"`
regardless of all other layer scores.

---

### Layer 3 — Physiological Signal (rPPG)

Algorithm: Plane-Orthogonal-to-Skin (POS), Wang et al. 2015.

Input: minimum 300 frames (10 seconds at 30fps).
Face regions: forehead + left cheek + right cheek from MediaPipe landmarks.

```python
def pos_rppg(rgb_signal):
    # rgb_signal: N x 3 array of mean R,G,B per frame from skin regions

    # Step 1: Normalize each channel
    mean_rgb = np.mean(rgb_signal, axis=0)
    normalized = rgb_signal / mean_rgb

    # Step 2: Project onto skin-orthogonal plane
    S1 = 3*normalized[:,0] - 2*normalized[:,1]
    S2 = 1.5*normalized[:,0] + normalized[:,1] - 1.5*normalized[:,2]

    # Step 3: Remove specular noise
    alpha = np.std(S1) / np.std(S2)
    P = S1 - alpha * S2

    # Step 4: Bandpass filter 0.7-3.0 Hz (42-180 BPM)
    P_filtered = bandpass_filter(P, low=0.7, high=3.0, fs=30)

    # Step 5: FFT to find dominant pulse frequency
    freqs = np.fft.rfftfreq(len(P_filtered), d=1.0/30)
    power = np.abs(np.fft.rfft(P_filtered))**2
    valid = (freqs >= 0.7) & (freqs <= 3.0)
    peak_freq = freqs[valid][np.argmax(power[valid])]
    bpm = peak_freq * 60

    # Step 6: Compute SNR for confidence
    peak_power = np.max(power[valid])
    noise_power = np.mean(power[valid])
    snr_db = 10 * np.log10(peak_power / max(noise_power, 1e-10))

    return {
        'bpm': bpm,
        'pulse_detected': snr_db > 3.0,
        'bpm_in_range': 50 <= bpm <= 110,
        'confidence': min(max((snr_db - 3.0) / 10.0, 0.0), 1.0)
    }
```

Low light handling (ambient_conditions.low_light_detected = true):
- Collect 20 seconds instead of 10
- Lower SNR threshold to 2.0 dB
- Multiply confidence by 0.7

`bandpass_filter` implementation (scipy):
```python
from scipy.signal import butter, filtfilt

def bandpass_filter(signal, low, high, fs):
    nyq = fs / 2.0
    b, a = butter(N=4, Wn=[low/nyq, high/nyq], btype='band')
    return filtfilt(b, a, signal)
```

---

### Layer 4 — Texture Analysis (LBP + Fourier)

Build on Silent-Face-Anti-Spoofing (Apache 2.0):
https://github.com/minivision-ai/Silent-Face-Anti-Spoofing

**LBP — print attack detection:**
```python
from skimage.feature import local_binary_pattern

def lbp_score(face_frame):
    face = cv2.resize(face_frame, (80, 80))
    gray = cv2.cvtColor(face, cv2.COLOR_BGR2GRAY)
    lbp = local_binary_pattern(gray, P=8, R=1, method='uniform')
    hist, _ = np.histogram(lbp.ravel(), bins=59, range=(0, 59))
    hist = hist.astype(float) / hist.sum()
    # SVM trained on MSU-MFSD dataset
    return float(svm_classifier.predict_proba([hist])[0][1])
    # Returns probability of live (1.0 = confident live texture)
```

**Fourier — screen replay detection:**
```python
def fourier_score(face_frame):
    gray = cv2.cvtColor(face_frame, cv2.COLOR_BGR2GRAY)
    dft_shift = np.fft.fftshift(np.fft.fft2(gray))
    magnitude = np.log(np.abs(dft_shift) + 1)

    rows, cols = gray.shape
    cr, cc = rows//2, cols//2

    # Screen displays have periodic patterns at pixel pitch frequency
    # Real faces have organic 1/f frequency distribution
    mask = np.zeros((rows, cols))
    for r in range(rows):
        for c in range(cols):
            d = np.sqrt((r-cr)**2 + (c-cc)**2)
            if rows//4 < d < rows//3:
                mask[r,c] = 1

    hf_energy = np.sum(magnitude * mask) / np.sum(mask)
    expected = estimate_organic_hf(magnitude)
    artifact_ratio = hf_energy / max(expected, 1e-10)

    # 1.0 = no artifacts (live), 0.0 = strong artifacts (replay)
    return float(1.0 / (1.0 + max(artifact_ratio - 1.0, 0) * 2))

def estimate_organic_hf(magnitude):
    """Baseline HF energy from mid-frequency ring. Real skin has 1/f rolloff."""
    rows, cols = magnitude.shape
    cr, cc = rows // 2, cols // 2
    lf_mask = np.zeros((rows, cols))
    for r in range(rows):
        for c in range(cols):
            d = np.sqrt((r - cr)**2 + (c - cc)**2)
            if rows // 8 < d < rows // 4:
                lf_mask[r, c] = 1
    lf_energy = np.sum(magnitude * lf_mask) / max(np.sum(lf_mask), 1e-10)
    return lf_energy / 4.0
```
---

### Layer 5 — Behavioral Biometrics

Reuses MediaPipe landmark data from Layer 2. No extra computation.

**Micro-movement score:**
```python
def micro_movement_score(landmarks_history):
    # 60 consecutive frames (~2 seconds at 30fps)
    stable = [6, 8, 9, 10]  # nose bridge — stable under expressions

    displacements = []
    for i in range(1, len(landmarks_history)):
        frame_disp = []
        for lid in stable:
            prev = landmarks_history[i-1][lid]
            curr = landmarks_history[i][lid]
            d = np.sqrt((curr.x-prev.x)**2 + (curr.y-prev.y)**2)
            frame_disp.append(d)
        displacements.append(np.mean(frame_disp))

    mean_disp = np.mean(displacements)

    # Real humans: mean_disp in [0.001, 0.015] from breathing/heartbeat
    if mean_disp < 0.0005:   return 0.1  # suspiciously still
    elif mean_disp < 0.001:  return 0.5  # borderline
    elif mean_disp <= 0.015: return 1.0  # natural movement
    else:                    return 0.6  # excessive motion
```

**Microsaccade detection:**
```python
def detect_microsaccades(landmarks_history, fps=30):
    # MediaPipe iris landmarks: 468-472 (left), 473-477 (right)
    velocities = []
    for i in range(1, len(landmarks_history)):
        left = np.mean([(landmarks_history[i][l].x,
                         landmarks_history[i][l].y)
                        for l in range(468, 473)], axis=0)
        prev_left = np.mean([(landmarks_history[i-1][l].x,
                              landmarks_history[i-1][l].y)
                             for l in range(468, 473)], axis=0)
        velocities.append(np.linalg.norm(left - prev_left))

    threshold = 0.005
    count = sum(1 for i in range(len(velocities)-1)
                if velocities[i] > threshold and
                   velocities[i+1] > threshold)

    # Real eyes: at least 2 involuntary saccades per 3-second window
    return count >= 2
```

---

### Layer 4 — Model training and mobile bundling

Layer 4 LBP scoring requires a trained SVM classifier. Training runs in Python
using the `core/` package. The trained model is exported to TFLite and bundled
into both mobile SDKs.

**Training pipeline:**
```
python core/models/train_texture_model.py   → outputs lbp_svm.joblib
python core/models/export_tflite.py         → outputs lbp_classifier.tflite
```

**Bundling:**
- iOS: copy `lbp_classifier.tflite` to `ios-sdk/Sources/OpenLiveness/Resources/`
- Android: copy `lbp_classifier.tflite` to `android-sdk/src/main/assets/`

**During development before training data is available**: stub `layer4_lbp_score`
and `layer4_fourier_score` to `0.85` and replace with real model inference once the
`.tflite` file is available.

---

### device_id_hash computation

The `device_id_hash` field must be the SHA-256 of the device identifier, hex-encoded.
The raw device identifier is never transmitted — hash only.

- iOS: `SHA-256(UIDevice.current.identifierForVendor.uuidString)`
- Android: `SHA-256(Settings.Secure.ANDROID_ID)`

---

### iOS platform requirements

The following must be configured before the SDK will function:

**Info.plist** — camera permission string (required by iOS, app will crash on camera access without it):
```xml
<key>NSCameraUsageDescription</key>
<string>Camera access is required to verify your identity.</string>
```

**Entitlements file** — App Attest capability:
```xml
<key>com.apple.developer.devicecheck.appattest-environment</key>
<string>production</string>
```
Use `development` for debug/simulator builds, `production` for App Store distribution.
App Attest does not function in the iOS Simulator — test on a real device.

---

### Android platform requirements

**AndroidManifest.xml** — minimum required permissions:
```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-feature android:name="android.hardware.camera.front" android:required="true" />
```

---

### QR code decoding (mobile SDK)

The QR image encodes a base64 string. After scanning:
1. Read `rawValue` from the barcode scanner result
2. Base64-decode `rawValue` → UTF-8 JSON string
3. JSON-parse the string → QRPayload object

Fields in QRPayload: `session_id`, `challenge_id`, `challenge_sequence`, `relay_ws`,
`relay_http`, `expires_at`, `version`.

---

### Ambient conditions detection

`low_light_detected`: true when ambient light sensor reads < 50 lux (iOS: AVCaptureDevice ISO > 800 as proxy; Android: `Sensor.TYPE_LIGHT < 50f`).

`motion_detected`: true when accelerometer magnitude deviates > 0.3g from 1g baseline (iOS: CMMotionManager; Android: `Sensor.TYPE_ACCELEROMETER`).

`front_camera_confirmed`: always true — SDK must enforce front-facing camera at session start and refuse to proceed if the user switches to rear camera.

---

## 13. ORCHESTRATOR

Runs on device. Combines layer scores into final liveness decision.

### Default Weights

```
layer2 (active liveness):  0.30
layer3 (rPPG):             0.25
layer4_lbp:                0.20
layer4_fourier:            0.15
layer5 (behavioral):       0.10
```

### Environmental Adjustments

```
low_light_detected = true:
  layer3 weight → 0.15
  layer2 weight → 0.35
  layer5 weight → 0.15

motion_detected = true:
  layer5 weight → 0.05
  layer3 weight → 0.30
```

### Decision

```python
def orchestrate(layer_scores, ambient_conditions):

    weights = get_weights(ambient_conditions)

    # Hard fail — challenge is mandatory
    if not layer_scores['layer2_challenge_completed']:
        return 'fail', 0.0

    weighted_score = (
        layer_scores['layer2_challenge_score']    * weights['layer2'] +
        layer_scores['layer3_rppg_confidence']    * weights['layer3'] +
        layer_scores['layer4_lbp_score']          * weights['layer4_lbp'] +
        layer_scores['layer4_fourier_score']       * weights['layer4_fourier'] +
        layer_scores['layer5_behavioral_score']   * weights['layer5']
    )

    if weighted_score >= 0.75:
        return 'pass', weighted_score
    elif weighted_score >= 0.60:
        return 'inconclusive', weighted_score
    else:
        return 'fail', weighted_score
```

### assurance_level Assignment

```
hardware attestation present + verified + decision == "pass" → "IAL2"
no hardware attestation     + decision == "pass"             → "software_only"
```

Note: `"IAL1"` appears in the attestation schema for forward compatibility but is NOT assigned by
the v1.0 orchestrator. Do not produce IAL1. Accept and pass through IAL1 in incoming objects.
---

## 14. REDIS KEY SCHEMA

| Key | Type | TTL | Value |
|-----|------|-----|-------|
| `cdl:session:{session_id}` | Hash | 24h | Full session fields |
| `cdl:session:{session_id}:state` | String | 24h | State string |
| `cdl:challenge:{challenge_id}:used` | String | 24h | `"1"` if used |
| `cdl:device:{key_id}:trusted` | String | 1 year | `"1"` |
| `cdl:device:{key_id}:counter` | String | 1 year | Counter integer |
| `cdl:device:{key_id}:challenge` | String | 5min | Phase 1 challenge |
| `cdl:session:{id}:integrity_nonce` | String | 5min | Play Integrity nonce |
| `cdl:ratelimit:init:{ip}` | String | 1min | Request count |
| `cdl:ratelimit:complete:{ip}` | String | 1min | Request count |

### Session Hash Fields

```typescript
interface CDLSession {
  session_id: string;
  state: 'initiated'|'phone_connected'|'processing'|'complete'|'failed'|'expired';
  relying_party: string;
  assurance_required: 'IAL2'|'IAL1'|'any';
  challenge_id: string;
  challenge_sequence: string;    // JSON stringified array
  created_at: string;            // ISO8601
  updated_at: string;
  expires_at: string;
  poll_token: string;
  liveness_decision: string | null;
  assurance_level: string | null;
  attestation_hash: string | null;
  jwt_issued: string | null;
  webhook_url: string | null;
}
```

---

## 15. RATE LIMITING

All limits use Redis sliding window counters.
Returns HTTP 429 with `Retry-After` header on breach.

| Endpoint | Limit | Window |
|----------|-------|--------|
| POST /cdl/session/initiate | 10 | per minute per IP |
| POST /cdl/session/*/complete | 20 | per minute per IP |
| POST /cdl/device/register | 5 | per minute per IP |
| POST /cdl/device/attest | 5 | per minute per IP |
| GET /cdl/session/*/status | 60 | per minute per IP |
| Attestations per session | 1 | per session |
| Phone connections per session | 3 | per session (allow reconnect) |

---

## 16. ERROR CODES

All errors:
```json
{ "error": "ERR_CODE", "message": "Human readable", "detail": "optional" }
```

| Code | HTTP | Meaning |
|------|------|---------|
| ERR_SESSION_NOT_FOUND | 404 | session_id does not exist |
| ERR_SESSION_EXPIRED | 410 | Past 5-minute expiry |
| ERR_SESSION_ALREADY_COMPLETE | 409 | Already has result |
| ERR_SESSION_WRONG_STATE | 409 | Invalid state transition |
| ERR_INVALID_POLL_TOKEN | 401 | Browser poll_token invalid |
| ERR_INVALID_SIGNATURE | 400 | ES256 signature failed |
| ERR_SIGNATURE_MISSING | 400 | signature field absent |
| ERR_PUBLIC_KEY_MISSING | 400 | public_key field absent |
| ERR_SCHEMA_INVALID | 400 | Fails JSON schema |
| ERR_TIMESTAMP_STALE | 400 | > 120 seconds old |
| ERR_TIMESTAMP_FUTURE | 400 | > 30 seconds in future |
| ERR_CHALLENGE_MISMATCH | 400 | challenge_response wrong |
| ERR_CHALLENGE_REUSED | 400 | challenge_id already used |
| ERR_SESSION_ID_MISMATCH | 400 | session_id mismatch |
| ERR_VERSION_UNSUPPORTED | 400 | version not "1.0" |
| ERR_APPLE_ATTEST_INVALID | 400 | App Attest failed |
| ERR_APPLE_ATTEST_COUNTER | 400 | Counter replay detected |
| ERR_GOOGLE_INTEGRITY_INVALID | 400 | Play Integrity invalid |
| ERR_GOOGLE_INTEGRITY_NONCE | 400 | Nonce mismatch |
| ERR_GOOGLE_INTEGRITY_LOW | 400 | Device integrity too low |
| ERR_ATTEST_REQUIRED | 400 | IAL2 required but no attestation |
| ERR_RATE_LIMITED | 429 | Too many requests |
| ERR_RELAY_UNAVAILABLE | 503 | Redis unavailable |
| ERR_INTERNAL | 500 | Unexpected error |

### Mobile SDK Error Codes (shown to user)

| Code | User Message |
|------|-------------|
| CDL_CAMERA_PERMISSION | "Camera access required. Allow in Settings." |
| CDL_HARDWARE_UNSUPPORTED | Continue with software_only assurance |
| CDL_CHALLENGE_TIMEOUT | "Timed out. Please try again." |
| CDL_LOW_LIGHT | "Move to better lighting." |
| CDL_MOTION | "Hold phone still." |
| CDL_SESSION_EXPIRED | "Code expired. Please refresh." |
| CDL_NETWORK_ERROR | "Check internet connection." |

---

## 17. ENVIRONMENT VARIABLES

```bash
# Server
PORT=3000
NODE_ENV=development
RELAY_BASE_URL=localhost

# Redis
REDIS_URL=redis://localhost:6379
REDIS_PASSWORD=

# JWT Signing
JWT_PRIVATE_KEY_PATH=./keys/relay-private.pem
JWT_PUBLIC_KEY_PATH=./keys/relay-public.pem

# Apple App Attest
APPLE_TEAM_ID=YOUR_APPLE_TEAM_ID
APPLE_APP_BUNDLE_ID=dev.openliveness.example

# Google Play Integrity
GOOGLE_APPLICATION_CREDENTIALS=./keys/google-service-account.json
GOOGLE_PLAY_PACKAGE_NAME=dev.openliveness.example

# Security
INTEGRITY_NONCE_SECRET=random-32-byte-hex-string

# Rate Limiting
SESSION_EXPIRY_MINUTES=5
ATTESTATION_MAX_AGE_SECONDS=120
```

---

## 18. DOCKER AND LOCAL DEVELOPMENT

### docker-compose.yml

```yaml
version: '3.8'
services:
  relay:
    build: .
    ports:
      - "3000:3000"
    environment:
      - REDIS_URL=redis://redis:6379
      - JWT_PRIVATE_KEY_PATH=/keys/relay-private.pem
      - RELAY_BASE_URL=localhost
      - NODE_ENV=development
      - INTEGRITY_NONCE_SECRET=dev-secret-change-in-production
    volumes:
      - ./keys:/keys:ro
    depends_on:
      redis:
        condition: service_healthy

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5
```

### Dockerfile (relay/Dockerfile)

```dockerfile
FROM node:20-alpine AS builder
WORKDIR /app
COPY package.json pnpm-lock.yaml ./
RUN npm install -g pnpm && pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

FROM node:20-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production
COPY --from=builder /app/dist ./dist
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/package.json ./
EXPOSE 3000
CMD ["node", "dist/server.js"]
```

### relay/package.json

```json
{
  "name": "@openliveness/relay",
  "version": "0.1.0",
  "scripts": {
    "dev": "tsx watch src/server.ts",
    "build": "tsc --outDir dist",
    "start": "node dist/server.js",
    "test": "vitest run",
    "test:watch": "vitest",
    "lint": "eslint src --ext .ts"
  },
  "dependencies": {
    "fastify": "^4.0.0",
    "ws": "^8.0.0",
    "@fastify/websocket": "^8.0.0",
    "ioredis": "^5.0.0",
    "jose": "^5.0.0",
    "zod": "^3.0.0",
    "pino": "^8.0.0",
    "@fastify/rate-limit": "^9.0.0",
    "@fastify/helmet": "^11.0.0",
    "uuid": "^9.0.0",
    "cbor": "^9.0.0",
    "googleapis": "^140.0.0"
  },
  "devDependencies": {
    "typescript": "^5.0.0",
    "tsx": "^4.0.0",
    "vitest": "^1.0.0",
    "supertest": "^6.0.0",
    "@types/node": "^20.0.0",
    "@types/ws": "^8.0.0",
    "@types/uuid": "^9.0.0"
  }
}
```

Start local development:
```bash
cd relay
docker-compose up
```
---

## 19. WEB SDK

### Public API

```typescript
export interface CDLOptions {
  relayUrl: string;
  assuranceRequired?: 'IAL2' | 'IAL1' | 'any';
  onQRReady?: (qrDataUrl: string) => void;
  onPhoneConnected?: () => void;
  onProcessing?: (progress: number, layer: string) => void;
  timeoutMs?: number;           // default 300000 (5 minutes)
}

export interface CDLResult {
  decision: 'pass' | 'fail' | 'inconclusive';
  assuranceLevel: 'IAL2' | 'IAL1' | 'software_only';
  token: string;                // JWT for relying party to verify
  sessionId: string;
}

export async function verify(options: CDLOptions): Promise<CDLResult>
```

### Usage — Vanilla JS

```typescript
import { verify } from '@openliveness/web';

const result = await verify({
  relayUrl: 'relay.openliveness.dev',
  assuranceRequired: 'IAL2',
  onQRReady: (dataUrl) => {
    document.getElementById('qr').src = dataUrl;
  },
  onPhoneConnected: () => {
    document.getElementById('status').textContent = 'Phone connected...';
  }
});

// result.decision === 'pass'
// result.assuranceLevel === 'IAL2'
// result.token → send to your backend for verification
```

### Usage — React

```tsx
import { LivenessWidget } from '@openliveness/web/react';

function KYCPage() {
  return (
    <LivenessWidget
      relayUrl="relay.openliveness.dev"
      assuranceRequired="IAL2"
      onComplete={(result) => {
        // result.token → send to backend
      }}
      onError={(err) => console.error(err)}
    />
  );
}
```

WebSocket is preferred for real-time updates. Falls back to HTTP
polling with exponential backoff if WebSocket is unavailable.

HTTP polling backoff parameters:
```
Initial interval:  1000 ms
Maximum interval:  5000 ms
Backoff multiplier: 1.5×
```
Sequence: 1s → 1.5s → 2.25s → 3.4s → 5s → 5s → 5s …

---

## 20. VERIFY PACKAGE

### Public API

```typescript
import { verifyAttestationJWT } from '@openliveness/verify';

const result = await verifyAttestationJWT(jwt, {
  relayUrl: 'relay.openliveness.dev',
  audience: 'example-bank.com',
  requiredAssurance: 'IAL2',
  requiredDecision: 'pass'
});

if (result.valid) {
  // result.sessionId
  // result.assuranceLevel === 'IAL2'
  // result.hardwareAttested === true
  // result.layerScores
  // result.devicePlatform
}
```

Fetches JWKS from `{relayUrl}/cdl/.well-known/jwks.json` to verify
JWT signature. Self-hosters configure a custom `relayUrl`.

Runs in Node.js and browser. Zero server dependencies.

---

## 21. SECURITY PROPERTIES

The relay server MUST enforce all of these checks for every attestation.

```
1.  Schema validation — object matches attestation-schema.json
2.  Session binding — session_id in object matches URL param
3.  Session state — exists, not expired, valid state transition
4.  Challenge binding — challenge_id matches stored, not reused,
                        challenge_response matches sequence
5.  Timestamp freshness — not > 120s old, not > 30s future
6.  Signature — ES256 verification using public_key field
7.  Hardware attestation — App Attest or Play Integrity verification
8.  Assurance check — reject if IAL2 required and attestation absent
9.  Mark challenge as used
10. Issue JWT
```

### Security Checklist (Must Pass Before Any Release)

- [ ] Tampered attestation object is rejected
- [ ] Same challenge_id cannot be submitted twice
- [ ] Attestation older than 120 seconds is rejected
- [ ] Wrong poll_token cannot access session status
- [ ] Phone cannot submit to an already-complete session
- [ ] 11th request per minute returns 429
- [ ] Play Integrity token from a different session is rejected
- [ ] App Attest assertion with replayed counter is rejected
- [ ] JWKS endpoint returns valid EC P-256 key
- [ ] JWT issued for domain A cannot be verified against domain B

---

## 22. THREAT MODEL

### Attacks Defeated

| Attack | Defeated By |
|--------|-------------|
| Photo attack (printed face) | Layer 4 LBP detects flat texture |
| Screen replay (video on tablet held up) | Layer 4 Fourier + Layer 3 no pulse |
| Pre-recorded video injection via virtual camera (mobile) | Layer 1 hardware attestation — virtual camera cannot forge signed proof |
| Static AI-generated image | Layer 4 Fourier + Layer 3 no pulse |
| Virtual camera driver on phone | Rooted device fails hardware attestation |
| Session replay (reuse old attestation) | Timestamp freshness + single-use challenge_id |
| Attestation forgery without device | ES256 requires private key in Secure Enclave |

### Residual Attack Surface

**Real-time face-swap with cooperating accomplice**: an attacker using
real-time face-swap software on a genuine unmodified phone, with a
human accomplice physically present, could theoretically pass liveness
detection. This attack is not automated, not scalable, and requires
the accomplice to accept criminal liability. It must also defeat a
separate face-matching step — meaning the face-swap must reproduce
a specific target face in real-time on mobile hardware while
completing a randomized challenge sequence. No freely available tool
does this reliably today. This is the same threat profile as physical
identity fraud — possible in theory, rare and expensive in practice.

**Browser-only liveness (no CDL)**: if a relying party accepts
browser-only liveness without requiring the CDL mobile handoff,
virtual camera injection remains possible. CDL solves this by
requiring mobile verification.

### What This System Does Not Claim

openliveness proves a live human is present. It does NOT prove that
human is the right person. A separate document verification and face
matching step is required for full KYC.

---

## 23. BENCHMARK HARNESS

Implements ISO/IEC 30107-3 Presentation Attack Detection metrics.

### Metrics

```python
def compute_metrics(scores, labels):
    """
    scores: [float] — 0.0 to 1.0, higher = more likely live
    labels: [int]   — 1 = live, 0 = attack

    APCER: Attack Presentation Classification Error Rate
           = fraction of attacks that pass as live (lower is better)

    BPCER: Bona Fide Presentation Classification Error Rate
           = fraction of live people rejected (lower is better)

    HTER:  (APCER + BPCER) / 2  (lower is better)

    AUC:   Area under ROC curve  (higher is better, 1.0 = perfect)
    """
    from sklearn.metrics import roc_auc_score

    best_threshold, best_hter = 0.5, 1.0
    for t in np.arange(0.0, 1.0, 0.01):
        preds = [1 if s >= t else 0 for s in scores]
        attack_idx = [i for i,l in enumerate(labels) if l == 0]
        live_idx   = [i for i,l in enumerate(labels) if l == 1]
        apcer = sum(preds[i]==1 for i in attack_idx) / len(attack_idx)
        bpcer = sum(preds[i]==0 for i in live_idx)   / len(live_idx)
        hter  = (apcer + bpcer) / 2
        if hter < best_hter:
            best_hter, best_threshold = hter, t

    preds = [1 if s >= best_threshold else 0 for s in scores]
    attack_idx = [i for i,l in enumerate(labels) if l == 0]
    live_idx   = [i for i,l in enumerate(labels) if l == 1]

    return {
        'threshold': best_threshold,
        'apcer': sum(preds[i]==1 for i in attack_idx) / len(attack_idx),
        'bpcer': sum(preds[i]==0 for i in live_idx)   / len(live_idx),
        'hter':  best_hter,
        'auc':   roc_auc_score(labels, scores)
    }
```

### Supported Datasets (publicly available for research)

| Dataset | Videos | Attack Types |
|---------|--------|-------------|
| MSU-MFSD | 280 | Print, replay |
| REPLAY-ATTACK | 1,300 | Print, replay |
| OULU-NPU | 4,950 | Print, replay, digital |

---

## 24. TESTING STRATEGY

### Unit Tests — relay/

```
session initiation creates correct fields
challenge sequence is 3 non-repeating steps
invalid request body returns 400
correct state for each session state
wrong poll_token returns 401
valid attestation returns 200
invalid signature returns ERR_INVALID_SIGNATURE
stale timestamp returns ERR_TIMESTAMP_STALE
reused challenge returns ERR_CHALLENGE_REUSED
expired session returns ERR_SESSION_EXPIRED
IAL2 required but no attestation returns ERR_ATTEST_REQUIRED
rate limiting triggers on 11th request per minute
```

### Unit Tests — verify/

```
valid JWT is accepted
wrong audience is rejected
expired JWT is rejected
wrong issuer is rejected
insufficient assurance returns valid: false
fail decision returns valid: false
valid ES256 signature is accepted
tampered object is rejected
canonical JSON sorts keys correctly
```

### Integration Test — End-to-End

```typescript
const session = await client.post('/cdl/session/initiate', {
  relying_party: 'test.example.com',
  assurance_required: 'IAL2'
});

const mockAttestation = buildMockAttestation({
  session_id: session.session_id,
  challenge_id: session.challenge_id,
  challenge_response: session.challenge_sequence.join(','),
  liveness_decision: 'pass',
  // ... all fields per schema
});
mockAttestation.signature = signWithTestKey(mockAttestation);

await client.post(
  `/cdl/session/${session.session_id}/complete`,
  mockAttestation
);

const status = await client.get(
  `/cdl/session/${session.session_id}/status`,
  { headers: { Authorization: `Bearer ${session.poll_token}` } }
);

assert(status.state === 'complete');
assert(status.liveness_decision === 'pass');
assert(status.attestation_token !== null);
```

---

## 25. ALGORITHM CHOICES

All algorithms are selected from academic publications or
Apache 2.0 / BSD licensed open source libraries.

| Layer | Algorithm | Source | License |
|-------|-----------|--------|---------|
| 1 | Platform hardware attestation | Android Play Integrity API, iOS App Attest | OS-provided |
| 2 | Face landmark tracking | MediaPipe Face Mesh | Apache 2.0 |
| 3 | Physiological signal extraction | POS algorithm, Wang et al. 2015, Radboud University | Academic |
| 4 | Texture analysis (LBP) | Chingovska et al. 2012, via Silent-Face | Apache 2.0 |
| 4 | Frequency analysis (Fourier) | Standard signal processing | No IP |
| 5 | Micro-movement analysis | MediaPipe landmarks, temporal analysis | Apache 2.0 |
| 5 | Microsaccade detection | MediaPipe iris landmarks | Apache 2.0 |

---

## 26. GLOSSARY

| Term | Definition |
|------|-----------|
| CDL | Cross-Device Liveness — the protocol defined by this project |
| Attestation object | Signed JSON from phone after liveness — the only data crossing the network |
| Attestation JWT | JWT issued by relay to browser after verifying attestation |
| Relying party | The service (bank, app) that initiated the liveness check |
| IAL2 | Identity Assurance Level 2 (NIST SP 800-63-3) — with hardware-attested liveness |
| IAL1 | Identity Assurance Level 1 — software liveness only |
| software_only | No hardware attestation available on this device |
| App Attest | Apple API proving frames came from genuine unmodified Apple device camera |
| Play Integrity | Google API proving app runs on genuine unmodified Android device |
| APCER | Attack Presentation Classification Error Rate — attacks passing as live |
| BPCER | Bona Fide Presentation Classification Error Rate — live people rejected |
| HTER | Half Total Error Rate — (APCER + BPCER) / 2 |
| AUC | Area Under ROC Curve |
| rPPG | Remote photoplethysmography — detecting pulse from skin color in video |
| POS | Plane-Orthogonal-to-Skin — the rPPG algorithm used in this project |
| LBP | Local Binary Pattern — texture analysis algorithm |
| MediaPipe | Google ML framework for face landmark detection (Apache 2.0) |
| Face Mesh | MediaPipe model providing 478 3D facial landmarks |
| Secure Enclave | Apple's dedicated security chip for key storage |
| Hardware Keystore | Android equivalent of Secure Enclave |
| ES256 | ECDSA with P-256 curve and SHA-256 — signing algorithm used throughout |
| Injection attack | Attack that bypasses the physical camera by feeding synthetic video at the software layer |
| KYC | Know Your Customer — regulated identity verification process |