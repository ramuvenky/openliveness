# @openliveness/verify

Check an openliveness attestation JWT on your backend. Works in Node and in the browser.

```bash
pnpm add @openliveness/verify
```

```ts
import { verifyAttestationJWT } from '@openliveness/verify';

const result = await verifyAttestationJWT(jwt, {
  relayUrl: 'localhost:3000',     // your relay host; used to fetch JWKS
  audience: 'example-bank.com',   // your relying party id
  requiredAssurance: 'IAL2',
  requiredDecision: 'pass',
});

if (!result.valid) throw new Error(result.reason);
// result.sessionId, result.assuranceLevel, result.hardwareAttested, result.layerScores
```

The relay is the Node server that issued the JWT — you run it yourself alongside your app
([openliveness/relay](https://github.com/ramuvenky/openliveness/tree/main/relay)).
`verifyAttestationJWT` fetches its public keys from `/.well-known/jwks.json` on that relay.

It never throws for a bad token. It returns `{ valid: false, reason }`.
The signature is checked against the relay's JWKS, along with issuer, audience and expiry.

Options: `issuer` (defaults to the hostname of `relayUrl`), `maxAgeSeconds` for a tighter age limit,
`jwks` to supply keys yourself instead of fetching them.
