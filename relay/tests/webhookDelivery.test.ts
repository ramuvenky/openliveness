import http from 'http';
import type { AddressInfo } from 'net';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { deliverPinned } from '../src/session/webhookDelivery';

// Covers the two invariants the delivery helper owns beyond what
// webhook.test.ts can assert via mock:
//   H-a — the socket connects to the pinned (address, family), not the
//         hostname the URL claims. We verify this by giving a hostname that
//         does NOT exist in DNS at all, pinned to 127.0.0.1 where a real
//         server is listening. If the pin works, the request reaches the
//         server; if the socket fell back to a fresh lookup, it would ENOTFOUND.
//   M-c — the response body is drained (res.destroy'd) rather than buffered.
//         We send a server that writes a large chunk, then verify the client
//         returned promptly and did not accumulate the payload.

let server: http.Server;
let port: number;
let lastRequestHeaders: http.IncomingHttpHeaders;
let serverBehavior: 'ok' | 'slow-large' | 'timeout';
let bytesWrittenByServer = 0;

beforeEach(async () => {
  lastRequestHeaders = {};
  serverBehavior = 'ok';
  bytesWrittenByServer = 0;
  server = http.createServer((req, res) => {
    lastRequestHeaders = req.headers;
    // Consume request body so the client isn't blocked by back-pressure.
    req.on('data', () => {});
    req.on('end', () => {
      if (serverBehavior === 'ok') {
        res.statusCode = 200;
        res.end('ok');
        return;
      }
      if (serverBehavior === 'slow-large') {
        res.statusCode = 200;
        res.setHeader('content-type', 'application/octet-stream');
        // 4 MB chunk — larger than any sane webhook ack. If the client
        // buffered this, it would live in the heap until the socket closed.
        const big = Buffer.alloc(4 * 1024 * 1024, 0x61);
        res.write(big, () => {
          bytesWrittenByServer += big.length;
        });
        // Hold the stream open for a bit so we can observe the client has
        // already resolved its promise without waiting for the full body.
        setTimeout(() => res.end(), 200);
        return;
      }
      if (serverBehavior === 'timeout') {
        // Never respond — the client timeout should fire.
        return;
      }
    });
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  port = (server.address() as AddressInfo).port;
});

afterEach(async () => {
  await new Promise<void>((r) => server.close(() => r()));
});

describe('deliverPinned — DNS pin (H-a)', () => {
  it('reaches the server via the pinned IP even when the URL hostname is non-resolvable', async () => {
    // this.hostname.does.not.exist. — a name DNS will not resolve. If the
    // socket performed its own lookup we'd get ENOTFOUND. The pinned
    // lookup callback hands back 127.0.0.1 and the request succeeds.
    const res = await deliverPinned(
      `http://this.hostname.does.not.exist.:${port}/hook`,
      {
        headers: { 'content-type': 'application/json' },
        body: '{"x":1}',
        timeoutMs: 2000,
        address: '127.0.0.1',
        family: 4,
      },
    );
    expect(res.ok).toBe(true);
    expect(res.status).toBe(200);
    // Host header must reflect the URL hostname (not the pinned IP) so
    // TLS SNI / virtual-host routing stays correct.
    expect(lastRequestHeaders.host).toBe(`this.hostname.does.not.exist.:${port}`);
  });

  it('forwards the signed body unchanged to the server', async () => {
    const body = JSON.stringify({ event: 'cdl.session.complete', nonce: 'abc' });
    let received = '';
    server.removeAllListeners('request');
    server.on('request', (req, res) => {
      req.setEncoding('utf8');
      req.on('data', (c) => (received += c));
      req.on('end', () => {
        res.statusCode = 200;
        res.end();
      });
    });
    await deliverPinned(`http://ignored.invalid:${port}/`, {
      headers: {
        'content-type': 'application/json',
        'x-openliveness-signature': 'sha256=deadbeef',
      },
      body,
      timeoutMs: 2000,
      address: '127.0.0.1',
      family: 4,
    });
    expect(received).toBe(body);
  });
});

describe('deliverPinned — response body drain (M-c)', () => {
  it('returns status without buffering a large response payload', async () => {
    serverBehavior = 'slow-large';
    const startHeap = process.memoryUsage().heapUsed;
    const t0 = Date.now();
    const res = await deliverPinned(`http://ignored.invalid:${port}/`, {
      headers: {},
      body: 'x',
      timeoutMs: 5000,
      address: '127.0.0.1',
      family: 4,
    });
    const elapsed = Date.now() - t0;
    const endHeap = process.memoryUsage().heapUsed;
    expect(res.status).toBe(200);
    // The client should resolve as soon as the status line is in. Even on
    // a slow CI this is milliseconds, not the 200ms the server waits
    // before res.end(). Give generous slack but keep the invariant: no
    // waiting on the full body.
    expect(elapsed).toBeLessThan(200);
    // Heap growth must be nowhere near the 4 MB the server tried to send.
    // A 1 MB ceiling is far above any legitimate reason the test would
    // allocate inside this call, and well below the attack payload.
    expect(endHeap - startHeap).toBeLessThan(1024 * 1024);
  });
});

describe('deliverPinned — timeout', () => {
  it('rejects when the server never responds within timeoutMs', async () => {
    serverBehavior = 'timeout';
    await expect(
      deliverPinned(`http://ignored.invalid:${port}/`, {
        headers: {},
        body: 'x',
        timeoutMs: 100,
        address: '127.0.0.1',
        family: 4,
      }),
    ).rejects.toThrow(/timeout/);
  });
});
