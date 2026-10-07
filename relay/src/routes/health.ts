import type { FastifyInstance } from 'fastify';
import { getJwks } from '../attestation/jwt';
import { getRedis } from '../session/store';

export async function healthRoutes(app: FastifyInstance) {
  app.get('/health', async (_req, reply) => {
    try {
      await getRedis().ping();
      return { status: 'ok', redis: 'connected', version: '1.0.0' };
    } catch {
      return reply.status(503).send({ status: 'degraded', redis: 'disconnected', version: '1.0.0' });
    }
  });

  app.get('/cdl/.well-known/jwks.json', async (_req, reply) => {
    reply.header('Cache-Control', 'public, max-age=300');
    return getJwks();
  });
}
