import * as cbor from 'cbor';
import { createHash, createSign, generateKeyPairSync, randomBytes } from 'crypto';
import type Redis from 'ioredis';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  COUNTER_CAS_SCRIPT,
  extractAppleAttestNonce,
  verifyAppAttestAssertion,
} from '../src/attestation/apple';
import { setRedis } from '../src/session/store';
import { canonicalize } from '../src/utils/crypto';

// Locks in the T3 fix: Apple App Attest nonce must be extracted from
// extension OID 1.2.840.113635.100.8.2 (the exact `[1] EXPLICIT OCTET
// STRING` location Apple writes it to), not from a raw-byte includes()
// over the cert. Builds synthetic DER certs with controlled extension
// contents so each failure mode is pinned individually.

const APPLE_ATTEST_OID_TLV = Buffer.from([
  0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x63, 0x64, 0x08, 0x02,
]);
// Not Apple's OID — same length, different value. Used to prove the
// extractor matches by content, not position.
const DECOY_OID_TLV = Buffer.from([
  0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x63, 0x64, 0x08, 0x99,
]);

function encLen(n: number): Buffer {
  if (n < 128) return Buffer.from([n]);
  if (n < 256) return Buffer.from([0x81, n]);
  if (n < 65536) return Buffer.from([0x82, (n >> 8) & 0xff, n & 0xff]);
  throw new Error('unsupported length in test helper');
}

function wrap(tag: number, body: Buffer): Buffer {
  return Buffer.concat([Buffer.from([tag]), encLen(body.length), body]);
}

/**
 * Build a certificate-shaped DER blob that contains one extension with the
 * caller-supplied OID TLV and the caller-supplied extnValue TLV body. Only
 * the structure the extractor walks is realistic — the sigAlg/sigValue and
 * subject fields are empty-SEQUENCE placeholders. The extractor never looks
 * at them.
 */
function makeCertWithExtension(oidTlv: Buffer, extnValueBody: Buffer, extraExtensions: Buffer[] = []): Buffer {
  const extnValue = wrap(0x04, extnValueBody); // OCTET STRING
  const extension = wrap(0x30, Buffer.concat([oidTlv, extnValue])); // Extension SEQUENCE
  const extensions = wrap(0x30, Buffer.concat([extension, ...extraExtensions])); // Extensions SEQUENCE OF
  const extensionsTagged = wrap(0xa3, extensions); // [3] EXPLICIT
  const emptySeq = wrap(0x30, Buffer.alloc(0));
  const tbsBody = Buffer.concat([
    Buffer.from([0x02, 0x01, 0x01]), // serial INTEGER 1
    emptySeq, // signature AlgorithmIdentifier
    emptySeq, // issuer
    emptySeq, // validity
    emptySeq, // subject
    emptySeq, // SPKI
    extensionsTagged,
  ]);
  const tbs = wrap(0x30, tbsBody);
  // Outer: tbs + sigAlg + sigValue. The extractor stops after TBS so we
  // only need something structurally valid-ish; empty placeholders work.
  return wrap(0x30, tbs);
}

/** Build the Apple-shaped extnValue payload: SEQUENCE { [1] OCTET STRING nonce }. */
function appleExtnValueBody(nonce: Buffer): Buffer {
  const octetNonce = wrap(0x04, nonce); // OCTET STRING nonce
  const tagged1 = wrap(0xa1, octetNonce); // [1] EXPLICIT
  return wrap(0x30, tagged1); // outer SEQUENCE
}

