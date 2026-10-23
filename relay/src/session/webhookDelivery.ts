import http from 'http';
import https from 'https';

// Isolated so webhook.test.ts can vi.mock(./webhookDelivery) without having to
// stub the Node http layer. The invariant this helper guards is DNS rebinding:
// the caller has already resolved the hostname and vetted the IP, so the
// actual TCP connect must use *that* IP — not a fresh DNS answer. We force
// that by passing a `lookup` function to node:http/https that returns the
// pinned address, bypassing the socket's default dns.lookup().
//
// We also cap memory per webhook: the response body is `.destroy()`-ed as
// soon as we read the status code. A malicious receiver that sends gigabytes
// of response data within the timeout window cannot push the relay into OOM.

export interface DeliverResult {
  ok: boolean;
  status: number;
}

export interface DeliverOptions {
  headers: Record<string, string>;
  body: string;
  timeoutMs: number;
  // Pre-resolved IP + family from resolveAndCheckHost. The connect() must use
  // this exact address — a fresh DNS lookup here would reopen the rebind
  // window the SSRF guard just closed.
  address: string;
  family: 4 | 6;
}

export async function deliverPinned(url: string, opts: DeliverOptions): Promise<DeliverResult> {
  const u = new URL(url);
  const isHttps = u.protocol === 'https:';
  const mod = isHttps ? https : http;
  const port = u.port ? Number(u.port) : isHttps ? 443 : 80;

  return new Promise<DeliverResult>((resolve, reject) => {
    const req = mod.request(
      {
        hostname: u.hostname,
        port,
        path: `${u.pathname}${u.search}`,
        method: 'POST',
        headers: {
          ...opts.headers,
          Host: u.host,
          'Content-Length': String(Buffer.byteLength(opts.body)),
        },
        // Pin the resolved IP so the socket does not re-resolve. This is the
        // DNS-rebind closure: the only way an attacker's DNS answer reaches
        // the kernel is through this callback, and we only ever hand it the
        // address resolveAndCheckHost already approved. Node's net module
        // calls lookup with `all: true` on recent versions and `all: false`
        // (or unset) on older ones — the callback must handle both signatures.
        lookup: (_hostname, options, cb) => {
          const all = options && typeof options === 'object' && 'all' in options
            ? (options as { all?: boolean }).all
            : false;
          if (all) {
            (cb as unknown as (
              err: Error | null,
              addresses: Array<{ address: string; family: 4 | 6 }>,
            ) => void)(null, [{ address: opts.address, family: opts.family }]);
          } else {
            cb(null, opts.address, opts.family);
          }
        },
        timeout: opts.timeoutMs,
      },
      (res) => {
        const status = res.statusCode ?? 0;
        // M-c: drop the response body without buffering. A slow / malicious
        // receiver that streams megabytes back within the timeout window
        // would otherwise live in the Node heap until the socket closes.
        res.destroy();
        resolve({ ok: status >= 200 && status < 300, status });
      },
    );
    req.once('error', (e) => reject(e));
    req.once('timeout', () => req.destroy(new Error('webhook timeout')));
    req.write(opts.body);
    req.end();
  });
}
