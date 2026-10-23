import Fastify, { FastifyInstance, FastifyRequest } from 'fastify';
import fastifyWebsocket from '@fastify/websocket';
import type { AddressInfo } from 'net';
import type Redis from 'ioredis';
import WebSocket from 'ws';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { setRedis } from '../src/session/store';
import { clearTokenCache } from '../src/session/tokenCache';
import {
  _getWsCountForIp,
  PHONE_COUNTER_MAX_TTL,
  phoneCounterTtl,
  registerWebsocketRoutes,
} from '../src/websocket/handler';
import { rpKeyGenerator } from '../src/middleware/rateLimit';
import { SESSION_GRACE_SECONDS } from '../src/session/store';
import { makeSession } from './helpers';

// Pins the second-review fixes:
//   H-b — the WS endpoints must cap concurrent connections per client IP so
//         an attacker can't hold open thousands of pre-auth sockets.
//   M-a — a late liveness_started frame on the phone WS must not regress a
//         terminal session state (complete / failed / expired) back to
//         'processing'. The handler now reloads and gates on 'phone_connected'.
//   M-b — the rate limiter keys per-{ip, rp} when the request body carries
//         a relying_party, so cross-RP noise can't drain a single RP's bucket.
//   L-a — the phone_connections counter TTL is clamped at 5 minutes
//         regardless of session TTL, so leaked session_ids can't be used
//         to burn reconnect quota long after the session is logically dead.

type Op =
  | ['hset', string, Record<string, string>]
  | ['set', string, string, unknown[]]
  | ['expire', string, number]
  | ['incr', string]
  | ['incr-result', number];

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
      if (op[0] === 'expire') {
        this.store.expireCalls.push([op[1], op[2]]);
      }
    }
    return [] as unknown as [Error | null, unknown][];
  }
}

class FakeRedis {
  hashes = new Map<string, Record<string, string>>();
  counters = new Map<string, number>();
  expireCalls: Array<[string, number]> = [];
  async hgetall(key: string) {
    return this.hashes.get(key) ?? {};
  }
  async incr(key: string) {
    const next = (this.counters.get(key) ?? 0) + 1;
    this.counters.set(key, next);
    return next;
  }
  async expire(key: string, ttl: number) {
    this.expireCalls.push([key, ttl]);
    return 1;
  }
  multi() {
    return new FakeMulti(this);
  }
}

async function buildApp(): Promise<{ app: FastifyInstance; port: number }> {
  const app = Fastify({ logger: false });
  // Mirror server.ts: WS with maxPayload=8 KiB. L-b depends on this.
  await app.register(fastifyWebsocket, { options: { maxPayload: 8 * 1024 } });
  await app.register(registerWebsocketRoutes);
  await app.listen({ port: 0, host: '127.0.0.1' });
  const port = (app.server.address() as AddressInfo).port;
  return { app, port };
}

function seed(store: FakeRedis, session: ReturnType<typeof makeSession>) {
  const flat: Record<string, string> = {};
  for (const [k, v] of Object.entries(session)) flat[k] = v === null ? '' : String(v);
  store.hashes.set(`cdl:session:${session.session_id}`, flat);
}

function openWs(url: string): Promise<{ ws: WebSocket; messages: unknown[] }> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    const messages: unknown[] = [];
    ws.on('message', (raw) => {
      try {
        messages.push(JSON.parse(raw.toString()));
      } catch {
        /* ignore */
      }
    });
    ws.once('open', () => resolve({ ws, messages }));
    ws.once('error', reject);
  });
}

async function waitForMessage(messages: unknown[], timeoutMs = 2000): Promise<unknown> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (messages.length > 0) return messages.shift();
    await new Promise((r) => setTimeout(r, 10));
  }
  throw new Error('ws message timeout');
}

function waitClose(ws: WebSocket, timeoutMs = 2000): Promise<number | undefined> {
  return new Promise((resolve, reject) => {
    if (ws.readyState === WebSocket.CLOSED) return resolve(undefined);
    const timer = setTimeout(() => reject(new Error('ws close timeout')), timeoutMs);
    ws.once('close', (code) => {
      clearTimeout(timer);
      resolve(code);
    });
  });
}

