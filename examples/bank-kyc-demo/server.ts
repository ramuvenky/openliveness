import express from 'express';
import { verifyAttestationJWT } from '@openliveness/verify';

const RELAY_URL = process.env.RELAY_URL ?? 'http://localhost:3000';
// Must match the hostname the browser page runs on, the relay uses it as the token audience.
const RELYING_PARTY = process.env.RELYING_PARTY ?? 'localhost';
const port = Number(process.env.PORT ?? 4000);

const app = express();
app.use(express.json());
app.use(express.static('public'));

// The browser hands us the token, we never trust its claim about the result.
app.post('/verify-liveness', async (req, res) => {
  const { token } = req.body ?? {};
  if (typeof token !== 'string') return res.status(400).json({ error: 'token is required' });

  const result = await verifyAttestationJWT(token, {
    relayUrl: RELAY_URL,
    audience: RELYING_PARTY,
    requiredAssurance: process.env.REQUIRE_IAL2 === 'true' ? 'IAL2' : 'any',
    requiredDecision: 'pass',
    maxAgeSeconds: 300,
  });

  res.status(result.valid ? 200 : 401).json(result);
});

app.listen(port, () => console.log(`bank demo on http://localhost:${port}`));
