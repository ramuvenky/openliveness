import type { FastifyBaseLogger } from 'fastify';
import { config } from '../config';
import { CDLError } from '../errors';
import { verifyHardware } from '../attestation/hardware';
import { issueAttestationJWT } from '../attestation/jwt';
import {
  checkAssurance,
  checkChallenge,
  checkTimestamp,
  verifyAttestationSignature,
} from '../attestation/verify';
import { now } from '../websocket/messages';
import { toBrowser, toPhone } from '../websocket/hub';
import { canonicalize, sha256base64url, sha256hex } from '../utils/crypto';
import { claimChallenge, isChallengeUsed } from './challenge';
import { loadSession, updateSession } from './store';
import { cacheToken } from './tokenCache';
import { attestationSchema, CDLAttestation } from './types';
import { callWebhook } from './webhook';

// Runs the 14 steps from the spec, in order.
export async function completeSession(
  sessionId: string,
  body: unknown,
  log: FastifyBaseLogger,
): Promise<{ token: string; decision: string }> {
  // 1. schema
  if (typeof body !== 'object' || body === null) throw new CDLError('ERR_SCHEMA_INVALID');
  const raw = body as Record<string, unknown>;
  if (!raw.signature) throw new CDLError('ERR_SIGNATURE_MISSING');
  if (!raw.public_key) throw new CDLError('ERR_PUBLIC_KEY_MISSING');
  const parsed = attestationSchema.safeParse(raw);
  if (!parsed.success) {
    throw new CDLError('ERR_SCHEMA_INVALID', parsed.error.issues[0]?.path.join('.') || 'invalid');
  }
  const att: CDLAttestation = parsed.data;
  if (att.version !== '1.0') throw new CDLError('ERR_VERSION_UNSUPPORTED');

  // 2. url and body agree
  if (att.session_id !== sessionId) throw new CDLError('ERR_SESSION_ID_MISMATCH');

  // 3. session state
  const session = await loadSession(sessionId);
  if (!session) throw new CDLError('ERR_SESSION_NOT_FOUND');
  if (session.state === 'complete' || session.state === 'failed') {
    throw new CDLError('ERR_SESSION_ALREADY_COMPLETE');
  }
  if (Date.parse(session.expires_at) < Date.now() || session.state === 'expired') {
    throw new CDLError('ERR_SESSION_EXPIRED');
  }
  if (session.state !== 'phone_connected' && session.state !== 'processing') {
    throw new CDLError('ERR_SESSION_WRONG_STATE');
  }

  try {
    // 4 and 5. challenge binding
    if (await isChallengeUsed(att.challenge_id)) throw new CDLError('ERR_CHALLENGE_REUSED');
    checkChallenge(att, session);

    // 5a. Burn the challenge atomically as soon as the binding is validated,
    // not after the expensive signature / hardware / assurance checks. Two
    // racing submissions for the same challenge_id (an attacker replaying a
    // captured QR, or a buggy phone sending duplicates) would otherwise each
    // run full signature-verify / hardware-attest / X.509-chain traversal
    // before the loser is rejected — that's CPU an attacker can burn by
    // flooding duplicates. The atomic SETNX turns the duplicate into an
    // O(1) Redis call. The trade: a submission whose signature later fails
    // still burns the challenge, so the device would need a fresh session.
    // That's acceptable because signature-verify failures are not a normal
    // retry case (a working device won't produce bad signatures); legit
    // retries happen on liveness fail/inconclusive, which still verify.
    if (!(await claimChallenge(att.challenge_id))) throw new CDLError('ERR_CHALLENGE_REUSED');

    // 6. freshness
    checkTimestamp(att.timestamp, config().attestationMaxAgeSeconds);

    // 7. signature
    if (!(await verifyAttestationSignature(att))) throw new CDLError('ERR_INVALID_SIGNATURE');

    // 8. hardware proof
    const hardwareAttested = await verifyHardware(att);

    // 9. assurance
    checkAssurance(att, session, hardwareAttested);

    // 11. token
    const token = await issueAttestationJWT(att, session, hardwareAttested);
    // The raw token is deliberately NOT persisted in Redis: a snapshot leak
    // would otherwise exfiltrate every live attestation JWT. Instead we
    // persist only its hash (for audit / later verification) and park the
    // raw string in-process for /status + late-join WS handoff.
    cacheToken(sessionId, token);

    // 12. state
    const passed = att.liveness_decision === 'pass';
    await updateSession(sessionId, {
      state: passed ? 'complete' : 'failed',
      liveness_decision: att.liveness_decision,
      assurance_level: att.assurance_level,
      attestation_hash: sha256hex(canonicalize(att)),
      jwt_hash: sha256base64url(token),
      jwt_issued_at: new Date().toISOString(),
    });

    // 13. tell the browser and the phone
    if (passed) {
      toBrowser(sessionId, {
        type: 'session_complete',
        liveness_decision: att.liveness_decision,
        assurance_level: att.assurance_level,
        attestation_token: token,
        timestamp: now(),
      });
    } else {
      toBrowser(sessionId, {
        type: 'session_failed',
        reason: 'liveness_check_failed',
        liveness_decision: att.liveness_decision,
        retry_allowed: true,
        timestamp: now(),
      });
    }
    toPhone(sessionId, { type: 'attestation_received', timestamp: now() });

    // 14. webhook, not awaited so a slow receiver cannot hold the phone up
    void callWebhook({ ...session, assurance_level: att.assurance_level }, token, att, hardwareAttested, log);

    return { token, decision: att.liveness_decision };
  } catch (e) {
    if (e instanceof CDLError) {
      toPhone(sessionId, { type: 'attestation_rejected', error_code: e.code, timestamp: now() });
    }
    throw e;
  }
}
