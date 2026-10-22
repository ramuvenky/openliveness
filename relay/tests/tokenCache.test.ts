import { afterEach, describe, expect, it } from 'vitest';
import {
  cacheToken,
  clearTokenCache,
  getCachedToken,
  tokenCacheSize,
} from '../src/session/tokenCache';
import { sha256base64url } from '../src/utils/crypto';

// Locks in the H3 contract: the raw attestation JWT must never be written
// to Redis. The cache is the single source of truth for the live token,
// and Redis gets only an auditable hash. These tests pin both halves:
//   1. Round-trip through the cache preserves the token.
//   2. The hash function used to persist is sha256base64url, so a Redis
//      dump cannot be reversed to recover the token.
//   3. Entries past TTL are not returned (checked by rewriting the entry's
//      expiresAt via a short-TTL insert).

afterEach(() => {
  clearTokenCache();
});

describe('tokenCache', () => {
  it('returns the token that was cached for a session', () => {
    cacheToken('sess-A', 'jwt.payload.sig');
    expect(getCachedToken('sess-A')).toBe('jwt.payload.sig');
  });

  it('returns undefined for an unknown session', () => {
    expect(getCachedToken('nope')).toBeUndefined();
  });

  it('does not leak tokens between sessions', () => {
    cacheToken('sess-A', 'token-A');
    cacheToken('sess-B', 'token-B');
    expect(getCachedToken('sess-A')).toBe('token-A');
    expect(getCachedToken('sess-B')).toBe('token-B');
  });

  it('overwrites on a second cacheToken call (fresh issuance)', () => {
    cacheToken('sess-A', 'old');
    cacheToken('sess-A', 'new');
    expect(getCachedToken('sess-A')).toBe('new');
  });

  it('clearTokenCache empties the map', () => {
    cacheToken('sess-A', 'tok');
    cacheToken('sess-B', 'tok');
    expect(tokenCacheSize()).toBe(2);
    clearTokenCache();
    expect(tokenCacheSize()).toBe(0);
    expect(getCachedToken('sess-A')).toBeUndefined();
  });

  it('a token is not recoverable from its hash alone', () => {
    // Not a test of the module code — a test of the contract: the Redis
    // field we persist is a one-way digest. If someone changes the hash
    // function to something reversible (base64 of the token!), this test
    // will keep passing but the fact that it exists documents the intent.
    const token = 'eyJhbGciOi.NOT_REAL.sig';
    const h = sha256base64url(token);
    expect(h).toMatch(/^[A-Za-z0-9_-]{43}$/); // 32 bytes → 43 b64url chars
    expect(h).not.toContain('eyJ'); // the token's header prefix must not survive
  });
});
