import { mkdtempSync, readFileSync, writeFileSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import Fastify from 'fastify';
import type Redis from 'ioredis';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  JWKS_CACHE_TTL_MS,
  getJwks,
  resetJwksCache,
  resetKeyCache,
} from '../src/attestation/jwt';
import { LIMITS } from '../src/middleware/rateLimit';
import { sessionRoutes } from '../src/routes/session';
import { setRedis } from '../src/session/store';
import { normalizeWebhookUrl } from '../src/session/webhook';
import { makePemKeys } from './helpers';

// Pins the third-review (N-series) hardening fixes:
//   N-a — the /integrity-nonce endpoint must carry a per-IP rate limit so a
//         leaked session_id can't be used to burn Redis memory with nonce
//         keys at wire speed.
//   N-b — /initiate must validate webhook_url synchronously (scheme / loopback
//         opt-in) so an RP posting garbage URL gets immediate feedback instead
//         of a silent dispatch-time failure.
//   N-c — the JWKS public-key cache must auto-refresh after JWKS_CACHE_TTL_MS
//         so an on-disk key rotation lands within one TTL window without a
//         server restart.

// -----------------------------------------------------------------------------
// N-a — /integrity-nonce rate limit is wired at route registration time
// -----------------------------------------------------------------------------
describe('N-a — /integrity-nonce rate limit', () => {
  it('exposes LIMITS.integrity at the expected ceiling (20 / min)', () => {
    // The constant is referenced from the route config and the test harness
    // below. If someone bumps it we want to notice at review, not in prod.
    expect(LIMITS.integrity).toBe(20);
  });

  it('attaches { rateLimit: { max: LIMITS.integrity } } to POST /:id/integrity-nonce', async () => {
    // onRoute runs at register-time and surfaces the config the route was
    // declared with. We don't need Redis or a listening socket — only the
    // registration pass has to succeed, so sessionRoutes' route-level config
    // can be inspected in isolation.
    const captured = new Map<string, Record<string, unknown> | undefined>();
    const app = Fastify({ logger: false });
    app.addHook('onRoute', (r) => {
      captured.set(`${r.method} ${r.url}`, r.config as Record<string, unknown> | undefined);
    });
    await app.register(sessionRoutes);
    try {
      const cfg = captured.get('POST /cdl/session/:id/integrity-nonce') as
        | { rateLimit?: { max?: number } }
        | undefined;
      expect(cfg?.rateLimit?.max).toBe(LIMITS.integrity);
    } finally {
      await app.close();
    }
  });

  it('also wires rate limits on the other session routes (regression guard)', async () => {
    // Pins that we didn't accidentally strip a limit elsewhere while adding
    // the integrity one. /status, /complete, /initiate all have their own
    // buckets.
    const captured = new Map<string, Record<string, unknown> | undefined>();
    const app = Fastify({ logger: false });
    app.addHook('onRoute', (r) => {
      captured.set(`${r.method} ${r.url}`, r.config as Record<string, unknown> | undefined);
    });
    await app.register(sessionRoutes);
    try {
      const paths = [
        'POST /cdl/session/initiate',
        'GET /cdl/session/:id/status',
        'POST /cdl/session/:id/complete',
        'POST /cdl/session/:id/integrity-nonce',
      ];
      for (const p of paths) {
        const cfg = captured.get(p) as { rateLimit?: { max?: number } } | undefined;
        expect(cfg?.rateLimit?.max, `${p} missing rateLimit config`).toBeTypeOf('number');
      }
    } finally {
      await app.close();
    }
  });
});

