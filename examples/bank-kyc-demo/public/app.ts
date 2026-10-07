import { verify } from '@openliveness/web';

declare const RELAY_URL: string;

const startBtn = document.getElementById('start') as HTMLButtonElement;
const qrBox = document.getElementById('qr') as HTMLDivElement;
const statusEl = document.getElementById('status') as HTMLParagraphElement;
const resultEl = document.getElementById('result') as HTMLPreElement;

startBtn.addEventListener('click', async () => {
  startBtn.disabled = true;
  qrBox.innerHTML = '';
  resultEl.textContent = '';
  statusEl.textContent = 'Starting...';

  try {
    const liveness = await verify({
      relayUrl: RELAY_URL,
      assuranceRequired: 'any',
      onQRReady: (dataUrl) => {
        const img = new Image();
        img.src = dataUrl;
        img.alt = 'QR code to scan with your phone';
        qrBox.replaceChildren(img);
        statusEl.textContent = 'Scan the code with your phone.';
      },
      onPhoneConnected: () => (statusEl.textContent = 'Phone connected. Follow the steps on your phone.'),
      onProcessing: (p) => (statusEl.textContent = `Checking, ${Math.round(p * 100)}%`),
    });

    qrBox.innerHTML = '';
    statusEl.textContent = 'Check finished, confirming with our server...';

    // Last hop: the bank's own backend decides, not the browser.
    const res = await fetch('/verify-liveness', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token: liveness.token }),
    });
    const verdict = await res.json();
    statusEl.textContent = verdict.valid ? 'Verified, thank you.' : 'We could not verify you.';
    resultEl.textContent = JSON.stringify(verdict, null, 2);
  } catch (e) {
    statusEl.textContent = `Could not complete the check (${(e as Error).message}). Please try again.`;
  } finally {
    startBtn.disabled = false;
  }
});
