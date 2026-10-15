import type { FastifyBaseLogger } from 'fastify';
import { CDLAttestation, CDLSession } from './types';

// Specs give urls without a scheme (example-bank.com/kyc/callback), so add https.
export function normalizeWebhookUrl(raw: string): string | null {
  try {
    const url = new URL(/^https?:\/\//i.test(raw) ? raw : `https://${raw}`);
    return url.toString();
  } catch {
    return null;
  }
}

export async function callWebhook(
  session: CDLSession,
  token: string,
  att: CDLAttestation,
  hardwareAttested: boolean,
  log: FastifyBaseLogger,
) {
  if (!session.webhook_url) return;
  const url = normalizeWebhookUrl(session.webhook_url);
  if (!url) return;

  try {
    await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      signal: AbortSignal.timeout(5000),
      body: JSON.stringify({
        event: 'cdl.session.complete',
        session_id: session.session_id,
        relying_party: session.relying_party,
        liveness_decision: att.liveness_decision,
        assurance_level: att.assurance_level,
        attestation_token: token,
        hardware_attested: hardwareAttested,
        device_platform: att.device_platform,
        timestamp: new Date().toISOString(),
      }),
    });
  } catch {
    // a dead webhook must never fail the session
    log.warn({ session_id: session.session_id }, 'webhook delivery failed');
  }
}
