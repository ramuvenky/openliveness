import { webcrypto, createSign, generateKeyPairSync } from 'crypto';
import { canonicalize } from '../src/utils/crypto';
import { CDLAttestation, CDLSession } from '../src/session/types';

const subtle = webcrypto.subtle;
const SESSION_ID = '11111111-1111-4111-8111-111111111111';
const CHALLENGE_ID = '22222222-2222-4222-8222-222222222222';

export function makeSession(over: Partial<CDLSession> = {}): CDLSession {
  const t = new Date().toISOString();
  return {
    session_id: SESSION_ID,
    state: 'phone_connected',
    relying_party: 'example-bank.com',
    assurance_required: 'any',
    challenge_id: CHALLENGE_ID,
    challenge_sequence: JSON.stringify(['blink', 'look_left', 'smile']),
    created_at: t,
    updated_at: t,
    expires_at: new Date(Date.now() + 300000).toISOString(),
    poll_token: 'poll-token',
    liveness_decision: null,
    assurance_level: null,
    attestation_hash: null,
    jwt_issued: null,
    webhook_url: null,
    ...over,
  };
}

export async function makeKeys() {
  const pair = await subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, [
    'sign',
    'verify',
  ]);
  const spki = Buffer.from(await subtle.exportKey('spki', pair.publicKey));
  return { privateKey: pair.privateKey, publicKeyB64: spki.toString('base64') };
}

export async function makeAttestation(over: Partial<CDLAttestation> = {}) {
  const keys = await makeKeys();
  const unsigned = {
    version: '1.0',
    session_id: SESSION_ID,
    challenge_id: CHALLENGE_ID,
    challenge_response: 'blink,look_left,smile',
    timestamp: new Date().toISOString(),
    liveness_decision: 'pass',
    assurance_level: 'software_only',
    layer_scores: {
      layer2_challenge_completed: true,
      layer2_challenge_score: 0.97,
      layer3_pulse_detected: true,
      layer3_bpm_in_range: true,
      layer3_rppg_confidence: 0.88,
      layer4_lbp_score: 0.94,
      layer4_fourier_score: 0.91,
      layer5_behavioral_score: 0.89,
      layer5_microsaccade_detected: true,
    },
    device_platform: 'android',
    device_id_hash: 'abc123',
    ambient_conditions: {
      low_light_detected: false,
      motion_detected: false,
      front_camera_confirmed: true,
    },
    public_key: keys.publicKeyB64,
    ...over,
  };
  const sig = await subtle.sign(
    { name: 'ECDSA', hash: 'SHA-256' },
    keys.privateKey,
    new TextEncoder().encode(canonicalize(unsigned)),
  );
  return {
    ...unsigned,
    signature: Buffer.from(sig).toString('base64'),
  } as CDLAttestation;
}

// Re-encode a raw r||s signature as DER, the way Android Keystore returns it.
export function rawToDer(raw: Buffer): Buffer {
  const enc = (v: Buffer) => {
    let i = 0;
    while (i < v.length - 1 && v[i] === 0) i++;
    let x = v.subarray(i);
    if (x[0] & 0x80) x = Buffer.concat([Buffer.from([0]), x]);
    return Buffer.concat([Buffer.from([0x02, x.length]), x]);
  };
  const body = Buffer.concat([enc(raw.subarray(0, 32)), enc(raw.subarray(32))]);
  return Buffer.concat([Buffer.from([0x30, body.length]), body]);
}

export function makePemKeys() {
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  return {
    privatePem: privateKey.export({ type: 'pkcs8', format: 'pem' }).toString(),
    publicPem: publicKey.export({ type: 'spki', format: 'pem' }).toString(),
  };
}

export { createSign };
