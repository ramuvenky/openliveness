import type { FastifyInstance } from 'fastify';
import { randomBytes } from 'crypto';
import { v4 as uuidv4 } from 'uuid';
import { z } from 'zod';
import { config } from '../config';
import { CDLError } from '../errors';
import { createIntegrityNonce } from '../attestation/google';
import { LIMITS, limit } from '../middleware/rateLimit';
import { completeSession } from '../session/complete';
import { generateChallengeSequence } from '../session/challenge';
import { loadSession, saveSession, updateSession } from '../session/store';
import { CDLSession } from '../session/types';
import { base64url, safeEqual } from '../utils/crypto';

const initiateBody = z.object({
  relying_party: z.string().min(1).max(253),
  assurance_required: z.enum(['IAL2', 'IAL1', 'any']).default('IAL2'),
  webhook_url: z.string().max(2000).optional(),
});

export async function sessionRoutes(app: FastifyInstance) {
  app.post('/cdl/session/initiate', { config: limit(LIMITS.initiate) }, async (req) => {
    const parsed = initiateBody.safeParse(req.body);
    if (!parsed.success) throw new CDLError('ERR_SCHEMA_INVALID', parsed.error.issues[0]?.message);
    const body = parsed.data;
    const c = config();

    const sessionId = uuidv4();
    const challengeId = uuidv4();
    const sequence = generateChallengeSequence();
    const pollToken = base64url(randomBytes(32));
    const created = new Date();
    const expires = new Date(created.getTime() + c.sessionExpiryMinutes * 60 * 1000);

    const session: CDLSession = {
      session_id: sessionId,
      state: 'initiated',
      relying_party: body.relying_party,
      assurance_required: body.assurance_required,
      challenge_id: challengeId,
      challenge_sequence: JSON.stringify(sequence),
      created_at: created.toISOString(),
      updated_at: created.toISOString(),
      expires_at: expires.toISOString(),
      poll_token: pollToken,
      liveness_decision: null,
      assurance_level: null,
      attestation_hash: null,
      jwt_issued: null,
      webhook_url: body.webhook_url ?? null,
    };
    await saveSession(session);

    // relay_http carries the scheme because the phone SDKs build urls from it directly
    const qr = {
      session_id: sessionId,
      challenge_id: challengeId,
      challenge_sequence: sequence,
      relay_ws: `${c.wsBase}/cdl/session/${sessionId}/device`,
      relay_http: c.httpBase,
      expires_at: session.expires_at,
      version: '1.0',
    };

    return {
      session_id: sessionId,
      challenge_id: challengeId,
      challenge_sequence: sequence,
      qr_payload: Buffer.from(JSON.stringify(qr), 'utf8').toString('base64'),
      poll_token: pollToken,
      expires_at: session.expires_at,
      ws_url: `${c.wsBase}/cdl/session/${sessionId}/ws`,
    };
  });

  app.get<{ Params: { id: string } }>(
    '/cdl/session/:id/status',
    { config: limit(LIMITS.status) },
    async (req) => {
      const session = await loadSession(req.params.id);
      if (!session) throw new CDLError('ERR_SESSION_NOT_FOUND');

      const token = (req.headers.authorization ?? '').replace(/^Bearer\s+/i, '');
      if (!token || !safeEqual(token, session.poll_token)) {
        throw new CDLError('ERR_INVALID_POLL_TOKEN');
      }

      let state = session.state;
      const done = state === 'complete' || state === 'failed';
      if (!done && state !== 'expired' && Date.parse(session.expires_at) < Date.now()) {
        state = 'expired';
        await updateSession(session.session_id, { state });
      }

      return {
        session_id: session.session_id,
        state,
        liveness_decision: session.liveness_decision,
        assurance_level: session.assurance_level,
        // only hand the token out for a finished, passing session
        attestation_token: state === 'complete' ? session.jwt_issued : undefined,
        created_at: session.created_at,
        updated_at: session.updated_at,
        expires_at: session.expires_at,
      };
    },
  );

  app.post<{ Params: { id: string } }>(
    '/cdl/session/:id/complete',
    { config: limit(LIMITS.complete) },
    async (req) => {
      const { token } = await completeSession(req.params.id, req.body, req.log);
      return { accepted: true, attestation_token: token };
    },
  );

  // Android only. See google.ts for why the liveness decision is not part of the nonce.
  app.post<{ Params: { id: string } }>('/cdl/session/:id/integrity-nonce', async (req) => {
    const session = await loadSession(req.params.id);
    if (!session) throw new CDLError('ERR_SESSION_NOT_FOUND');
    if (Date.parse(session.expires_at) < Date.now()) throw new CDLError('ERR_SESSION_EXPIRED');
    if (session.state === 'complete' || session.state === 'failed') {
      throw new CDLError('ERR_SESSION_ALREADY_COMPLETE');
    }
    return { nonce: await createIntegrityNonce(session.session_id, session.challenge_id) };
  });
}
