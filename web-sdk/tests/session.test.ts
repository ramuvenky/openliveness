import { describe, expect, it, vi } from 'vitest';
import { httpBase, isLocalHost, pollWithBackoff } from '../src/session';
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

  it('L6: treats the full RFC 1918 range as local, including 172.16/12', () => {
    // The previous heuristic missed 172.16/12 entirely, so a dev running the
    // relay on a corp subnet in that range got https:// and a TLS failure.
    expect(httpBase('172.16.0.5:3000')).toBe('http://172.16.0.5:3000');
    expect(httpBase('172.20.1.1')).toBe('http://172.20.1.1');
    expect(httpBase('172.31.254.254')).toBe('http://172.31.254.254');
    // Boundary — 172.32.* is public, must stay https.
    expect(httpBase('172.32.0.1')).toBe('https://172.32.0.1');
    // 172.15.* is public too, must stay https.
    expect(httpBase('172.15.0.1')).toBe('https://172.15.0.1');
  });

  it('L6: treats 0.0.0.0 and bracketed IPv6 loopback as local', () => {
    expect(httpBase('0.0.0.0:3000')).toBe('http://0.0.0.0:3000');
    expect(httpBase('[::1]:3000')).toBe('http://[::1]:3000');
  });
});

describe('isLocalHost', () => {
  it.each([
    ['localhost', true],
    ['Localhost', true],
    ['localhost:3000', true],
    ['127.0.0.1', true],
    ['127.1.2.3:8080', true],
    ['10.0.0.1', true],
    ['192.168.1.1', true],
    ['172.16.0.1', true],
    ['172.31.255.255', true],
    ['0.0.0.0', true],
    ['::1', true],
    ['[::1]', true],
    ['[::1]:3000', true],
    ['fc00::1', true],
    ['fd12:3456::1', true],
    ['fe80::1', true],
    // Public ranges that must stay https.
    ['relay.openliveness.dev', false],
    ['8.8.8.8', false],
    ['172.15.0.1', false],
    ['172.32.0.1', false],
    ['2606:4700::1111', false],
  ])('isLocalHost(%s) === %s', (host, expected) => {
    expect(isLocalHost(host)).toBe(expected);
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
