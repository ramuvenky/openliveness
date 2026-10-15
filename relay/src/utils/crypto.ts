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

// Keys sorted alphabetically, no whitespace. Must match the phone SDKs exactly.
export function canonicalize(obj: unknown): string {
  if (typeof obj !== 'object' || obj === null) return JSON.stringify(obj);
  if (Array.isArray(obj)) return '[' + obj.map(canonicalize).join(',') + ']';
  const record = obj as Record<string, unknown>;
  const pairs = Object.keys(record)
    .sort()
    .filter((k) => record[k] !== undefined)
    .map((k) => JSON.stringify(k) + ':' + canonicalize(record[k]));
  return '{' + pairs.join(',') + '}';
}
