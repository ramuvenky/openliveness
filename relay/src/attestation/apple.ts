import * as cbor from 'cbor';
import { createHash, createVerify, X509Certificate, createPublicKey, timingSafeEqual } from 'crypto';
import { readFileSync } from 'fs';
import { config } from '../config';
import { CDLError } from '../errors';
import { getRedis } from '../session/store';

const YEAR = 365 * 24 * 3600;

// Apple App Attest certificate extension (1.2.840.113635.100.8.2) DER body:
// first subid is 1.2 → 0x2a; the rest base-128.
const APPLE_ATTEST_EXT_OID = Buffer.from([
  0x2a, 0x86, 0x48, 0x86, 0xf7, 0x63, 0x64, 0x08, 0x02,
]);

type Tlv = { tag: number; headerLen: number; valueLen: number; valueOffset: number };

// Minimal DER TLV reader. Supports short form and multi-byte long form up to
// 4-byte lengths (ample for an X.509 cert). We deliberately avoid a general
// ASN.1 library to keep the trusted parse surface tiny and auditable.
function readTlv(buf: Buffer, offset: number): Tlv {
  if (offset + 2 > buf.length) throw new Error('TLV: truncated header');
  const tag = buf[offset];
  const lenByte = buf[offset + 1];
  if (lenByte < 0x80) {
    const end = offset + 2 + lenByte;
    if (end > buf.length) throw new Error('TLV: truncated value');
    return { tag, headerLen: 2, valueLen: lenByte, valueOffset: offset + 2 };
  }
  const n = lenByte & 0x7f;
  if (n === 0 || n > 4) throw new Error('TLV: unsupported length form');
  if (offset + 2 + n > buf.length) throw new Error('TLV: truncated long length');
  let valueLen = 0;
  for (let i = 0; i < n; i++) {
    valueLen = (valueLen << 8) | buf[offset + 2 + i];
  }
  const end = offset + 2 + n + valueLen;
  if (end > buf.length) throw new Error('TLV: truncated value');
  return { tag, headerLen: 2 + n, valueLen, valueOffset: offset + 2 + n };
}

// Walks the DER-encoded X.509 cert to locate the Apple App Attest extension
// (OID 1.2.840.113635.100.8.2) and returns the SHA-256 nonce bytes carried
// inside its `[1] EXPLICIT OCTET STRING` payload. Returns null if the
// extension is absent or malformed. Exported for direct unit testing — the
// nonce comparison itself happens in verifyAppAttestAttestation under
// timingSafeEqual.
export function extractAppleAttestNonce(certDer: Buffer): Buffer | null {
  try {
    // Certificate ::= SEQUENCE { tbsCertificate, sigAlg, sigValue }
    const outer = readTlv(certDer, 0);
    if (outer.tag !== 0x30) return null;
    const tbs = readTlv(certDer, outer.valueOffset);
    if (tbs.tag !== 0x30) return null;
    const tbsEnd = tbs.valueOffset + tbs.valueLen;

    // Find [3] EXPLICIT extensions in TBSCertificate.
    let p = tbs.valueOffset;
    let extensionsSeq: Tlv | null = null;
    while (p < tbsEnd) {
      const t = readTlv(certDer, p);
      if (t.tag === 0xa3) {
        const inner = readTlv(certDer, t.valueOffset);
        if (inner.tag === 0x30) extensionsSeq = inner;
        break;
      }
      p = t.valueOffset + t.valueLen;
    }
    if (!extensionsSeq) return null;

    // Walk each Extension SEQUENCE { OID, [BOOLEAN critical], OCTET STRING }.
    const extEnd = extensionsSeq.valueOffset + extensionsSeq.valueLen;
    let q = extensionsSeq.valueOffset;
    while (q < extEnd) {
      const ext = readTlv(certDer, q);
      if (ext.tag !== 0x30) return null;
      const oidTlv = readTlv(certDer, ext.valueOffset);
      if (oidTlv.tag !== 0x06) {
        q = ext.valueOffset + ext.valueLen;
        continue;
      }
      const oid = certDer.subarray(oidTlv.valueOffset, oidTlv.valueOffset + oidTlv.valueLen);
      if (!oid.equals(APPLE_ATTEST_EXT_OID)) {
        q = ext.valueOffset + ext.valueLen;
        continue;
      }
      // Skip optional BOOLEAN critical, then read extnValue OCTET STRING.
      let r = oidTlv.valueOffset + oidTlv.valueLen;
      const maybeCritical = readTlv(certDer, r);
      if (maybeCritical.tag === 0x01) r = maybeCritical.valueOffset + maybeCritical.valueLen;
      const extnValue = readTlv(certDer, r);
      if (extnValue.tag !== 0x04) return null;
      // Inside extnValue: SEQUENCE { [1] EXPLICIT OCTET STRING }
      const seq = readTlv(certDer, extnValue.valueOffset);
      if (seq.tag !== 0x30) return null;
      const seqEnd = seq.valueOffset + seq.valueLen;
      let s = seq.valueOffset;
      while (s < seqEnd) {
        const tagged = readTlv(certDer, s);
        if (tagged.tag === 0xa1) {
          const octet = readTlv(certDer, tagged.valueOffset);
          if (octet.tag === 0x04) {
            return certDer.subarray(octet.valueOffset, octet.valueOffset + octet.valueLen);
          }
        }
        s = tagged.valueOffset + tagged.valueLen;
      }
      return null;
    }
    return null;
  } catch {
    return null;
  }
}

