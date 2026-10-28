import { readFileSync } from 'fs';
import { resolve } from 'path';
import { describe, expect, it } from 'vitest';
import {
  checkAssurance,
  checkChallenge,
  checkTimestamp,
  derToRaw,
  verifyAttestationSignature,
} from '../src/attestation/verify';
import { canonicalize, sha256base64url } from '../src/utils/crypto';
import { makeAttestation, makeSession, rawToDer } from './helpers';

describe('canonicalize', () => {
  it('sorts keys and drops whitespace', () => {
    expect(canonicalize({ b: 1, a: { d: [2, { z: 1, y: 2 }], c: 'x' } })).toBe(
      '{"a":{"c":"x","d":[2,{"y":2,"z":1}]},"b":1}',
    );
  });

  // The shared protocol fixtures are the cross-stack contract: if the server
  // diverges, mobile signatures stop verifying for every real attestation.
  // Load them from protocol/canonical-json-vectors.json so a drift here fails
  // in CI rather than at the next phone handshake.
  const vectorsPath = resolve(__dirname, '../../protocol/canonical-json-vectors.json');
  const vectors = JSON.parse(readFileSync(vectorsPath, 'utf8')) as {
    name: string;
    input: unknown;
    expected: string;
  }[];
  for (const v of vectors) {
    it(`vector: ${v.name}`, () => {
      expect(canonicalize(v.input)).toBe(v.expected);
    });
  }

  it('rejects NaN', () => {
    expect(() => canonicalize({ n: Number.NaN })).toThrow(/non-finite/);
  });
  it('rejects +Infinity', () => {
    expect(() => canonicalize({ n: Number.POSITIVE_INFINITY })).toThrow(/non-finite/);
  });
  it('rejects -Infinity', () => {
    expect(() => canonicalize({ n: Number.NEGATIVE_INFINITY })).toThrow(/non-finite/);
  });
});

describe('sha256base64url', () => {
  it('has no padding or url unsafe characters', () => {
    expect(sha256base64url('hello')).toMatch(/^[A-Za-z0-9_-]+$/);
  });
});

describe('signature', () => {
  it('accepts a valid attestation', async () => {
    expect(await verifyAttestationSignature(await makeAttestation())).toBe(true);
  });

  it('rejects a tampered attestation', async () => {
    const att = await makeAttestation({ liveness_decision: 'fail' });
    // flip the decision after signing, as an attacker would
    const tampered = { ...att, liveness_decision: 'pass' as const };
    expect(await verifyAttestationSignature(tampered)).toBe(false);
  });

  it('accepts a DER encoded signature from Android', async () => {
    const att = await makeAttestation();
    const der = rawToDer(Buffer.from(att.signature, 'base64'));
    expect(derToRaw(der).length).toBe(64);
    expect(await verifyAttestationSignature({ ...att, signature: der.toString('base64') })).toBe(true);
  });

  it('rejects a signature made by another key', async () => {
    const a = await makeAttestation();
    const b = await makeAttestation();
    expect(await verifyAttestationSignature({ ...a, signature: b.signature })).toBe(false);
  });
});

describe('timestamp', () => {
  const now = Date.parse('2026-10-07T10:00:00Z');
  it('accepts fresh', () => {
    expect(() => checkTimestamp('2026-10-07T09:59:30Z', 120, now)).not.toThrow();
  });
  it('rejects stale', () => {
    expect(() => checkTimestamp('2026-10-07T09:57:00Z', 120, now)).toThrowError(
      expect.objectContaining({ code: 'ERR_TIMESTAMP_STALE' }),
    );
  });
  it('rejects the future', () => {
    expect(() => checkTimestamp('2026-10-07T10:01:00Z', 120, now)).toThrowError(
      expect.objectContaining({ code: 'ERR_TIMESTAMP_FUTURE' }),
    );
  });
});

describe('challenge', () => {
  it('accepts a matching response', async () => {
    const att = await makeAttestation();
    expect(() => checkChallenge(att, makeSession())).not.toThrow();
  });
  it('rejects a wrong order', async () => {
    const att = await makeAttestation({ challenge_response: 'smile,blink,look_left' });
    expect(() => checkChallenge(att, makeSession())).toThrowError(
      expect.objectContaining({ code: 'ERR_CHALLENGE_MISMATCH' }),
    );
  });
  it('rejects a different challenge id', async () => {
    const att = await makeAttestation({ challenge_id: '33333333-3333-4333-8333-333333333333' });
    expect(() => checkChallenge(att, makeSession())).toThrowError(
      expect.objectContaining({ code: 'ERR_CHALLENGE_MISMATCH' }),
    );
  });
});

