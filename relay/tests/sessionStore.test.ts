import type Redis from 'ioredis';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  loadSession,
  saveSession,
  setRedis,
  updateSession,
} from '../src/session/store';
import { makeSession } from './helpers';

// Phase 2 drift — the Phase 2 fix persists hw_attestation_status onto the
// session row so later queries (operator dashboards, per-device clustering
// scripts) can read the downgrade bucket a session landed in. These lock
// the round-trip through the Redis hash encoding so the empty-string ↔ null
// coercion in store.ts can't silently drop the field.

type Hash = Record<string, string>;

class FakeMulti {
  ops: Array<() => void> = [];
  constructor(private store: FakeRedis) {}
  hset(key: string, flat: Hash) {
    this.ops.push(() => {
      const existing = this.store.hashes.get(key) ?? {};
      this.store.hashes.set(key, { ...existing, ...flat });
    });
    return this;
  }
  expire() {
    return this;
  }
  set() {
    return this;
  }
  async exec() {
    for (const op of this.ops) op();
    return [] as unknown as [Error | null, unknown][];
  }
}

class FakeRedis {
  hashes = new Map<string, Hash>();
  async hgetall(key: string) {
    return this.hashes.get(key) ?? {};
  }
  multi() {
    return new FakeMulti(this);
  }
}

let fake: FakeRedis;

beforeEach(() => {
  fake = new FakeRedis();
  setRedis(fake as unknown as Redis);
});

afterEach(() => {
  setRedis(null);
});

describe('session store — hw_attestation_status round-trip', () => {
  it('persists hw_attestation_status=ok through save + load', async () => {
    const s = makeSession({ hw_attestation_status: 'ok' });
    await saveSession(s);
    const loaded = await loadSession(s.session_id);
    expect(loaded?.hw_attestation_status).toBe('ok');
  });

  it('persists hw_attestation_status=failed (downgrade bucket) through save + load', async () => {
    const s = makeSession({ hw_attestation_status: 'failed' });
    await saveSession(s);
    const loaded = await loadSession(s.session_id);
    expect(loaded?.hw_attestation_status).toBe('failed');
  });

  it('persists hw_attestation_status=unsupported through save + load', async () => {
    const s = makeSession({ hw_attestation_status: 'unsupported' });
    await saveSession(s);
    const loaded = await loadSession(s.session_id);
    expect(loaded?.hw_attestation_status).toBe('unsupported');
  });

  // Load-bearing: NULLABLE in store.ts coerces '' back to null on read.
  // If hw_attestation_status is missing from NULLABLE it round-trips as
  // '' instead of null, which would type-poison downstream consumers.
  it('reads null (not empty string) when the field was never set', async () => {
    const s = makeSession();
    await saveSession(s);
    const loaded = await loadSession(s.session_id);
    expect(loaded?.hw_attestation_status).toBeNull();
  });

  // Mirrors the completeSession path: a session row created with hw status
  // null is later patched with the real status via updateSession.
  it('updateSession can patch hw_attestation_status onto an existing row', async () => {
    const s = makeSession();
    await saveSession(s);
    await updateSession(s.session_id, { hw_attestation_status: 'failed' });
    const loaded = await loadSession(s.session_id);
    expect(loaded?.hw_attestation_status).toBe('failed');
  });

  it('updateSession can clear hw_attestation_status back to null', async () => {
    const s = makeSession({ hw_attestation_status: 'ok' });
    await saveSession(s);
    await updateSession(s.session_id, { hw_attestation_status: null });
    const loaded = await loadSession(s.session_id);
    expect(loaded?.hw_attestation_status).toBeNull();
  });
});
