import Redis from 'ioredis';
import { config } from '../config';
import { CDLSession, SessionState } from './types';

const DAY = 24 * 3600;
let client: Redis | null = null;

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

export function setRedis(next: Redis | null) {
  client = next;
}

const NULLABLE = [
  'liveness_decision',
  'assurance_level',
  'attestation_hash',
  'jwt_issued',
  'webhook_url',
] as const;

export async function saveSession(s: CDLSession): Promise<void> {
  const r = getRedis();
  const flat: Record<string, string> = {};
  for (const [k, v] of Object.entries(s)) flat[k] = v === null ? '' : String(v);

  const key = `cdl:session:${s.session_id}`;
  await r
    .multi()
    .hset(key, flat)
    .expire(key, DAY)
    .set(`${key}:state`, s.state, 'EX', DAY)
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
  if (patch.state) m.set(`${key}:state`, patch.state, 'EX', DAY);
  await m.exec();
}
