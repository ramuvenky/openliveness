import { webcrypto } from 'crypto';
import { CDLError } from '../errors';
import { canonicalize } from '../utils/crypto';
import { CDLAttestation, CDLSession } from '../session/types';

const subtle = webcrypto.subtle;

// Android Keystore returns DER encoded signatures, WebCrypto wants raw r||s (64 bytes).
// iOS CryptoKit rawRepresentation is already r||s.
export function derToRaw(der: Buffer): Buffer {
  if (der[0] !== 0x30) throw new CDLError('ERR_INVALID_SIGNATURE', 'not a DER signature');
  let o = 2;
  if (der[1] & 0x80) o = 2 + (der[1] & 0x7f);

  const readInt = (): Buffer => {
    if (der[o] !== 0x02) throw new CDLError('ERR_INVALID_SIGNATURE', 'bad DER integer');
    const len = der[o + 1];
    let v = der.subarray(o + 2, o + 2 + len);
    o += 2 + len;
    while (v.length > 32 && v[0] === 0) v = v.subarray(1);
    if (v.length > 32) throw new CDLError('ERR_INVALID_SIGNATURE', 'integer too long');
    return Buffer.concat([Buffer.alloc(32 - v.length), v]);
  };

  const r = readInt();
  const s = readInt();
  return Buffer.concat([r, s]);
}

export async function verifyAttestationSignature(attestation: CDLAttestation): Promise<boolean> {
  const { signature, ...rest } = attestation;
  const data = new TextEncoder().encode(canonicalize(rest));

  try {
    const key = await subtle.importKey(
      'spki',
      Buffer.from(attestation.public_key, 'base64'),
      { name: 'ECDSA', namedCurve: 'P-256' },
      false,
      ['verify'],
    );

    let sig: Buffer = Buffer.from(signature, 'base64');
    if (sig.length !== 64) sig = derToRaw(sig);

    // WebCrypto hashes the message itself with SHA-256, so we pass the canonical
    // bytes and not a pre-computed digest. Phones sign the same way (hash once).
    return await subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, sig, data);
  } catch (e) {
    if (e instanceof CDLError) throw e;
    return false;
  }
}

export function checkChallenge(att: CDLAttestation, session: CDLSession): void {
  if (att.challenge_id !== session.challenge_id) {
    throw new CDLError('ERR_CHALLENGE_MISMATCH', 'challenge_id does not match session');
  }
  const expected = (JSON.parse(session.challenge_sequence) as string[]).join(',');
  if (att.challenge_response !== expected) {
    throw new CDLError('ERR_CHALLENGE_MISMATCH', 'challenge_response does not match sequence');
  }
}

export function checkTimestamp(
  timestamp: string,
  maxAgeSeconds: number,
  now: number = Date.now(),
): void {
  const t = Date.parse(timestamp);
  if (Number.isNaN(t)) throw new CDLError('ERR_SCHEMA_INVALID', 'bad timestamp');
  if (now - t > maxAgeSeconds * 1000) throw new CDLError('ERR_TIMESTAMP_STALE');
  if (t - now > 30 * 1000) throw new CDLError('ERR_TIMESTAMP_FUTURE');
}

const RANK: Record<string, number> = { any: 0, software_only: 0, IAL1: 1, IAL2: 2 };

export function checkAssurance(att: CDLAttestation, session: CDLSession, hardwareVerified: boolean) {
  // A phone cannot claim IAL2 unless the relay verified the hardware proof itself.
  if (att.assurance_level === 'IAL2' && !hardwareVerified) {
    throw new CDLError('ERR_ATTEST_REQUIRED', 'IAL2 claimed without verified attestation');
  }
  const need = RANK[session.assurance_required] ?? 0;
  if (RANK[att.assurance_level] < need) {
    throw new CDLError('ERR_ATTEST_REQUIRED', `${session.assurance_required} required`);
  }
}