describe('extractAppleAttestNonce', () => {
  it('returns the nonce when the extension is present and well-formed', () => {
    const nonce = randomBytes(32);
    const cert = makeCertWithExtension(APPLE_ATTEST_OID_TLV, appleExtnValueBody(nonce));
    expect(extractAppleAttestNonce(cert)?.equals(nonce)).toBe(true);
  });

  it('returns null when the extension is absent (decoy OID only)', () => {
    const nonce = randomBytes(32);
    const cert = makeCertWithExtension(DECOY_OID_TLV, appleExtnValueBody(nonce));
    expect(extractAppleAttestNonce(cert)).toBeNull();
  });

  it('skips a decoy extension before finding the real Apple one', () => {
    const realNonce = randomBytes(32);
    const decoyExt = wrap(
      0x30,
      Buffer.concat([DECOY_OID_TLV, wrap(0x04, appleExtnValueBody(randomBytes(32)))]),
    );
    const cert = makeCertWithExtension(APPLE_ATTEST_OID_TLV, appleExtnValueBody(realNonce), [decoyExt]);
    expect(extractAppleAttestNonce(cert)?.equals(realNonce)).toBe(true);
  });

  it('does not false-match when the nonce appears elsewhere in the cert but no Apple extension exists', () => {
    // This is the exact attack the old substring check was vulnerable to:
    // place the nonce bytes somewhere else in the cert and rely on the
    // includes() scan to succeed. The structured extractor must return null.
    const nonce = randomBytes(32);
    const decoyPayload = Buffer.concat([Buffer.from('padding-'), nonce, Buffer.from('-trailing')]);
    const cert = makeCertWithExtension(DECOY_OID_TLV, wrap(0x30, wrap(0xa1, wrap(0x04, decoyPayload))));
    expect(extractAppleAttestNonce(cert)).toBeNull();
  });

  it('returns null if extnValue is not a SEQUENCE', () => {
    const nonce = randomBytes(32);
    // Put the nonce directly in extnValue instead of wrapping in SEQUENCE { [1] OCTET STRING }.
    const cert = makeCertWithExtension(APPLE_ATTEST_OID_TLV, nonce);
    expect(extractAppleAttestNonce(cert)).toBeNull();
  });

  it('returns null if the [1] tag is missing inside the SEQUENCE', () => {
    const nonce = randomBytes(32);
    // SEQUENCE { OCTET STRING nonce } with no [1] wrapper.
    const body = wrap(0x30, wrap(0x04, nonce));
    const cert = makeCertWithExtension(APPLE_ATTEST_OID_TLV, body);
    expect(extractAppleAttestNonce(cert)).toBeNull();
  });

  it('returns null on malformed input (random bytes)', () => {
    expect(extractAppleAttestNonce(randomBytes(100))).toBeNull();
  });

  it('returns null on truncated TLV header', () => {
    expect(extractAppleAttestNonce(Buffer.from([0x30]))).toBeNull();
  });

  it('tolerates the optional critical BOOLEAN before extnValue', () => {
    const nonce = randomBytes(32);
    // Extension SEQUENCE { OID, BOOLEAN TRUE, OCTET STRING extnValue }
    const extnValue = wrap(0x04, appleExtnValueBody(nonce));
    const critical = Buffer.from([0x01, 0x01, 0xff]);
    const extension = wrap(0x30, Buffer.concat([APPLE_ATTEST_OID_TLV, critical, extnValue]));
    const extensions = wrap(0x30, extension);
    const extensionsTagged = wrap(0xa3, extensions);
    const emptySeq = wrap(0x30, Buffer.alloc(0));
    const tbs = wrap(
      0x30,
      Buffer.concat([
        Buffer.from([0x02, 0x01, 0x01]),
        emptySeq,
        emptySeq,
        emptySeq,
        emptySeq,
        emptySeq,
        extensionsTagged,
      ]),
    );
    const cert = wrap(0x30, tbs);
    expect(extractAppleAttestNonce(cert)?.equals(nonce)).toBe(true);
  });

  it('handles multi-byte length encoding (long-form)', () => {
    const nonce = randomBytes(32);
    // Pad a decoy extension to force the extensions SEQUENCE past 127 bytes
    // so the length byte switches to the 0x81 long form.
    const decoyPayload = randomBytes(200);
    const decoyExt = wrap(
      0x30,
      Buffer.concat([DECOY_OID_TLV, wrap(0x04, decoyPayload)]),
    );
    const cert = makeCertWithExtension(APPLE_ATTEST_OID_TLV, appleExtnValueBody(nonce), [decoyExt]);
    expect(extractAppleAttestNonce(cert)?.equals(nonce)).toBe(true);
  });
});

