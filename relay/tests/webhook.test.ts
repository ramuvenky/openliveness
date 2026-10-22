import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  callWebhook,
  isBlockedIp,
  normalizeWebhookUrl,
  resolveAndCheckHost,
  signWebhookBody,
} from '../src/session/webhook';
import { makeSession } from './helpers';
import type { CDLAttestation } from '../src/session/types';

// Locks in the T2 + M3 fixes: webhook URLs may never leak attestation tokens
// to non-TLS endpoints, SSRF targets (cloud metadata, RFC1918, loopback by
// default), and bodies must be HMAC-signed so a third party can't forge
// cdl.session.complete events. Loopback http is only accepted when an
// explicit `allowLoopback=true` flag is passed in — the previous NODE_ENV
// heuristic silently opened SSRF when the env was unset or misspelled.

const originalEnv = process.env.NODE_ENV;

afterEach(() => {
  process.env.NODE_ENV = originalEnv;
  delete process.env.WEBHOOK_SIGNING_SECRET;
  delete process.env.ALLOW_LOOPBACK_WEBHOOKS;
  vi.restoreAllMocks();
});

describe('normalizeWebhookUrl — scheme policy', () => {
  it('accepts https (loopback flag irrelevant)', () => {
    expect(normalizeWebhookUrl('https://example.com/hook', false)).toBe(
      'https://example.com/hook',
    );
  });

  it('adds https:// to bare host', () => {
    expect(normalizeWebhookUrl('example.com/hook', false)).toBe(
      'https://example.com/hook',
    );
  });

  it('rejects http even for public hosts when loopback is allowed', () => {
    expect(normalizeWebhookUrl('http://example.com/hook', true)).toBeNull();
  });

  it('rejects http in the default (loopback-disallowed) config', () => {
    expect(normalizeWebhookUrl('http://example.com/hook', false)).toBeNull();
  });

  it('allows http://localhost only when allowLoopback=true', () => {
    expect(normalizeWebhookUrl('http://localhost:8080/x', true)).toBe(
      'http://localhost:8080/x',
    );
    expect(normalizeWebhookUrl('http://localhost:8080/x', false)).toBeNull();
  });

  it('allows http://127.0.0.1 only when allowLoopback=true', () => {
    expect(normalizeWebhookUrl('http://127.0.0.1/x', true)).toBe('http://127.0.0.1/x');
    expect(normalizeWebhookUrl('http://127.0.0.1/x', false)).toBeNull();
  });

  it('allows http://[::1] only when allowLoopback=true', () => {
    expect(normalizeWebhookUrl('http://[::1]/x', true)).toBe('http://[::1]/x');
    expect(normalizeWebhookUrl('http://[::1]/x', false)).toBeNull();
  });

  it('rejects http://non-loopback even when allowLoopback=true', () => {
    expect(normalizeWebhookUrl('http://example.com/hook', true)).toBeNull();
  });

  it('rejects file:// scheme', () => {
    expect(normalizeWebhookUrl('file:///etc/passwd', true)).toBeNull();
  });

  it('rejects ftp:// scheme', () => {
    expect(normalizeWebhookUrl('ftp://example.com/x', true)).toBeNull();
  });

  it('rejects javascript:// scheme', () => {
    expect(normalizeWebhookUrl('javascript://example.com/x', true)).toBeNull();
  });

  it('rejects ws:// scheme', () => {
    expect(normalizeWebhookUrl('ws://example.com/x', true)).toBeNull();
  });

  it('rejects garbage input', () => {
    expect(normalizeWebhookUrl(':::not a url', true)).toBeNull();
  });
});

