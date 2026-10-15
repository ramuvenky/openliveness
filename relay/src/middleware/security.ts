import type { FastifyInstance } from 'fastify';
import helmet from '@fastify/helmet';
import { sha256hex } from '../utils/crypto';
import { CDLError } from '../errors';

export async function registerSecurity(app: FastifyInstance) {
  await app.register(helmet);

  // One structured line per request, the ip is hashed so logs hold no raw addresses.
  app.addHook('onResponse', async (req, reply) => {
    const params = req.params as { id?: string } | undefined;
    req.log.info(
      {
        session_id: params?.id,
        ip: sha256hex(req.ip).slice(0, 16),
        method: req.method,
        url: req.routeOptions?.url,
        outcome: reply.statusCode,
      },
      'request',
    );
  });

  app.setErrorHandler((err, req, reply) => {
    if (err instanceof CDLError) {
      return reply.status(err.status).send({ error_code: err.code, message: err.message });
    }
    const status = (err as { statusCode?: number }).statusCode;
    if (status === 429) {
      return reply.status(429).send({ error_code: 'ERR_RATE_LIMITED', message: 'too many requests' });
    }
    if (status && status < 500) {
      return reply.status(status).send({ error_code: 'ERR_SCHEMA_INVALID', message: err.message });
    }
    // redis being down is the usual cause of a 500 here
    if (/ECONNREFUSED|Connection is closed|ETIMEDOUT/.test(err.message)) {
      return reply.status(503).send({ error_code: 'ERR_RELAY_UNAVAILABLE', message: 'storage unavailable' });
    }
    req.log.error(err);
    return reply.status(500).send({ error_code: 'ERR_INTERNAL', message: 'internal error' });
  });
}
