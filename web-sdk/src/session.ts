import { CDLError, CDLOptions, CDLResult, CDLRetryableError, InitiateResponse } from './types';

export const POLL_INITIAL_INTERVAL_MS = 1000;
export const POLL_MAX_INTERVAL_MS = 5000;
export const POLL_BACKOFF_MULTIPLIER = 1.5;

type Fetch = typeof fetch;

// "relay.openliveness.dev" becomes https, local addresses become http.
export function httpBase(relayUrl: string): string {
  const withScheme = /^https?:\/\//i.test(relayUrl)
    ? relayUrl
    : `${/^(localhost|127\.|192\.168\.|10\.)/.test(relayUrl) ? 'http' : 'https'}://${relayUrl}`;
  return withScheme.replace(/\/+$/, '');
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
