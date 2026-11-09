# @openliveness/verify

> Server-side JWT verification for [openliveness](https://github.com/ramuvenky/openliveness) — the backend half of a liveness check that never touches the user's face.

In an era where AI-generated video is indistinguishable from real footage, proving a real, live human was present at the other end of a camera has never been more important — or more fraught for privacy. openliveness solves both: the biometric work happens on the user's phone, signed by a hardware-backed key; your backend only ever sees a cryptographically signed verdict. **This package is where you check that verdict.**

## Why liveness without a biometric vendor

Commercial liveness historically meant shipping users' faces to a vendor's cloud. That's a privacy burden for them and a biometric-data-processor burden for you under GDPR Article 9, HIPAA, and similar regimes.

openliveness moves the computation to the device. The user's phone proves its camera is genuine using Apple App Attest / Google Play Integrity, runs five independent anti-spoof layers locally, and signs the decision with a hardware-backed EC key. **Your backend never sees video, never sees a face image, never sees face embeddings.** Just a JWT carrying per-layer scores and a signature over them.

This package is where that JWT gets checked.

## Install

```bash
pnpm add @openliveness/verify
# or
npm install @openliveness/verify
```

Works in Node, in edge runtimes (Cloudflare Workers, Vercel Edge, Deno), and in browsers. Zero native dependencies.

## Usage

```ts
import { verifyAttestationJWT } from '@openliveness/verify';

const result = await verifyAttestationJWT(token, {
  relayUrl: 'localhost:3000',     // the relay that issued the JWT
  audience: 'your-domain.com',    // your relying party id
  requiredAssurance: 'IAL2',      // or 'software_only' to accept either
  requiredDecision: 'pass',
});

if (!result.valid) {
  // result.reason — e.g. 'Invalid Compact JWS', 'expired', 'audience mismatch'
  return reject(result.reason);
}

// result.sessionId        — the CDL session id
// result.assuranceLevel   — 'IAL2' | 'software_only'
// result.hardwareAttested — true if Play Integrity / App Attest succeeded
// result.layerScores      — per-layer liveness scores for your audit log
```

`verifyAttestationJWT` **never throws for a bad token** — it returns `{ valid: false, reason }` so your error path stays predictable.

## What it checks

- Signature against the relay's JWKS (fetched from `${relayUrl}/.well-known/jwks.json`)
- Issuer (defaults to the hostname of `relayUrl`; override with `issuer` option)
- Audience you passed in
- Expiry (`exp` claim)
- Optional: `maxAgeSeconds` for a tighter age limit than `exp`
- Optional: `requiredAssurance` and `requiredDecision` gates

Pass `jwks` directly instead of `relayUrl` if you want to supply keys yourself — useful for tests or air-gapped setups.

## The complete picture

openliveness is four pieces working together. This package is one of them:

| Where | Package | Role |
|-------|---------|------|
| Browser | [`@openliveness/web`](https://www.npmjs.com/package/@openliveness/web) | Shows QR, waits for the phone |
| Phone | [Android SDK](https://jitpack.io/#ramuvenky/openliveness) · iOS SDK (coming soon) | Runs the five liveness layers, signs the result |
| Relay | [`relay/`](https://github.com/ramuvenky/openliveness/tree/main/relay) | Brokers the handshake, issues the JWT |
| Server | **`@openliveness/verify`** *(this package)* | Verifies the JWT |

## Running the relay

This package verifies JWTs issued by your relay. **You run the relay yourself** — it is a Node service in the same repo:

```bash
git clone https://github.com/ramuvenky/openliveness.git
cd openliveness/relay && docker compose up -d
# relay listens on http://localhost:3000, serves JWKS at /.well-known/jwks.json
```

`verifyAttestationJWT` fetches public keys from `${relayUrl}/.well-known/jwks.json` and caches them.

## What you don't process by using this

Because biometric computation happens on the user's device, your backend:

- Never sees video, photos, or face embeddings
- Never stores biometric features
- Does not fall under GDPR Article 9 (special category data) for this flow

The JWT carries liveness scores and a cryptographic proof — nothing from which a face could be reconstructed.

## Who this is for

- **Banks and financial institutions** — IAL2-grade liveness for KYC onboarding (NIST SP 800-63-3) without cloud biometric processing
- **Healthcare** — patient verification compatible with HIPAA and GDPR
- **Government and public sector** — remote liveness proofing with auditable, open-source algorithms
- **KYC and identity platforms** — add liveness without becoming a biometric data processor
- **Any backend** — where proving human presence must not compromise privacy

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
