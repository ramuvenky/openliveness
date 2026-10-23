import Fastify, { FastifyInstance } from 'fastify';
import type Redis from 'ioredis';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { registerSecurity } from '../src/middleware/security';
import { sessionRoutes } from '../src/routes/session';
import { setRedis } from '../src/session/store';
import { cacheToken, clearTokenCache } from '../src/session/tokenCache';
import { makeSession } from './helpers';

// Covers the M4 contract: when state=complete but the in-memory token cache
// no longer holds the JWT (TTL expired, or the relay restarted), /status
// must reject with ERR_ATTESTATION_TOKEN_UNAVAILABLE (410) rather than
// silently returning attestation_token=undefined. The old behavior resolved
// the web-sdk polling promise with a nullish token and looked like success.

type Op = ['hset', string, Record<string, string>] | ['set', string, string, unknown[]] | ['expire', string, number];

class FakeMulti {
  ops: Op[] = [];
  constructor(private store: FakeRedis) {}
  hset(key: string, flat: Record<string, string>) {
    this.ops.push(['hset', key, flat]);
    return this;
  }
  expire(key: string, ttl: number) {
    this.ops.push(['expire', key, ttl]);
    return this;
  }
  set(key: string, value: string, ...rest: unknown[]) {
    this.ops.push(['set', key, value, rest]);
    return this;
  }
  async exec() {
    for (const op of this.ops) {
      if (op[0] === 'hset') {
        const [, key, flat] = op;
        const existing = this.store.hashes.get(key) ?? {};
        this.store.hashes.set(key, { ...existing, ...flat });
      }
    }
    return [] as unknown as [Error | null, unknown][];
  }
}

class FakeRedis {
  hashes = new Map<string, Record<string, string>>();
  async hgetall(key: string) {
    return this.hashes.get(key) ?? {};
  }
  multi() {
    return new FakeMulti(this);
  }
}

async function buildApp(): Promise<FastifyInstance> {
  const app = Fastify({ logger: false });
  await registerSecurity(app);
  await app.register(sessionRoutes);
  return app;
}

const SAVED_ENV = { ...process.env };
let app: FastifyInstance;
let fake: FakeRedis;

beforeEach(async () => {
  process.env.NODE_ENV = 'test';
  process.env.IP_HASH_SECRET = 'test-secret';
  fake = new FakeRedis();
  setRedis(fake as unknown as Redis);
  clearTokenCache();
  app = await buildApp();
});

afterEach(async () => {
  await app?.close();
  setRedis(null);
  clearTokenCache();
  process.env = { ...SAVED_ENV };
});

function seed(session: ReturnType<typeof makeSession>) {
  const flat: Record<string, string> = {};
  for (const [k, v] of Object.entries(session)) flat[k] = v === null ? '' : String(v);
  fake.hashes.set(`cdl:session:${session.session_id}`, flat);
}

describe('GET /cdl/session/:id/status — M4 token-cache-miss contract', () => {
  it('returns the attestation token when state=complete and the cache holds it', async () => {
    const s = makeSession({
      state: 'complete',
      liveness_decision: 'pass',
      assurance_level: 'software_only',
    });
    seed(s);
    cacheToken(s.session_id, 'jwt.header.payload.sig');

    const res = await app.inject({
      method: 'GET',
      url: `/cdl/session/${s.session_id}/status`,
      headers: { authorization: `Bearer ${s.poll_token}` },
    });

    expect(res.statusCode).toBe(200);
    expect(res.json()).toMatchObject({
      state: 'complete',
      attestation_token: 'jwt.header.payload.sig',
      liveness_decision: 'pass',
    });
  });

  it('returns ERR_ATTESTATION_TOKEN_UNAVAILABLE (410) when state=complete but cache has evicted the token', async () => {
    const s = makeSession({ state: 'complete', liveness_decision: 'pass' });
    seed(s);
    // Deliberately no cacheToken — simulates the post-15-minute window.

    const res = await app.inject({
      method: 'GET',
      url: `/cdl/session/${s.session_id}/status`,
      headers: { authorization: `Bearer ${s.poll_token}` },
    });

    expect(res.statusCode).toBe(410);
    expect(res.json()).toMatchObject({ error_code: 'ERR_ATTESTATION_TOKEN_UNAVAILABLE' });
  });

  it('does not query the token cache for non-complete states (returns no attestation_token)', async () => {
    const s = makeSession({ state: 'phone_connected' });
    seed(s);

    const res = await app.inject({
      method: 'GET',
      url: `/cdl/session/${s.session_id}/status`,
      headers: { authorization: `Bearer ${s.poll_token}` },
    });

    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.state).toBe('phone_connected');
    expect(body.attestation_token).toBeUndefined();
  });

  it('rejects an invalid poll token with 401', async () => {
    const s = makeSession({ state: 'phone_connected' });
    seed(s);

    const res = await app.inject({
      method: 'GET',
      url: `/cdl/session/${s.session_id}/status`,
      headers: { authorization: 'Bearer wrong' },
    });
    expect(res.statusCode).toBe(401);
    expect(res.json().error_code).toBe('ERR_INVALID_POLL_TOKEN');
  });

  it('transitions a stale initiated session to expired on read', async () => {
    const s = makeSession({
      state: 'initiated',
      expires_at: new Date(Date.now() - 60_000).toISOString(),
    });
    seed(s);

    const res = await app.inject({
      method: 'GET',
      url: `/cdl/session/${s.session_id}/status`,
      headers: { authorization: `Bearer ${s.poll_token}` },
    });

    expect(res.statusCode).toBe(200);
    expect(res.json().state).toBe('expired');
    // The transition should have been written back via updateSession.
    const after = fake.hashes.get(`cdl:session:${s.session_id}`);
    expect(after?.state).toBe('expired');
  });
});
