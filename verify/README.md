# @openliveness/verify

Check an openliveness attestation JWT on your backend. Works in Node and in the browser.

```bash
pnpm add @openliveness/verify
```

```ts
import { verifyAttestationJWT } from '@openliveness/verify';

const result = await verifyAttestationJWT(jwt, {
  relayUrl: 'relay.openliveness.dev',
  audience: 'example-bank.com',   // your relying party id
  requiredAssurance: 'IAL2',
  requiredDecision: 'pass',
});

if (!result.valid) throw new Error(result.reason);
// result.sessionId, result.assuranceLevel, result.hardwareAttested, result.layerScores
```

It never throws for a bad token. It returns `{ valid: false, reason }`.
The signature is checked against the relay's JWKS, along with issuer, audience and expiry.

Options: `issuer` (defaults to the hostname of `relayUrl`), `maxAgeSeconds` for a tighter age limit,
`jwks` to supply keys yourself instead of fetching them.
