# @openliveness/web

Browser side of openliveness. Shows the QR code and waits for the phone to finish.

```bash
pnpm add @openliveness/web
```

```ts
import { verify } from '@openliveness/web';

const result = await verify({
  relayUrl: 'relay.openliveness.dev',
  assuranceRequired: 'IAL2',
  onQRReady: (dataUrl) => (img.src = dataUrl),
  onPhoneConnected: () => setStatus('Follow the steps on your phone'),
});

// send result.token to your backend and verify it with @openliveness/verify
```

It uses a WebSocket and falls back to polling (1s, growing 1.5x up to 5s) if the socket fails.
If the phone reports a failed check, `verify()` rejects with `CDLRetryableError`. Call `verify()` again for a fresh session.

React:

```tsx
import { LivenessWidget } from '@openliveness/web/react';

<LivenessWidget relayUrl="relay.openliveness.dev" onComplete={(r) => send(r.token)} />
```

`relyingParty` defaults to the page hostname. Always re-check the token on your backend,
the browser result alone is not proof.
