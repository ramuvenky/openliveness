import type { FastifyInstance } from 'fastify';
import helmet from '@fastify/helmet';
import { config } from '../config';
import { hmacHex } from '../utils/crypto';
import { CDLError } from '../errors';

export async function registerSecurity(app: FastifyInstance) {
  await app.register(helmet);

  // One structured line per request. The ip is pseudonymized with HMAC-SHA256
  // under IP_HASH_SECRET — a plain SHA-256 is reversible with a 2^32 rainbow
  // table for IPv4, so the previous truncated digest offered close to no
  // protection. We also emit the full 64-hex digest; truncation to 16 hex
  // (64 bits) was a false-economy tradeoff that cost collision resistance
  // without materially shortening the log line.
  if (config().nodeEnv === 'production' && !config().ipHashSecret) {
    app.log.warn('IP_HASH_SECRET not set in production — IP log pseudonyms are not keyed');
  }
  app.addHook('onResponse', async (req, reply) => {
    const params = req.params as { id?: string } | undefined;
    req.log.info(
      {
        session_id: params?.id,
        ip: hmacHex(config().ipHashSecret, req.ip),
        method: req.method,
        url: req.routeOptions?.url,
        outcome: reply.statusCode,
      },
      'request',
    );
  });

  app.setErrorHandler((err, req, reply) => {
    if (err instanceof CDLError) {
      // For codes whose detailed message would describe which attestation
      // sub-check failed (certificate validity window / rpIdHash / key-id
      // mismatch / nonce / signature …), send a generic client message and
      // keep the raw text server-side only. Each distinct message would
      // otherwise serve as a free oracle for a hostile RP probing the
      // verification pipeline.
      if (OPAQUE_ERROR_CODES.has(err.code)) {
        req.log.warn(
          { error_code: err.code, raw_message: err.message },
          'opaque error returned to client',
        );
        return reply
          .status(err.status)
          .send({ error_code: err.code, message: 'attestation rejected' });
      }
      return reply.status(err.status).send({ error_code: err.code, message: err.message });
    }
    const status = (err as { statusCode?: number }).statusCode;
    if (status === 429) {
      return reply.status(429).send({ error_code: 'ERR_RATE_LIMITED', message: 'too many requests' });
    }
    if (status && status < 500) {
      // Fastify's default validation errors have helpful messages like
      // "body/relying_party Required" — we want to forward those so the SDK
      // can show "fix field X". But err.message is attacker-reachable: a
      // malformed body can produce a message quoting the raw input, and
      // framework-level errors can embed internal file paths or secrets
      // from env in stack-trace-adjacent text. Sanitize before egress:
      //   - take only the first line (no multi-line stacks),
      //   - cap length (budget leak of exfiltrated data per request),
      //   - drop anything that looks like a filesystem path (`/foo/bar.ts`,
      //     `C:\...`) which would reveal the deployment layout.
      req.log.info({ raw_message: err.message }, 'suppressed 4xx error message detail');
      return reply.status(status).send({
        error_code: 'ERR_SCHEMA_INVALID',
        message: sanitizeClientMessage(err.message),
      });
    }
    // redis being down is the usual cause of a 500 here
    if (/ECONNREFUSED|Connection is closed|ETIMEDOUT/.test(err.message)) {
      return reply.status(503).send({ error_code: 'ERR_RELAY_UNAVAILABLE', message: 'storage unavailable' });
    }
    req.log.error(err);
    return reply.status(500).send({ error_code: 'ERR_INTERNAL', message: 'internal error' });
  });
}

// Error codes whose detailed `message` string would describe internal
// attestation sub-checks (which cert extension failed, which hash mismatched,
// which counter was out of range, …). We keep the specific error_code so the
// SDK can branch, but collapse the human-readable message to a generic string
// to avoid handing a hostile RP a per-check oracle. Exported for tests.
export const OPAQUE_ERROR_CODES = new Set([
  'ERR_APPLE_ATTEST_INVALID',
  'ERR_APPLE_ATTEST_COUNTER',
  'ERR_GOOGLE_INTEGRITY_INVALID',
  'ERR_GOOGLE_INTEGRITY_NONCE',
  'ERR_GOOGLE_INTEGRITY_LOW',
  'ERR_INVALID_SIGNATURE',
]);

// Exported for tests. The regex blacklist is intentionally conservative — if
// a legitimate validation message happens to match the path pattern, the
// client sees a generic 'invalid request' and must infer the issue; that
// trade is acceptable because the raw text is still logged server-side.
export function sanitizeClientMessage(raw: string): string {
  const firstLine = String(raw ?? '').split(/\r?\n/)[0] ?? '';
  const trimmed = firstLine.trim().slice(0, 200);
  // Posix paths with at least one segment, Windows drive letters, and
  // bare internal module specifiers like `node:crypto`. Catching all of
  // these keeps the message useful without exposing the deployment tree.
  if (/(?:^|\s)(?:\/[\w.\-]+)+/.test(trimmed)) return 'invalid request';
  if (/[A-Za-z]:\\/.test(trimmed)) return 'invalid request';
  if (/\bnode:[a-z_]+/.test(trimmed)) return 'invalid request';
  return trimmed || 'invalid request';
}