describe('M3 — ALLOW_LOOPBACK_WEBHOOKS gating in callWebhook', () => {
  // callWebhook reads config().allowLoopbackWebhooks, which is derived from
  // the env on each call. These tests pin the end-to-end behavior rather
  // than the pure normalize helper above.
  const att: CDLAttestation = {
    version: '1.0',
    session_id: 'ignored',
    challenge_id: 'ignored',
    challenge_response: 'blink,look_left,smile',
    timestamp: new Date().toISOString(),
    liveness_decision: 'pass',
    assurance_level: 'software_only',
    layer_scores: {
      layer2_challenge_completed: true,
      layer2_challenge_score: 0.9,
      layer3_pulse_detected: true,
      layer3_bpm_in_range: true,
      layer3_rppg_confidence: 0.9,
      layer4_lbp_score: 0.9,
      layer4_fourier_score: 0.9,
      layer5_behavioral_score: 0.9,
      layer5_microsaccade_detected: true,
    },
    device_platform: 'android',
    device_id_hash: 'abc',
    ambient_conditions: {
      low_light_detected: false,
      motion_detected: false,
      front_camera_confirmed: true,
    },
    public_key: 'ignored',
    signature: 'ignored',
  };

  const log = {
    warn: vi.fn(),
    info: vi.fn(),
    error: vi.fn(),
    debug: vi.fn(),
    trace: vi.fn(),
    fatal: vi.fn(),
    child: () => log,
    level: 'info',
    silent: vi.fn(),
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  } as any;

  it('rejects loopback webhook when ALLOW_LOOPBACK_WEBHOOKS is unset', async () => {
    delete process.env.ALLOW_LOOPBACK_WEBHOOKS;
    process.env.WEBHOOK_SIGNING_SECRET = 'sek';
    const fetchSpy = vi.spyOn(global, 'fetch').mockResolvedValue(new Response(''));
    const warn = vi.fn();
    const l = { ...log, warn };
    await callWebhook(
      makeSession({ webhook_url: 'http://127.0.0.1:9000/x' }),
      'tok',
      att,
      false,
      l,
    );
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(warn).toHaveBeenCalledWith(
      expect.anything(),
      expect.stringMatching(/scheme not allowed/),
    );
  });

  it('rejects loopback webhook when NODE_ENV=development alone (no explicit opt-in)', async () => {
    // The previous behavior accepted loopback whenever NODE_ENV !== 'production'.
    // That silently opened SSRF whenever NODE_ENV was unset or misspelled.
    // Confirm that NODE_ENV alone is no longer sufficient.
    process.env.NODE_ENV = 'development';
    delete process.env.ALLOW_LOOPBACK_WEBHOOKS;
    process.env.WEBHOOK_SIGNING_SECRET = 'sek';
    const fetchSpy = vi.spyOn(global, 'fetch').mockResolvedValue(new Response(''));
    await callWebhook(
      makeSession({ webhook_url: 'http://127.0.0.1:9000/x' }),
      'tok',
      att,
      false,
      log,
    );
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('accepts loopback webhook only when ALLOW_LOOPBACK_WEBHOOKS=true', async () => {
    process.env.ALLOW_LOOPBACK_WEBHOOKS = 'true';
    process.env.WEBHOOK_SIGNING_SECRET = 'sek';
    const fetchSpy = vi.spyOn(global, 'fetch').mockResolvedValue(new Response(''));
    await callWebhook(
      makeSession({ webhook_url: 'http://127.0.0.1:9000/x' }),
      'tok',
      att,
      false,
      log,
    );
    expect(fetchSpy).toHaveBeenCalledOnce();
  });
});

describe('isBlockedIp — SSRF guard', () => {
  it('blocks AWS/GCP/Azure metadata (169.254.169.254)', () => {
    expect(isBlockedIp('169.254.169.254')).toBe(true);
  });

  it('blocks all 169.254.0.0/16 link-local', () => {
    expect(isBlockedIp('169.254.1.1')).toBe(true);
  });

  it('blocks 10.0.0.0/8', () => {
    expect(isBlockedIp('10.0.0.1')).toBe(true);
    expect(isBlockedIp('10.255.255.255')).toBe(true);
  });

  it('blocks 172.16.0.0/12', () => {
    expect(isBlockedIp('172.16.0.1')).toBe(true);
    expect(isBlockedIp('172.31.255.255')).toBe(true);
    expect(isBlockedIp('172.32.0.1')).toBe(false); // just outside
  });

  it('blocks 192.168.0.0/16', () => {
    expect(isBlockedIp('192.168.1.1')).toBe(true);
  });

  it('blocks 127.0.0.0/8 loopback by default', () => {
    expect(isBlockedIp('127.0.0.1')).toBe(true);
    expect(isBlockedIp('127.1.2.3')).toBe(true);
  });

  it('allows 127.0.0.1 when loopback flag is set (dev mode)', () => {
    expect(isBlockedIp('127.0.0.1', true)).toBe(false);
  });

  it('allows public IPs', () => {
    expect(isBlockedIp('8.8.8.8')).toBe(false);
    expect(isBlockedIp('1.1.1.1')).toBe(false);
  });

  it('blocks IPv6 loopback by default', () => {
    expect(isBlockedIp('::1')).toBe(true);
  });

  it('blocks IPv6 link-local fe80::/10', () => {
    expect(isBlockedIp('fe80::1')).toBe(true);
  });

  it('blocks IPv6 unique-local fc00::/7', () => {
    expect(isBlockedIp('fc00::1')).toBe(true);
    expect(isBlockedIp('fd12:3456::1')).toBe(true);
  });

  it('blocks IPv6 multicast ff00::/8', () => {
    expect(isBlockedIp('ff02::1')).toBe(true);
  });

  it('blocks IPv4-mapped loopback via ::ffff:', () => {
    expect(isBlockedIp('::ffff:127.0.0.1')).toBe(true);
  });

  it('allows a public IPv6', () => {
    expect(isBlockedIp('2606:4700:4700::1111')).toBe(false);
  });
});

describe('resolveAndCheckHost', () => {
  it('throws on blocked IP literal', async () => {
    await expect(resolveAndCheckHost('169.254.169.254', false)).rejects.toThrow(/blocked ip/);
  });

  it('passes a public IP literal', async () => {
    await expect(resolveAndCheckHost('8.8.8.8', false)).resolves.toBeUndefined();
  });

  it('rejects localhost when loopback is not allowed', async () => {
    // In the sandbox CI this may resolve to 127.0.0.1 or ::1 — either way, blocked.
    await expect(resolveAndCheckHost('localhost', false)).rejects.toThrow(/blocked ip/);
  });

  it('passes localhost when loopback is allowed', async () => {
    await expect(resolveAndCheckHost('localhost', true)).resolves.toBeUndefined();
  });
});

describe('signWebhookBody', () => {
  it('produces deterministic HMAC-SHA256 hex', () => {
    const a = signWebhookBody('secret', 'hello');
    const b = signWebhookBody('secret', 'hello');
    expect(a).toBe(b);
    expect(a).toMatch(/^sha256=[0-9a-f]{64}$/);
  });

  it('changes with a different secret', () => {
    expect(signWebhookBody('a', 'hello')).not.toBe(signWebhookBody('b', 'hello'));
  });
});

describe('callWebhook integration', () => {
  const att: CDLAttestation = {
    version: '1.0',
    session_id: 'ignored',
    challenge_id: 'ignored',
    challenge_response: 'blink,look_left,smile',
    timestamp: new Date().toISOString(),
    liveness_decision: 'pass',
    assurance_level: 'software_only',
    layer_scores: {
      layer2_challenge_completed: true,
      layer2_challenge_score: 0.9,
      layer3_pulse_detected: true,
      layer3_bpm_in_range: true,
      layer3_rppg_confidence: 0.9,
      layer4_lbp_score: 0.9,
      layer4_fourier_score: 0.9,
      layer5_behavioral_score: 0.9,
      layer5_microsaccade_detected: true,
    },
    device_platform: 'android',
    device_id_hash: 'abc',
    ambient_conditions: {
      low_light_detected: false,
      motion_detected: false,
      front_camera_confirmed: true,
    },
    public_key: 'ignored',
    signature: 'ignored',
  };

  const log = {
    warn: vi.fn(),
    info: vi.fn(),
    error: vi.fn(),
    debug: vi.fn(),
    trace: vi.fn(),
    fatal: vi.fn(),
    child: () => log,
    level: 'info',
    silent: vi.fn(),
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  } as any;

  beforeEach(() => {
    log.warn.mockReset();
  });

  it('skips when webhook_url is null', async () => {
    const fetchSpy = vi.spyOn(global, 'fetch').mockResolvedValue(new Response(''));
    await callWebhook(makeSession({ webhook_url: null }), 'tok', att, false, log);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('skips and warns on non-https URL in production', async () => {
    process.env.NODE_ENV = 'production';
    const fetchSpy = vi.spyOn(global, 'fetch').mockResolvedValue(new Response(''));
    await callWebhook(
      makeSession({ webhook_url: 'http://example.com/hook' }),
      'tok',
      att,
      false,
      log,
    );
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(log.warn).toHaveBeenCalledWith(
      expect.anything(),
      expect.stringMatching(/scheme not allowed/),
    );
  });

  it('skips and warns when host is a cloud metadata IP', async () => {
    process.env.NODE_ENV = 'production';
    process.env.WEBHOOK_SIGNING_SECRET = 'sek';
    const fetchSpy = vi.spyOn(global, 'fetch').mockResolvedValue(new Response(''));
    await callWebhook(
      makeSession({ webhook_url: 'https://169.254.169.254/latest/meta-data/' }),
      'tok',
      att,
      false,
      log,
    );
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(log.warn).toHaveBeenCalledWith(
      expect.anything(),
      expect.stringMatching(/SSRF guard/),
    );
  });

  it('skips and warns when signing secret is not set', async () => {
    process.env.NODE_ENV = 'production';
    delete process.env.WEBHOOK_SIGNING_SECRET;
    const fetchSpy = vi.spyOn(global, 'fetch').mockResolvedValue(new Response(''));
    await callWebhook(
      makeSession({ webhook_url: 'https://8.8.8.8/hook' }),
      'tok',
      att,
      false,
      log,
    );
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(log.warn).toHaveBeenCalledWith(
      expect.anything(),
      expect.stringMatching(/WEBHOOK_SIGNING_SECRET not configured/),
    );
  });

  it('posts with signature header on happy path', async () => {
    process.env.NODE_ENV = 'production';
    process.env.WEBHOOK_SIGNING_SECRET = 'sek';
    const fetchSpy = vi.spyOn(global, 'fetch').mockResolvedValue(new Response(''));
    await callWebhook(
      makeSession({ webhook_url: 'https://8.8.8.8/hook' }),
      'tok-123',
      att,
      true,
      log,
    );
    expect(fetchSpy).toHaveBeenCalledOnce();
    const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('https://8.8.8.8/hook');
    const headers = init.headers as Record<string, string>;
    expect(headers['Content-Type']).toBe('application/json');
    expect(headers['X-OpenLiveness-Signature']).toMatch(/^sha256=[0-9a-f]{64}$/);
    const body = init.body as string;
    expect(JSON.parse(body)).toMatchObject({
      event: 'cdl.session.complete',
      attestation_token: 'tok-123',
      hardware_attested: true,
    });
    // Signature must be computable from exactly the body we send.
    const expected = signWebhookBody('sek', body);
    expect(headers['X-OpenLiveness-Signature']).toBe(expected);
  });
});