let app: FastifyInstance;
let port: number;
let fake: FakeRedis;

beforeEach(async () => {
  fake = new FakeRedis();
  setRedis(fake as unknown as Redis);
  clearTokenCache();
  ({ app, port } = await buildApp());
});

afterEach(async () => {
  await app?.close();
  setRedis(null);
  clearTokenCache();
});

describe('H-b — WS per-IP connection cap', () => {
  it('closes a connection that would push a single IP past MAX_WS_PER_IP (20)', async () => {
    const s = makeSession({ state: 'initiated' });
    seed(fake, s);
    const url = `ws://127.0.0.1:${port}/cdl/session/${s.session_id}/ws`;

    const opened: WebSocket[] = [];
    try {
      for (let i = 0; i < 20; i++) {
        const { ws } = await openWs(url);
        opened.push(ws);
      }
      // The 21st connect from the same loopback IP must be refused with
      // close code 1013 (try again later) before any auth frame is read.
      const extra = new WebSocket(url);
      const code = await new Promise<number | undefined>((resolve) => {
        extra.once('close', (c) => resolve(c));
        extra.once('error', () => resolve(undefined));
      });
      expect(code).toBe(1013);
    } finally {
      for (const ws of opened) ws.close();
      await Promise.all(opened.map((w) => waitClose(w).catch(() => undefined)));
    }
  });

  it('decrements the per-IP count on close so a long-running test can reopen', async () => {
    const s = makeSession({ state: 'initiated' });
    seed(fake, s);
    const url = `ws://127.0.0.1:${port}/cdl/session/${s.session_id}/ws`;

    const { ws } = await openWs(url);
    expect(_getWsCountForIp('127.0.0.1')).toBeGreaterThan(0);
    ws.close();
    await waitClose(ws);
    // Give the close handler a tick to run.
    await new Promise((r) => setTimeout(r, 20));
    expect(_getWsCountForIp('127.0.0.1')).toBe(0);
  });
});

describe('M-a — phone WS liveness_started must not regress terminal state', () => {
  it('drops a liveness_started frame when the session has already moved to complete', async () => {
    const s = makeSession({ state: 'phone_connected' });
    seed(fake, s);

    const { ws } = await openWs(
      `ws://127.0.0.1:${port}/cdl/session/${s.session_id}/device?token=${s.phone_token}`,
    );
    // Simulate /complete landing between the phone WS open and the first
    // frame: Redis now says state=complete.
    const key = `cdl:session:${s.session_id}`;
    const flat = fake.hashes.get(key)!;
    fake.hashes.set(key, { ...flat, state: 'complete' });

    ws.send(JSON.stringify({ type: 'liveness_started' }));
    // Let the handler drain the message.
    await new Promise((r) => setTimeout(r, 100));

    const after = fake.hashes.get(key)!;
    expect(after.state).toBe('complete');
    ws.close();
    await waitClose(ws);
  });

  it('still accepts liveness_started on a phone_connected session (happy path)', async () => {
    const s = makeSession({ state: 'phone_connected' });
    seed(fake, s);

    const { ws } = await openWs(
      `ws://127.0.0.1:${port}/cdl/session/${s.session_id}/device?token=${s.phone_token}`,
    );
    ws.send(JSON.stringify({ type: 'liveness_started' }));
    await new Promise((r) => setTimeout(r, 100));

    const after = fake.hashes.get(`cdl:session:${s.session_id}`)!;
    expect(after.state).toBe('processing');
    ws.close();
    await waitClose(ws);
  });
});