// -----------------------------------------------------------------------------
// N-b — /initiate rejects invalid webhook_url synchronously
// -----------------------------------------------------------------------------
describe('N-b — /initiate webhook_url validation', () => {
  // Minimal fake Redis: /initiate throws before saveSession on the reject
  // path (so Redis never gets called), and the happy path only needs multi()
  // / exec() to succeed.
  class FakeMulti {
    hset() {
      return this;
    }
    expire() {
      return this;
    }
    set() {
      return this;
    }
    async exec() {
      return [];
    }
  }
  class FakeRedis {
    multi() {
      return new FakeMulti();
    }
  }

  const SAVED = { ...process.env };

  beforeEach(() => {
    process.env = { ...SAVED };
    setRedis(new FakeRedis() as unknown as Redis);
  });
  afterEach(() => {
    process.env = { ...SAVED };
    setRedis(null);
  });

  it('rejects http:// webhook_url with ERR_SCHEMA_INVALID (loopback not allowed)', async () => {
    process.env.ALLOW_LOOPBACK_WEBHOOKS = 'false';
    const app = Fastify({ logger: false });
    await app.register(sessionRoutes);
    const res = await app.inject({
      method: 'POST',
      url: '/cdl/session/initiate',
      payload: {
        relying_party: 'example-bank.com',
        assurance_required: 'any',
        webhook_url: 'http://attacker.example.com/collect',
      },
    });
    try {
      // CDLError('ERR_SCHEMA_INVALID') -> 400. Rejection must happen BEFORE
      // saveSession, otherwise a bad URL silently persists on the session
      // and surfaces only at dispatch time (where we log and drop — the RP
      // never sees it).
      expect(res.statusCode).toBe(400);
      // Default Fastify error serializer uses `code`; the production error
      // handler maps it to `error_code` in the body. Either shape surfaces
      // the same identifier.
      expect(res.json().code).toBe('ERR_SCHEMA_INVALID');
    } finally {
      await app.close();
    }
  });

  it('rejects a totally malformed webhook_url', async () => {
    const app = Fastify({ logger: false });
    await app.register(sessionRoutes);
    const res = await app.inject({
      method: 'POST',
      url: '/cdl/session/initiate',
      payload: {
        relying_party: 'example-bank.com',
        assurance_required: 'any',
        webhook_url: 'not a url at all',
      },
    });
    try {
      expect(res.statusCode).toBe(400);
      expect(res.json().code).toBe('ERR_SCHEMA_INVALID');
    } finally {
      await app.close();
    }
  });

  it('accepts https:// webhook_url (happy path)', async () => {
    const app = Fastify({ logger: false });
    await app.register(sessionRoutes);
    const res = await app.inject({
      method: 'POST',
      url: '/cdl/session/initiate',
      payload: {
        relying_party: 'example-bank.com',
        assurance_required: 'any',
        webhook_url: 'https://rp.example.com/cdl/callback',
      },
    });
    try {
      expect(res.statusCode).toBe(200);
      expect(res.json()).toMatchObject({ relying_party: 'example-bank.com' });
    } finally {
      await app.close();
    }
  });

  it('accepts http://localhost webhook when ALLOW_LOOPBACK_WEBHOOKS=true (dev opt-in)', async () => {
    process.env.ALLOW_LOOPBACK_WEBHOOKS = 'true';
    expect(normalizeWebhookUrl('http://localhost:4000/x', true)).not.toBeNull();
    const app = Fastify({ logger: false });
    await app.register(sessionRoutes);
    const res = await app.inject({
      method: 'POST',
      url: '/cdl/session/initiate',
      payload: {
        relying_party: 'example-bank.com',
        assurance_required: 'any',
        webhook_url: 'http://localhost:4000/collect',
      },
    });
    try {
      expect(res.statusCode).toBe(200);
    } finally {
      await app.close();
    }
  });

  it('still accepts /initiate with no webhook_url at all', async () => {
    const app = Fastify({ logger: false });
    await app.register(sessionRoutes);
    const res = await app.inject({
      method: 'POST',
      url: '/cdl/session/initiate',
      payload: { relying_party: 'example-bank.com', assurance_required: 'any' },
    });
    try {
      expect(res.statusCode).toBe(200);
    } finally {
      await app.close();
    }
  });
});

