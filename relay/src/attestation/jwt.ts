import { readFileSync } from 'fs';
import { createPublicKey } from 'crypto';
import { SignJWT, importPKCS8, exportJWK, importSPKI, KeyLike } from 'jose';
import { config } from '../config';
import { CDLAttestation, CDLSession } from '../session/types';
import { canonicalize, sha256hex } from '../utils/crypto';

export const KEY_ID = 'relay-signing-key-v1';
let privateKey: KeyLike | null = null;

async function getPrivateKey(): Promise<KeyLike> {
  if (!privateKey) {
    const pem = readFileSync(config().jwtPrivateKeyPath, 'utf8');
    privateKey = await importPKCS8(pem, 'ES256');
  }
  return privateKey;
}

export function resetKeyCache() {
  privateKey = null;
}

export async function issueAttestationJWT(
  attestation: CDLAttestation,
  session: CDLSession,
  hardwareAttested: boolean,
): Promise<string> {
  const s = attestation.layer_scores;
  return new SignJWT({
    cdl_version: '1.0',
    cdl_decision: attestation.liveness_decision,
    cdl_assurance: attestation.assurance_level,
    cdl_device_platform: attestation.device_platform,
    cdl_challenge_id: attestation.challenge_id,
    cdl_layer_scores: {
      layer2: s.layer2_challenge_score,
      layer3: s.layer3_rppg_confidence,
      layer4_lbp: s.layer4_lbp_score,
      layer4_fourier: s.layer4_fourier_score,
      layer5: s.layer5_behavioral_score,
    },
    // true only when the relay verified the proof, not just when a field was present
    cdl_hardware_attested: hardwareAttested,
    cdl_attestation_hash: sha256hex(canonicalize(attestation)),
    cdl_low_light: attestation.ambient_conditions.low_light_detected,
    cdl_motion: attestation.ambient_conditions.motion_detected,
  })
    .setProtectedHeader({ alg: 'ES256', kid: KEY_ID })
    .setIssuedAt()
    .setIssuer(config().issuer)
    .setAudience(session.relying_party)
    .setSubject(session.session_id)
    .setExpirationTime('15m')
    .sign(await getPrivateKey());
}

let jwksCache: { keys: unknown[] } | null = null;

export async function getJwks() {
  if (!jwksCache) {
    const pem = readFileSync(config().jwtPublicKeyPath, 'utf8');
    // round trip through KeyObject so a private key file is never exposed by mistake
    const spki = createPublicKey(pem).export({ type: 'spki', format: 'pem' }).toString();
    const jwk = await exportJWK(await importSPKI(spki, 'ES256'));
    jwksCache = { keys: [{ ...jwk, kid: KEY_ID, alg: 'ES256', use: 'sig' }] };
  }
  return jwksCache;
}

export function resetJwksCache() {
  jwksCache = null;
}