describe('L-a — phone_connections counter TTL is clamped', () => {
  it('phoneCounterTtl(short session) = session remaining + grace, clamped to 300', () => {
    const expiresSoon = new Date(Date.now() + 30_000).toISOString(); // 30s away
    const ttl = phoneCounterTtl(expiresSoon);
    // sessionTtlSeconds would return ~30 + SESSION_GRACE_SECONDS (3600). We
    // cap at 300.
    expect(ttl).toBe(PHONE_COUNTER_MAX_TTL);
  });

  it('phoneCounterTtl(far-future session) still 300 (never above the cap)', () => {
    const far = new Date(Date.now() + 24 * 3600 * 1000).toISOString();
    expect(phoneCounterTtl(far)).toBe(PHONE_COUNTER_MAX_TTL);
  });

  it('phoneCounterTtl exposes the hard ceiling we committed to (5 min)', () => {
    expect(PHONE_COUNTER_MAX_TTL).toBe(300);
    expect(PHONE_COUNTER_MAX_TTL).toBeLessThan(SESSION_GRACE_SECONDS);
  });

  it('the phone WS actually sets the capped TTL on the counter key', async () => {
    const s = makeSession({ state: 'initiated' });
    seed(fake, s);
    const { ws } = await openWs(
      `ws://127.0.0.1:${port}/cdl/session/${s.session_id}/device?token=${s.phone_token}`,
    );
    // Wait for the handler to call expire().
    await new Promise((r) => setTimeout(r, 100));
    const call = fake.expireCalls.find(
      ([k]) => k === `cdl:session:${s.session_id}:phone_connections`,
    );
    expect(call).toBeDefined();
    expect(call![1]).toBeLessThanOrEqual(PHONE_COUNTER_MAX_TTL);
    expect(call![1]).toBeGreaterThan(0);
    ws.close();
    await waitClose(ws);
  });
});

describe('L-b — WS inbound frame cap', () => {
  it('rejects a frame larger than maxPayload (8 KiB) on the browser WS', async () => {
    const s = makeSession({ state: 'phone_connected' });
    seed(fake, s);
    const { ws } = await openWs(`ws://127.0.0.1:${port}/cdl/session/${s.session_id}/ws`);
    // A 32 KiB JSON payload — well over the 8 KiB cap. The ws library
    // closes the connection with code 1009 (message too big) when the
    // server enforces maxPayload.
    const huge = JSON.stringify({ type: 'auth', token: 'x'.repeat(32 * 1024) });
    ws.send(huge);
    const code = await waitClose(ws);
    // code may be 1009 (received Close frame too big) or the socket may
    // just drop (undefined/1006). Either way, the key assertion is: the
    // connection does not survive a 32 KiB frame.
    expect([1006, 1009, 1011]).toContain(code ?? 1006);
  });
});

describe('M-b — rate limit key generator', () => {
  function fakeReq(ip: string, body: unknown): FastifyRequest {
    return { ip, body } as unknown as FastifyRequest;
  }

  it('returns ip alone when no relying_party is in the body', () => {
    expect(rpKeyGenerator(fakeReq('203.0.113.9', undefined))).toBe('203.0.113.9');
    expect(rpKeyGenerator(fakeReq('203.0.113.9', {}))).toBe('203.0.113.9');
    expect(rpKeyGenerator(fakeReq('203.0.113.9', { relying_party: 42 }))).toBe('203.0.113.9');
  });

  it('returns ip|rp when relying_party is a string', () => {
    expect(
      rpKeyGenerator(fakeReq('203.0.113.9', { relying_party: 'example-bank.com' })),
    ).toBe('203.0.113.9|example-bank.com');
  });

  it('clamps a hostile relying_party at 253 chars so the key cannot blow up Redis memory', () => {
    const long = 'a'.repeat(10_000);
    const key = rpKeyGenerator(fakeReq('203.0.113.9', { relying_party: long }));
    // ip + "|" + 253 chars
    expect(key.length).toBe('203.0.113.9'.length + 1 + 253);
    expect(key.startsWith('203.0.113.9|' + 'a'.repeat(253))).toBe(true);
  });

  it('distinct (ip, rp) pairs produce distinct keys', () => {
    const a = rpKeyGenerator(fakeReq('1.1.1.1', { relying_party: 'rp-a' }));
    const b = rpKeyGenerator(fakeReq('1.1.1.1', { relying_party: 'rp-b' }));
    const c = rpKeyGenerator(fakeReq('2.2.2.2', { relying_party: 'rp-a' }));
    expect(new Set([a, b, c]).size).toBe(3);
  });
});
