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
      issuer: 'relay.test',
      audience: 'example-bank.com',
    });
    expect(payload.cdl_decision).toBe('pass');
    expect(payload.cdl_hardware_attested).toBe(true);
    expect(payload.sub).toBe(att.session_id);

    await expect(jwtVerify(token, jwks, { audience: 'bank-b.com' })).rejects.toThrow();
  });
});
