import { describe, expect, it } from 'vitest';

import { generateChallengeSequence } from '../src/session/challenge';
import { CHALLENGE_STEPS } from '../src/session/types';

describe('generateChallengeSequence', () => {
  it('returns the requested count of distinct challenges', () => {
    for (let i = 0; i < 50; i++) {
      const seq = generateChallengeSequence(3);
      expect(seq).toHaveLength(3);
      expect(new Set(seq).size).toBe(3);
      for (const step of seq) {
        expect(CHALLENGE_STEPS).toContain(step);
      }
    }
  });

  // Nov 2026 on-device photo-attack testing showed look_left is the only
  // challenge a flat printed photo cannot defeat. Pinning it into every
  // sequence closes the ~60% of sessions where it was previously absent.
  it('always includes look_left', () => {
    for (let i = 0; i < 200; i++) {
      const seq = generateChallengeSequence(3);
      expect(seq).toContain('look_left');
    }
  });

  // Position randomisation guards against a fingerprinting shortcut — if
  // look_left were always at index 0 an attacker could adapt their script
  // to the fixed slot. With 3 slots we expect ~33% occupancy per index;
  // 200 trials gives us a tight-enough band to catch "stuck at 0".
  it('varies the position of look_left across sequences', () => {
    const positionCounts = [0, 0, 0];
    const trials = 200;
    for (let i = 0; i < trials; i++) {
      const seq = generateChallengeSequence(3);
      positionCounts[seq.indexOf('look_left')]++;
    }
    for (const c of positionCounts) {
      expect(c).toBeGreaterThan(trials * 0.2);
      expect(c).toBeLessThan(trials * 0.5);
    }
  });

  // Count-1 sequences degenerate to a single element — must still be
  // look_left. Future callers that ask for 2 or 4 shouldn't accidentally
  // bypass the invariant either.
  it.each([1, 2, 3, 4])('always seeds look_left at count=%d', count => {
    for (let i = 0; i < 20; i++) {
      const seq = generateChallengeSequence(count);
      expect(seq).toHaveLength(count);
      expect(seq).toContain('look_left');
    }
  });
});
