// In-memory one-time handoff for freshly issued attestation JWTs.
//
// We used to persist the raw JWT at cdl:session:{id}:jwt_issued so that
// polling browsers and late-joining WS clients could pick it up. That means
// a Redis dump (snapshot leak, misconfigured RDB file, replica stealing)
// exfiltrates attestation tokens for every session that completed in the
// last 24h, and each token is a bearer credential signed with the relay's
// key — the RP backend would accept it as proof the user passed liveness.
//
// Mitigation: hold the raw token only in process memory (this map) and only
// store its SHA-256 hash in Redis (so an operator can still audit "which
// token was issued" without being able to replay it). The hash is enough
// to answer "is this token still the one we issued?" via timing-safe
// comparison, and the memory copy is bounded by the JWT exp (15 minutes).
//
// Multi-instance deployments: this cache is per-process, so route the
// browser WS and /status for a given session_id to the same relay instance
// (sticky routing on the session_id path parameter). The alternative is to
// have the browser rely exclusively on the WS `session_complete` message —
// which is already pushed at the moment of completion — and treat /status
// as outcome-only (no token). We keep /status returning the token because
// older RPs rely on it; sticky routing is the smaller operational ask.

const TTL_MS = 15 * 60 * 1000;
const cache = new Map<string, { token: string; expiresAt: number }>();

function sweep() {
  const now = Date.now();
  for (const [k, v] of cache) {
    if (v.expiresAt <= now) cache.delete(k);
  }
}

export function cacheToken(sessionId: string, token: string): void {
  sweep();
  cache.set(sessionId, { token, expiresAt: Date.now() + TTL_MS });
}

export function getCachedToken(sessionId: string): string | undefined {
  const hit = cache.get(sessionId);
  if (!hit) return undefined;
  if (hit.expiresAt <= Date.now()) {
    cache.delete(sessionId);
    return undefined;
  }
  return hit.token;
}

export function clearTokenCache(): void {
  cache.clear();
}

// Exposed for tests only — lets a test assert cache size without exporting
// the Map itself (which callers could then mutate).
export function tokenCacheSize(): number {
  return cache.size;
}
