# @openliveness/web

> Browser SDK for [openliveness](https://github.com/ramuvenky/openliveness) — proves there is a real, live human in front of the camera, without sending any biometric data to a server.

In a world where deepfakes and AI-generated video are becoming indistinguishable from reality, this package lets your web app verify that the person on the other end of a camera is actually a live human — **without the user's face, video, or any biometric features ever leaving their phone**.

## Why liveness without the cloud

Browser-only liveness is broken. Virtual-camera software (OBS, ManyCam) lets an attacker feed AI-generated video into any `getUserMedia()` call, and the browser has no way to tell real-camera footage from injected footage. This is the dominant fraud vector against remote liveness checks today.

Every commercial fix ships your users' faces to the vendor's cloud. **openliveness does not.** All biometric computation runs on the user's phone, which cryptographically proves its camera is genuine using hardware attestation (Apple App Attest / Google Play Integrity). Your backend — and this package — only ever see a signed attestation JWT.

## How it works

```
Browser shows QR  →  User scans with phone  →  Phone runs liveness locally
                                            →  Phone signs result with hardware key
                                            →  Server verifies proof
                                            →  Browser session complete
```

This package handles the browser half: display the QR, wait for the phone to finish, hand you the JWT.

## Install

```bash
pnpm add @openliveness/web
# or
npm install @openliveness/web
```

Zero runtime dependencies beyond `qrcode`. Works with any framework or no framework.

## Usage

```ts
import { verify } from '@openliveness/web';

const result = await verify({
  relayUrl: 'localhost:3000',     // your relay — see "Running the relay" below
  assuranceRequired: 'IAL2',      // 'IAL2' | 'software_only'
  onQRReady: (dataUrl) => (document.getElementById('qr').src = dataUrl),
  onPhoneConnected: () => setStatus('Follow the steps on your phone'),
});

// result.decision        === 'pass'
// result.assuranceLevel  === 'IAL2'
// result.token           === JWT — forward it to your backend
```

Send `result.token` to your backend and verify it with [`@openliveness/verify`](https://www.npmjs.com/package/@openliveness/verify). **Never trust the browser result on its own** — the JWT is the cryptographic proof; everything else is UI state.

### React

```tsx
import { LivenessWidget } from '@openliveness/web/react';

<LivenessWidget
  relayUrl="localhost:3000"
  onComplete={(r) => send(r.token)}
/>
```

### Errors

If the phone reports a failed check, `verify()` rejects with `CDLRetryableError` — call `verify()` again for a fresh session.

Transport: WebSocket first, falling back to HTTP polling (1s, growing 1.5x up to 5s) if the socket fails.

## Running the relay

This package talks to a small Node service — the relay — that brokers the browser↔phone handshake and issues the JWT. **You run the relay yourself.** It is open source and ships in the same repo:

```bash
git clone https://github.com/ramuvenky/openliveness.git
cd openliveness/relay && docker compose up -d
# relay listens on http://localhost:3000
```

Point `relayUrl` at wherever you deploy it. The relay never sees biometric data — only signed scores.

## The complete picture

openliveness is four pieces working together. This package is one of them:

| Where | Package | Role |
|-------|---------|------|
| Browser | **`@openliveness/web`** *(this package)* | Shows QR, waits for the phone |
| Phone | [Android SDK](https://jitpack.io/#ramuvenky/openliveness) · iOS SDK (coming soon) | Runs the five liveness layers, signs the result |
| Relay | [`relay/`](https://github.com/ramuvenky/openliveness/tree/main/relay) | Brokers the handshake, issues the JWT |
| Server | [`@openliveness/verify`](https://www.npmjs.com/package/@openliveness/verify) | Verifies the JWT on your backend |

## What you don't ship by using this

- Video, photos, face embeddings, or any biometric features leaving the user's device
- A dependency on anyone's cloud (relay is self-host; phone runs locally)
- GDPR Article 9 scope for your backend — you never process special-category data

## Who this is for

- **Banks and financial institutions** — IAL2-grade liveness for KYC onboarding without cloud biometric processing
- **Healthcare** — patient verification where biometric data must stay on-device (HIPAA, GDPR)
- **Government and public sector** — remote liveness proofing with auditable, open-source algorithms
- **KYC and identity platforms** — add liveness without becoming a biometric data processor
- **Any application** — where proving human presence must not compromise privacy

## Documentation

- [Project README](https://github.com/ramuvenky/openliveness#readme) — overview and design principles
- [Technical specification](https://github.com/ramuvenky/openliveness/blob/main/docs/Spec.md) — full 28-section protocol reference
- [Threat model](https://github.com/ramuvenky/openliveness/blob/main/protocol/ThreatModel.md) — what this protects against, what it does not
- [Attestation schema](https://github.com/ramuvenky/openliveness/blob/main/protocol/AttestationSchema.json) — JWT payload contract

## Contributing

Issues and pull requests welcome at [github.com/ramuvenky/openliveness](https://github.com/ramuvenky/openliveness). See [CONTRIBUTING.md](https://github.com/ramuvenky/openliveness/blob/main/CONTRIBUTING.md) — the iOS SDK port is currently the highest-value contribution.

Report security issues privately per [SECURITY.md](https://github.com/ramuvenky/openliveness/blob/main/SECURITY.md).

## License

Apache 2.0
