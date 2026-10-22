import { createHash, timingSafeEqual } from 'crypto';

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

// Constant time string compare, safe for tokens of different length.
export function safeEqual(a: string, b: string): boolean {
  const ab = Buffer.from(a);
  const bb = Buffer.from(b);
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
