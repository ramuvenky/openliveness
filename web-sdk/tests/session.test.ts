import { describe, expect, it, vi } from 'vitest';
import { httpBase, pollWithBackoff } from '../src/session';
import { CDLRetryableError } from '../src/types';

function fakeFetch(states: Record<string, unknown>[]) {
  let i = 0;
  return vi.fn(async () => {
    const body = states[Math.min(i++, states.length - 1)];
    return { ok: true, json: async () => body } as Response;
  });
}

describe('httpBase', () => {
  it('adds https for hosts and http for local addresses', () => {
    expect(httpBase('relay.openliveness.dev')).toBe('https://relay.openliveness.dev');
    expect(httpBase('localhost:3000')).toBe('http://localhost:3000');
    expect(httpBase('https://x.test/')).toBe('https://x.test');
  });
});

describe('pollWithBackoff', () => {
  it('backs off 1s, 1.5s, 2.25s and resolves on complete', async () => {
    const waits: number[] = [];
    const doFetch = fakeFetch([
      { state: 'initiated' },
      { state: 'phone_connected' },
      { state: 'complete', liveness_decision: 'pass', assurance_level: 'IAL2', attestation_token: 'jwt' },
    ]);
    const seen: string[] = [];

    const result = await pollWithBackoff('sid', 'tok', 'localhost:3000', (s) => seen.push(s), {
      doFetch: doFetch as never,
      sleepFn: async (ms) => void waits.push(ms),
    });

    expect(waits).toEqual([1000, 1500, 2250]);
    expect(seen).toEqual(['initiated', 'phone_connected', 'complete']);
    expect(result).toEqual({ decision: 'pass', assuranceLevel: 'IAL2', token: 'jwt', sessionId: 'sid' });
  });

  it('caps the interval at 5s', async () => {
    const waits: number[] = [];
    const doFetch = fakeFetch([...Array(9).fill({ state: 'initiated' }), { state: 'expired' }]);
    await expect(
      pollWithBackoff('sid', 'tok', 'localhost:3000', () => {}, {
        doFetch: doFetch as never,
        sleepFn: async (ms) => void waits.push(ms),
      }),
    ).rejects.toThrow('session_expired');
    expect(Math.max(...waits)).toBe(5000);
  });

  it('throws a retryable error when the session fails', async () => {
    const doFetch = fakeFetch([{ state: 'failed' }]);
    await expect(
      pollWithBackoff('sid', 'tok', 'localhost:3000', () => {}, {
        doFetch: doFetch as never,
        sleepFn: async () => {},
      }),
    ).rejects.toBeInstanceOf(CDLRetryableError);
  });
});
