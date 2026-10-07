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
    const { getRedis } = await import('../src/session/store');
    getRedis().disconnect();
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

  it('QR payload decodes to the documented shape', async () => {
    const { body } = await initiate();
    expect(body.challenge_sequence).toHaveLength(3);
    expect(new Set(body.challenge_sequence).size).toBe(3);
    const qr = JSON.parse(Buffer.from(body.qr_payload, 'base64').toString('utf8'));
    expect(Object.keys(qr).sort()).toEqual(
      ['challenge_id', 'challenge_sequence', 'expires_at', 'relay_http', 'relay_ws', 'session_id', 'version'].sort(),
    );
    expect(qr.relay_ws).toContain(`/cdl/session/${body.session_id}/device`);
  });

  it('runs the full flow and the browser receives session_complete', async () => {
    const { body: s } = await initiate();
    const browser = await open(`${s.ws_url.replace(/^ws:\/\/[^/]+/, wsBase)}?token=${s.poll_token}`);
    const connected = nextMessage(browser, 'phone_connected');
    const done = nextMessage(browser, 'session_complete');

    const phone = await open(`${wsBase}/cdl/session/${s.session_id}/device`);
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
    browser.close();
    phone.close();
  });

  it('rejects a tampered attestation', async () => {
    const { body: s } = await initiate();
    (await open(`${wsBase}/cdl/session/${s.session_id}/device`)).close();
    const att = await sign(s, { liveness_decision: 'fail' });
    const res = await complete(s.session_id, { ...att, liveness_decision: 'pass' });
    expect(res.status).toBe(400);
    expect(((await res.json()) as Record<string, any>).error_code).toBe('ERR_INVALID_SIGNATURE');
  });

  it('rejects a second submission and a completed session', async () => {
    const { body: s } = await initiate();
    (await open(`${wsBase}/cdl/session/${s.session_id}/device`)).close();
    const att = await sign(s);
    expect((await complete(s.session_id, att)).status).toBe(200);
    const again = await complete(s.session_id, att);
    expect(again.status).toBe(409);
  });

  it('rejects a stale attestation', async () => {
    const { body: s } = await initiate();
    (await open(`${wsBase}/cdl/session/${s.session_id}/device`)).close();
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
    (await open(`${wsBase}/cdl/session/${s.session_id}/device`)).close();
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
