import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// Pins the C1 + C2 fixes for Play Integrity:
//   C1 — the integrity_nonce must be single-use. Reading it with GET only
//        meant a stolen Play Integrity token could be replayed inside the
//        5-minute nonce TTL. GETDEL (via MULTI get+del) closes that window.
//   C2 — the relay must enforce appRecognitionVerdict === PLAY_RECOGNIZED
//        and (for IAL2) MEETS_STRONG_INTEGRITY. Device-level verdicts alone
//        are satisfied by a cloned / sideloaded build on a non-rooted phone.
//
// We stub the Play Integrity decode so the tests do not touch Google's API,
// and inject a FakeRedis that supports multi().get().del().exec().

type Decode = { requestDetails?: { nonce?: string }; deviceIntegrity?: { deviceRecognitionVerdict?: string[] }; appIntegrity?: { appRecognitionVerdict?: string } };
let nextDecode: Decode | null = null;
let decodeShouldThrow = false;

vi.mock('googleapis', () => ({
  google: {
    auth: { GoogleAuth: class {} },
    playintegrity: () => ({
      v1: {
        decodeIntegrityToken: async () => {
          if (decodeShouldThrow) throw new Error('simulated api failure');
          return { data: { tokenPayloadExternal: nextDecode } };
        },
      },
    }),
  },
}));

class FakeRedis {
  store = new Map<string, string>();
  async set(key: string, value: string) {
    this.store.set(key, value);
    return 'OK' as const;
  }
  async get(key: string) {
    return this.store.get(key) ?? null;
  }
  async del(key: string) {
    const existed = this.store.delete(key);
    return existed ? 1 : 0;
  }
  multi() {
    const ops: Array<['get' | 'del', string]> = [];
    const chain = {
      get: (k: string) => {
        ops.push(['get', k]);
        return chain;
      },
      del: (k: string) => {
        ops.push(['del', k]);
        return chain;
      },
      exec: async () => {
        const results: Array<[Error | null, unknown]> = [];
        for (const [op, k] of ops) {
          if (op === 'get') results.push([null, this.store.get(k) ?? null]);
          else {
            const existed = this.store.delete(k);
            results.push([null, existed ? 1 : 0]);
          }
        }
        return results;
      },
    };
    return chain;
  }
}

let fake: FakeRedis;

beforeEach(async () => {
  fake = new FakeRedis();
  nextDecode = null;
  decodeShouldThrow = false;
  const { setRedis } = await import('../src/session/store');
  setRedis(fake as unknown as never);
  process.env.INTEGRITY_NONCE_SECRET = 'test-secret';
  process.env.GOOGLE_PLAY_PACKAGE_NAME = 'com.example.app';
});

afterEach(async () => {
  const { setRedis } = await import('../src/session/store');
  setRedis(null);
  delete process.env.INTEGRITY_NONCE_SECRET;
  delete process.env.GOOGLE_PLAY_PACKAGE_NAME;
});

describe('C1 — Play Integrity nonce single-use', () => {
  it('accepts the first submission and deletes the nonce atomically', async () => {
    const { buildIntegrityNonce, createIntegrityNonce, verifyPlayIntegrity } = await import(
      '../src/attestation/google'
    );
    const nonce = buildIntegrityNonce('sess-1', 'chal-1');
    await createIntegrityNonce('sess-1', 'chal-1');
    expect(fake.store.has('cdl:session:sess-1:integrity_nonce')).toBe(true);

    nextDecode = {
      requestDetails: { nonce },
      deviceIntegrity: { deviceRecognitionVerdict: ['MEETS_DEVICE_INTEGRITY', 'MEETS_STRONG_INTEGRITY'] },
      appIntegrity: { appRecognitionVerdict: 'PLAY_RECOGNIZED' },
    };
    const r = await verifyPlayIntegrity('token-1', 'sess-1');
    expect(r.valid).toBe(true);
    // The nonce must be gone after the first successful verify.
    expect(fake.store.has('cdl:session:sess-1:integrity_nonce')).toBe(false);
  });

  it('rejects a replay of the same (session, token) because the nonce was consumed', async () => {
    const { buildIntegrityNonce, createIntegrityNonce, verifyPlayIntegrity } = await import(
      '../src/attestation/google'
    );
    const nonce = buildIntegrityNonce('sess-2', 'chal-2');
    await createIntegrityNonce('sess-2', 'chal-2');
    nextDecode = {
      requestDetails: { nonce },
      deviceIntegrity: { deviceRecognitionVerdict: ['MEETS_DEVICE_INTEGRITY', 'MEETS_STRONG_INTEGRITY'] },
      appIntegrity: { appRecognitionVerdict: 'PLAY_RECOGNIZED' },
    };
    await verifyPlayIntegrity('token-2', 'sess-2');
    await expect(verifyPlayIntegrity('token-2', 'sess-2')).rejects.toMatchObject({
      code: 'ERR_GOOGLE_INTEGRITY_NONCE',
    });
  });

  it('deletes the nonce even when the verdict is rejected downstream (otherwise an attacker could probe without burning it)', async () => {
    // The GETDEL happens before the device / app checks, so a Play response
    // with a matching nonce but a weak verdict still consumes the nonce.
    const { buildIntegrityNonce, createIntegrityNonce, verifyPlayIntegrity } = await import(
      '../src/attestation/google'
    );
    const nonce = buildIntegrityNonce('sess-3', 'chal-3');
    await createIntegrityNonce('sess-3', 'chal-3');
    nextDecode = {
      requestDetails: { nonce },
      deviceIntegrity: { deviceRecognitionVerdict: [] },
      appIntegrity: { appRecognitionVerdict: 'PLAY_RECOGNIZED' },
    };
    await expect(verifyPlayIntegrity('tok', 'sess-3')).rejects.toMatchObject({
      code: 'ERR_GOOGLE_INTEGRITY_LOW',
    });
    expect(fake.store.has('cdl:session:sess-3:integrity_nonce')).toBe(false);
  });
});

