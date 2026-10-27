import type Redis from 'ioredis';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { CDLError } from '../src/errors';
import { completeSession } from '../src/session/complete';
import { setRedis } from '../src/session/store';
import { makeAttestation, makeSession } from './helpers';

// Pins the stub_mode policy that the server MUST reject `stub_mode: true`
// from any relying_party outside STUB_MODE_RP_ALLOWLIST. The marker is a
// defense-in-depth check against a hostile fork that flips the SDK's
// `allowStubMode` flag and ships an "always passes" build — accepting it
// from an arbitrary RP would make the Phase-1 stub escape hatch into a
// production bypass.
//
// The policy sits BEFORE challenge / signature / hardware verification, so
// we can exercise it without exercising those paths: on the reject branch
// the error is raised before any Redis mutation; on the accept branch we
// pre-seed the challenge-used bit so the next check (ERR_CHALLENGE_REUSED)
// fires and proves we got past the policy gate.

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
  existsKeys = new Set<string>();
  async hgetall(key: string) {
    return this.hashes.get(key) ?? {};
  }
  async exists(key: string) {
    return this.existsKeys.has(key) ? 1 : 0;
  }
  async set(_key: string, _value: string, ..._rest: unknown[]) {
    return 'OK';
  }
  multi() {
    return new FakeMulti(this);
  }
}

type LogCall = { level: 'info' | 'warn'; obj: Record<string, unknown>; msg: string };

function makeLog() {
  const calls: LogCall[] = [];
  const log = {
    info: (obj: Record<string, unknown>, msg: string) => calls.push({ level: 'info', obj, msg }),
    warn: (obj: Record<string, unknown>, msg: string) => calls.push({ level: 'warn', obj, msg }),
    error: () => {},
    debug: () => {},
    trace: () => {},
    fatal: () => {},
    child: () => log,
    level: 'info',
  };
  return { log: log as unknown as import('fastify').FastifyBaseLogger, calls };
}

function seedSession(fake: FakeRedis, session: ReturnType<typeof makeSession>) {
  const flat: Hash = {};
  for (const [k, v] of Object.entries(session)) flat[k] = v === null ? '' : String(v);
  fake.hashes.set(`cdl:session:${session.session_id}`, flat);
}

let fake: FakeRedis;
const RP_ALLOWED = 'ci-allowed-rp.example';
const RP_FORBIDDEN = 'production-bank.example';
const ORIGINAL_ENV = process.env.STUB_MODE_RP_ALLOWLIST;

beforeEach(() => {
  fake = new FakeRedis();
  setRedis(fake as unknown as Redis);
  process.env.STUB_MODE_RP_ALLOWLIST = RP_ALLOWED;
});

afterEach(() => {
  setRedis(null);
  if (ORIGINAL_ENV === undefined) delete process.env.STUB_MODE_RP_ALLOWLIST;
  else process.env.STUB_MODE_RP_ALLOWLIST = ORIGINAL_ENV;
});

describe('stub_mode policy — config', () => {
  it('parses STUB_MODE_RP_ALLOWLIST as CSV with trim + empty-filter', async () => {
    const { config } = await import('../src/config');
    process.env.STUB_MODE_RP_ALLOWLIST = ' a.example , ,b.example,';
    expect(config().stubModeRpAllowlist).toEqual(['a.example', 'b.example']);
  });

  it('defaults to empty allowlist when the env var is unset', async () => {
    const { config } = await import('../src/config');
    delete process.env.STUB_MODE_RP_ALLOWLIST;
    expect(config().stubModeRpAllowlist).toEqual([]);
  });
});

describe('stub_mode policy — schema', () => {
  it('accepts stub_mode: true at the attestation root', async () => {
    // The relay used to reject this with ERR_SCHEMA_INVALID "invalid" because
    // the schema was .strict() without the field. Guard against regression.
    const att = await makeAttestation({ stub_mode: true });
    expect(att.stub_mode).toBe(true);
  });

  it('accepts stub_mode: false', async () => {
    const att = await makeAttestation({ stub_mode: false });
    expect(att.stub_mode).toBe(false);
  });

  it('accepts an attestation that omits stub_mode (older SDK builds)', async () => {
    const att = await makeAttestation();
    expect(att.stub_mode).toBeUndefined();
  });
});

