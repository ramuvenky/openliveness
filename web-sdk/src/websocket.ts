import { CDLError, CDLOptions, CDLResult, CDLRetryableError } from './types';

export interface WsFallback extends Error {
  fallback: true;
}

const fallbackError = (msg: string): WsFallback =>
  Object.assign(new Error(msg), { fallback: true as const });

export const isFallback = (e: unknown): e is WsFallback => !!(e as WsFallback)?.fallback;

// Resolves with the result. Rejects with a WsFallback if the socket itself fails,
// so the caller can switch to polling instead of giving up.
export function waitForResult(
  wsUrl: string,
  pollToken: string,
  sessionId: string,
  options: Pick<CDLOptions, 'onPhoneConnected' | 'onProcessing' | 'signal'>,
): Promise<CDLResult> {
  return new Promise((resolve, reject) => {
    if (typeof WebSocket === 'undefined') return reject(fallbackError('no WebSocket'));

    const ws = new WebSocket(`${wsUrl}?token=${encodeURIComponent(pollToken)}`);
    let settled = false;
    const finish = (fn: () => void) => {
      if (settled) return;
      settled = true;
      ws.close();
      fn();
    };

    options.signal?.addEventListener('abort', () =>
      finish(() => reject(new CDLError('aborted'))),
    );

    ws.onerror = () => finish(() => reject(fallbackError('socket error')));
    ws.onclose = () => finish(() => reject(fallbackError('socket closed')));

    ws.onmessage = (ev) => {
      let msg: Record<string, any>;
      try {
        msg = JSON.parse(String(ev.data));
      } catch {
        return;
      }
      switch (msg.type) {
        case 'phone_connected':
          options.onPhoneConnected?.();
          break;
        case 'processing_update':
          options.onProcessing?.(msg.progress, msg.current_layer);
          break;
        case 'session_complete':
          finish(() =>
            resolve({
              decision: msg.liveness_decision,
              assuranceLevel: msg.assurance_level,
              token: msg.attestation_token,
              sessionId,
            }),
          );
          break;
        case 'session_failed':
          finish(() =>
            reject(
              msg.retry_allowed
                ? new CDLRetryableError(msg.reason)
                : new CDLError(msg.reason ?? 'session_failed'),
            ),
          );
          break;
        case 'session_expired':
          finish(() => reject(new CDLError('session_expired', msg.reason)));
          break;
        case 'error':
          finish(() => reject(new CDLError(msg.error_code, msg.message)));
          break;
      }
    };
  });
}
