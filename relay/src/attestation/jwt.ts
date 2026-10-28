import { readFileSync } from 'fs';
import { createPublicKey, randomUUID } from 'crypto';
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
    // Mirror the mobile SDK's declared attestation.version instead of a
    // hard-coded '1.0'. If an older SDK keeps sending v1.0 after we've
    // bumped the schema, the RP needs to see v1.0 in the JWT so its
    // compatibility checks match reality rather than this handler's build.
    cdl_version: attestation.version,
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
    // Downgrade-attack signal from the mobile SDK: 'ok' matches a
    // successful hardware attempt (and therefore IAL2); 'failed' with
    // software_only tells the RP "the device tried and could not", which
    // is distinct from 'unsupported' = "device cannot do this at all".
    // Omitted when the SDK pre-dates the field — RPs can tell "unknown"
    // from "ok" by the field being absent vs. equal to 'ok'.
    ...(attestation.hw_attestation_status !== undefined
      ? { cdl_hw_status: attestation.hw_attestation_status }
      : {}),
    cdl_attestation_hash: sha256hex(canonicalize(attestation)),
    cdl_low_light: attestation.ambient_conditions.low_light_detected,
    cdl_motion: attestation.ambient_conditions.motion_detected,
  })
    .setProtectedHeader({ alg: 'ES256', kid: KEY_ID })
    .setIssuedAt()
    .setIssuer(config().issuer)
    .setAudience(session.relying_party)
    .setSubject(session.session_id)
    // jti is a random per-token UUID. sub already uniquely identifies a
    // session, so an RP can dedupe on either, but jti gives downstream
    // "have I seen this exact token before?" caches a canonical key even
    // if a buggy relay ever issued twice for the same session.
    .setJti(randomUUID())
    .setExpirationTime('15m')
    .sign(await getPrivateKey());
}

// N-c: cache the parsed JWKS but auto-refresh after JWKS_CACHE_TTL_MS so
// an on-disk key rotation lands within the TTL without a server restart.
// 5 minutes matches the Cache-Control: max-age we advertise to clients at
// /cdl/.well-known/jwks.json — a client's cached copy is no fresher than
// ours, so there's no benefit to a tighter server-side window.
export const JWKS_CACHE_TTL_MS = 5 * 60 * 1000;
let jwksCache: { keys: unknown[] } | null = null;
let jwksCachedAt = 0;

// Exported for tests so they can advance "now" without relying on real time.
export function _now(): number {
  return Date.now();
}

export async function getJwks(now: number = _now()) {
  if (!jwksCache || now - jwksCachedAt > JWKS_CACHE_TTL_MS) {
    const pem = readFileSync(config().jwtPublicKeyPath, 'utf8');
    // round trip through KeyObject so a private key file is never exposed by mistake
    const spki = createPublicKey(pem).export({ type: 'spki', format: 'pem' }).toString();
    const jwk = await exportJWK(await importSPKI(spki, 'ES256'));
    jwksCache = { keys: [{ ...jwk, kid: KEY_ID, alg: 'ES256', use: 'sig' }] };
    jwksCachedAt = now;
  }
  return jwksCache;
}

export function resetJwksCache() {
  jwksCache = null;
  jwksCachedAt = 0;
}
