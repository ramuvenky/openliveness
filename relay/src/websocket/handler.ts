import type { FastifyInstance } from 'fastify';
import type { WebSocket } from 'ws';
import { safeEqual } from '../utils/crypto';
import { getRedis, loadSession, sessionTtlSeconds, updateSession } from '../session/store';
import { getCachedToken } from '../session/tokenCache';
import { addBrowser, addPhone, toBrowser } from './hub';
import { now, PhoneInbound, PhoneMessage } from './messages';

const MAX_PHONE_CONNECTIONS = 3;

// L-a: hard ceiling on how long the per-session reconnect counter can live
// in Redis. The session TTL (with grace) is ~1h+ which is way longer than
// any reasonable phone-connect burst. 5 minutes catches legitimate
// reconnects (page refresh, flaky mobile network) and lets stale keys
// expire so a leaked session_id can't be used to burn quota long after.
export const PHONE_COUNTER_MAX_TTL = 300;
export function phoneCounterTtl(expiresAt: string): number {
  return Math.min(sessionTtlSeconds(expiresAt), PHONE_COUNTER_MAX_TTL);
}

function reply(ws: WebSocket, msg: PhoneMessage) {
  ws.send(JSON.stringify(msg));
}

const BROWSER_AUTH_TIMEOUT_MS = 5000;

// H-b: cap concurrent WS connections per client IP. The browser handler
// accepts the socket *before* authentication (the token arrives in the
// first frame), so without a cap one attacker IP can hold thousands of
// pre-auth sockets open and consume file descriptors / event-loop slots.
// 20 is well above any legitimate single-user count (a dev with many tabs
// + a few phones), and far below a floodable number. The map is in-process
// — this cap is per-replica, which is intentional: a horizontal scale-out
// gives the attacker proportionally more capacity, which matches how we
// scale legitimate traffic.
const MAX_WS_PER_IP = 20;
const wsPerIp = new Map<string, number>();
export function _getWsCountForIp(ip: string): number {
  return wsPerIp.get(ip) ?? 0;
}
function incWsIp(ip: string): number {
  const n = (wsPerIp.get(ip) ?? 0) + 1;
  wsPerIp.set(ip, n);
  return n;
}
function decWsIp(ip: string) {
  const n = (wsPerIp.get(ip) ?? 1) - 1;
  if (n <= 0) wsPerIp.delete(ip);
  else wsPerIp.set(ip, n);
}

