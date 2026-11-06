import { createHash } from 'crypto';
import { google } from 'googleapis';
import { config } from '../config';
import { CDLError } from '../errors';
import { getRedis } from '../session/store';
import { sha256base64url } from '../utils/crypto';

export interface PlayIntegrityResult {
  valid: boolean;
  meetsDevice: boolean;
  meetsStrong: boolean;
  appRecognized: boolean;
}

export function buildIntegrityNonce(sessionId: string, challengeId: string): string {
  return sha256base64url(sessionId + challengeId + config().integritySecret);
}

// The phone asks for the nonce before liveness runs, so the decision cannot be part of it.
// The decision IS bound into the hardware signature via the Standard API's
// requestHash: the SDK computes `SHA256(nonce|decision|session|challenge)` and
// passes it to prepareIntegrityToken, and `verifyPlayIntegrity` below
// recomputes the same hash from the posted attestation's fields. A hostile
// SDK fork that flips liveness_decision after the Play Integrity call
// breaks that recompute → ERR_GOOGLE_INTEGRITY_NONCE. Replaces the
// Classic-era "nonce-only, decision only covered by outer SDK signature"
// trust model.
export async function createIntegrityNonce(sessionId: string, challengeId: string): Promise<string> {
  const nonce = buildIntegrityNonce(sessionId, challengeId);
  await getRedis().set(`cdl:session:${sessionId}:integrity_nonce`, nonce, 'EX', 300);
  return nonce;
}

/**
 * Derives the Play Integrity Standard `requestHash` from the stored nonce
 * and the fields the SDK committed to when it called Play Integrity. Must
 * match `CDLSession.Companion.computeRequestHash` in the Android SDK and
 * the iOS equivalent — field order, separator, and encoding are all part
 * of the wire contract. Exported for the unit test to pin the encoding.
 */
export function computeExpectedRequestHash(
  nonce: string,
  decision: string,
  sessionId: string,
  challengeId: string,
): string {
  const material = `${nonce}|${decision}|${sessionId}|${challengeId}`;
  return createHash('sha256').update(material, 'utf8').digest('base64url');
}

export async function verifyPlayIntegrity(
  token: string,
  sessionId: string,
  challengeId: string,
  livenessDecision: string,
): Promise<PlayIntegrityResult> {
  const auth = new google.auth.GoogleAuth({
    scopes: ['https://www.googleapis.com/auth/playintegrity'],
  });
  const api = google.playintegrity({ version: 'v1', auth });

  let verdict;
  try {
    const res = await api.v1.decodeIntegrityToken({
      packageName: config().googlePackageName,
      requestBody: { integrityToken: token },
    });
    verdict = res.data.tokenPayloadExternal;
  } catch {
    throw new CDLError('ERR_GOOGLE_INTEGRITY_INVALID', 'token could not be decoded');
  }
  if (!verdict) throw new CDLError('ERR_GOOGLE_INTEGRITY_INVALID');

  // The nonce is single-use: fetch-and-delete in one MULTI so a captured
  // Play Integrity token can't be replayed within the 5-minute nonce TTL.
  // (GETDEL would be one round-trip but the MULTI form works on any Redis
  // version and against the FakeRedis used by the tests.)
  const nonceKey = `cdl:session:${sessionId}:integrity_nonce`;
  const r = getRedis();
  const results = await r.multi().get(nonceKey).del(nonceKey).exec();
  const storedNonce = (results?.[0]?.[1] ?? null) as string | null;
  if (!storedNonce) {
    throw new CDLError('ERR_GOOGLE_INTEGRITY_NONCE');
  }
  // Standard binds via requestHash, not nonce. Recompute from the stored
  // nonce + the fields the posted attestation claims it sent to Play
  // Integrity. A mismatch means either (a) the SDK posted a different
  // decision than it hardware-bound, (b) the session or challenge drifted,
  // or (c) an attacker is replaying a different verdict. All three are
  // the same server response: reject.
  const expectedHash = computeExpectedRequestHash(
    storedNonce,
    livenessDecision,
    sessionId,
    challengeId,
  );
  if (verdict.requestDetails?.requestHash !== expectedHash) {
    throw new CDLError('ERR_GOOGLE_INTEGRITY_NONCE');
  }

  const verdicts = verdict.deviceIntegrity?.deviceRecognitionVerdict ?? [];
  if (!verdicts.includes('MEETS_DEVICE_INTEGRITY')) {
    throw new CDLError('ERR_GOOGLE_INTEGRITY_LOW');
  }

  // A valid device verdict for a non-PLAY_RECOGNIZED app means the user is
  // running a sideloaded or cloned build of the SDK. The device-level proof
  // only tells us "this hardware is unmodified", not "this is our app" — so
  // the relay refuses here rather than letting the clone pass as IAL2.
  const appRecognized = verdict.appIntegrity?.appRecognitionVerdict === 'PLAY_RECOGNIZED';
  if (!appRecognized) {
    throw new CDLError('ERR_GOOGLE_INTEGRITY_INVALID', 'app not recognized by Play');
  }

  return {
    valid: true,
    meetsDevice: true,
    meetsStrong: verdicts.includes('MEETS_STRONG_INTEGRITY'),
    appRecognized: true,
  };
}