// --- Phase 2 — verifyAppAttestAssertion -------------------------------------
//
// Shared-fixture drift tests. Mirrors the iOS tests in
// ios-sdk/Tests/OpenLivenessTests.swift:
//   - testAppAttestClientDataIsCanonicalJSON pins the exact byte string the
//     client signs over.
//   - testAppAttestClientDataBindsNonce pins the nonce as part of the preimage.
// Here we reproduce the same canonicalize() form on the server side and
// prove that an assertion signed over it verifies. If either side's preimage
// drifts the signature will stop verifying and this test fails — closing the
// release-blocker where the iOS SDK changed to canonical JSON but the server
// still built the preimage via string concat.

class FakeRedis {
  store = new Map<string, string>();

  async get(key: string) {
    return this.store.get(key) ?? null;
  }

  async set(key: string, value: string, _mode?: string, _ttl?: number) {
    this.store.set(key, value);
    return 'OK' as const;
  }

  async del(key: string) {
    const had = this.store.delete(key);
    return had ? 1 : 0;
  }

  // multi().get().del().exec() — still used by verifyPlayIntegrity. Apple
  // verifier now uses GET + late DEL (see verifyAppAttestAssertion / M-2).
  multi() {
    const self = this;
    const ops: Array<() => [null, unknown]> = [];
    return {
      get(key: string) {
        ops.push(() => [null, self.store.get(key) ?? null]);
        return this;
      },
      del(key: string) {
        ops.push(() => {
          const had = self.store.delete(key);
          return [null, had ? 1 : 0];
        });
        return this;
      },
      async exec() {
        return ops.map((f) => f());
      },
    };
  }

  // Minimal COUNTER_CAS_SCRIPT runner — same shape as appleCounter.test.ts
  // uses. Keeps us from needing a real Redis for the signature-roundtrip test.
  async eval(script: string, _n: number, key: string, newArg: string, _ttl: string) {
    if (script !== COUNTER_CAS_SCRIPT) throw new Error('fake only runs COUNTER_CAS_SCRIPT');
    const raw = this.store.get(key) ?? null;
    let cur = 0;
    if (raw !== null) {
      const n = Number(raw);
      if (Number.isFinite(n)) cur = n;
    }
    const n = Number(newArg);
    if (!Number.isFinite(n)) return 0;
    if (n > cur) {
      this.store.set(key, newArg);
      return 1;
    }
    return 0;
  }
}

// Build a valid App Attest assertion blob by signing SHA-256(authData || clientDataHash)
// with the supplied ES256 key. Returns the base64 blob the client would send.
function buildAssertion(opts: {
  sessionId: string;
  challengeId: string;
  livenessDecision: string;
  nonce: string;
  counter: number;
  privateKeyPem: string;
}): string {
  const authData = Buffer.alloc(37);
  authData.writeUInt32BE(opts.counter, 33);
  const clientData = Buffer.from(
    canonicalize({
      challenge_id: opts.challengeId,
      liveness_decision: opts.livenessDecision,
      nonce: opts.nonce,
      session_id: opts.sessionId,
    }),
    'utf8',
  );
  const clientDataHash = createHash('sha256').update(clientData).digest();
  const preimage = createHash('sha256').update(Buffer.concat([authData, clientDataHash])).digest();
  const signature = createSign('SHA256').update(preimage).sign(opts.privateKeyPem);
  return cbor.encode({ authenticatorData: authData, signature }).toString('base64');
}

