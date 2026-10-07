import type { FastifyInstance } from 'fastify';
import type { WebSocket } from 'ws';
import { safeEqual } from '../utils/crypto';
import { getRedis, loadSession, updateSession } from '../session/store';
import { addBrowser, addPhone, toBrowser } from './hub';
import { now, PhoneInbound, PhoneMessage } from './messages';

const MAX_PHONE_CONNECTIONS = 3;

function reply(ws: WebSocket, msg: PhoneMessage) {
  ws.send(JSON.stringify(msg));
}

export async function registerWebsocketRoutes(app: FastifyInstance) {
  // Browser side: needs the poll token, passed as ?token= since browsers cannot set WS headers.
  app.get<{ Params: { id: string }; Querystring: { token?: string } }>(
    '/cdl/session/:id/ws',
    { websocket: true },
    async (connection, req) => {
      const ws = connection.socket;
      const { id } = req.params;
      const session = await loadSession(id);

      if (!session || !req.query.token || !safeEqual(session.poll_token, req.query.token)) {
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

      addBrowser(id, ws);

      // Late joiners still get the outcome if it already happened.
      if (session.state === 'phone_connected') {
        ws.send(JSON.stringify({ type: 'phone_connected', timestamp: now() }));
      }
      if (session.state === 'complete' && session.jwt_issued) {
        ws.send(
          JSON.stringify({
            type: 'session_complete',
            liveness_decision: session.liveness_decision,
            assurance_level: session.assurance_level,
            attestation_token: session.jwt_issued,
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

  // Phone side: no token, the session id from the QR code is the capability.
  app.get<{ Params: { id: string } }>(
    '/cdl/session/:id/device',
    { websocket: true },
    async (connection, req) => {
      const ws = connection.socket;
      const { id } = req.params;
      const session = await loadSession(id);

      if (!session) {
        reply(ws, { type: 'session_invalid', reason: 'not_found', timestamp: now() });
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
      await redis.expire(countKey, 24 * 3600);
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
