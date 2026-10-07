import { exportJWK, generateKeyPair, SignJWT, JSONWebKeySet, KeyLike } from 'jose';
import { beforeAll, describe, expect, it } from 'vitest';
import { verifyAttestationJWT } from '../src';

let privateKey: KeyLike;
let jwks: JSONWebKeySet;
const relayUrl = 'relay.test';

const claims = {
  cdl_version: '1.0',
  cdl_decision: 'pass',
  cdl_assurance: 'IAL2',
  cdl_device_platform: 'ios',
  cdl_challenge_id: 'c1',
  cdl_layer_scores: { layer2: 0.9, layer3: 0.9, layer4_lbp: 0.9, layer4_fourier: 0.9, layer5: 0.9 },
  cdl_hardware_attested: true,
  cdl_attestation_hash: 'abc',
  cdl_low_light: false,
  cdl_motion: false,
};

async function token(over: { iss?: string; aud?: string; exp?: string | number; claims?: object } = {}) {
  return new SignJWT({ ...claims, ...over.claims })
    .setProtectedHeader({ alg: 'ES256', kid: 'k1' })
    .setIssuedAt()
    .setIssuer(over.iss ?? 'relay.test')
    .setAudience(over.aud ?? 'example-bank.com')
    .setSubject('session-1')
    .setExpirationTime(over.exp ?? '15m')
    .sign(privateKey);
}

const opts = () => ({ relayUrl, audience: 'example-bank.com', jwks });

beforeAll(async () => {
  const pair = await generateKeyPair('ES256');
  privateKey = pair.privateKey;
  jwks = { keys: [{ ...(await exportJWK(pair.publicKey)), kid: 'k1', alg: 'ES256' }] };
});

describe('verifyAttestationJWT', () => {
  it('accepts a valid token', async () => {
    const r = await verifyAttestationJWT(await token(), { ...opts(), requiredAssurance: 'IAL2' });
    expect(r.valid).toBe(true);
    expect(r.sessionId).toBe('session-1');
    expect(r.hardwareAttested).toBe(true);
    expect(r.layerScores?.layer2).toBe(0.9);
  });

  it('rejects an expired token', async () => {
    const past = Math.floor(Date.now() / 1000) - 3600;
    const r = await verifyAttestationJWT(await token({ exp: past }), opts());
    expect(r.valid).toBe(false);
  });

  it('rejects a token issued for another relying party', async () => {
    const r = await verifyAttestationJWT(await token({ aud: 'bank-b.com' }), opts());
    expect(r.valid).toBe(false);
  });

  it('rejects the wrong issuer', async () => {
    const r = await verifyAttestationJWT(await token({ iss: 'evil.example' }), opts());
    expect(r.valid).toBe(false);
  });

  it('returns valid false when assurance is too low', async () => {
    const t = await token({ claims: { cdl_assurance: 'software_only', cdl_hardware_attested: false } });
    const r = await verifyAttestationJWT(t, { ...opts(), requiredAssurance: 'IAL2' });
    expect(r.valid).toBe(false);
    expect(r.assuranceLevel).toBe('software_only');
  });

  it('returns valid false when the decision is not pass', async () => {
    const r = await verifyAttestationJWT(await token({ claims: { cdl_decision: 'fail' } }), opts());
    expect(r.valid).toBe(false);
  });

  it('rejects a token signed by an unknown key', async () => {
    const other = await generateKeyPair('ES256');
    const forged = await new SignJWT(claims)
      .setProtectedHeader({ alg: 'ES256', kid: 'k1' })
      .setIssuer('relay.test')
      .setAudience('example-bank.com')
      .setSubject('x')
      .setExpirationTime('15m')
      .sign(other.privateKey);
    expect((await verifyAttestationJWT(forged, opts())).valid).toBe(false);
  });
});
