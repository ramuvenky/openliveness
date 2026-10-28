import { z } from 'zod';

export type SessionState =
  | 'initiated'
  | 'phone_connected'
  | 'processing'
  | 'complete'
  | 'failed'
  | 'expired';

export interface CDLSession {
  session_id: string;
  state: SessionState;
  relying_party: string;
  assurance_required: 'IAL2' | 'IAL1' | 'any';
  challenge_id: string;
  challenge_sequence: string; // JSON stringified array
  created_at: string;
  updated_at: string;
  expires_at: string;
  poll_token: string;
  // Capability token embedded in the QR payload. The phone WS at
  // /cdl/session/:id/device requires it as ?token=, so possession of the
  // session_id alone (which may leak via logs, URL referers, over-the-
  // shoulder etc.) is not enough to impersonate the phone leg.
  phone_token: string;
  liveness_decision: string | null;
  assurance_level: string | null;
  // Phase 2 downgrade-attack signal persisted from the attestation. Null
  // when the submitting SDK pre-dates the field. See checkAssurance for
  // the ↔ assurance_level invariants enforced at submission time.
  hw_attestation_status: 'ok' | 'unsupported' | 'failed' | null;
  attestation_hash: string | null;
  // SHA-256 of the issued JWT (base64url). We deliberately do NOT persist
  // the raw token in Redis; see src/session/tokenCache.ts for the handoff.
  jwt_hash: string | null;
  jwt_issued_at: string | null;
  webhook_url: string | null;
}

export const CHALLENGE_STEPS = ['blink', 'look_left', 'look_right', 'smile', 'nod'] as const;

// Scores feed the canonical-JSON preimage used to verify the device signature,
// and canonicalize() throws on NaN / ±Infinity (its contract with the mobile
// SDKs is "finite only"). We reject non-finite numbers up-front at the schema
// boundary so a malformed body never reaches canonicalize — the implicit
// NaN-fails-comparisons behavior of .min / .max would still catch it, but
// relying on that is brittle: a future refactor dropping the range check
// would silently open the door. `.finite()` is the explicit contract.
const score = z.number().finite().min(0).max(1);

export const attestationSchema = z
  .object({
    version: z.string(),
    session_id: z.string().uuid(),
    challenge_id: z.string().uuid(),
    challenge_response: z.string().min(1),
    timestamp: z.string().datetime(),
    liveness_decision: z.enum(['pass', 'fail', 'inconclusive']),
    assurance_level: z.enum(['IAL2', 'IAL1', 'software_only']),
    // Phase 2 downgrade-attack signal. Lets the server distinguish
    // "device cannot attest" (expected software_only) from "device
    // tried and failed" (potential forced downgrade). Optional so
    // older SDK builds that pre-date the field don't fail the strict
    // schema. When absent, the field is omitted (not nulled) from the
    // attestation JWT and the webhook body so consumers can distinguish
    // "SDK didn't say" from "SDK said ok".
    hw_attestation_status: z.enum(['ok', 'unsupported', 'failed']).optional(),
    layer_scores: z
      .object({
        layer2_challenge_completed: z.boolean(),
        layer2_challenge_score: score,
        layer3_pulse_detected: z.boolean(),
        layer3_bpm_in_range: z.boolean(),
        layer3_rppg_confidence: score,
        layer4_lbp_score: score,
        layer4_fourier_score: score,
        layer5_behavioral_score: score,
        layer5_microsaccade_detected: z.boolean(),
      })
      .strict(),
    device_platform: z.enum(['ios', 'android']),
    device_attestation: z.string().min(1).optional(),
    device_id_hash: z.string().min(1),
    // Defense-in-depth wire marker. The mobile SDKs stamp this `true` when
    // their liveness path fell back to the Phase-1 hardcoded scores (no
    // real landmark stream). The relay should refuse `stub_mode: true`
    // from any session_id not on the CI allowlist — otherwise a hostile
    // fork that flips the SDK's `allowStubMode` flag could ship an
    // "always passes" build. Keeping the field optional so builds that
    // predate this marker continue to parse.
    stub_mode: z.boolean().optional(),
    ambient_conditions: z
      .object({
        low_light_detected: z.boolean(),
        motion_detected: z.boolean(),
        front_camera_confirmed: z.boolean(),
      })
      // Passthrough (not .strict()) so a newer mobile SDK that adds signal
      // fields (ambient_temperature, glare_detected, …) can roll out without
      // the server rejecting the whole attestation. layer_scores stays strict
      // because unknown score keys would change the canonicalize preimage and
      // break signature verification; ambient_conditions is read for logging
      // and advisory JWT claims only, so new keys are safe to ignore here.
      .passthrough(),
    public_key: z.string().min(1),
    signature: z.string().min(1),
  })
  .strict();

export type CDLAttestation = z.infer<typeof attestationSchema>;
