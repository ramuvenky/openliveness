import Fastify, { FastifyInstance } from 'fastify';
import websocket from '@fastify/websocket';
import { config } from './config';
import { registerRateLimit } from './middleware/rateLimit';
import { registerSecurity } from './middleware/security';
import { deviceRoutes } from './routes/device';
import { healthRoutes } from './routes/health';
import { sessionRoutes } from './routes/session';
import { registerWebsocketRoutes } from './websocket/handler';

export async function buildServer(): Promise<FastifyInstance> {
  const c = config();
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
  await app.register(websocket);
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
