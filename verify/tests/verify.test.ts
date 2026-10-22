import { exportJWK, generateKeyPair, SignJWT, JSONWebKeySet, KeyLike } from 'jose';
import { beforeAll, describe, expect, it } from 'vitest';
import { DEFAULT_MAX_AGE_SECONDS, verifyAttestationJWT } from '../src';
import { defaultIssuer, normalizeRelayUrl } from '../src/jwt';

let privateKey: KeyLike;
let jwks: JSONWebKeySet;
const relayUrl = 'relay.test';
// The relay emits `iss` as a full origin so OIDC/JOSE validators that URL-
// parse the claim keep working. Mirror that here so the verify package's
// defaultIssuer check matches real relay tokens, not a bare-hostname fixture
// that drifted from the shipped behavior.
const RELAY_ISS = `https://${relayUrl}`;

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

async function token(
  over: { iss?: string; aud?: string; iat?: number; exp?: string | number; claims?: object } = {},
) {
  const b = new SignJWT({ ...claims, ...over.claims })
    .setProtectedHeader({ alg: 'ES256', kid: 'k1' })
    .setIssuer(over.iss ?? RELAY_ISS)
    .setAudience(over.aud ?? 'example-bank.com')
    .setSubject('session-1')
    .setExpirationTime(over.exp ?? '15m');
  if (over.iat !== undefined) b.setIssuedAt(over.iat);
  else b.setIssuedAt();
  return b.sign(privateKey);
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
      .setIssuer(RELAY_ISS)
      .setAudience('example-bank.com')
      .setSubject('x')
      .setExpirationTime('15m')
      .sign(other.privateKey);
    expect((await verifyAttestationJWT(forged, opts())).valid).toBe(false);
  });

  // H1 — age cap must apply by default. The relay's own 15m exp is the only
  // backstop otherwise, which is a very long window for "did this liveness
  // happen recently enough to onboard the user."
  describe('H1 — maxAgeSeconds default', () => {
    it('rejects a token whose iat is older than DEFAULT_MAX_AGE_SECONDS when caller passes no maxAgeSeconds', async () => {
      const stale = Math.floor(Date.now() / 1000) - DEFAULT_MAX_AGE_SECONDS - 60;
      const r = await verifyAttestationJWT(await token({ iat: stale }), opts());
      expect(r.valid).toBe(false);
      expect(r.reason).toMatch(/older than maxAgeSeconds/);
    });

    it('accepts a token within DEFAULT_MAX_AGE_SECONDS', async () => {
      const r = await verifyAttestationJWT(await token(), opts());
      expect(r.valid).toBe(true);
    });

    it('respects a caller-supplied maxAgeSeconds override', async () => {
      // 10s window — a fresh token (iat ~= now) always passes.
      const r = await verifyAttestationJWT(await token(), { ...opts(), maxAgeSeconds: 10 });
      expect(r.valid).toBe(true);
      // A 15s-old token must fail that 10s window.
      const old = Math.floor(Date.now() / 1000) - 15;
      const r2 = await verifyAttestationJWT(await token({ iat: old }), {
        ...opts(),
        maxAgeSeconds: 10,
      });
      expect(r2.valid).toBe(false);
    });

    it('accepts a very old token only when maxAgeSeconds=null (explicit opt-out)', async () => {
      const ancient = Math.floor(Date.now() / 1000) - 10 * DEFAULT_MAX_AGE_SECONDS;
      const r = await verifyAttestationJWT(await token({ iat: ancient, exp: '24h' }), {
        ...opts(),
        maxAgeSeconds: null,
      });
      expect(r.valid).toBe(true);
    });

    it('pins the default to 300 seconds so a loosening change surfaces in review', () => {
      expect(DEFAULT_MAX_AGE_SECONDS).toBe(300);
    });
  });

  // H2 — JWKS fetched over http is MITM-able; the verify package must refuse
  // non-loopback http relay URLs before any token can be checked.
  describe('H2 — relay URL scheme policy', () => {
    it('accepts https:// relay URLs', () => {
      expect(() => normalizeRelayUrl('https://relay.openliveness.dev')).not.toThrow();
      expect(normalizeRelayUrl('https://relay.openliveness.dev').protocol).toBe('https:');
    });

    it('defaults bare hosts to https://', () => {
      expect(normalizeRelayUrl('relay.openliveness.dev').protocol).toBe('https:');
    });

    it('rejects explicit http:// for a public host', () => {
      expect(() => normalizeRelayUrl('http://relay.openliveness.dev')).toThrow(
        /refusing http:\/\//,
      );
    });

    it('allows http://localhost for local testing', () => {
      expect(normalizeRelayUrl('http://localhost:3000').protocol).toBe('http:');
    });

    it('allows http://127.0.0.1 for local testing', () => {
      expect(normalizeRelayUrl('http://127.0.0.1:3000').protocol).toBe('http:');
    });

    it('allows http://[::1] for local testing', () => {
      expect(normalizeRelayUrl('http://[::1]:3000').protocol).toBe('http:');
    });

    it('defaults bare "localhost" to http://', () => {
      expect(normalizeRelayUrl('localhost:3000').protocol).toBe('http:');
    });

    // Documents the issuer-shape contract between the relay and verify. The
    // relay signs with iss = origin (scheme://host[:port]); defaultIssuer()
    // must return the same shape or every real token fails verification.
    it('defaultIssuer returns a URL origin, not a bare hostname', () => {
      expect(defaultIssuer('relay.openliveness.dev')).toBe('https://relay.openliveness.dev');
      expect(defaultIssuer('localhost:3000')).toBe('http://localhost:3000');
    });
  });
});
