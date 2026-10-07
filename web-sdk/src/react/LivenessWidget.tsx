import { useEffect, useState } from 'react';
import { verify } from '../index';
import type { CDLOptions, CDLResult } from '../types';

export interface LivenessWidgetProps extends Omit<CDLOptions, 'onQRReady' | 'signal'> {
  onComplete: (result: CDLResult) => void;
  onError?: (error: Error) => void;
}

export function LivenessWidget({ onComplete, onError, ...options }: LivenessWidgetProps) {
  const [qr, setQr] = useState<string | null>(null);
  const [status, setStatus] = useState('Preparing...');

  useEffect(() => {
    const controller = new AbortController();

    verify({
      ...options,
      signal: controller.signal,
      onQRReady: (url) => {
        setQr(url);
        setStatus('Scan the code with your phone');
      },
      onPhoneConnected: () => setStatus('Phone connected, follow the steps on your phone'),
      onProcessing: (progress) => setStatus(`Checking... ${Math.round(progress * 100)}%`),
    })
      .then(onComplete)
      .catch((e: Error) => {
        if (controller.signal.aborted) return;
        setStatus('Something went wrong, please try again');
        onError?.(e);
      });

    return () => controller.abort();
    // the session is started once per mount
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div>
      {qr && <img src={qr} alt="Scan with your phone to start liveness check" width={320} height={320} />}
      <p role="status">{status}</p>
    </div>
  );
}
