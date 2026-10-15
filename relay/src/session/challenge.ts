import { randomInt } from 'crypto';
import { getRedis } from './store';
import { CHALLENGE_STEPS } from './types';

// Pick 3 distinct steps with a partial Fisher-Yates shuffle.
export function generateChallengeSequence(count = 3): string[] {
  const pool: string[] = [...CHALLENGE_STEPS];
  for (let i = pool.length - 1; i > 0; i--) {
    const j = randomInt(0, i + 1);
    [pool[i], pool[j]] = [pool[j], pool[i]];
  }
  return pool.slice(0, count);
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