describe('C2 — Play Integrity app + device verdicts', () => {
  it('rejects when the app is not PLAY_RECOGNIZED (cloned / sideloaded build)', async () => {
    const { buildIntegrityNonce, createIntegrityNonce, verifyPlayIntegrity } = await import(
      '../src/attestation/google'
    );
    const nonce = buildIntegrityNonce('sess-4', 'chal-4');
    await createIntegrityNonce('sess-4', 'chal-4');
    nextDecode = {
      requestDetails: { nonce },
      deviceIntegrity: { deviceRecognitionVerdict: ['MEETS_DEVICE_INTEGRITY', 'MEETS_STRONG_INTEGRITY'] },
      appIntegrity: { appRecognitionVerdict: 'UNRECOGNIZED_VERSION' },
    };
    await expect(verifyPlayIntegrity('tok', 'sess-4')).rejects.toMatchObject({
      code: 'ERR_GOOGLE_INTEGRITY_INVALID',
    });
  });

  it('rejects when deviceRecognitionVerdict is below MEETS_DEVICE_INTEGRITY', async () => {
    const { buildIntegrityNonce, createIntegrityNonce, verifyPlayIntegrity } = await import(
      '../src/attestation/google'
    );
    const nonce = buildIntegrityNonce('sess-5', 'chal-5');
    await createIntegrityNonce('sess-5', 'chal-5');
    nextDecode = {
      requestDetails: { nonce },
      deviceIntegrity: { deviceRecognitionVerdict: ['MEETS_BASIC_INTEGRITY'] },
      appIntegrity: { appRecognitionVerdict: 'PLAY_RECOGNIZED' },
    };
    await expect(verifyPlayIntegrity('tok', 'sess-5')).rejects.toMatchObject({
      code: 'ERR_GOOGLE_INTEGRITY_LOW',
    });
  });

  it('verifyHardware demands MEETS_STRONG_INTEGRITY for IAL2 even if device verdict passes', async () => {
    const { buildIntegrityNonce, createIntegrityNonce } = await import('../src/attestation/google');
    const { verifyHardware } = await import('../src/attestation/hardware');
    const { makeAttestation, makeSession } = await import('./helpers');

    const att = await makeAttestation({
      device_platform: 'android',
      device_attestation: 'play-integrity-token',
    });
    const nonce = buildIntegrityNonce(att.session_id, att.challenge_id);
    await createIntegrityNonce(att.session_id, att.challenge_id);
    nextDecode = {
      requestDetails: { nonce },
      // Device integrity passes but not STRONG.
      deviceIntegrity: { deviceRecognitionVerdict: ['MEETS_DEVICE_INTEGRITY'] },
      appIntegrity: { appRecognitionVerdict: 'PLAY_RECOGNIZED' },
    };
    process.env.HARDWARE_ATTESTATION = 'enforce';
    try {
      await expect(
        verifyHardware(att, makeSession({ assurance_required: 'IAL2' })),
      ).rejects.toMatchObject({ code: 'ERR_GOOGLE_INTEGRITY_LOW' });
    } finally {
      delete process.env.HARDWARE_ATTESTATION;
    }
  });

  it('verifyHardware accepts MEETS_STRONG_INTEGRITY and returns strong=true', async () => {
    const { buildIntegrityNonce, createIntegrityNonce } = await import('../src/attestation/google');
    const { verifyHardware } = await import('../src/attestation/hardware');
    const { makeAttestation, makeSession } = await import('./helpers');

    const att = await makeAttestation({
      device_platform: 'android',
      device_attestation: 'play-integrity-token',
    });
    const nonce = buildIntegrityNonce(att.session_id, att.challenge_id);
    await createIntegrityNonce(att.session_id, att.challenge_id);
    nextDecode = {
      requestDetails: { nonce },
      deviceIntegrity: { deviceRecognitionVerdict: ['MEETS_DEVICE_INTEGRITY', 'MEETS_STRONG_INTEGRITY'] },
      appIntegrity: { appRecognitionVerdict: 'PLAY_RECOGNIZED' },
    };
    process.env.HARDWARE_ATTESTATION = 'enforce';
    try {
      const result = await verifyHardware(att, makeSession({ assurance_required: 'IAL2' }));
      expect(result).toEqual({ attested: true, strong: true });
    } finally {
      delete process.env.HARDWARE_ATTESTATION;
    }
  });
});
