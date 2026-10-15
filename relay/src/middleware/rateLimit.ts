import type { FastifyInstance } from 'fastify';
import rateLimit from '@fastify/rate-limit';
import { getRedis } from '../session/store';

export async function registerRateLimit(app: FastifyInstance) {
  // global false, each route opts in with its own limit through limit()
  await app.register(rateLimit, {
    global: false,
    redis: getRedis(),
    nameSpace: 'cdl:ratelimit:',
    skipOnError: true,
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
