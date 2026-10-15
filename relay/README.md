# @openliveness/relay

Relay between a browser and a phone. It creates sessions, checks the signed attestation the phone
sends, and issues a short lived JWT the relying party can verify.
No images, video or face data ever reach this server.

## Setup

```bash
pnpm install
sh gen-keys.sh          # JWT signing key pair into ./keys
cp .env.example .env
pnpm dev                # needs Redis on REDIS_URL
```

or `docker compose up`.

## Endpoints

| Method | Path | Notes |
|---|---|---|
| POST | /cdl/session/initiate | creates session, returns QR payload and poll token |
| GET | /cdl/session/:id/status | `Authorization: Bearer <poll_token>` |
| POST | /cdl/session/:id/complete | phone submits the signed attestation |
| POST | /cdl/session/:id/integrity-nonce | Android only |
| POST | /cdl/device/register, /cdl/device/attest | iOS App Attest enrolment |
| GET | /cdl/.well-known/jwks.json | public key for JWT verification |
| GET | /health | Redis ping |
| WS | /cdl/session/:id/ws?token=... | browser updates |
| WS | /cdl/session/:id/device | phone connection |

## Configuration

See `.env.example`. `HARDWARE_ATTESTATION=stub` accepts any `device_attestation` and is for local
testing only. It defaults to `enforce` when `NODE_ENV=production`.

For App Attest in enforce mode, put Apple's App Attest root CA PEM at
`APPLE_APP_ATTEST_ROOT_CA_PATH`. For Play Integrity set `GOOGLE_APPLICATION_CREDENTIALS`.

## Tests

```bash
pnpm test
```

The end to end suite uses Redis db 15 and skips itself if Redis is not running.

## Notes

* Sockets are tracked in memory, so run one instance. More than one needs Redis pub/sub in `websocket/hub.ts`.
* The `webhook_url` is called by the server, so in production restrict it to hosts you trust.
