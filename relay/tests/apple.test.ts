import { randomBytes } from 'crypto';
import { describe, expect, it } from 'vitest';
import { extractAppleAttestNonce } from '../src/attestation/apple';

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