describe('assurance', () => {
  it('rejects IAL2 claimed without verified hardware', async () => {
    const att = await makeAttestation({ assurance_level: 'IAL2' });
    expect(() => checkAssurance(att, makeSession(), false)).toThrowError(
      expect.objectContaining({ code: 'ERR_ATTEST_REQUIRED' }),
    );
  });
  it('rejects software_only when the relying party needs IAL2', async () => {
    const att = await makeAttestation();
    expect(() => checkAssurance(att, makeSession({ assurance_required: 'IAL2' }), false)).toThrow();
  });
  it('accepts IAL2 with verified hardware', async () => {
    const att = await makeAttestation({ assurance_level: 'IAL2' });
    expect(() => checkAssurance(att, makeSession({ assurance_required: 'IAL2' }), true)).not.toThrow();
  });

  // Phase 2 drift tests — hw_attestation_status ↔ assurance_level cross-check.
  // The field is validated here (not just stored) because an attestation that
  // claims IAL2 but reports hw_attestation_status != 'ok' is internally
  // inconsistent: it means the client stamped the higher assurance level but
  // also admits the hardware step didn't actually succeed. Reject up front
  // instead of letting the inconsistency flow into the JWT.

  it('rejects hw_attestation_status=ok paired with software_only', async () => {
    const att = await makeAttestation({ hw_attestation_status: 'ok' });
    expect(() => checkAssurance(att, makeSession(), false)).toThrowError(
      expect.objectContaining({ code: 'ERR_SCHEMA_INVALID' }),
    );
  });

  it('rejects hw_attestation_status=ok paired with IAL1', async () => {
    const att = await makeAttestation({
      assurance_level: 'IAL1',
      hw_attestation_status: 'ok',
    });
    expect(() => checkAssurance(att, makeSession(), true)).toThrowError(
      expect.objectContaining({ code: 'ERR_SCHEMA_INVALID' }),
    );
  });

  it('rejects IAL2 paired with hw_attestation_status=failed', async () => {
    const att = await makeAttestation({
      assurance_level: 'IAL2',
      hw_attestation_status: 'failed',
    });
    expect(() => checkAssurance(att, makeSession({ assurance_required: 'IAL2' }), true)).toThrowError(
      expect.objectContaining({ code: 'ERR_SCHEMA_INVALID' }),
    );
  });

  it('rejects IAL2 paired with hw_attestation_status=unsupported', async () => {
    const att = await makeAttestation({
      assurance_level: 'IAL2',
      hw_attestation_status: 'unsupported',
    });
    expect(() => checkAssurance(att, makeSession({ assurance_required: 'IAL2' }), true)).toThrowError(
      expect.objectContaining({ code: 'ERR_SCHEMA_INVALID' }),
    );
  });

  it('accepts IAL2 with hw_attestation_status=ok and verified hardware', async () => {
    const att = await makeAttestation({
      assurance_level: 'IAL2',
      hw_attestation_status: 'ok',
    });
    expect(() => checkAssurance(att, makeSession({ assurance_required: 'IAL2' }), true)).not.toThrow();
  });

  // The downgrade-signal paths — the client reports hardware tried & failed,
  // and claims the matching software_only level. The relay must accept the
  // honest downgrade, log it, and let policy decide; rejecting here would
  // disable the whole downgrade-detection story.
  it('accepts software_only + failed', async () => {
    const att = await makeAttestation({ hw_attestation_status: 'failed' });
    expect(() => checkAssurance(att, makeSession(), false)).not.toThrow();
  });

  it('accepts software_only + unsupported', async () => {
    const att = await makeAttestation({ hw_attestation_status: 'unsupported' });
    expect(() => checkAssurance(att, makeSession(), false)).not.toThrow();
  });

  // Field is optional in the schema (older SDKs). A missing field must behave
  // exactly like today's attestation flow — no new reject path.
  it('accepts software_only with hw_attestation_status missing (older SDK)', async () => {
    const att = await makeAttestation();
    expect(() => checkAssurance(att, makeSession(), false)).not.toThrow();
  });

  it('accepts IAL2 + verified hardware with hw_attestation_status missing (older SDK)', async () => {
    const att = await makeAttestation({ assurance_level: 'IAL2' });
    expect(() => checkAssurance(att, makeSession({ assurance_required: 'IAL2' }), true)).not.toThrow();
  });
});
