import { config } from '../config';
import { CDLAttestation } from '../session/types';
import { resolveKeyId, verifyAppAttestAssertion } from './apple';
import { verifyPlayIntegrity } from './google';

// Returns true only when a hardware proof was present and checked.
// Missing proof is not an error here, checkAssurance decides if that is acceptable.
export async function verifyHardware(att: CDLAttestation): Promise<boolean> {
  if (!att.device_attestation) return false;

  // Local testing mode: the phone team can exercise the flow before real keys exist.
  if (config().hardwareMode === 'stub') return true;

  if (att.device_platform === 'ios') {
    const keyId = await resolveKeyId(att.public_key);
    return verifyAppAttestAssertion(
      att.device_attestation,
      att.session_id,
      att.challenge_id,
      att.liveness_decision,
      keyId,
    );
  }

  const result = await verifyPlayIntegrity(att.device_attestation, att.session_id);
  return result.valid;
}
