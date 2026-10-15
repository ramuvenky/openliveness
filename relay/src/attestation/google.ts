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
// The decision is still bound, because the attestation (which holds the token) is signed.
export async function createIntegrityNonce(sessionId: string, challengeId: string): Promise<string> {
  const nonce = buildIntegrityNonce(sessionId, challengeId);
  await getRedis().set(`cdl:session:${sessionId}:integrity_nonce`, nonce, 'EX', 300);
  return nonce;
}

export async function verifyPlayIntegrity(
  token: string,
  sessionId: string,
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

  const expected = await getRedis().get(`cdl:session:${sessionId}:integrity_nonce`);
  if (!expected || verdict.requestDetails?.nonce !== expected) {
    throw new CDLError('ERR_GOOGLE_INTEGRITY_NONCE');
  }

  const verdicts = verdict.deviceIntegrity?.deviceRecognitionVerdict ?? [];
  if (!verdicts.includes('MEETS_DEVICE_INTEGRITY')) {
    throw new CDLError('ERR_GOOGLE_INTEGRITY_LOW');
  }

  return {
    valid: true,
    meetsDevice: true,
    meetsStrong: verdicts.includes('MEETS_STRONG_INTEGRITY'),
    appRecognized: verdict.appIntegrity?.appRecognitionVerdict === 'PLAY_RECOGNIZED',
  };
}
