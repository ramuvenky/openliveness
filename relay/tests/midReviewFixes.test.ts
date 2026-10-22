import { createHmac } from 'crypto';
import { afterEach, describe, expect, it } from 'vitest';
import { canonicalize, hmacHex } from '../src/utils/crypto';
import { sessionTtlSeconds, SESSION_GRACE_SECONDS } from '../src/session/store';
import { attestationSchema } from '../src/session/types';
import { config } from '../src/config';
import { sanitizeClientMessage } from '../src/middleware/security';
import { makeAttestation } from './helpers';

// Pins the three pure-helper contracts introduced by the M-tier review fixes:
//   M1 — hmacHex is HMAC-SHA256 of the input under the given secret, encoded
//        as a 64-char lowercase hex string. The log pseudonym depends on the
//        full HMAC, not a truncated prefix: anyone rotating the secret must
//        get a non-correlatable value for the same IP.
//   M2 — config().trustProxy is false unless TRUST_PROXY=true is set. The
//        rate limiter / request logger must not blindly trust X-Forwarded-For
//        in environments without a trusted reverse proxy.
//   M6 — sessionTtlSeconds equals (expires_at - now) + grace, floored at the
//        grace window so an already-expired session still lingers long enough
//        for /status to observe it and transition to 'expired'.

const SAVED_ENV = { ...process.env };
afterEach(() => {
  process.env = { ...SAVED_ENV };
});

describe('M1 — hmacHex (IP log pseudonymization)', () => {
  it('produces a 64-char lowercase hex digest', () => {
    const h = hmacHex('secret', '203.0.113.9');
    expect(h).toMatch(/^[0-9a-f]{64}$/);
  });

  it('matches a reference HMAC-SHA256 computation', () => {
    const reference = createHmac('sha256', 'rotate-me').update('203.0.113.9', 'utf8').digest('hex');
    expect(hmacHex('rotate-me', '203.0.113.9')).toBe(reference);
  });

  it('is deterministic for the same (secret, input)', () => {
    expect(hmacHex('k', '1.2.3.4')).toBe(hmacHex('k', '1.2.3.4'));
  });

  it('decorrelates across secret rotations', () => {
    const a = hmacHex('old-secret', '1.2.3.4');
    const b = hmacHex('new-secret', '1.2.3.4');
    expect(a).not.toBe(b);
  });

  it('decorrelates across inputs under the same secret', () => {
    const a = hmacHex('k', '1.2.3.4');
    const b = hmacHex('k', '1.2.3.5');
    expect(a).not.toBe(b);
  });
});

describe('M2 — config().trustProxy gating', () => {
  it('is false by default (no TRUST_PROXY env)', () => {
    delete process.env.TRUST_PROXY;
    expect(config().trustProxy).toBe(false);
  });

  it('is false when TRUST_PROXY is anything other than the literal string "true"', () => {
    for (const v of ['1', 'yes', 'True', 'TRUE', 'on', '']) {
      process.env.TRUST_PROXY = v;
      expect(config().trustProxy).toBe(false);
    }
  });

  it('is true only when TRUST_PROXY=true', () => {
    process.env.TRUST_PROXY = 'true';
    expect(config().trustProxy).toBe(true);
  });
});

describe('M6 — sessionTtlSeconds (session Redis TTL)', () => {
  it('returns remaining session time plus the grace window', () => {
    const expires = new Date(Date.now() + 60_000).toISOString(); // 60s away
    const ttl = sessionTtlSeconds(expires);
    // Allow a 2s drift for the Date.now() between construction and the call.
    expect(ttl).toBeGreaterThanOrEqual(60 + SESSION_GRACE_SECONDS - 2);
    expect(ttl).toBeLessThanOrEqual(60 + SESSION_GRACE_SECONDS + 2);
  });

  it('clamps to the grace window when the session is already past expires_at', () => {
    const expired = new Date(Date.now() - 10_000).toISOString(); // 10s ago
    expect(sessionTtlSeconds(expired)).toBe(SESSION_GRACE_SECONDS);
  });

  it('exposes a bounded grace window — not the historical 24h', () => {
    // Documents the M6 intent: the former 24h TTL let a leaked session_id be
    // WS-pinged / phone_connections-incremented for a full day after logical
    // expiry. 1h is the ceiling we committed to.
    expect(SESSION_GRACE_SECONDS).toBe(3600);
  });
});

