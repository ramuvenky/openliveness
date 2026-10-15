import * as cbor from 'cbor';
import { createHash, createVerify, X509Certificate, createPublicKey } from 'crypto';
import { readFileSync } from 'fs';
import { config } from '../config';
import { CDLError } from '../errors';
import { getRedis } from '../session/store';

const YEAR = 365 * 24 * 3600;

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
  // The nonce lives in a certificate extension, finding it in the raw bytes is enough here.
  if (!leaf.raw.includes(nonce)) {
    throw new CDLError('ERR_APPLE_ATTEST_INVALID', 'nonce not found in certificate');
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

  const stored = parseInt((await r.get(`cdl:device:${keyId}:counter`)) || '0', 10);
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