export async function registerWebsocketRoutes(app: FastifyInstance) {
  // Browser side. The poll_token used to arrive on the URL as ?token= but
  // that landed the token in LB/CDN access logs. We now accept the WS open
  // and wait for the browser's first frame to carry {type:"auth",token}.
  // The connection is forcibly closed after BROWSER_AUTH_TIMEOUT_MS if the
  // browser never sends it, so the pre-auth attack surface is bounded.
  // Query-string ?token= remains supported for one release as a transition
  // so older web-sdk clients don't break; the server logs when it falls
  // back to it so operators can see when it's safe to remove.
  app.get<{ Params: { id: string }; Querystring: { token?: string } }>(
    '/cdl/session/:id/ws',
    { websocket: true },
    async (connection, req) => {
      const ws = connection.socket;
      const { id } = req.params;
      // Per-IP cap first — before any session load or auth work. An attacker
      // flooding sockets must not get free Redis reads or the 5s auth timer.
      const ip = req.ip;
      if (incWsIp(ip) > MAX_WS_PER_IP) {
        decWsIp(ip);
        req.log.warn({ ip_hash_tag: 'ws_per_ip_cap' }, 'browser WS rejected: per-IP cap exceeded');
        ws.close(1013); // 1013 = try again later
        return;
      }
      ws.on('close', () => decWsIp(ip));
      const session = await loadSession(id);

      const authenticated = await (async () => {
        if (!session) return false;
        if (req.query.token) {
          req.log.warn({ session_id: id }, 'browser WS used legacy ?token= — upgrade the web-sdk');
          return safeEqual(session.poll_token, req.query.token);
        }
        return await new Promise<boolean>((resolve) => {
          const timer = setTimeout(() => {
            ws.off('message', onMessage);
            resolve(false);
          }, BROWSER_AUTH_TIMEOUT_MS);
          const onMessage = (raw: unknown) => {
            clearTimeout(timer);
            ws.off('message', onMessage);
            try {
              const parsed = JSON.parse(String(raw));
              if (
                parsed?.type === 'auth' &&
                typeof parsed.token === 'string' &&
                safeEqual(session.poll_token, parsed.token)
              ) {
                return resolve(true);
              }
            } catch {
              // fall through to reject
            }
            resolve(false);
          };
          ws.on('message', onMessage);
        });
      })();

      if (!authenticated) {
        ws.send(
          JSON.stringify({
            type: 'error',
            error_code: 'ERR_INVALID_POLL_TOKEN',
            message: 'invalid session or token',
            timestamp: now(),
          }),
        );
        ws.close();
        return;
      }
      if (!session) return; // unreachable but narrows the type

      addBrowser(id, ws);

      // Late joiners still get the outcome if it already happened. The
      // original handler only re-sent phone_connected and session_complete;
      // if the browser reconnected after a failed or expired session it
      // would sit silent until the timer below fired (which only triggers
      // on 'initiated'). Resend the terminal frame for every post-initiated
      // state so the browser can render the right outcome on reconnect.
      if (session.state === 'phone_connected') {
        ws.send(JSON.stringify({ type: 'phone_connected', timestamp: now() }));
      }
      if (session.state === 'complete') {
        // The token is held only in process memory (see tokenCache). Late
        // joiners after the 15-minute window will see the completion but
        // not the token — by that point they'd have had to retrieve it
        // through the live session_complete push or /status earlier. If
        // the token is gone, the browser gets no misleading complete frame
        // (that would resolve the SDK promise with token=undefined); it
        // sees the empty WS and should fall back to /status, which the
        // M4 fix now hard-errors so the SDK can surface it correctly.
        const token = getCachedToken(session.session_id);
        if (token) {
          ws.send(
            JSON.stringify({
              type: 'session_complete',
              liveness_decision: session.liveness_decision,
              assurance_level: session.assurance_level,
              attestation_token: token,
              timestamp: now(),
            }),
          );
        }
        return;
      }
      if (session.state === 'failed') {
        ws.send(
          JSON.stringify({
            type: 'session_failed',
            reason: 'liveness_check_failed',
            liveness_decision: session.liveness_decision,
            retry_allowed: true,
            timestamp: now(),
          }),
        );
        return;
      }
      if (session.state === 'expired') {
        ws.send(
          JSON.stringify({
            type: 'session_expired',
            reason: 'session_expired',
            timestamp: now(),
          }),
        );
        return;
      }

      // Tell the browser if nobody scans the code in time.
      const wait = Date.parse(session.expires_at) - Date.now();
      const timer = setTimeout(async () => {
        const latest = await loadSession(id);
        if (latest && latest.state === 'initiated') {
          await updateSession(id, { state: 'expired' });
          toBrowser(id, { type: 'session_expired', reason: 'phone_did_not_connect', timestamp: now() });
        }
      }, Math.max(wait, 0));
      ws.on('close', () => clearTimeout(timer));
    },
  );

  // Phone side: capability-token-gated. The token comes from the QR payload
  // (see /cdl/session/initiate) and must be supplied as ?token= — the session
  // id alone is not enough, because session ids leak more readily (logs,
  // Referer headers, screenshots of URLs) than the full QR which only lands
  // on a camera-facing surface.
  app.get<{ Params: { id: string }; Querystring: { token?: string } }>(
    '/cdl/session/:id/device',
    { websocket: true },
    async (connection, req) => {
      const ws = connection.socket;
      const { id } = req.params;
      const ip = req.ip;
      if (incWsIp(ip) > MAX_WS_PER_IP) {
        decWsIp(ip);
        req.log.warn({ ip_hash_tag: 'ws_per_ip_cap' }, 'phone WS rejected: per-IP cap exceeded');
        ws.close(1013);
        return;
      }
      ws.on('close', () => decWsIp(ip));
      const session = await loadSession(id);

      if (!session) {
        reply(ws, { type: 'session_invalid', reason: 'not_found', timestamp: now() });
        ws.close();
        return;
      }
      if (!req.query.token || !safeEqual(session.phone_token, req.query.token)) {
        reply(ws, { type: 'session_invalid', reason: 'invalid_phone_token', timestamp: now() });
        ws.close();
        return;
      }
      if (session.state === 'complete' || session.state === 'failed') {
        reply(ws, { type: 'session_invalid', reason: 'already_complete', timestamp: now() });
        ws.close();
        return;
      }
      if (Date.parse(session.expires_at) < Date.now()) {
        reply(ws, { type: 'session_invalid', reason: 'expired', timestamp: now() });
        ws.close();
        return;
      }

      const redis = getRedis();
      const countKey = `cdl:session:${id}:phone_connections`;
      const count = await redis.incr(countKey);
      await redis.expire(countKey, phoneCounterTtl(session.expires_at));
      if (count > MAX_PHONE_CONNECTIONS) {
        reply(ws, { type: 'session_invalid', reason: 'too_many_reconnects', timestamp: now() });
        ws.close();
        return;
      }

      addPhone(id, ws);
      if (session.state === 'initiated') {
        await updateSession(id, { state: 'phone_connected' });
        toBrowser(id, { type: 'phone_connected', timestamp: now() });
      }

      // Also covers the reconnect case, the phone just gets the same ack again.
      reply(ws, {
        type: 'session_acknowledged',
        challenge_sequence: JSON.parse(session.challenge_sequence),
        challenge_id: session.challenge_id,
        session_id: session.session_id,
        expires_at: session.expires_at,
        timestamp: now(),
      });

      ws.on('message', async (raw) => {
        let msg: PhoneInbound;
        try {
          msg = JSON.parse(raw.toString());
        } catch {
          return;
        }
        if (msg.type === 'liveness_started') {
          // M-a: don't regress terminal state. If /complete already landed
          // (or the session expired) the phone's late liveness_started frame
          // used to overwrite 'complete'/'failed'/'expired' with 'processing',
          // corrupting the state machine and causing /status to lie.
          const latest = await loadSession(id);
          if (!latest || latest.state !== 'phone_connected') {
            return;
          }
          await updateSession(id, { state: 'processing' });
          toBrowser(id, { type: 'liveness_started', timestamp: now() });
        } else if (msg.type === 'processing_update') {
          const progress = Math.min(Math.max(Number(msg.progress) || 0, 0), 1);
          toBrowser(id, {
            type: 'processing_update',
            progress,
            current_layer: String(msg.current_layer ?? '').slice(0, 40),
            timestamp: now(),
          });
        }
      });
    },
  );
}
