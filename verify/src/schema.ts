import { z } from 'zod';

const score = z.number().min(0).max(1);

// The cdl_* claims the relay puts in the token.
export const claimsSchema = z.object({
  sub: z.string(),
  cdl_version: z.string(),
  cdl_decision: z.enum(['pass', 'fail', 'inconclusive']),
  cdl_assurance: z.enum(['IAL2', 'IAL1', 'software_only']),
  cdl_device_platform: z.enum(['ios', 'android']),
  cdl_challenge_id: z.string(),
  cdl_layer_scores: z.object({
    layer2: score,
    layer3: score,
    layer4_lbp: score,
    layer4_fourier: score,
    layer5: score,
  }),
  cdl_hardware_attested: z.boolean(),
  cdl_attestation_hash: z.string(),
  cdl_low_light: z.boolean(),
  cdl_motion: z.boolean(),
});

export type CDLClaims = z.infer<typeof claimsSchema>;
