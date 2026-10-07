import type { FastifyInstance } from 'fastify';
import { randomBytes } from 'crypto';
import { z } from 'zod';
import { CDLError } from '../errors';
import { verifyAppAttestAttestation } from '../attestation/apple';
import { LIMITS, limit } from '../middleware/rateLimit';
import { getRedis } from '../session/store';
import { base64url } from '../utils/crypto';

const YEAR = 365 * 24 * 3600;

const registerBody = z.object({ platform: z.literal('ios'), key_id: z.string().min(1).max(200) });
const attestBody = registerBody.extend({ attestation: z.string().min(1) });

export async function deviceRoutes(app: FastifyInstance) {
  app.post('/cdl/device/register', { config: limit(LIMITS.deviceRegister) }, async (req) => {
    const parsed = registerBody.safeParse(req.body);
    if (!parsed.success) throw new CDLError('ERR_SCHEMA_INVALID');

    const challenge = randomBytes(32).toString('base64');
    await getRedis().set(`cdl:device:${parsed.data.key_id}:challenge`, challenge, 'EX', 300);
    return { challenge };
  });

  app.post('/cdl/device/attest', { config: limit(LIMITS.deviceAttest) }, async (req) => {
    const parsed = attestBody.safeParse(req.body);
    if (!parsed.success) throw new CDLError('ERR_SCHEMA_INVALID');
    const { key_id, attestation } = parsed.data;
    const r = getRedis();

    // getdel so a challenge can only be used for one attempt
    const challenge = await r.getdel(`cdl:device:${key_id}:challenge`);
    if (!challenge) throw new CDLError('ERR_APPLE_ATTEST_INVALID', 'no pending challenge');

    try {
      await verifyAppAttestAttestation(key_id, attestation, challenge);
    } catch (e) {
      if (e instanceof CDLError) throw e;
      throw new CDLError('ERR_APPLE_ATTEST_INVALID', 'attestation could not be parsed');
    }

    await r.set(`cdl:device:${key_id}:trusted`, '1', 'EX', YEAR);
    await r.set(`cdl:device:${key_id}:counter`, '0', 'EX', YEAR);
    return { device_token: base64url(randomBytes(24)) };
  });
}