describe('stub_mode policy — enforcement in completeSession', () => {
  it('rejects stub_mode: true when the session RP is not on the allowlist', async () => {
    const session = makeSession({ relying_party: RP_FORBIDDEN });
    seedSession(fake, session);
    const att = await makeAttestation({ stub_mode: true });
    const { log } = makeLog();

    await expect(completeSession(session.session_id, att, log)).rejects.toMatchObject({
      code: 'ERR_STUB_MODE_FORBIDDEN',
    });
  });

  it('attaches HTTP 403 to ERR_STUB_MODE_FORBIDDEN (so the operator log can distinguish policy refusal from a 400 client bug)', () => {
    expect(new CDLError('ERR_STUB_MODE_FORBIDDEN').status).toBe(403);
  });

  it('does NOT reject stub_mode: true when the RP is on the allowlist (falls through to the next check)', async () => {
    const session = makeSession({ relying_party: RP_ALLOWED });
    seedSession(fake, session);
    // Pre-burn the challenge so the next gate after the policy (step 4) fires.
    // Any error that is NOT ERR_STUB_MODE_FORBIDDEN proves the policy let us
    // through.
    fake.existsKeys.add(`cdl:challenge:${session.challenge_id}:used`);
    const att = await makeAttestation({ stub_mode: true });
    const { log } = makeLog();

    await expect(completeSession(session.session_id, att, log)).rejects.toMatchObject({
      code: 'ERR_CHALLENGE_REUSED',
    });
  });

  it('does not fire for stub_mode: false (the SDK explicitly reported "not stub")', async () => {
    const session = makeSession({ relying_party: RP_FORBIDDEN });
    seedSession(fake, session);
    fake.existsKeys.add(`cdl:challenge:${session.challenge_id}:used`);
    const att = await makeAttestation({ stub_mode: false });
    const { log } = makeLog();

    await expect(completeSession(session.session_id, att, log)).rejects.toMatchObject({
      code: 'ERR_CHALLENGE_REUSED',
    });
  });

  it('does not fire when stub_mode is absent (older SDK)', async () => {
    const session = makeSession({ relying_party: RP_FORBIDDEN });
    seedSession(fake, session);
    fake.existsKeys.add(`cdl:challenge:${session.challenge_id}:used`);
    const att = await makeAttestation();
    const { log } = makeLog();

    await expect(completeSession(session.session_id, att, log)).rejects.toMatchObject({
      code: 'ERR_CHALLENGE_REUSED',
    });
  });

  it('policy fires AFTER session-state checks so an expired session returns ERR_SESSION_EXPIRED, not ERR_STUB_MODE_FORBIDDEN', async () => {
    // Ordering matters: if the policy ran before state checks, a leaked
    // session_id with stub_mode:true would surface as 403, hinting the RP
    // is on the allowlist (or isn't) based on timing. State checks run first
    // so expired/unknown sessions return their native error.
    const session = makeSession({
      relying_party: RP_FORBIDDEN,
      expires_at: new Date(Date.now() - 1000).toISOString(),
    });
    seedSession(fake, session);
    const att = await makeAttestation({ stub_mode: true });
    const { log } = makeLog();

    await expect(completeSession(session.session_id, att, log)).rejects.toMatchObject({
      code: 'ERR_SESSION_EXPIRED',
    });
  });
});

describe('stub_mode policy — logging', () => {
  it('emits a warn-level stub_mode_submitted log with allowed=false on the reject branch', async () => {
    const session = makeSession({ relying_party: RP_FORBIDDEN });
    seedSession(fake, session);
    const att = await makeAttestation({ stub_mode: true });
    const { log, calls } = makeLog();

    await expect(completeSession(session.session_id, att, log)).rejects.toThrow();

    const stubLogs = calls.filter((c) => c.msg === 'stub_mode_submitted');
    expect(stubLogs).toHaveLength(1);
    expect(stubLogs[0].level).toBe('warn');
    expect(stubLogs[0].obj).toMatchObject({
      event: 'stub_mode_submitted',
      session_id: session.session_id,
      relying_party: RP_FORBIDDEN,
      allowed: false,
    });
  });

  it('emits a warn-level stub_mode_submitted log with allowed=true on the accept branch', async () => {
    // The accept branch is as interesting as the reject branch: a sudden
    // spike in allowlisted stub traffic is a signal (CI runaway, allowlist
    // drift, test code pointed at prod). Metric fires regardless.
    const session = makeSession({ relying_party: RP_ALLOWED });
    seedSession(fake, session);
    fake.existsKeys.add(`cdl:challenge:${session.challenge_id}:used`);
    const att = await makeAttestation({ stub_mode: true });
    const { log, calls } = makeLog();

    await expect(completeSession(session.session_id, att, log)).rejects.toThrow();

    const stubLogs = calls.filter((c) => c.msg === 'stub_mode_submitted');
    expect(stubLogs).toHaveLength(1);
    expect(stubLogs[0].obj).toMatchObject({
      event: 'stub_mode_submitted',
      session_id: session.session_id,
      relying_party: RP_ALLOWED,
      allowed: true,
    });
  });

  it('does NOT emit stub_mode_submitted when stub_mode is false or absent', async () => {
    const session = makeSession({ relying_party: RP_FORBIDDEN });
    seedSession(fake, session);
    fake.existsKeys.add(`cdl:challenge:${session.challenge_id}:used`);
    const att = await makeAttestation({ stub_mode: false });
    const { log, calls } = makeLog();

    await expect(completeSession(session.session_id, att, log)).rejects.toThrow();

    expect(calls.find((c) => c.msg === 'stub_mode_submitted')).toBeUndefined();
  });
});
