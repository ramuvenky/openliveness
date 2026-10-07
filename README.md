# openliveness, server side components

The server side components hold the relay server, the JWT verification library, the browser SDK and a demo.
The phone SDKs (`ios-sdk/`, `android-sdk/`) and the algorithms (`core/`, `benchmark/`) live on the mobile branch.

```
relay/      Fastify + WebSocket relay, issues attestation JWTs
verify/     @openliveness/verify, check a JWT on your backend
web-sdk/    @openliveness/web, QR display and session tracking in the browser
examples/   bank-kyc-demo, the whole flow end to end
```

## Run it locally

You need Node 20+, pnpm and Redis (or Docker).

```bash
pnpm install
pnpm build
pnpm test

cd relay
sh gen-keys.sh                 # creates keys/ (git ignored)
cp .env.example .env           # then export the variables, or use docker compose
docker compose up              # relay on :3000 with Redis
```

Then in another terminal:

```bash
cd examples/bank-kyc-demo
RELAY_URL=http://<your-lan-ip>:3000 pnpm dev   # demo on :4000
```

Use your LAN address and not `localhost` when a phone has to reach the relay.

## Contract with the phone SDKs

* QR text is the base64 of the JSON payload (`qr_payload` from `/cdl/session/initiate`).
* `relay_http` and `relay_ws` in that payload include the scheme (`http://...`, `ws://...`).
* The phone signs SHA-256 over the canonical JSON of every field except `signature`
  (keys sorted, no whitespace). Raw `r||s` and DER signatures are both accepted,
  base64 and base64url are both accepted.
* The Play Integrity nonce is requested before liveness runs, so it covers session and challenge only.

## Status

Verified here: unit tests, a Redis backed end to end test, and a live run of
relay, simulated phone and `verify`. Not yet verified: App Attest and Play Integrity
against real Apple and Google responses (needs real devices and credentials).
Run the relay with `HARDWARE_ATTESTATION=stub` until then.