// App Attest key ids are the SHA-256 of the uncompressed public key point.
// Phones may send the DER SPKI, so we hash the raw point (last 65 bytes of a P-256 SPKI).
// Both base64 (what Apple returns) and hex are produced so lookups work either way.
export function deriveKeyIdCandidates(publicKeyBase64: string): string[] {
  const der = Buffer.from(publicKeyBase64, 'base64');
  const point = der.length > 65 ? der.subarray(der.length - 65) : der;
  const digest = createHash('sha256').update(point).digest();
  return [digest.toString('base64'), digest.toString('hex')];
}

export function deriveKeyIdFromPublicKey(publicKeyBase64: string): string {
  return deriveKeyIdCandidates(publicKeyBase64)[0];
}

export async function resolveKeyId(publicKeyBase64: string): Promise<string> {
  const r = getRedis();
  for (const id of deriveKeyIdCandidates(publicKeyBase64)) {
    if ((await r.get(`cdl:device:${id}:trusted`)) === '1') return id;
  }
  throw new CDLError('ERR_APPLE_ATTEST_INVALID', 'device key is not registered');
}

export async function getStoredPublicKey(keyId: string): Promise<string> {
  const pem = await getRedis().get(`cdl:device:${keyId}:pubkey`);
  if (!pem) throw new CDLError('ERR_APPLE_ATTEST_INVALID', 'no stored key for device');
  return pem;
}

// Checks that each cert in the chain was signed by the next one, ending at Apple's root.
function verifyChain(x5c: Buffer[]) {
  const certs = x5c.map((der) => new X509Certificate(der));
  const rootPath = config().appleRootCaPath;
  if (!rootPath) {
    if (config().nodeEnv === 'production') {
      throw new CDLError('ERR_APPLE_ATTEST_INVALID', 'APPLE_APP_ATTEST_ROOT_CA_PATH not set');
    }
    return; // dev only, chain left unchecked
  }
  const root = new X509Certificate(readFileSync(rootPath));
  const chain = [...certs, root];
  const now = Date.now();
  for (let i = 0; i < chain.length - 1; i++) {
    const c = chain[i];
    if (now < Date.parse(c.validFrom) || now > Date.parse(c.validTo)) {
      throw new CDLError('ERR_APPLE_ATTEST_INVALID', 'certificate outside validity window');
    }
    if (!c.verify(chain[i + 1].publicKey)) {
      throw new CDLError('ERR_APPLE_ATTEST_INVALID', 'certificate chain is broken');
    }
  }
}

