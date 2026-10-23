import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { buildIntegrityNonce } from '../src/attestation/google';
import { sha256base64url } from '../src/utils/crypto';

// Locks in the H4 contract: the Play Integrity nonce must depend only on
// (sessionId, challengeId, INTEGRITY_NONCE_SECRET). The phone requests it
// before liveness runs, so pulling liveness_decision into the formula would
// make the handshake uncomputable at the point the phone needs the nonce.
// If a well-meaning change ever adds liveness_decision back in (the old spec
// language leaned that way), these tests will fail before it ships.

const SECRET = 'test-secret-xyz';
const original = process.env.INTEGRITY_NONCE_SECRET;

describe('buildIntegrityNonce', () => {
  beforeAll(() => {
    process.env.INTEGRITY_NONCE_SECRET = SECRET;
  });
  afterAll(() => {
    if (original === undefined) delete process.env.INTEGRITY_NONCE_SECRET;
    else process.env.INTEGRITY_NONCE_SECRET = original;
  });

  it('is deterministic for the same (sessionId, challengeId) pair', () => {
    const a = buildIntegrityNonce('sess-1', 'chal-1');
    const b = buildIntegrityNonce('sess-1', 'chal-1');
    expect(a).toBe(b);
  });

  it('matches sha256base64url(sessionId + challengeId + secret) exactly', () => {
    expect(buildIntegrityNonce('sess-1', 'chal-1')).toBe(
      sha256base64url('sess-1' + 'chal-1' + SECRET),
    );
  });

  it('changes when sessionId changes', () => {
    expect(buildIntegrityNonce('sess-1', 'chal-1')).not.toBe(
      buildIntegrityNonce('sess-2', 'chal-1'),
    );
  });

  it('changes when challengeId changes', () => {
    expect(buildIntegrityNonce('sess-1', 'chal-1')).not.toBe(
      buildIntegrityNonce('sess-1', 'chal-2'),
    );
  });

  it('changes when the secret rotates', () => {
    const first = buildIntegrityNonce('sess-1', 'chal-1');
    process.env.INTEGRITY_NONCE_SECRET = SECRET + '-rotated';
    try {
      expect(buildIntegrityNonce('sess-1', 'chal-1')).not.toBe(first);
    } finally {
      process.env.INTEGRITY_NONCE_SECRET = SECRET;
    }
  });

  // Documentation-as-test: the function signature intentionally takes no
  // third argument. If someone adds a liveness_decision parameter here, this
  // file will fail to typecheck — surfacing the invariant at the right layer.
  it('exposes a (sessionId, challengeId) => string signature with no third arg', () => {
    const sig: (sessionId: string, challengeId: string) => string = buildIntegrityNonce;
    expect(typeof sig('a', 'b')).toBe('string');
  });
});
