import Fastify, { FastifyInstance } from 'fastify';
import websocket from '@fastify/websocket';
import { config } from './config';
import { registerRateLimit } from './middleware/rateLimit';
import { registerSecurity } from './middleware/security';
import { deviceRoutes } from './routes/device';
import { healthRoutes } from './routes/health';
import { sessionRoutes } from './routes/session';
import { registerWebsocketRoutes } from './websocket/handler';

// Known-weak placeholders that have shipped in docs / compose files. If any
// of these ever lands as the production nonce HMAC key, an attacker with
// access to the repo can reproduce every integrity nonce the relay issues.
// The check is case-insensitive and trims whitespace so near-misses still
// trip the guard. Exported for tests.
export const WEAK_INTEGRITY_SECRETS = new Set([
  '',
  'dev-secret',
  'dev-secret-change-in-production',
  'change-me',
  'changeme',
  'secret',
  'password',
]);
export function isWeakIntegritySecret(secret: string): boolean {
  return WEAK_INTEGRITY_SECRETS.has(secret.trim().toLowerCase());
}

export async function buildServer(): Promise<FastifyInstance> {
  const c = config();
  // HARDWARE_ATTESTATION=stub makes verifyHardware return attested=true
  // unconditionally. That's useful in dev / CI, but a deploy that ships
  // with the stub env var still set would rubber-stamp every attestation
  // while looking healthy — only a boot warn would surface the mistake.
  // Refuse to start instead: an operator who really wants the stub in a
  // production-labelled env has to flip NODE_ENV, which is a louder choice.
  if (c.nodeEnv === 'production' && c.hardwareMode === 'stub') {
    throw new Error(
      'refusing to start: HARDWARE_ATTESTATION=stub is not allowed when NODE_ENV=production',
    );
  }
  // N-e: refuse to boot with an empty or well-known INTEGRITY_NONCE_SECRET
  // in production. The default compose file ships a documented
  // "dev-secret-change-in-production" placeholder; a deploy that copies
  // the compose into production without rotating the secret inherits a
  // nonce HMAC key that anyone with the repo can reproduce. Catching this
  // at boot is louder than a startup warn.
  if (c.nodeEnv === 'production' && isWeakIntegritySecret(c.integritySecret)) {
    throw new Error(
      'refusing to start: INTEGRITY_NONCE_SECRET is empty or a well-known placeholder in production',
    );
  }
  const app = Fastify({
    logger: { level: process.env.LOG_LEVEL ?? 'info' },
    // Only trust the proxy headers when the operator has explicitly promised
    // there's a trusted upstream (TRUST_PROXY=true). Otherwise an attacker
    // controlling the client can forge X-Forwarded-For to defeat per-IP
    // rate limits.
    trustProxy: c.trustProxy,
    bodyLimit: 64 * 1024,
  });
  if (c.nodeEnv === 'production' && !c.trustProxy) {
    app.log.warn('TRUST_PROXY is not set — req.ip reflects the direct peer; set it only if a trusted proxy is in front');
  }
  // In development the Apple App Attest chain verification silently returns
  // (dev-only escape hatch). That's fine for local work but easy to deploy by
  // accident if the chain-of-trust assumption isn't on the operator's radar.
  // One warn at boot documents the shortcut so NODE_ENV ≠ 'production' deploys
  // don't quietly accept any device-attestation chain.
  if (c.nodeEnv !== 'production' && !c.appleRootCaPath) {
    app.log.warn(
      'APPLE_APP_ATTEST_ROOT_CA_PATH is unset — iOS attestation chain verification is SKIPPED in this environment',
    );
  }

  await registerSecurity(app);
  await registerRateLimit(app);
  // L-b: cap inbound WS frame size. The only frames clients send are tiny
  // JSON objects — browser auth (~200B), phone auth/liveness_started/
  // processing_update (~300B). An 8 KiB ceiling is 20x the largest
  // legitimate frame and refuses multi-MB floods before they reach the
  // JSON parser. Applies to both /ws and /device routes.
  await app.register(websocket, {
    options: {
      maxPayload: 8 * 1024,
    },
  });
  await app.register(healthRoutes);
  await app.register(sessionRoutes);
  await app.register(deviceRoutes);
  await app.register(registerWebsocketRoutes);
  return app;
}

async function main() {
  const app = await buildServer();
  const c = config();
  if (c.hardwareMode === 'stub') {
    app.log.warn('HARDWARE_ATTESTATION=stub, device proofs are NOT being verified');
  }
  await app.listen({ port: c.port, host: '0.0.0.0' });
}

if (require.main === module) {
  main().catch((err) => {
    console.error(err);
    process.exit(1);
  });
}
