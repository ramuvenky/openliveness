import { CDLError, CDLOptions, CDLResult, CDLRetryableError, InitiateResponse } from './types';

export const POLL_INITIAL_INTERVAL_MS = 1000;
export const POLL_MAX_INTERVAL_MS = 5000;
export const POLL_BACKOFF_MULTIPLIER = 1.5;

type Fetch = typeof fetch;

// "relay.openliveness.dev" becomes https, local / private addresses become http.
// RFC 1918 covers 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 — the previous
// regex missed the 172.16/12 range, so a developer running the relay on a
// private corporate subnet in that range got https:// (and a TLS handshake
// failure) instead of http://. We also recognize 0.0.0.0 (wildcard bind
// addresses hit from the same host) and IPv6 loopback / unique-local
// prefixes. If a caller is on a non-loopback IPv6 and wants plaintext they
// can still pass the scheme explicitly in relayUrl.
export function httpBase(relayUrl: string): string {
  const withScheme = /^https?:\/\//i.test(relayUrl)
    ? relayUrl
    : `${isLocalHost(relayUrl) ? 'http' : 'https'}://${relayUrl}`;
  return withScheme.replace(/\/+$/, '');
}

// Exported so tests can pin the host-classification contract directly.
export function isLocalHost(host: string): boolean {
  // Strip :port and any trailing path for the match. For bracketed IPv6
  // ([::1]:3000) the bracketed form is kept so the ipv6 branch sees it.
  const bare = host.replace(/\/.*$/, '').replace(/:(\d+)$/, '');
  if (/^localhost$/i.test(bare)) return true;
  if (bare === '0.0.0.0') return true;
  if (/^127\./.test(bare)) return true;
  if (/^10\./.test(bare)) return true;
  if (/^192\.168\./.test(bare)) return true;
  // RFC 1918 172.16.0.0/12 — second octet 16..31. The previous heuristic
  // missed this entirely.
  if (/^172\.(1[6-9]|2[0-9]|3[0-1])\./.test(bare)) return true;
  // IPv6: ::1 (loopback), fc00::/7 (unique local), fe80::/10 (link local).
  // Accept with or without brackets. Match the leading hex chunk only; a
  // bogus host like "fcx..." falls through to https.
  const ipv6 = bare.replace(/^\[|\]$/g, '').toLowerCase();
  if (ipv6 === '::1') return true;
  if (/^(fc|fd)[0-9a-f]{2}:/.test(ipv6)) return true;
  if (/^fe[89ab][0-9a-f]:/.test(ipv6)) return true;
  return false;
}

export const sleep = (ms: number, signal?: AbortSignal) =>
  new Promise<void>((resolve, reject) => {
    const t = setTimeout(resolve, ms);
    signal?.addEventListener('abort', () => {
      clearTimeout(t);
      reject(new CDLError('aborted'));
    });
  });

export async function initiate(options: CDLOptions, doFetch: Fetch = fetch): Promise<InitiateResponse> {
  const relyingParty =
    options.relyingParty ?? (typeof location !== 'undefined' ? location.hostname : undefined);
  if (!relyingParty) throw new CDLError('ERR_SCHEMA_INVALID', 'relyingParty is required outside a browser');

  const res = await doFetch(`${httpBase(options.relayUrl)}/cdl/session/initiate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      relying_party: relyingParty,
      assurance_required: options.assuranceRequired ?? 'IAL2',
      webhook_url: options.webhookUrl,
    }),
    signal: options.signal,
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new CDLError(body.error_code ?? 'ERR_INTERNAL', body.message);
  return body as InitiateResponse;
}

export function toResult(status: Record<string, string>, sessionId: string): CDLResult {
  return {
    decision: status.liveness_decision as CDLResult['decision'],
    assuranceLevel: status.assurance_level as CDLResult['assuranceLevel'],
    token: status.attestation_token,
    sessionId,
  };
}

// Fallback for networks that block WebSockets.
export async function pollWithBackoff(
  sessionId: string,
  pollToken: string,
  relayUrl: string,
  onUpdate: (state: string) => void,
  opts: { signal?: AbortSignal; doFetch?: Fetch; sleepFn?: (ms: number) => Promise<void> } = {},
): Promise<CDLResult> {
  const doFetch = opts.doFetch ?? fetch;
  const wait = opts.sleepFn ?? ((ms: number) => sleep(ms, opts.signal));
  let interval = POLL_INITIAL_INTERVAL_MS;

  for (;;) {
    await wait(interval);
    interval = Math.min(interval * POLL_BACKOFF_MULTIPLIER, POLL_MAX_INTERVAL_MS);

    const res = await doFetch(`${httpBase(relayUrl)}/cdl/session/${sessionId}/status`, {
      headers: { Authorization: `Bearer ${pollToken}` },
      signal: opts.signal,
    });
    const status = await res.json();
    if (!res.ok) throw new CDLError(status.error_code ?? 'ERR_INTERNAL', status.message);

    onUpdate(status.state);
    if (status.state === 'complete') return toResult(status, sessionId);
    if (status.state === 'failed') throw new CDLRetryableError('liveness_check_failed');
    if (status.state === 'expired') throw new CDLError('session_expired');
  }
}