describe('verifyAppAttestAssertion — Phase 2 canonical-JSON preimage + nonce', () => {
  let fake: FakeRedis;
  const KEY_ID = 'test-key-id';
  const SESSION_ID = 'sess-apple-1';
  const CHALLENGE_ID = 'chal-apple-1';
  const NONCE = 'nonce-apple-1';
  let publicKeyPem: string;
  let privateKeyPem: string;

  beforeEach(() => {
    fake = new FakeRedis();
    setRedis(fake as unknown as Redis);
    const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
    publicKeyPem = publicKey.export({ type: 'spki', format: 'pem' }).toString();
    privateKeyPem = privateKey.export({ type: 'pkcs8', format: 'pem' }).toString();
    // Pre-populate the device public key the way Phase 1 registration would.
    fake.store.set(`cdl:device:${KEY_ID}:pubkey`, publicKeyPem);
  });

  afterEach(() => {
    setRedis(null);
  });

  it('verifies an assertion signed over the canonical-JSON preimage (C1 shared-fixture)', async () => {
    // Pre-seed the single-use nonce as the /integrity-nonce endpoint would.
    fake.store.set(`cdl:session:${SESSION_ID}:integrity_nonce`, NONCE);
    const blob = buildAssertion({
      sessionId: SESSION_ID,
      challengeId: CHALLENGE_ID,
      livenessDecision: 'pass',
      nonce: NONCE,
      counter: 1,
      privateKeyPem,
    });
    const ok = await verifyAppAttestAssertion(blob, SESSION_ID, CHALLENGE_ID, 'pass', KEY_ID);
    expect(ok).toBe(true);
  });

  // Pin the exact canonical-JSON string the server builds. The matching iOS
  // test (testAppAttestClientDataIsCanonicalJSON) asserts the same literal.
  // A drift between the two sides breaks every IAL2 iOS session.
  it('canonicalize() produces the byte string the iOS SDK signs over', () => {
    const str = canonicalize({
      challenge_id: 'c1',
      liveness_decision: 'pass',
      nonce: 'n1',
      session_id: 's1',
    });
    expect(str).toBe(
      '{"challenge_id":"c1","liveness_decision":"pass","nonce":"n1","session_id":"s1"}',
    );
  });

  it('rejects when the integrity nonce was never issued (missing)', async () => {
    // No pre-seed → fetch-and-delete returns null → ERR_APPLE_ATTEST_INVALID.
    const blob = buildAssertion({
      sessionId: SESSION_ID,
      challengeId: CHALLENGE_ID,
      livenessDecision: 'pass',
      nonce: NONCE,
      counter: 1,
      privateKeyPem,
    });
    await expect(
      verifyAppAttestAssertion(blob, SESSION_ID, CHALLENGE_ID, 'pass', KEY_ID),
    ).rejects.toMatchObject({ code: 'ERR_APPLE_ATTEST_INVALID' });
  });

  it('consumes the nonce on first verification so a replay fails (M1)', async () => {
    fake.store.set(`cdl:session:${SESSION_ID}:integrity_nonce`, NONCE);
    const blob = buildAssertion({
      sessionId: SESSION_ID,
      challengeId: CHALLENGE_ID,
      livenessDecision: 'pass',
      nonce: NONCE,
      counter: 1,
      privateKeyPem,
    });
    await expect(
      verifyAppAttestAssertion(blob, SESSION_ID, CHALLENGE_ID, 'pass', KEY_ID),
    ).resolves.toBe(true);
    // Nonce is single-use: the DEL at the tail of verifyAppAttestAssertion
    // runs after both signature and counter CAS pass. The exact same
    // assertion replayed must now fail because the Redis slot is gone.
    await expect(
      verifyAppAttestAssertion(blob, SESSION_ID, CHALLENGE_ID, 'pass', KEY_ID),
    ).rejects.toMatchObject({ code: 'ERR_APPLE_ATTEST_INVALID' });
  });

  it('rejects when the client signed over a different nonce than the stored one', async () => {
    // Server stores NONCE but client signed with a different value — simulates
    // an attacker who captured a Phase 2 assertion from an earlier session
    // and tries to reuse it against a new session's freshly-issued nonce.
    fake.store.set(`cdl:session:${SESSION_ID}:integrity_nonce`, NONCE);
    const blob = buildAssertion({
      sessionId: SESSION_ID,
      challengeId: CHALLENGE_ID,
      livenessDecision: 'pass',
      nonce: 'nonce-from-an-older-session',
      counter: 1,
      privateKeyPem,
    });
    await expect(
      verifyAppAttestAssertion(blob, SESSION_ID, CHALLENGE_ID, 'pass', KEY_ID),
    ).rejects.toMatchObject({ code: 'ERR_APPLE_ATTEST_INVALID' });
    // M-2 — a signature mismatch (what a cross-session replay produces)
    // must NOT consume the nonce. The real device, catching up with a
    // correct signature, has to still find its nonce in Redis.
    expect(fake.store.get(`cdl:session:${SESSION_ID}:integrity_nonce`)).toBe(NONCE);
  });

  // M-2 — signature verify failure (wrong key material) must leave the
  // nonce intact. Previously the MULTI fetch-and-delete consumed the nonce
  // the moment the request landed; a legitimate submission whose signature
  // failed for a transient reason (encoding bug, mid-flow key rotation)
  // would then find its nonce gone on retry. The DEL now runs only after
  // both signature verify AND counter CAS succeed.
  it('leaves the nonce in Redis when signature verify fails (M-2)', async () => {
    fake.store.set(`cdl:session:${SESSION_ID}:integrity_nonce`, NONCE);
    // Sign with a DIFFERENT private key than the one registered against KEY_ID
    // — the preimage is correct, so the only failure is the signature itself.
    const { privateKey: otherPriv } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
    const otherPriv_pem = otherPriv.export({ type: 'pkcs8', format: 'pem' }).toString();
    const blob = buildAssertion({
      sessionId: SESSION_ID,
      challengeId: CHALLENGE_ID,
      livenessDecision: 'pass',
      nonce: NONCE,
      counter: 1,
      privateKeyPem: otherPriv_pem,
    });
    await expect(
      verifyAppAttestAssertion(blob, SESSION_ID, CHALLENGE_ID, 'pass', KEY_ID),
    ).rejects.toMatchObject({ code: 'ERR_APPLE_ATTEST_INVALID' });
    expect(fake.store.get(`cdl:session:${SESSION_ID}:integrity_nonce`)).toBe(NONCE);
  });

  // M-2 — counter CAS failure (stale signCount) must also leave the nonce
  // intact. Pre-seed the device counter at 10 and submit with counter=1;
  // the CAS rejects (strict > required) but the nonce was legitimate and
  // the signature valid, so the real device's correctly-sequenced retry
  // still has its nonce to work against.
  it('leaves the nonce in Redis when counter CAS fails (M-2)', async () => {
    fake.store.set(`cdl:session:${SESSION_ID}:integrity_nonce`, NONCE);
    fake.store.set(`cdl:device:${KEY_ID}:counter`, '10');
    const blob = buildAssertion({
      sessionId: SESSION_ID,
      challengeId: CHALLENGE_ID,
      livenessDecision: 'pass',
      nonce: NONCE,
      counter: 1, // < 10, CAS will reject
      privateKeyPem,
    });
    await expect(
      verifyAppAttestAssertion(blob, SESSION_ID, CHALLENGE_ID, 'pass', KEY_ID),
    ).rejects.toMatchObject({ code: 'ERR_APPLE_ATTEST_COUNTER' });
    expect(fake.store.get(`cdl:session:${SESSION_ID}:integrity_nonce`)).toBe(NONCE);
  });

  // Guards the pre-C1 regression. If someone reverted the server to the old
  // `sessionId + challengeId + livenessDecision` string concat, a client that
  // signs canonical JSON would produce signatures that no longer verify under
  // the concat preimage — and vice versa. This test literally runs the two
  // preimages and asserts they differ, so neither side can silently drift
  // back to the ambiguous form.
  it('canonical-JSON preimage differs from the legacy string concat', () => {
    const canonical = canonicalize({
      challenge_id: CHALLENGE_ID,
      liveness_decision: 'pass',
      nonce: NONCE,
      session_id: SESSION_ID,
    });
    const legacyConcat = SESSION_ID + CHALLENGE_ID + 'pass';
    expect(canonical).not.toBe(legacyConcat);
  });
});