describe('M7 — finite-number contract end-to-end', () => {
  it('attestation schema rejects NaN in layer_scores', async () => {
    const att = await makeAttestation();
    const bad = { ...att, layer_scores: { ...att.layer_scores, layer2_challenge_score: NaN } };
    const parsed = attestationSchema.safeParse(bad);
    expect(parsed.success).toBe(false);
  });

  it('attestation schema rejects Infinity in layer_scores', async () => {
    const att = await makeAttestation();
    const bad = { ...att, layer_scores: { ...att.layer_scores, layer3_rppg_confidence: Infinity } };
    const parsed = attestationSchema.safeParse(bad);
    expect(parsed.success).toBe(false);
  });

  it('attestation schema rejects -Infinity in layer_scores', async () => {
    const att = await makeAttestation();
    const bad = { ...att, layer_scores: { ...att.layer_scores, layer4_lbp_score: -Infinity } };
    const parsed = attestationSchema.safeParse(bad);
    expect(parsed.success).toBe(false);
  });

  it('canonicalize throws when fed a non-finite number directly (defense in depth)', () => {
    expect(() => canonicalize({ x: NaN })).toThrow(/non-finite/);
    expect(() => canonicalize({ x: Infinity })).toThrow(/non-finite/);
    expect(() => canonicalize({ x: -Infinity })).toThrow(/non-finite/);
  });

  it('attestation schema still accepts valid finite scores at the boundaries', async () => {
    const att = await makeAttestation();
    const edge = { ...att, layer_scores: { ...att.layer_scores, layer5_behavioral_score: 0 } };
    expect(attestationSchema.safeParse(edge).success).toBe(true);
    const edgeHigh = { ...att, layer_scores: { ...att.layer_scores, layer5_behavioral_score: 1 } };
    expect(attestationSchema.safeParse(edgeHigh).success).toBe(true);
  });
});

describe('M8 — sanitizeClientMessage', () => {
  it('passes through a benign validation message unchanged', () => {
    expect(sanitizeClientMessage('body/relying_party Required')).toBe('body/relying_party Required');
  });

  it('replaces posix-path-shaped messages with a generic string', () => {
    // A validation error that quotes an internal module path should not reach
    // the client. The server still has the raw text in its logs.
    expect(sanitizeClientMessage('Cannot find module /app/src/attestation/apple.ts')).toBe('invalid request');
  });

  it('replaces Windows-path-shaped messages', () => {
    expect(sanitizeClientMessage('Error at C:\\relay\\dist\\server.js:120')).toBe('invalid request');
  });

  it('replaces node:internal module paths', () => {
    expect(sanitizeClientMessage("Error [ERR_REQUIRE_ESM]: require() of node:crypto")).toBe('invalid request');
  });

  it('keeps only the first line of a multi-line error', () => {
    const multi = 'headline\n    at Object.<anonymous> (/foo/bar.js:1:1)';
    expect(sanitizeClientMessage(multi)).toBe('headline');
  });

  it('caps the message length at 200 characters', () => {
    const long = 'a'.repeat(500);
    expect(sanitizeClientMessage(long).length).toBe(200);
  });

  it('returns a safe fallback for empty / nullish input', () => {
    expect(sanitizeClientMessage('')).toBe('invalid request');
    expect(sanitizeClientMessage(undefined as unknown as string)).toBe('invalid request');
  });
});
