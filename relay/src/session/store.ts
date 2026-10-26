import Redis from 'ioredis';
import { config } from '../config';
import { CDLSession, SessionState } from './types';

// Grace window kept on top of the session's logical lifetime so late /status
// polls can still read a terminal state ('complete', 'failed', 'expired') for
// a short while after expires_at — but not for 24h, which lets a leaked
// session_id be WS-pinged and phone_connections-incremented for a full day
// after logical expiry. 1h is enough for operational tolerance and short
// enough that abuse windows are bounded.
const TERMINAL_GRACE_SECONDS = 3600;

export function sessionTtlSeconds(expiresAtIso: string): number {
  const remaining = Math.ceil((Date.parse(expiresAtIso) - Date.now()) / 1000);
  // Even if the session is already past expires_at (test harnesses do this),
  // we still want the Redis entry to exist long enough for /status to
  // observe it and transition to 'expired'.
  return Math.max(remaining, 0) + TERMINAL_GRACE_SECONDS;
}

export const SESSION_GRACE_SECONDS = TERMINAL_GRACE_SECONDS;

let client: Redis | null = null;
let rateLimitClient: Redis | null = null;

export function getRedis(): Redis {
  if (!client) {
    const c = config();
    client = new Redis(c.redisUrl, {
      password: c.redisPassword,
      maxRetriesPerRequest: 2,
    });
    client.on('error', () => {
      // connection errors surface per request as ERR_RELAY_UNAVAILABLE
    });
  }
  return client;
}

// Dedicated connection used only by @fastify/rate-limit. Keeps the limiter's
// counter traffic off the main client: a Lua script or large pipeline on the
// session path must not stall limit decisions, and a flood of limit checks
// must not starve session reads. Both would otherwise contend on the same
// ioredis command queue (ioredis serializes writes on a single socket).
export function getRateLimitRedis(): Redis {
  if (!rateLimitClient) {
    const c = config();
    rateLimitClient = new Redis(c.redisUrl, {
      password: c.redisPassword,
      maxRetriesPerRequest: 2,
      enableOfflineQueue: false, // in prod we'd rather fail fast than queue
    });
    rateLimitClient.on('error', () => {
      // handled by @fastify/rate-limit via skipOnError
    });
  }
  return rateLimitClient;
}

export function setRedis(next: Redis | null) {
  client = next;
}

export function setRateLimitRedis(next: Redis | null) {
  rateLimitClient = next;
}

export function disconnectAllRedis() {
  client?.disconnect();
  rateLimitClient?.disconnect();
  client = null;
  rateLimitClient = null;
}

const NULLABLE = [
  'liveness_decision',
  'assurance_level',
  'hw_attestation_status',
  'attestation_hash',
  'jwt_hash',
  'jwt_issued_at',
  'webhook_url',
] as const;

export async function saveSession(s: CDLSession): Promise<void> {
  const r = getRedis();
  const flat: Record<string, string> = {};
  for (const [k, v] of Object.entries(s)) flat[k] = v === null ? '' : String(v);

  const key = `cdl:session:${s.session_id}`;
  const ttl = sessionTtlSeconds(s.expires_at);
  await r
    .multi()
    .hset(key, flat)
    .expire(key, ttl)
    .set(`${key}:state`, s.state, 'EX', ttl)
    .exec();
}

export async function loadSession(id: string): Promise<CDLSession | null> {
  const raw = await getRedis().hgetall(`cdl:session:${id}`);
  if (!raw || !raw.session_id) return null;
  const s: Record<string, string | null> = { ...raw };
  for (const k of NULLABLE) if (s[k] === '') s[k] = null;
  return s as unknown as CDLSession;
}

export async function updateSession(
  id: string,
  patch: Partial<CDLSession> & { state?: SessionState },
): Promise<void> {
  const r = getRedis();
  const key = `cdl:session:${id}`;
  const flat: Record<string, string> = { updated_at: new Date().toISOString() };
  for (const [k, v] of Object.entries(patch)) flat[k] = v === null ? '' : String(v);

  const m = r.multi().hset(key, flat);
  // HSET preserves the hash key's existing TTL (set at saveSession time to
  // match expires_at + grace). The :state helper key is overwritten with SET,
  // which would clear the TTL unless we pass KEEPTTL — that keeps its lifetime
  // aligned with the hash and prevents orphan state records outliving the
  // session. KEEPTTL requires Redis 6.0+, which is our baseline.
  if (patch.state) m.set(`${key}:state`, patch.state, 'KEEPTTL');
  await m.exec();
}
