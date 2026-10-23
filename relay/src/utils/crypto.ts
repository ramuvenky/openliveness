import { createHash, createHmac, timingSafeEqual } from 'crypto';

// sha256hex(JSON.stringify(attestation)) style hashing
export function sha256hex(input: string): string {
  return createHash('sha256').update(input, 'utf8').digest('hex');
}

export function sha256bytes(input: string): Buffer {
  return createHash('sha256').update(input, 'utf8').digest();
}

export function base64url(buf: Buffer): string {
  return buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function sha256base64url(input: string): string {
  return base64url(sha256bytes(input));
}

// Keyed one-way hash for pseudonymizing client IPs in logs. A plain SHA-256
// of a /32 IPv4 address covers only 2^32 inputs — a rainbow table reverses
// it trivially. Using HMAC with a long-lived server secret turns that into
// an attack on the secret, not an enumeration of the input space. Rotate
// the secret periodically to invalidate cross-rotation correlation.
export function hmacHex(secret: string, input: string): string {
  return createHmac('sha256', secret).update(input, 'utf8').digest('hex');
}

// Constant-time string compare, safe for tokens of different length.
//
// The comparison is BYTE-level after a default UTF-8 encode (Buffer.from(x)
// with no explicit encoding). Callers must pass raw tokens, not any form
// that might differ in encoding — specifically:
//   - Do not pre-decode a base64url token on one side and compare against
//     the base64url form on the other; one side will be bytes, the other
//     ASCII, and lengths will differ for the trivial case while a crafted
//     payload could coincidentally match length and leak timing.
//   - Do not Unicode-normalize one side (NFC/NFKC) without doing it to the
//     other; "é" (U+00E9) and "e\u0301" compare unequal and the length
//     short-circuit discloses that difference non-constant-time.
// For the relay's two uses (poll_token, phone_token) both sides are random
// base64url strings produced by crypto.randomBytes, so there is nothing to
// normalize — but this comment pins the contract for future callers.
export function safeEqual(a: string, b: string): boolean {
  const ab = Buffer.from(a);
  const bb = Buffer.from(b);
  // Different-length inputs intentionally short-circuit: timingSafeEqual
  // itself requires equal-length buffers, so leaking "length mismatch" via
  // early return is both unavoidable and benign for high-entropy tokens.
  if (ab.length !== bb.length) return false;
  return timingSafeEqual(ab, bb);
}

// Canonical JSON used for the ES256 signature preimage. Rules:
//   - Keys sorted alphabetically (recursive), no whitespace, UTF-8.
//   - Integer-valued doubles with |x| < 1e16 emit as integers ("1" not "1.0").
//   - All other finite doubles emit as fixed-point "%.6f" ("0.85" → "0.850000").
//   - NaN and infinity throw rather than silently become "null".
// Must byte-match ios-sdk/CanonicalJSON.swift and android-sdk/CanonicalJson.kt;
// the fixtures in protocol/canonical-json-vectors.json are the source of truth.
export function canonicalize(obj: unknown): string {
  if (obj === null) return 'null';
  if (typeof obj === 'boolean') return obj ? 'true' : 'false';
  if (typeof obj === 'string') return JSON.stringify(obj);
  if (typeof obj === 'number') return canonicalizeNumber(obj);
  if (Array.isArray(obj)) return '[' + obj.map(canonicalize).join(',') + ']';
  if (typeof obj === 'object') {
    const record = obj as Record<string, unknown>;
    const pairs = Object.keys(record)
      .sort()
      .filter((k) => record[k] !== undefined)
      .map((k) => JSON.stringify(k) + ':' + canonicalize(record[k]));
    return '{' + pairs.join(',') + '}';
  }
  throw new Error(`canonicalize: unsupported type ${typeof obj}`);
}

function canonicalizeNumber(n: number): string {
  if (!Number.isFinite(n)) {
    throw new Error('canonicalize: non-finite number');
  }
  if (Number.isInteger(n) && Math.abs(n) < 1e16) {
    return n.toString();
  }
  return n.toFixed(6);
}
