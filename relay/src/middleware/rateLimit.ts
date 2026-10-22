import type { FastifyInstance } from 'fastify';
import rateLimit from '@fastify/rate-limit';
import { config } from '../config';
import { getRateLimitRedis } from '../session/store';

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
};
