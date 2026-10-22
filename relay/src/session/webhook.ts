import { createHmac } from 'crypto';
import { lookup } from 'dns/promises';
import { isIP } from 'net';
import type { FastifyBaseLogger } from 'fastify';
import { config } from '../config';
import { CDLAttestation, CDLSession } from './types';

// Webhook destinations are provided by the relying party and stored on the
// session. Treat them as attacker-influenced input: an operator misconfig or
// a malicious RP must not turn the relay into an SSRF probe or leak the
// attestation token to a non-TLS endpoint. The checks here are:
//   1. Scheme policy: https only in production. http allowed only in dev,
//      and only when the host resolves to loopback.
//   2. Private/link-local/loopback IPs rejected via DNS resolution so a
//      hostile DNS answer can't bypass textual filters (e.g. a public
//      hostname that resolves to 169.254.169.254 cloud metadata).
//   3. Body signed with HMAC-SHA256 using WEBHOOK_SIGNING_SECRET so the
//      receiving service can verify authenticity before trusting the
//      attestation_token payload.
// A missing WEBHOOK_SIGNING_SECRET causes the webhook to be skipped rather
// than sent unauthenticated — losing a webhook is strictly better than
// sending a forgeable one.

export function normalizeWebhookUrl(
  raw: string,
  env: string = config().nodeEnv,
): string | null {
  let url: URL;
  try {
    url = new URL(/^[a-z][a-z0-9+.-]*:\/\//i.test(raw) ? raw : `https://${raw}`);
  } catch {
    return null;
  }
  if (url.protocol === 'https:') return url.toString();
  if (url.protocol === 'http:' && env !== 'production' && isLoopbackHostname(url.hostname)) {
    return url.toString();
  }
  return null;
}

function isLoopbackHostname(host: string): boolean {
  // Strip IPv6 brackets if present.
  const h = host.startsWith('[') && host.endsWith(']') ? host.slice(1, -1) : host;
  if (h === 'localhost') return true;
  if (h === '127.0.0.1') return true;
  if (h === '::1') return true;
  return false;
}

// IPv4 ranges we must never deliver to (RFC 1918 + friends + cloud metadata).
const BLOCKED_V4: { net: number; mask: number }[] = [
  cidr('0.0.0.0', 8),
  cidr('10.0.0.0', 8),
  cidr('100.64.0.0', 10), // CGNAT
  cidr('127.0.0.0', 8), // loopback (bypassable in dev via allowLoopback)
  cidr('169.254.0.0', 16), // link-local incl. 169.254.169.254 (AWS/GCP/Azure metadata)
  cidr('172.16.0.0', 12),
  cidr('192.0.0.0', 24), // IETF protocol assignments
  cidr('192.168.0.0', 16),
  cidr('198.18.0.0', 15), // benchmarking
  cidr('224.0.0.0', 4), // multicast
  cidr('240.0.0.0', 4), // reserved
];

function cidr(net: string, mask: number): { net: number; mask: number } {
  const parts = net.split('.').map(Number);
  const n = ((parts[0] << 24) | (parts[1] << 16) | (parts[2] << 8) | parts[3]) >>> 0;
  return { net: n, mask };
}

export function isBlockedIp(ip: string, allowLoopback = false): boolean {
  const v = isIP(ip);
  if (v === 4) {
    const parts = ip.split('.').map(Number);
    const num = ((parts[0] << 24) | (parts[1] << 16) | (parts[2] << 8) | parts[3]) >>> 0;
    for (const { net, mask } of BLOCKED_V4) {
      if (allowLoopback && mask === 8 && net === cidr('127.0.0.0', 8).net) continue;
      const bits = mask === 0 ? 0 : (0xffffffff << (32 - mask)) >>> 0;
      if ((num & bits) === (net & bits)) return true;
    }
    return false;
  }
  if (v === 6) {
    const lower = ip.toLowerCase();
    if (lower === '::' || lower === '::1') return !allowLoopback;
    // link-local fe80::/10, unique-local fc00::/7, multicast ff00::/8
    if (/^fe[89ab]/.test(lower)) return true;
    if (/^f[cd]/.test(lower)) return true;
    if (lower.startsWith('ff')) return true;
    // IPv4-mapped: ::ffff:a.b.c.d — delegate to v4 check.
    const mapped = lower.match(/^::ffff:(\d+\.\d+\.\d+\.\d+)$/);
    if (mapped) return isBlockedIp(mapped[1], allowLoopback);
    return false;
  }
  return false;
}

export async function resolveAndCheckHost(host: string, allowLoopback: boolean): Promise<void> {
  const stripped = host.startsWith('[') && host.endsWith(']') ? host.slice(1, -1) : host;
  if (isIP(stripped)) {
    if (isBlockedIp(stripped, allowLoopback)) throw new Error('ssrf: blocked ip literal');
    return;
  }
  const addresses = await lookup(stripped, { all: true });
  if (addresses.length === 0) throw new Error('ssrf: host did not resolve');
  for (const { address } of addresses) {
    if (isBlockedIp(address, allowLoopback)) throw new Error('ssrf: blocked ip via dns');
  }
}

export function signWebhookBody(secret: string, body: string): string {
  return `sha256=${createHmac('sha256', secret).update(body).digest('hex')}`;
}

export async function callWebhook(
  session: CDLSession,
  token: string,
  att: CDLAttestation,
  hardwareAttested: boolean,
  log: FastifyBaseLogger,
) {
  if (!session.webhook_url) return;
  const cfg = config();
  const url = normalizeWebhookUrl(session.webhook_url, cfg.nodeEnv);
  if (!url) {
    log.warn(
      { session_id: session.session_id, webhook_url: session.webhook_url },
      'webhook rejected: scheme not allowed',
    );
    return;
  }
  const parsed = new URL(url);
  const allowLoopback = cfg.nodeEnv !== 'production';
  try {
    await resolveAndCheckHost(parsed.hostname, allowLoopback);
  } catch (e) {
    log.warn(
      { session_id: session.session_id, host: parsed.hostname, reason: (e as Error).message },
      'webhook rejected: SSRF guard',
    );
    return;
  }

  if (!cfg.webhookSigningSecret) {
    log.warn(
      { session_id: session.session_id },
      'webhook skipped: WEBHOOK_SIGNING_SECRET not configured',
    );
    return;
  }

  const body = JSON.stringify({
    event: 'cdl.session.complete',
    session_id: session.session_id,
    relying_party: session.relying_party,
    liveness_decision: att.liveness_decision,
    assurance_level: att.assurance_level,
    attestation_token: token,
    hardware_attested: hardwareAttested,
    device_platform: att.device_platform,
    timestamp: new Date().toISOString(),
  });
  const signature = signWebhookBody(cfg.webhookSigningSecret, body);

  try {
    await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-OpenLiveness-Signature': signature,
      },
      signal: AbortSignal.timeout(5000),
      body,
    });
  } catch {
    // a dead webhook must never fail the session
    log.warn({ session_id: session.session_id }, 'webhook delivery failed');
  }
}