// Phase 1: one time key registration. Returns the PEM public key it stored.
export async function verifyAppAttestAttestation(
  keyId: string,
  attestationBase64: string,
  challengeBase64: string,
): Promise<void> {
  const decoded = cbor.decodeFirstSync(Buffer.from(attestationBase64, 'base64'));
  const authData: Buffer = decoded.authData;
  const x5c: Buffer[] = decoded.attStmt?.x5c;
  if (!authData || !x5c?.length) throw new CDLError('ERR_APPLE_ATTEST_INVALID', 'malformed attestation');

  verifyChain(x5c);

  const appId = `${config().appleTeamId}.${config().appleBundleId}`;
  const expectedRp = createHash('sha256').update(appId).digest();
  if (!authData.subarray(0, 32).equals(expectedRp)) {
    throw new CDLError('ERR_APPLE_ATTEST_INVALID', 'rpIdHash mismatch');
  }

  const clientDataHash = createHash('sha256').update(Buffer.from(challengeBase64, 'base64')).digest();
  const nonce = createHash('sha256').update(Buffer.concat([authData, clientDataHash])).digest();

  const leaf = new X509Certificate(x5c[0]);
  // The nonce lives in Apple's `1.2.840.113635.100.8.2` extension, inside a
  // SEQUENCE { [1] EXPLICIT OCTET STRING }. A raw-bytes includes() check
  // would match if the nonce bytes happened to appear anywhere else in the
  // cert, so parse the extension explicitly and compare in constant time.
  const certNonce = extractAppleAttestNonce(leaf.raw);
  if (!certNonce || certNonce.length !== nonce.length || !timingSafeEqual(certNonce, nonce)) {
    throw new CDLError('ERR_APPLE_ATTEST_INVALID', 'nonce mismatch in certificate extension');
  }

  const point = createPublicKey(leaf.publicKey.export({ type: 'spki', format: 'pem' }))
    .export({ type: 'spki', format: 'der' })
    .subarray(-65);
  const idFromCert = createHash('sha256').update(point).digest('base64');
  if (idFromCert !== keyId) throw new CDLError('ERR_APPLE_ATTEST_INVALID', 'key id mismatch');

  const pem = leaf.publicKey.export({ type: 'spki', format: 'pem' }).toString();
  await getRedis().set(`cdl:device:${keyId}:pubkey`, pem, 'EX', YEAR);
}

// Phase 2: per session assertion.
export async function verifyAppAttestAssertion(
  deviceAttestation: string,
  sessionId: string,
  challengeId: string,
  livenessDecision: string,
  keyId: string,
): Promise<boolean> {
  const r = getRedis();
  const decoded = cbor.decodeFirstSync(Buffer.from(deviceAttestation, 'base64'));
  const authData: Buffer = decoded.authenticatorData;
  const signature: Buffer = decoded.signature;
  if (!authData || !signature) throw new CDLError('ERR_APPLE_ATTEST_INVALID', 'malformed assertion');

  // parseInt returns NaN for a corrupted value; comparing anything against
  // NaN is false, which would silently let a replayed counter through. Treat
  // a non-finite stored value as "no prior counter known" (0) so the next
  // valid counter still has to be strictly greater than any plausible prior.
  const parsed = parseInt((await r.get(`cdl:device:${keyId}:counter`)) || '0', 10);
  const stored = Number.isFinite(parsed) ? parsed : 0;
  const counter = authData.readUInt32BE(33);
  if (counter <= stored) throw new CDLError('ERR_APPLE_ATTEST_COUNTER');

  const clientData = sessionId + challengeId + livenessDecision;
  const clientDataHash = createHash('sha256').update(clientData).digest();
  // Apple signs SHA256(authData + clientDataHash), and ES256 hashes that once more.
  const nonce = createHash('sha256').update(Buffer.concat([authData, clientDataHash])).digest();

  const pem = await getStoredPublicKey(keyId);
  const v = createVerify('SHA256');
  v.update(nonce);
  if (!v.verify(pem, signature)) throw new CDLError('ERR_APPLE_ATTEST_INVALID');

  await r.set(`cdl:device:${keyId}:counter`, String(counter), 'EX', YEAR);
  return true;
}
