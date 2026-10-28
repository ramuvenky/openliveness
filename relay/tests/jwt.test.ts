import { mkdtempSync, writeFileSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import { createLocalJWKSet, jwtVerify } from 'jose';
import { beforeAll, describe, expect, it } from 'vitest';
import { getJwks, issueAttestationJWT, resetJwksCache, resetKeyCache } from '../src/attestation/jwt';
import { makeAttestation, makePemKeys, makeSession } from './helpers';

beforeAll(() => {
  const dir = mkdtempSync(join(tmpdir(), 'cdl-'));
  const { privatePem, publicPem } = makePemKeys();
  writeFileSync(join(dir, 'priv.pem'), privatePem);
  writeFileSync(join(dir, 'pub.pem'), publicPem);
  process.env.JWT_PRIVATE_KEY_PATH = join(dir, 'priv.pem');
  process.env.JWT_PUBLIC_KEY_PATH = join(dir, 'pub.pem');
  process.env.RELAY_BASE_URL = 'relay.test';
  resetKeyCache();
  resetJwksCache();
});

describe('jwt and jwks', () => {
  it('serves an EC P-256 key without private material', async () => {
    const { keys } = (await getJwks()) as { keys: Record<string, string>[] };
    expect(keys[0]).toMatchObject({ kty: 'EC', crv: 'P-256', alg: 'ES256' });
    expect(keys[0].d).toBeUndefined();
  });

  it('issues a token the published key can verify, bound to the audience', async () => {
    const att = await makeAttestation();
    const token = await issueAttestationJWT(att, makeSession(), true);
    const jwks = createLocalJWKSet((await getJwks()) as never);

    const { payload } = await jwtVerify(token, jwks, {
      // L5: iss is normalized to a URL so OIDC / JOSE validators that expect
      // StringOrURI can parse it. Default port 3000 appears because PORT is
      // unset in this test environment and NODE_ENV != 'production'.
      issuer: 'http://relay.test:3000',
      audience: 'example-bank.com',
    });
    expect(payload.cdl_decision).toBe('pass');
    expect(payload.cdl_hardware_attested).toBe(true);
    expect(payload.sub).toBe(att.session_id);

    await expect(jwtVerify(token, jwks, { audience: 'bank-b.com' })).rejects.toThrow();
  });

  // Phase 2 drift — cdl_hw_status surfaces the downgrade signal to RPs
  // via the JWT. The claim must be present when the SDK reported one
  // (so an RP can gate on it) and absent when the SDK didn't (so an RP
  // can distinguish "pre-Phase-2 client" from "client said ok").
  it('includes cdl_hw_status when the attestation carries one', async () => {
    const att = await makeAttestation({
      assurance_level: 'IAL2',
      hw_attestation_status: 'ok',
    });
    const jwks = createLocalJWKSet((await getJwks()) as never);
    const token = await issueAttestationJWT(att, makeSession(), true);
    const { payload } = await jwtVerify(token, jwks, {
      issuer: 'http://relay.test:3000',
      audience: 'example-bank.com',
    });
    expect(payload.cdl_hw_status).toBe('ok');
  });

  it('surfaces hw_attestation_status=failed verbatim (downgrade signal)', async () => {
    const att = await makeAttestation({ hw_attestation_status: 'failed' });
    const jwks = createLocalJWKSet((await getJwks()) as never);
    const token = await issueAttestationJWT(att, makeSession(), false);
    const { payload } = await jwtVerify(token, jwks, {
      issuer: 'http://relay.test:3000',
      audience: 'example-bank.com',
    });
    expect(payload.cdl_hw_status).toBe('failed');
  });

  it('omits cdl_hw_status when the attestation did not include one (older SDK)', async () => {
    const att = await makeAttestation();
    const jwks = createLocalJWKSet((await getJwks()) as never);
    const token = await issueAttestationJWT(att, makeSession(), false);
    const { payload } = await jwtVerify(token, jwks, {
      issuer: 'http://relay.test:3000',
      audience: 'example-bank.com',
    });
    // Must be truly absent, not null — null would be an RP-visible signal
    // ("client said no") which is different from "client didn't say".
    expect('cdl_hw_status' in payload).toBe(false);
  });

  // L1 — jti is a per-token random id. Enables RP-side "have I seen this
  // exact token before?" dedupe caches even if a buggy relay ever issued
  // twice for the same session.
  it('L1: sets a unique jti claim per issuance', async () => {
    const att = await makeAttestation();
    const jwks = createLocalJWKSet((await getJwks()) as never);
    const t1 = await issueAttestationJWT(att, makeSession(), true);
    const t2 = await issueAttestationJWT(att, makeSession(), true);
    const p1 = (
      await jwtVerify(t1, jwks, { issuer: 'http://relay.test:3000', audience: 'example-bank.com' })
    ).payload;
    const p2 = (
      await jwtVerify(t2, jwks, { issuer: 'http://relay.test:3000', audience: 'example-bank.com' })
    ).payload;
    expect(typeof p1.jti).toBe('string');
    expect(p1.jti).toMatch(/^[0-9a-f-]{36}$/);
    expect(p1.jti).not.toBe(p2.jti);
  });
});
