import type { FastifyInstance, FastifyRequest } from 'fastify';
import rateLimit from '@fastify/rate-limit';
import { config } from '../config';
import { getRateLimitRedis } from '../session/store';

// M-b: when the relying_party is known (POST /initiate), key the limiter
// per-{ip, rp} rather than per-ip alone. The old scheme meant one attacker
// IP exhausting the global per-IP bucket could drain a specific RP's
// capacity by hammering /initiate against it, and a NATed IP's share was
// not scoped by which RP it was actually calling. With the composite key
// each (ip, rp) pair gets its own bucket, so cross-RP noise doesn't
// cross-contaminate. Routes without an identifiable RP fall back to ip
// alone — the common case for /device/* and /status.
export function rpKeyGenerator(req: FastifyRequest): string {
  const body = req.body as { relying_party?: unknown } | undefined;
  const rp = typeof body?.relying_party === 'string' ? body.relying_party : '';
  // Cap the RP segment so a hostile client can't blow up the key length /
  // Redis memory. 253 matches the schema's upper bound.
  const rpClamped = rp.length > 253 ? rp.slice(0, 253) : rp;
  return rpClamped ? `${req.ip}|${rpClamped}` : req.ip;
}

export async function registerRateLimit(app: FastifyInstance) {
  // Rate limiting is a security control, not a convenience: if Redis is
  // unreachable in production we must fail closed, otherwise an attacker can
  // simply drop the limiter's backing store to burst past the ceilings.
  // `skipOnError: true` is only acceptable in development, where losing the
  // limiter is strictly a devex concern. The limiter also uses its own Redis
  // client (getRateLimitRedis) so that heavy session traffic cannot starve
  // the counter checks and vice versa.
  const failOpen = config().nodeEnv !== 'production';
  await app.register(rateLimit, {
    global: false,
    redis: getRateLimitRedis(),
    nameSpace: 'cdl:ratelimit:',
    skipOnError: failOpen,
    keyGenerator: rpKeyGenerator,
    errorResponseBuilder: () => ({
      error_code: 'ERR_RATE_LIMITED',
      message: 'too many requests',
      statusCode: 429,
    }),
  });
}

export const limit = (max: number) => ({ rateLimit: { max, timeWindow: '1 minute' } });

export const LIMITS = {
  initiate: 10,
  complete: 20,
  deviceRegister: 5,
  deviceAttest: 5,
  status: 60,
  // N-a: /integrity-nonce is an unauthenticated Redis-write endpoint
  // (any valid session_id triggers a SET). Without a cap, a leaked
  // session_id can be used to burn Redis memory with 5-min TTL keys.
  integrity: 20,
};
