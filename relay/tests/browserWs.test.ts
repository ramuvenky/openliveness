import Fastify, { FastifyInstance } from 'fastify';
import fastifyWebsocket from '@fastify/websocket';
import type { AddressInfo } from 'net';
import type Redis from 'ioredis';
import WebSocket from 'ws';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { setRedis } from '../src/session/store';
import { cacheToken, clearTokenCache } from '../src/session/tokenCache';
import { registerWebsocketRoutes } from '../src/websocket/handler';
import { makeSession } from './helpers';

// Covers the M3 and M5 contracts on the browser-side WS endpoint.
//   M3 — on late-join, the server must resend a terminal frame for 'failed'
//        and 'expired' states, not only 'complete'/'phone_connected'. Older
//        behavior left the browser silent on reconnect to a failed session.
//   M5 — the browser WS is now authenticated by a {type:"auth",token} first
//        frame (previously ?token=, which landed the poll token in LB / CDN
//        access logs). Legacy ?token= remains supported for one release so
//        older web-sdk builds don't break; the server logs a warn when used.
//        A silent client gets closed after BROWSER_AUTH_TIMEOUT_MS.

type Op = ['hset', string, Record<string, string>] | ['set', string, string, unknown[]] | ['expire', string, number] | ['incr', string] | ['incr-result', number];

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
  counters = new Map<string, number>();
  async hgetall(key: string) {
    return this.hashes.get(key) ?? {};
  }
  async incr(key: string) {
    const next = (this.counters.get(key) ?? 0) + 1;
    this.counters.set(key, next);
    return next;
  }
  async expire() {
    return 1;
  }
  multi() {
    return new FakeMulti(this);
  }
}

async function buildApp(): Promise<{ app: FastifyInstance; port: number }> {
  const app = Fastify({ logger: false });
  await app.register(fastifyWebsocket);
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

// Open a WS and start buffering 'message' events immediately on construction,
// before 'open' resolves. Server-side `ws.send` from the handler can land on
// the client between the TCP open and the test attaching its listener; a
// buffer-from-construction pattern makes the test timing-agnostic.
function openWs(url: string): Promise<{ ws: WebSocket; messages: any[] }> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    const messages: any[] = [];
    ws.on('message', (raw) => {
      try {
        messages.push(JSON.parse(raw.toString()));
      } catch {
        /* ignore non-JSON frames */
      }
    });
    ws.once('open', () => resolve({ ws, messages }));
    ws.once('error', reject);
  });
}

async function waitForMessage(messages: any[], timeoutMs = 2000): Promise<any> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (messages.length > 0) return messages.shift();
    await new Promise((r) => setTimeout(r, 10));
  }
  throw new Error('ws message timeout');
}

function waitClose(ws: WebSocket, timeoutMs = 2_000): Promise<void> {
  return new Promise((resolve, reject) => {
    if (ws.readyState === WebSocket.CLOSED) return resolve();
    const timer = setTimeout(() => reject(new Error('ws close timeout')), timeoutMs);
    ws.once('close', () => {
      clearTimeout(timer);
      resolve();
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

describe('browser WS /cdl/session/:id/ws — M3 late-join and M5 auth handshake', () => {
  it('M5: accepts the first-frame {type:"auth",token} and does not log the token in URL', async () => {
    const s = makeSession({ state: 'phone_connected' });
    seed(fake, s);

    const { ws, messages } = await openWs(`ws://127.0.0.1:${port}/cdl/session/${s.session_id}/ws`);
    ws.send(JSON.stringify({ type: 'auth', token: s.poll_token }));

    const frame = await waitForMessage(messages);
    expect(frame.type).toBe('phone_connected');
    ws.close();
    await waitClose(ws);
  });

  it('M5: rejects a first frame with the wrong token and closes the socket', async () => {
    const s = makeSession({ state: 'phone_connected' });
    seed(fake, s);

    const { ws, messages } = await openWs(`ws://127.0.0.1:${port}/cdl/session/${s.session_id}/ws`);
    ws.send(JSON.stringify({ type: 'auth', token: 'bogus' }));

    const frame = await waitForMessage(messages);
    expect(frame).toMatchObject({ type: 'error', error_code: 'ERR_INVALID_POLL_TOKEN' });
    await waitClose(ws);
  });

  it('M5: still accepts the legacy ?token= query string so older web-sdk clients keep working', async () => {
    const s = makeSession({ state: 'phone_connected' });
    seed(fake, s);

    const { ws, messages } = await openWs(
      `ws://127.0.0.1:${port}/cdl/session/${s.session_id}/ws?token=${s.poll_token}`,
    );
    const frame = await waitForMessage(messages);
    expect(frame.type).toBe('phone_connected');
    ws.close();
    await waitClose(ws);
  });

  it('M3: on late-join to a failed session, pushes session_failed and does not sit silent', async () => {
    const s = makeSession({ state: 'failed', liveness_decision: 'fail' });
    seed(fake, s);

    const { ws, messages } = await openWs(`ws://127.0.0.1:${port}/cdl/session/${s.session_id}/ws`);
    ws.send(JSON.stringify({ type: 'auth', token: s.poll_token }));

    const frame = await waitForMessage(messages);
    expect(frame).toMatchObject({
      type: 'session_failed',
      liveness_decision: 'fail',
    });
    ws.close();
  });

  it('M3: on late-join to an expired session, pushes session_expired', async () => {
    const s = makeSession({ state: 'expired' });
    seed(fake, s);

    const { ws, messages } = await openWs(`ws://127.0.0.1:${port}/cdl/session/${s.session_id}/ws`);
    ws.send(JSON.stringify({ type: 'auth', token: s.poll_token }));

    const frame = await waitForMessage(messages);
    expect(frame.type).toBe('session_expired');
    ws.close();
  });

  it('M3+M4: on late-join to complete without a cached token, does not emit a misleading session_complete', async () => {
    // Guards the composite behavior: WS late-join after the 15-min token
    // cache window should not push session_complete with token=undefined —
    // which would resolve the SDK promise as a happy path. The SDK is
    // expected to fall back to /status and surface the explicit 410.
    const s = makeSession({
      state: 'complete',
      liveness_decision: 'pass',
      assurance_level: 'software_only',
    });
    seed(fake, s);
    // No cacheToken call — the token is gone.

    const { ws, messages } = await openWs(`ws://127.0.0.1:${port}/cdl/session/${s.session_id}/ws`);
    ws.send(JSON.stringify({ type: 'auth', token: s.poll_token }));

    // Give the server a brief window to send anything. The assertion is
    // negative: no session_complete frame. Either silence or a different
    // frame type is acceptable.
    await new Promise((r) => setTimeout(r, 150));
    for (const m of messages) expect(m.type).not.toBe('session_complete');
    ws.close();
  });

  it('M3: on late-join to complete WITH a cached token, pushes session_complete carrying the token', async () => {
    const s = makeSession({
      state: 'complete',
      liveness_decision: 'pass',
      assurance_level: 'software_only',
    });
    seed(fake, s);
    cacheToken(s.session_id, 'jwt.header.payload.sig');

    const { ws, messages } = await openWs(`ws://127.0.0.1:${port}/cdl/session/${s.session_id}/ws`);
    ws.send(JSON.stringify({ type: 'auth', token: s.poll_token }));

    const frame = await waitForMessage(messages);
    expect(frame).toMatchObject({
      type: 'session_complete',
      attestation_token: 'jwt.header.payload.sig',
      liveness_decision: 'pass',
    });
    ws.close();
  });
});
