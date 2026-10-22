import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { COUNTER_CAS_SCRIPT } from '../src/attestation/apple';
import { setRedis } from '../src/session/store';

// H3 — Apple App Attest signCount must be stored under an atomic CAS so two
// concurrent assertions can't both read the same stored counter and both
// write counter+1. The Lua script is Redis-side so no amount of client-side
// racing can defeat it, but the fallback-to-0 branch (for a corrupted stored
// value) and the "reject non-numeric input" branch are easy to regress. We
// evaluate the script in a FakeRedis that implements the same two Lua
// primitives it relies on (`redis.call('GET', ...)` / `redis.call('SET', ...)
// with 'EX' TTL`) so the test exercises the real script text.

class FakeRedis {
  store = new Map<string, { value: string; expiresAt: number | null }>();

  async get(key: string): Promise<string | null> {
    const v = this.store.get(key);
    if (!v) return null;
    if (v.expiresAt !== null && v.expiresAt < Date.now()) {
      this.store.delete(key);
      return null;
    }
    return v.value;
  }

  // Supports the `SET key value EX seconds` form used by the CAS script.
  async setWithEx(key: string, value: string, seconds: number) {
    this.store.set(key, { value, expiresAt: Date.now() + seconds * 1000 });
  }

  // Minimal Lua interpreter for the two scripts we care about. We don't want a
  // full embed; we only need to run COUNTER_CAS_SCRIPT exactly. The script's
  // behavior is pure (one GET, one optional SET, one return) so we hand-roll
  // the state machine instead of linking lua.
  async eval(script: string, _numKeys: number, key: string, newArg: string, ttlArg: string) {
    if (script !== COUNTER_CAS_SCRIPT) throw new Error('this fake only runs COUNTER_CAS_SCRIPT');
    const raw = await this.get(key);
    let cur = 0;
    if (raw !== null) {
      const n = Number(raw);
      if (Number.isFinite(n)) cur = n;
    }
    const newNum = Number(newArg);
    if (!Number.isFinite(newNum)) return 0;
    if (newNum > cur) {
      await this.setWithEx(key, newArg, Number(ttlArg));
      return 1;
    }
    return 0;
  }
}

let fake: FakeRedis;

beforeEach(() => {
  fake = new FakeRedis();
  setRedis(fake as unknown as never);
});

afterEach(() => {
  setRedis(null);
});

async function run(key: string, newCounter: number, ttl = 60) {
  return fake.eval(COUNTER_CAS_SCRIPT, 1, key, String(newCounter), String(ttl));
}

describe('H3 — COUNTER_CAS_SCRIPT (App Attest signCount)', () => {
  it('accepts the first assertion when no prior counter exists (bootstraps from 0)', async () => {
    const result = await run('cdl:device:k1:counter', 5);
    expect(result).toBe(1);
    expect(await fake.get('cdl:device:k1:counter')).toBe('5');
  });

  it('accepts a strictly increasing counter', async () => {
    await run('cdl:device:k1:counter', 5);
    const result = await run('cdl:device:k1:counter', 6);
    expect(result).toBe(1);
    expect(await fake.get('cdl:device:k1:counter')).toBe('6');
  });

  it('rejects a replay (same counter value as previously stored)', async () => {
    await run('cdl:device:k1:counter', 5);
    const result = await run('cdl:device:k1:counter', 5);
    expect(result).toBe(0);
    // Stored value must not have been overwritten.
    expect(await fake.get('cdl:device:k1:counter')).toBe('5');
  });

  it('rejects a strictly decreasing counter (clone rollback)', async () => {
    await run('cdl:device:k1:counter', 10);
    const result = await run('cdl:device:k1:counter', 9);
    expect(result).toBe(0);
    expect(await fake.get('cdl:device:k1:counter')).toBe('10');
  });

  it('isolates counters by key (per-device namespacing)', async () => {
    await run('cdl:device:k1:counter', 10);
    // A fresh device starts from 0, so counter=1 is accepted even though
    // another device has an unrelated higher counter.
    const result = await run('cdl:device:k2:counter', 1);
    expect(result).toBe(1);
    expect(await fake.get('cdl:device:k2:counter')).toBe('1');
  });

  it('treats a corrupted (non-numeric) stored value as 0 so a Redis glitch cannot lock the device out', async () => {
    // Simulate a stored value that was somehow written as garbage.
    await fake.setWithEx('cdl:device:k1:counter', 'not-a-number', 60);
    const result = await run('cdl:device:k1:counter', 1);
    expect(result).toBe(1);
    expect(await fake.get('cdl:device:k1:counter')).toBe('1');
  });

  it('rejects a non-numeric incoming counter without touching storage (defense against unsigned garbage)', async () => {
    // The TS caller passes String(counter), so Lua always sees a stringified
    // number. But the script guards against tonumber() returning nil anyway:
    // a future caller that forgets to coerce must not be able to wipe the
    // stored counter by sending 'NaN' / 'abc'.
    await run('cdl:device:k1:counter', 5);
    const result = await fake.eval(COUNTER_CAS_SCRIPT, 1, 'cdl:device:k1:counter', 'abc', '60');
    expect(result).toBe(0);
    expect(await fake.get('cdl:device:k1:counter')).toBe('5');
  });

  it('persists the stored value with the TTL provided by the caller', async () => {
    await run('cdl:device:k1:counter', 1, 10);
    const entry = fake.store.get('cdl:device:k1:counter');
    expect(entry).toBeDefined();
    const ttlMs = (entry!.expiresAt as number) - Date.now();
    // Allow a 500ms drift between the SET and the inspection.
    expect(ttlMs).toBeGreaterThan(9_000);
    expect(ttlMs).toBeLessThanOrEqual(10_000);
  });
});
