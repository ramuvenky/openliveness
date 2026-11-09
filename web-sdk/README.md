# @openliveness/web

Browser side of openliveness. Shows the QR code and waits for the phone to finish.

```bash
pnpm add @openliveness/web
```

```ts
import { verify } from '@openliveness/web';

const result = await verify({
  relayUrl: 'localhost:3000',              // your relay host
  assuranceRequired: 'IAL2',
  onQRReady: (dataUrl) => (img.src = dataUrl),
  onPhoneConnected: () => setStatus('Follow the steps on your phone'),
});

// send result.token to your backend and verify it with @openliveness/verify
```

**You run the relay yourself.** It's the Node server that brokers the
browser↔phone handshake — see
[openliveness/relay](https://github.com/ramuvenky/openliveness/tree/main/relay)
for the `docker compose` setup. Point `relayUrl` at wherever you deploy it.

It uses a WebSocket and falls back to polling (1s, growing 1.5x up to 5s) if the socket fails.
If the phone reports a failed check, `verify()` rejects with `CDLRetryableError`. Call `verify()` again for a fresh session.

React:

```tsx
import { LivenessWidget } from '@openliveness/web/react';

<LivenessWidget relayUrl="relay.openliveness.dev" onComplete={(r) => send(r.token)} />
```

`relyingParty` defaults to the page hostname. Always re-check the token on your backend,
the browser result alone is not proof.