// -----------------------------------------------------------------------------
// N-c — JWKS cache auto-refreshes after JWKS_CACHE_TTL_MS
// -----------------------------------------------------------------------------
describe('N-c — JWKS cache TTL', () => {
  const SAVED = { ...process.env };

  beforeEach(() => {
    process.env = { ...SAVED };
    resetJwksCache();
    resetKeyCache();
  });
  afterEach(() => {
    process.env = { ...SAVED };
    resetJwksCache();
    resetKeyCache();
  });

  it('advertises a 5-minute TTL (matches the client Cache-Control window)', () => {
    expect(JWKS_CACHE_TTL_MS).toBe(5 * 60 * 1000);
  });

  it('returns the cached JWKS within the TTL (no file reread)', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'cdl-jwks-ttl-'));
    const a = makePemKeys();
    const b = makePemKeys();
    const pathA = join(dir, 'a.pem');
    const pathB = join(dir, 'b.pem');
    writeFileSync(pathA, a.publicPem);
    writeFileSync(pathB, b.publicPem);

    process.env.JWT_PUBLIC_KEY_PATH = pathA;
    const first = (await getJwks(1000)) as { keys: Array<Record<string, string>> };

    // Swap the file on disk; without a reread, the cache must still return
    // the first key.
    process.env.JWT_PUBLIC_KEY_PATH = pathB;
    const stillCached = (await getJwks(1000 + JWKS_CACHE_TTL_MS - 1)) as {
      keys: Array<Record<string, string>>;
    };
    expect(stillCached.keys[0].x).toBe(first.keys[0].x);
    expect(stillCached.keys[0].y).toBe(first.keys[0].y);
  });

  it('rereads the key file once the TTL has elapsed', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'cdl-jwks-ttl-'));
    const a = makePemKeys();
    const b = makePemKeys();
    const pathA = join(dir, 'a.pem');
    const pathB = join(dir, 'b.pem');
    writeFileSync(pathA, a.publicPem);
    writeFileSync(pathB, b.publicPem);

    process.env.JWT_PUBLIC_KEY_PATH = pathA;
    const first = (await getJwks(1000)) as { keys: Array<Record<string, string>> };

    process.env.JWT_PUBLIC_KEY_PATH = pathB;
    // Advance past the TTL; getJwks compares `now - cachedAt > TTL`, so
    // (TTL + 1) is the first "miss".
    const refreshed = (await getJwks(1000 + JWKS_CACHE_TTL_MS + 1)) as {
      keys: Array<Record<string, string>>;
    };
    // Two distinct P-256 keys have different (x, y) coordinates with
    // overwhelming probability. If both match this test will catch it.
    const different =
      refreshed.keys[0].x !== first.keys[0].x || refreshed.keys[0].y !== first.keys[0].y;
    expect(different).toBe(true);
  });

  it('still carries the committed kid + alg + use on refresh', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'cdl-jwks-ttl-'));
    const { publicPem } = makePemKeys();
    const p = join(dir, 'pub.pem');
    writeFileSync(p, publicPem);
    process.env.JWT_PUBLIC_KEY_PATH = p;
    const jwks = (await getJwks(1)) as { keys: Array<Record<string, string>> };
    expect(jwks.keys[0]).toMatchObject({
      kid: 'relay-signing-key-v1',
      alg: 'ES256',
      use: 'sig',
      kty: 'EC',
      crv: 'P-256',
    });
    // Private material must never leak into the JWKS, even after a refresh.
    expect(jwks.keys[0].d).toBeUndefined();
  });
});

