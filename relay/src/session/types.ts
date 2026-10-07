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
  liveness_decision: string | null;
  assurance_level: string | null;
  attestation_hash: string | null;
  jwt_issued: string | null;
  webhook_url: string | null;
}

export const CHALLENGE_STEPS = ['blink', 'look_left', 'look_right', 'smile', 'nod'] as const;

const score = z.number().min(0).max(1);

export const attestationSchema = z
  .object({
    version: z.string(),
    session_id: z.string().uuid(),
    challenge_id: z.string().uuid(),
    challenge_response: z.string().min(1),
    timestamp: z.string().datetime(),
    liveness_decision: z.enum(['pass', 'fail', 'inconclusive']),
    assurance_level: z.enum(['IAL2', 'IAL1', 'software_only']),
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
    ambient_conditions: z
      .object({
        low_light_detected: z.boolean(),
        motion_detected: z.boolean(),
        front_camera_confirmed: z.boolean(),
      })
      .strict(),
    public_key: z.string().min(1),
    signature: z.string().min(1),
  })
  .strict();

export type CDLAttestation = z.infer<typeof attestationSchema>;
