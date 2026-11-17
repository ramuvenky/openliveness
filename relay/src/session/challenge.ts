import { randomInt } from 'crypto';
import { getRedis } from './store';
import { CHALLENGE_STEPS } from './types';

// The one challenge a flat printed photo cannot defeat. On-device photo-
// attack testing (Nov 2026) showed printed photos pass blink, smile, and
// sometimes look_right by hand-tilting, but a left-profile view can't be
// produced from a straight-on paper photo at any orientation. Every
// session is seeded with REQUIRED_CHALLENGE so the Layer-2 gate always
// has at least one physics-based 2D-photo defense. Attackers knowing
// this challenge is always present doesn't help — the defense is
// geometric, not secret.
const REQUIRED_CHALLENGE = 'look_left';

// Pick `count` distinct steps, always including REQUIRED_CHALLENGE, with
// the remaining slots sampled uniformly from the rest of the pool via a
// partial Fisher-Yates shuffle. Position of REQUIRED_CHALLENGE within
// the sequence is also randomised so the sequencer isn't trivially
// fingerprintable.
export function generateChallengeSequence(count = 3): string[] {
  const rest: string[] = CHALLENGE_STEPS.filter(c => c !== REQUIRED_CHALLENGE);
  for (let i = rest.length - 1; i > 0; i--) {
    const j = randomInt(0, i + 1);
    [rest[i], rest[j]] = [rest[j], rest[i]];
  }
  const sequence = [REQUIRED_CHALLENGE, ...rest.slice(0, count - 1)];
  // Shuffle the final sequence so REQUIRED_CHALLENGE isn't always at position 0.
  for (let i = sequence.length - 1; i > 0; i--) {
    const j = randomInt(0, i + 1);
    [sequence[i], sequence[j]] = [sequence[j], sequence[i]];
  }
  return sequence;
}

export async function isChallengeUsed(challengeId: string): Promise<boolean> {
  return (await getRedis().exists(`cdl:challenge:${challengeId}:used`)) === 1;
}

// SET NX makes the claim atomic, so two racing submissions cannot both win.
export async function claimChallenge(challengeId: string): Promise<boolean> {
  const res = await getRedis().set(
    `cdl:challenge:${challengeId}:used`,
    '1',
    'EX',
    24 * 3600,
    'NX',
  );
  return res === 'OK';
}