// -----------------------------------------------------------------------------
// N-d — docker-compose binds Redis to loopback only
// -----------------------------------------------------------------------------
describe('N-d — docker-compose Redis is loopback-only', () => {
  const compose = readFileSync(
    join(__dirname, '..', 'docker-compose.yml'),
    'utf8',
  );

  it('publishes Redis on 127.0.0.1 only', () => {
    expect(compose).toMatch(/127\.0\.0\.1:6379:6379/);
  });

  it('does not publish Redis on all interfaces (plain "6379:6379")', () => {
    // The regex specifically matches a mapping NOT prefixed by 127.0.0.1 —
    // a bare "6379:6379" would re-expose Redis on every host interface.
    // We allow it to appear inside the loopback mapping itself, so the check
    // is: no line contains '"6379:6379"' as the entire ports entry.
    const lines = compose.split('\n');
    for (const l of lines) {
      const trimmed = l.trim();
      // tolerate quoted or unquoted forms
      expect(trimmed).not.toBe('- "6379:6379"');
      expect(trimmed).not.toBe('- 6379:6379');
    }
  });
});

// -----------------------------------------------------------------------------
// .env.example — every env var the code reads is documented
// -----------------------------------------------------------------------------
describe('.env.example completeness', () => {
  const envExample = readFileSync(
    join(__dirname, '..', '.env.example'),
    'utf8',
  );
  const configSrc = readFileSync(join(__dirname, '..', 'src', 'config.ts'), 'utf8');

  it('documents every env var config.ts reads', () => {
    // All `env.FOO_BAR` references in config.ts; we don't scan all of src/
    // because config.ts is the chokepoint — anything else reading process.env
    // outside of config is a separate smell.
    const matches = [...configSrc.matchAll(/\benv\.([A-Z][A-Z0-9_]+)\b/g)];
    const names = new Set(matches.map((m) => m[1]));
    expect(names.size).toBeGreaterThan(0);
    const missing: string[] = [];
    for (const name of names) {
      // Match either "NAME=" at a line start or "# NAME" in a comment.
      // We require NAME= to appear so operators can actually copy the file.
      const re = new RegExp(`^${name}=`, 'm');
      if (!re.test(envExample)) missing.push(name);
    }
    expect(missing, `.env.example missing: ${missing.join(', ')}`).toEqual([]);
  });

  it('documents LOG_LEVEL even though config.ts reads it inline in server.ts', () => {
    // LOG_LEVEL is read in server.ts rather than config.ts, so the auto-scan
    // above wouldn't catch its absence. Keep it in the template.
    expect(envExample).toMatch(/^LOG_LEVEL=/m);
  });
});

// -----------------------------------------------------------------------------
// Dockerfile — multi-stage build drops dev deps and runs as non-root
// -----------------------------------------------------------------------------
describe('Dockerfile — multi-stage runtime', () => {
  const dockerfile = readFileSync(
    join(__dirname, '..', 'Dockerfile'),
    'utf8',
  );

  it('has at least two FROM stages (builder + runtime)', () => {
    const froms = dockerfile.match(/^FROM /gm) ?? [];
    expect(froms.length).toBeGreaterThanOrEqual(2);
    expect(dockerfile).toMatch(/^FROM\s+node:20-alpine\s+AS\s+builder/m);
    expect(dockerfile).toMatch(/^FROM\s+node:20-alpine\s+AS\s+runtime/m);
  });

  it('copies the built dist/ from the builder, not from the host context', () => {
    expect(dockerfile).toMatch(/COPY\s+--from=builder\s+\/app\/dist\s+\.\/dist/);
  });

  it('installs only production dependencies in the runtime stage', () => {
    expect(dockerfile).toMatch(/pnpm install --prod/);
  });

  it('drops root privileges before CMD', () => {
    // node:20-alpine ships a `node` user. Running as root inside the
    // container lets a compromise chown /keys and write /app, which negates
    // the keys/ read-only volume mount in docker-compose.yml.
    const userLine = dockerfile.match(/^USER\s+(\S+)/m);
    expect(userLine?.[1]).toBe('node');
    // USER must come before CMD or the process still boots as root.
    const userIdx = dockerfile.indexOf('\nUSER ');
    const cmdIdx = dockerfile.lastIndexOf('\nCMD ');
    expect(userIdx).toBeGreaterThan(-1);
    expect(cmdIdx).toBeGreaterThan(userIdx);
  });
});
