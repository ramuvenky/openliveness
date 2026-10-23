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
import { getCachedToken } from '../session/tokenCache';
import { CDLSession } from '../session/types';
import { base64url, safeEqual } from '../utils/crypto';
import { normalizeWebhookUrl } from '../session/webhook';

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

    // N-b: validate webhook_url synchronously at /initiate so an RP that
    // posts a garbage URL gets immediate feedback instead of a silent
    // dispatch-time failure (dispatch logs and continues; the RP has no
    // signal). Scheme/loopback checks are pure-string; the SSRF/DNS pin
    // runs later at dispatch where it belongs.
    let normalizedWebhookUrl: string | null = null;
    if (body.webhook_url !== undefined) {
      normalizedWebhookUrl = normalizeWebhookUrl(body.webhook_url, c.allowLoopbackWebhooks);
      if (!normalizedWebhookUrl) {
        throw new CDLError('ERR_SCHEMA_INVALID', 'webhook_url is not an https URL');
      }
    }

    const sessionId = uuidv4();
    const challengeId = uuidv4();
    const sequence = generateChallengeSequence();
    const pollToken = base64url(randomBytes(32));
    const phoneToken = base64url(randomBytes(32));
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
      phone_token: phoneToken,
      liveness_decision: null,
      assurance_level: null,
      attestation_hash: null,
      jwt_hash: null,
      jwt_issued_at: null,
      webhook_url: normalizedWebhookUrl,
    };
    await saveSession(session);

    // relay_http carries the scheme because the phone SDKs build urls from it directly.
    // phone_token is carried inside the QR (which the phone sees via camera scan) so
    // it never traverses the browser or the RP server. The phone appends it as
    // ?token= when opening the device WS; the server rejects any connect without it.
    const qr = {
      session_id: sessionId,
      challenge_id: challengeId,
      challenge_sequence: sequence,
      relay_ws: `${c.wsBase}/cdl/session/${sessionId}/device?token=${phoneToken}`,
      relay_http: c.httpBase,
      phone_token: phoneToken,
      expires_at: session.expires_at,
      version: '1.0',
    };

    return {
      session_id: sessionId,
      // Echoed so the browser can cross-check the JWT's `aud` matches what
      // it asked for before forwarding the token to its backend. The server
      // ultimately binds this to the JWT aud (see attestation/jwt.ts), so
      // returning it here is advisory — not authoritative — but lets a
      // defensive caller fail fast without re-parsing the token.
      relying_party: body.relying_party,
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

      // The raw token lives in process memory only (see tokenCache). After
      // its 15-minute TTL expires (or across a relay restart) the token is
      // gone, which is a legitimate end-state — not an internal error —
      // but silently returning attestation_token=undefined would resolve
      // the web-sdk's polling promise with a nullish token and look like a
      // happy path to callers. Return an explicit error_code so the SDK
      // can distinguish "stop polling, token's gone" from "keep waiting".
      let attestationToken: string | undefined;
      if (state === 'complete') {
        attestationToken = getCachedToken(session.session_id);
        if (!attestationToken) {
          throw new CDLError(
            'ERR_ATTESTATION_TOKEN_UNAVAILABLE',
            'attestation token is no longer retrievable; retrieve it from the WS session_complete frame at completion time',
          );
        }
      }

      return {
        session_id: session.session_id,
        state,
        liveness_decision: session.liveness_decision,
        assurance_level: session.assurance_level,
        attestation_token: attestationToken,
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
      const { token, decision } = await completeSession(req.params.id, req.body, req.log);
      // "accepted" tracks the liveness outcome, not merely that the attestation
      // was well-formed. A failing decision still issues a token (so the RP has
      // a signed record of the attempt) but must not be reported as accepted —
      // RPs that only check `accepted` would otherwise treat a spoof as a pass.
      const accepted = decision === 'pass';
      return accepted
        ? { accepted: true, attestation_token: token }
        : { accepted: false };
    },
  );

  // Android only. See google.ts for why the liveness decision is not part of the nonce.
  app.post<{ Params: { id: string } }>(
    '/cdl/session/:id/integrity-nonce',
    { config: limit(LIMITS.integrity) },
    async (req) => {
      const session = await loadSession(req.params.id);
      if (!session) throw new CDLError('ERR_SESSION_NOT_FOUND');
      if (Date.parse(session.expires_at) < Date.now()) throw new CDLError('ERR_SESSION_EXPIRED');
      if (session.state === 'complete' || session.state === 'failed') {
        throw new CDLError('ERR_SESSION_ALREADY_COMPLETE');
      }
      return { nonce: await createIntegrityNonce(session.session_id, session.challenge_id) };
    },
  );
}
