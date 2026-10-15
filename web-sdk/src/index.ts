import { qrDataUrl } from './qr';
import { initiate, pollWithBackoff } from './session';
import { CDLError, CDLOptions, CDLResult } from './types';
import { isFallback, waitForResult } from './websocket';

export async function verify(options: CDLOptions): Promise<CDLResult> {
  const session = await initiate(options);
  options.onQRReady?.(await qrDataUrl(session.qr_payload));

  const limit =
    options.timeoutMs ?? Math.max(Date.parse(session.expires_at) - Date.now(), 0) + 60_000;

  // One controller lets the timeout and the caller's own signal stop both transports.
  const controller = new AbortController();
  options.signal?.addEventListener('abort', () => controller.abort());
  const timer = setTimeout(() => controller.abort(), limit);
  const signal = controller.signal;

  try {
    try {
      return await waitForResult(session.ws_url, session.poll_token, session.session_id, {
        onPhoneConnected: options.onPhoneConnected,
        onProcessing: options.onProcessing,
        signal,
      });
    } catch (e) {
      if (!isFallback(e) || signal.aborted) throw e;
      return await pollWithBackoff(
        session.session_id,
        session.poll_token,
        options.relayUrl,
        (state) => {
          if (state === 'phone_connected') options.onPhoneConnected?.();
        },
        { signal },
      );
    }
  } catch (e) {
    if (e instanceof CDLError && e.code === 'aborted' && !options.signal?.aborted) {
      throw new CDLError('timeout', 'liveness check timed out');
    }
    throw e;
  } finally {
    clearTimeout(timer);
  }
}

export { CDLError, CDLRetryableError } from './types';
export type { CDLOptions, CDLResult } from './types';
