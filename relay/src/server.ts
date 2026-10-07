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
  const app = Fastify({
    logger: { level: process.env.LOG_LEVEL ?? 'info' },
    trustProxy: true,
    bodyLimit: 64 * 1024,
  });

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
