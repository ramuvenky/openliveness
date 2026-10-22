import Fastify from 'fastify';
import Redis from 'ioredis';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { registerRateLimit, limit, LIMITS } from '../src/middleware/rateLimit';
import { setRateLimitRedis } from '../src/session/store';

// Locks in the H1 contract:
//   1. The rate limiter must have its own Redis client (verified by injecting
//      one via setRateLimitRedis — if the middleware falls back to the main
//      client, the e2e teardown would still leak a connection, and the
//      instrumented client below would never see traffic).
//   2. In production, when the limiter's Redis is unreachable, requests must
//      be rejected (fail closed), because an attacker who downs Redis would
//      otherwise burst past every ceiling.
//   3. In development, requests must still succeed with a dead limiter, so
//      a stopped local redis doesn't block iteration.
//
// We simulate "Redis is down" by handing the limiter an ioredis client that
// points at a port with nothing listening and refuses to queue commands —
// so every call rejects immediately. That matches the production failure
// mode where the limiter can't reach its backing store.

const PRIOR_NODE_ENV = process.env.NODE_ENV;

function deadRedis(): Redis {
  // 127.0.0.1:1 is reserved and nothing listens there in CI sandboxes.
  // enableOfflineQueue:false makes commands reject rather than buffering,
  // and lazyConnect prevents a background reconnect loop from spamming logs.
  return new Redis({
    host: '127.0.0.1',
    port: 1,
    enableOfflineQueue: false,
    lazyConnect: true,
    maxRetriesPerRequest: 0,
    retryStrategy: () => null,
  });
}

async function buildApp(nodeEnv: string) {
  process.env.NODE_ENV = nodeEnv;
  const r = deadRedis();
  setRateLimitRedis(r);
  const app = Fastify({ logger: false });
  await registerRateLimit(app);
  app.get('/limited', { config: limit(LIMITS.initiate) }, async () => ({ ok: true }));
  return { app, r };
}

afterEach(async () => {
  process.env.NODE_ENV = PRIOR_NODE_ENV;
  setRateLimitRedis(null);
});

describe('registerRateLimit — fail-closed semantics', () => {
  it('fails closed when NODE_ENV=production and the limiter Redis is unreachable', async () => {
    const { app, r } = await buildApp('production');
    try {
      const res = await app.inject({ method: 'GET', url: '/limited' });
      // skipOnError:false surfaces the ioredis error through the request
      // lifecycle. The exact status is 500 (Fastify's default error path);
      // the invariant we care about is that the request did NOT quietly
      // succeed, since that would mean the limit was bypassed.
      expect(res.statusCode).not.toBe(200);
    } finally {
      await app.close();
      r.disconnect();
    }
  });

  it('fails open when NODE_ENV=development and the limiter Redis is unreachable', async () => {
    const { app, r } = await buildApp('development');
    try {
      const res = await app.inject({ method: 'GET', url: '/limited' });
      expect(res.statusCode).toBe(200);
      expect(res.json()).toEqual({ ok: true });
    } finally {
      await app.close();
      r.disconnect();
    }
  });

  it('also fails open when NODE_ENV is unset (treated as non-production)', async () => {
    delete process.env.NODE_ENV;
    const r = deadRedis();
    setRateLimitRedis(r);
    const app = Fastify({ logger: false });
    await registerRateLimit(app);
    app.get('/limited', { config: limit(LIMITS.initiate) }, async () => ({ ok: true }));
    try {
      const res = await app.inject({ method: 'GET', url: '/limited' });
      expect(res.statusCode).toBe(200);
    } finally {
      await app.close();
      r.disconnect();
    }
  });
});

describe('registerRateLimit — uses the injected rate-limit Redis client', () => {
  it('routes limiter traffic through the setRateLimitRedis-provided client', async () => {
    // We can't easily observe which client the limiter called, but we can
    // observe side effects: if the limiter were sharing the main session
    // client, swapping only the rate-limit client out for a dead one would
    // not change behavior. The fail-closed test above only passes because
    // the limiter is actually hitting the client we injected — i.e. this
    // test asserts the plumbing by virtue of the above tests being able to
    // discriminate dev vs prod behavior at all.
    process.env.NODE_ENV = 'production';
    const calls: string[] = [];
    const spyR = deadRedis();
    const originalCall = spyR.sendCommand.bind(spyR);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    spyR.sendCommand = ((cmd: any) => {
      calls.push(cmd.name);
      return originalCall(cmd);
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
    }) as any;
    setRateLimitRedis(spyR);

    const app = Fastify({ logger: false });
    await registerRateLimit(app);
    app.get('/limited', { config: limit(LIMITS.initiate) }, async () => ({ ok: true }));
    try {
      await app.inject({ method: 'GET', url: '/limited' });
      // @fastify/rate-limit issues at least one redis command per request
      // (pttl / incr / pexpire family). The dead client rejects it, but we
      // can still see that *a* command was attempted against this client.
      expect(calls.length).toBeGreaterThan(0);
    } finally {
      await app.close();
      spyR.disconnect();
    }
  });
});
