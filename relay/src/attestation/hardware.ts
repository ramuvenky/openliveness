import { config } from '../config';
import { CDLError } from '../errors';
import { CDLAttestation, CDLSession } from '../session/types';
import { resolveKeyId, verifyAppAttestAssertion } from './apple';
import { verifyPlayIntegrity } from './google';

export interface HardwareResult {
  // true only when the relay actually verified a hardware proof. "Missing
  // proof" is reported as attested=false here — checkAssurance decides
  // whether that's acceptable given the session's assurance_required.
  attested: boolean;
  // true when the proof is anchored in tamper-resistant hardware
  // (Android MEETS_STRONG_INTEGRITY / iOS App Attest). Required for IAL2.
  strong: boolean;
}

export async function verifyHardware(
  att: CDLAttestation,
  session: CDLSession,
): Promise<HardwareResult> {
  if (!att.device_attestation) return { attested: false, strong: false };

  // Local testing mode: the phone team can exercise the flow before real keys exist.
  if (config().hardwareMode === 'stub') return { attested: true, strong: true };

  if (att.device_platform === 'ios') {
    const keyId = await resolveKeyId(att.public_key);
    const ok = await verifyAppAttestAssertion(
      att.device_attestation,
      att.session_id,
      att.challenge_id,
      att.liveness_decision,
      keyId,
    );
    // App Attest keys are generated inside the Secure Enclave; a successful
    // assertion against a stored key proves possession of hardware-backed
    // material, so we treat ios-attested as "strong" uniformly.
    return { attested: ok, strong: ok };
  }

  const result = await verifyPlayIntegrity(att.device_attestation, att.session_id);
  // Android MEETS_DEVICE_INTEGRITY alone can be satisfied by a software-only
  // keystore on a non-TEE device; IAL2 requires the StrongBox / TEE-backed
  // MEETS_STRONG_INTEGRITY verdict. Reject here rather than at checkAssurance
  // so the client gets ERR_GOOGLE_INTEGRITY_LOW (which maps to "upgrade the
  // device") instead of ERR_ATTEST_REQUIRED (which looks like a config bug).
  if (session.assurance_required === 'IAL2' && !result.meetsStrong) {
    throw new CDLError('ERR_GOOGLE_INTEGRITY_LOW', 'IAL2 requires MEETS_STRONG_INTEGRITY');
  }
  return { attested: result.valid, strong: result.meetsStrong };
}
