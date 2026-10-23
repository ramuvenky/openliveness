import { mkdtempSync, writeFileSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import Redis from 'ioredis';
import WebSocket from 'ws';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { makeAttestation, makePemKeys } from './helpers';

// Uses redis db 15 and is skipped when no Redis is listening.
const REDIS_URL = process.env.TEST_REDIS_URL ?? 'redis://localhost:6379/15';

async function redisUp(): Promise<boolean> {
  const r = new Redis(REDIS_URL, { lazyConnect: true, maxRetriesPerRequest: 0, retryStrategy: () => null });
  r.on('error', () => {});
  try {
    await r.connect();
    await r.ping();
    return true;
  } catch {
    return false;
  } finally {
    r.disconnect();
  }
}

const available = await redisUp();

describe.skipIf(!available)('relay end to end', () => {
  let app: FastifyInstance;
  let base: string;
  let wsBase: string;

  beforeAll(async () => {
    const dir = mkdtempSync(join(tmpdir(), 'cdl-e2e-'));
    const { privatePem, publicPem } = makePemKeys();
    writeFileSync(join(dir, 'priv.pem'), privatePem);
    writeFileSync(join(dir, 'pub.pem'), publicPem);
    Object.assign(process.env, {
      REDIS_URL,
      JWT_PRIVATE_KEY_PATH: join(dir, 'priv.pem'),
      JWT_PUBLIC_KEY_PATH: join(dir, 'pub.pem'),
      RELAY_BASE_URL: 'localhost',
      HARDWARE_ATTESTATION: 'stub',
      LOG_LEVEL: 'silent',
      PORT: '0',
    });

    const { getRedis } = await import('../src/session/store');
    await getRedis().flushdb();

    const { buildServer } = await import('../src/server');
    app = await buildServer();
    await app.listen({ port: 0, host: '127.0.0.1' });
    const port = (app.server.address() as { port: number }).port;
    base = `http://127.0.0.1:${port}`;
    wsBase = `ws://127.0.0.1:${port}`;
  });

  afterAll(async () => {
    await app?.close();
    const { disconnectAllRedis } = await import('../src/session/store');
    disconnectAllRedis();
  });

  async function initiate(assurance = 'any') {
    const res = await fetch(`${base}/cdl/session/initiate`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ relying_party: 'example-bank.com', assurance_required: assurance }),
    });
    return { res, body: (await res.json()) as Record<string, any> };
  }

  const open = (url: string) =>
    new Promise<WebSocket>((resolve, reject) => {
      const ws = new WebSocket(url);
      ws.once('open', () => resolve(ws));
      ws.once('error', reject);
    });

  const nextMessage = (ws: WebSocket, type: string) =>
    new Promise<Record<string, any>>((resolve) => {
      ws.on('message', (raw) => {
        const msg = JSON.parse(raw.toString());
        if (msg.type === type) resolve(msg);
      });
    });

  async function sign(s: Record<string, any>, over = {}) {
    return makeAttestation({
      session_id: s.session_id,
      challenge_id: s.challenge_id,
      challenge_response: s.challenge_sequence.join(','),
      ...over,
    });
  }

  const complete = (id: string, body: unknown) =>
    fetch(`${base}/cdl/session/${id}/complete`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });

  function qrFrom(s: Record<string, any>): Record<string, any> {
    return JSON.parse(Buffer.from(s.qr_payload, 'base64').toString('utf8'));
  }

  // Rewrites the WS URL from the QR to point at the test server port while
  // preserving the ?token=... that the server embedded. The e2e tests used
  // to construct the device URL by hand, which hides the token requirement.
  function deviceUrl(s: Record<string, any>): string {
    const relayWs: string = qrFrom(s).relay_ws;
    return relayWs.replace(/^ws:\/\/[^/]+/, wsBase);
  }

  it('QR payload decodes to the documented shape', async () => {
    const { body } = await initiate();
    expect(body.challenge_sequence).toHaveLength(3);
    expect(new Set(body.challenge_sequence).size).toBe(3);
    const qr = qrFrom(body);
    expect(Object.keys(qr).sort()).toEqual(
      [
        'challenge_id',
        'challenge_sequence',
        'expires_at',
        'phone_token',
        'relay_http',
        'relay_ws',
        'session_id',
        'version',
      ].sort(),
    );
    expect(qr.relay_ws).toContain(`/cdl/session/${body.session_id}/device`);
    expect(qr.relay_ws).toContain(`token=${qr.phone_token}`);
    // Server-side invariant: the HTTP response body must NOT carry phone_token
    // so it never traverses the RP server or logs. The browser gets only the
    // base64 QR, and only the phone (via camera) sees the token.
    expect(body.phone_token).toBeUndefined();
    expect(qr.phone_token).toMatch(/^[A-Za-z0-9_-]{20,}$/);
  });

  // L4 — initiate response echoes the relying_party the caller asked for so
  // the browser can cross-check the JWT's `aud` matches *before* forwarding
  // the token to its backend. The server also binds this to the JWT aud, so
  // this echo is advisory — not authoritative — but lets a defensive caller
  // fail fast without parsing the token.
  it('L4: initiate response echoes relying_party', async () => {
    const { res, body } = await initiate();
    expect(res.status).toBe(200);
    expect(body.relying_party).toBe('example-bank.com');
  });

  it('rejects a phone WS connect without the capability token', async () => {
    const { body: s } = await initiate();
    const url = `${wsBase}/cdl/session/${s.session_id}/device`;
    const ws = await open(url);
    const inv = await new Promise<Record<string, any>>((res, rej) => {
      ws.on('message', (raw) => {
        try {
          res(JSON.parse(raw.toString()));
        } catch (e) {
          rej(e);
        }
      });
    });
    expect(inv.type).toBe('session_invalid');
    expect(inv.reason).toBe('invalid_phone_token');
    ws.close();
  });

  it('rejects a phone WS connect with the wrong capability token', async () => {
    const { body: s } = await initiate();
    const url = `${wsBase}/cdl/session/${s.session_id}/device?token=not-the-real-one`;
    const ws = await open(url);
    const inv = await new Promise<Record<string, any>>((res, rej) => {
      ws.on('message', (raw) => {
        try {
          res(JSON.parse(raw.toString()));
        } catch (e) {
          rej(e);
        }
      });
    });
    expect(inv.type).toBe('session_invalid');
    expect(inv.reason).toBe('invalid_phone_token');
    ws.close();
  });

  it('rejects a phone WS connect that reuses one session_id with another session_id\'s token', async () => {
    // Session-mix replay: the attacker has the phone_token for session A
    // and tries to connect to session B. The server must bind the token to
    // the session_id and reject.
    const { body: a } = await initiate();
    const { body: b } = await initiate();
    const stolenToken = qrFrom(a).phone_token;
    const url = `${wsBase}/cdl/session/${b.session_id}/device?token=${stolenToken}`;
    const ws = await open(url);
    const inv = await new Promise<Record<string, any>>((res, rej) => {
      ws.on('message', (raw) => {
        try {
          res(JSON.parse(raw.toString()));
        } catch (e) {
          rej(e);
        }
      });
    });
    expect(inv.type).toBe('session_invalid');
    expect(inv.reason).toBe('invalid_phone_token');
    ws.close();
  });

  it('runs the full flow and the browser receives session_complete', async () => {
    const { body: s } = await initiate();
    const browser = await open(`${s.ws_url.replace(/^ws:\/\/[^/]+/, wsBase)}?token=${s.poll_token}`);
    const connected = nextMessage(browser, 'phone_connected');
    const done = nextMessage(browser, 'session_complete');

    const phone = await open(deviceUrl(s));
    const ack = nextMessage(phone, 'session_acknowledged');
    expect((await ack).challenge_id).toBe(s.challenge_id);
    await connected;

    const res = await complete(s.session_id, await sign(s));
    expect(res.status).toBe(200);
    const accepted = (await res.json()) as Record<string, any>;
    expect(accepted.accepted).toBe(true);

    const msg = await done;
    expect(msg.attestation_token).toBe(accepted.attestation_token);

    const status = await fetch(`${base}/cdl/session/${s.session_id}/status`, {
      headers: { Authorization: `Bearer ${s.poll_token}` },
    });
    expect(((await status.json()) as Record<string, any>).state).toBe('complete');

    // H3: a Redis dump must not contain the raw JWT. Load the session hash
    // directly and check both that the raw token is absent and that we did
    // persist its SHA-256 (so operators can still prove which token was
    // issued, just not replay it).
    const { getRedis } = await import('../src/session/store');
    const raw = await getRedis().hgetall(`cdl:session:${s.session_id}`);
    const token = accepted.attestation_token as string;
    expect(token.length).toBeGreaterThan(20);
    for (const [k, v] of Object.entries(raw)) {
      expect(v, `field ${k} must not leak the raw JWT`).not.toContain(token);
    }
    expect(raw.jwt_hash).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(raw.jwt_issued_at).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    // Legacy field must be gone.
    expect(raw.jwt_issued).toBeUndefined();

    browser.close();
    phone.close();
  });

  it('reports accepted=false and withholds the token on a failing liveness decision', async () => {
    // Signed-but-failed attestations still produce a signed JWT record of the
    // attempt (the RP may want to log it), but the /complete response must
    // make the outcome unambiguous: accepted mirrors the liveness decision,
    // and no attestation_token leaks to a client that only checks accepted.
    const { body: s } = await initiate();
    (await open(deviceUrl(s))).close();
    const res = await complete(s.session_id, await sign(s, { liveness_decision: 'fail' }));
    expect(res.status).toBe(200);
    const parsed = (await res.json()) as Record<string, any>;
    expect(parsed.accepted).toBe(false);
    expect(parsed.attestation_token).toBeUndefined();

    // /status for a failed session must also not surface the token.
    const status = await fetch(`${base}/cdl/session/${s.session_id}/status`, {
      headers: { Authorization: `Bearer ${s.poll_token}` },
    });
    const sj = (await status.json()) as Record<string, any>;
    expect(sj.state).toBe('failed');
    expect(sj.attestation_token).toBeUndefined();
  });

  it('rejects a tampered attestation', async () => {
    const { body: s } = await initiate();
    (await open(deviceUrl(s))).close();
    const att = await sign(s, { liveness_decision: 'fail' });
    const res = await complete(s.session_id, { ...att, liveness_decision: 'pass' });
    expect(res.status).toBe(400);
    expect(((await res.json()) as Record<string, any>).error_code).toBe('ERR_INVALID_SIGNATURE');
  });

  // M1 — signature verification must run BEFORE the challenge is burned.
  // Previously, a legit device whose submission happened to arrive with a bad
  // signature (encoding bug, transient key rotation) would still burn the
  // challenge, forcing the real user to re-initiate. The current order
  // (verify then burn) means a retry with the same challenge still works.
  it('M1: a bad-signature submission does NOT burn the challenge; a legit retry still succeeds', async () => {
    const { body: s } = await initiate();
    (await open(deviceUrl(s))).close();

    const legit = await sign(s);
    // Flip liveness_decision after signing to simulate a bad signature.
    const tampered = { ...legit, liveness_decision: 'fail' as const };
    const bad = await complete(s.session_id, tampered);
    expect(bad.status).toBe(400);
    expect(((await bad.json()) as Record<string, any>).error_code).toBe('ERR_INVALID_SIGNATURE');

    // The challenge key must not have been written. Checking the resource
    // directly keeps the test insensitive to error-handling refactors that
    // might swallow the error code but still corrupt state.
    const { getRedis } = await import('../src/session/store');
    expect(await getRedis().exists(`cdl:challenge:${s.challenge_id}:used`)).toBe(0);

    // A legit retry with the SAME challenge_id must succeed because the
    // bad submission did not consume it.
    const ok = await complete(s.session_id, legit);
    expect(ok.status).toBe(200);
    expect(((await ok.json()) as Record<string, any>).accepted).toBe(true);
  });

  it('rejects a second submission and a completed session', async () => {
    const { body: s } = await initiate();
    (await open(deviceUrl(s))).close();
    const att = await sign(s);
    expect((await complete(s.session_id, att)).status).toBe(200);
    const again = await complete(s.session_id, att);
    expect(again.status).toBe(409);
  });

  it('rejects a stale attestation', async () => {
    const { body: s } = await initiate();
    (await open(deviceUrl(s))).close();
    const old = new Date(Date.now() - 200_000).toISOString();
    const res = await complete(s.session_id, await sign(s, { timestamp: old }));
    expect(((await res.json()) as Record<string, any>).error_code).toBe('ERR_TIMESTAMP_STALE');
  });

  it('refuses a submission before the phone connected', async () => {
    const { body: s } = await initiate();
    const res = await complete(s.session_id, await sign(s));
    expect(res.status).toBe(409);
  });

  it('refuses a wrong poll token', async () => {
    const { body: s } = await initiate();
    const res = await fetch(`${base}/cdl/session/${s.session_id}/status`, {
      headers: { Authorization: 'Bearer nope' },
    });
    expect(res.status).toBe(401);
  });

  it('refuses a software_only result when IAL2 is required', async () => {
    const { body: s } = await initiate('IAL2');
    (await open(deviceUrl(s))).close();
    const res = await complete(s.session_id, await sign(s));
    expect(((await res.json()) as Record<string, any>).error_code).toBe('ERR_ATTEST_REQUIRED');
  });

  it('serves a JWKS and a health check', async () => {
    const jwks = (await (await fetch(`${base}/cdl/.well-known/jwks.json`)).json()) as any;
    expect(jwks.keys[0]).toMatchObject({ kty: 'EC', crv: 'P-256' });
    const health = (await (await fetch(`${base}/health`)).json()) as any;
    expect(health.redis).toBe('connected');
  });

  it('returns 429 once the per minute limit is used up', async () => {
    // earlier tests already used part of the budget of 10, keep going until it trips
    let last = 200;
    for (let i = 0; i < 12 && last !== 429; i++) last = (await initiate()).res.status;
    expect(last).toBe(429);
  });
});
