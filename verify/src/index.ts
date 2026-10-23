import type { JSONWebKeySet } from 'jose';
import { isFresh } from './freshness';
import { Assurance, defaultIssuer, jwksUrl, meetsAssurance } from './jwt';
import { claimsSchema } from './schema';
import { verifySignedToken } from './signature';

export interface VerifyOptions {
  /** Relay host or url, e.g. "relay.openliveness.dev". Used to find the JWKS. */
  relayUrl: string;
  /** Your relying party id. Tokens issued for another party are rejected. */
  audience: string;
  requiredAssurance?: Assurance;
  requiredDecision?: 'pass' | 'fail' | 'inconclusive';
  /** Defaults to the hostname of relayUrl. */
  issuer?: string;
  /**
   * Reject tokens older than this many seconds, on top of the normal expiry.
   * Defaults to {@link DEFAULT_MAX_AGE_SECONDS}; pass `null` to disable the
   * age check entirely (not recommended — the relay's exp of 15 min is the
   * only remaining upper bound).
   */
  maxAgeSeconds?: number | null;
  clockToleranceSeconds?: number;
  /** Supply the key set directly instead of fetching it. Handy for tests and offline use. */
  jwks?: JSONWebKeySet;
}

// Short enough that a bank's "liveness check should be recent" guarantee is
// meaningful, long enough that a slow onboarding form (user filling fields
// after the liveness scan) doesn't invalidate the token. Callers can override
// per use case: 60s for high-value transactions, 15m to allow out-of-session
// token forwarding.
export const DEFAULT_MAX_AGE_SECONDS = 300;

export interface VerifyResult {
  valid: boolean;
  /** Why the token was rejected. Only set when valid is false. */
  reason?: string;
  sessionId?: string;
  decision?: 'pass' | 'fail' | 'inconclusive';
  assuranceLevel?: 'IAL2' | 'IAL1' | 'software_only';
  hardwareAttested?: boolean;
  devicePlatform?: 'ios' | 'android';
  layerScores?: {
    layer2: number;
    layer3: number;
    layer4_lbp: number;
    layer4_fourier: number;
    layer5: number;
  };
}

export async function verifyAttestationJWT(
  jwt: string,
  options: VerifyOptions,
): Promise<VerifyResult> {
  let payload;
  try {
    payload = await verifySignedToken(jwt, {
      jwksUrl: jwksUrl(options.relayUrl),
      jwks: options.jwks,
      issuer: options.issuer ?? defaultIssuer(options.relayUrl),
      audience: options.audience,
      clockTolerance: options.clockToleranceSeconds ?? 5,
    });
  } catch (e) {
    // covers bad signature, expiry, wrong issuer and wrong audience
    return { valid: false, reason: (e as Error).message };
  }

  const claims = claimsSchema.safeParse(payload);
  if (!claims.success) return { valid: false, reason: 'token claims are malformed' };
  const c = claims.data;

  const base: VerifyResult = {
    valid: false,
    sessionId: c.sub,
    decision: c.cdl_decision,
    assuranceLevel: c.cdl_assurance,
    hardwareAttested: c.cdl_hardware_attested,
    devicePlatform: c.cdl_device_platform,
    layerScores: c.cdl_layer_scores,
  };

  const maxAge =
    options.maxAgeSeconds === null
      ? undefined
      : options.maxAgeSeconds ?? DEFAULT_MAX_AGE_SECONDS;
  if (!isFresh(payload.iat, maxAge)) {
    return { ...base, reason: 'token is older than maxAgeSeconds' };
  }
  const wantedDecision = options.requiredDecision ?? 'pass';
  if (c.cdl_decision !== wantedDecision) {
    return { ...base, reason: `decision is ${c.cdl_decision}, expected ${wantedDecision}` };
  }
  if (!meetsAssurance(c.cdl_assurance, options.requiredAssurance ?? 'any')) {
    return { ...base, reason: `assurance ${c.cdl_assurance} is below ${options.requiredAssurance}` };
  }

  return { ...base, valid: true };
}

export type { Assurance } from './jwt';
