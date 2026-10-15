// jose already rejects expired tokens. This adds an optional tighter limit on token age,
// useful when a bank wants the check done within a minute or two of the liveness session.
export function isFresh(iat: number | undefined, maxAgeSeconds: number | undefined, nowMs = Date.now()) {
  if (maxAgeSeconds === undefined) return true;
  if (typeof iat !== 'number') return false;
  return nowMs / 1000 - iat <= maxAgeSeconds;
}
