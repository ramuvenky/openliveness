import Fastify, { FastifyInstance } from 'fastify';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { OPAQUE_ERROR_CODES, sanitizeClientMessage } from '../src/middleware/security';
import { CDLError } from '../src/errors';

// H4 — hostile RPs probing /complete can enumerate "which sub-check tripped"
// (nonce vs. verdict vs. signature vs. counter) if the server echoes the
// internal reason string. The error_code remains, so legitimate SDKs can
// still branch; only the human message is collapsed.
//
// We stand up a minimal Fastify instance and install just the error-handler
// branch, mirroring what registerSecurity does. This keeps the test free of
// Redis/helmet/rate-limit while pinning the exact wire contract.

function buildMinimalApp(): FastifyInstance {
  const app = Fastify();
  app.setErrorHandler((err, req, reply) => {
    if (err instanceof CDLError) {
      if (OPAQUE_ERROR_CODES.has(err.code)) {
        return reply
          .status(err.status)
          .send({ error_code: err.code, message: 'attestation rejected' });
      }
      return reply.status(err.status).send({ error_code: err.code, message: err.message });
    }
    const status = (err as { statusCode?: number }).statusCode ?? 500;
    if (status < 500) {
      return reply
        .status(status)
        .send({ error_code: 'ERR_SCHEMA_INVALID', message: sanitizeClientMessage(err.message) });
    }
    return reply.status(500).send({ error_code: 'ERR_INTERNAL', message: 'internal error' });
  });
  app.post('/boom', async (req) => {
    const { code, detail } = req.body as { code: string; detail: string };
    throw new CDLError(code, detail);
  });
  return app;
}

let app: FastifyInstance;

beforeEach(async () => {
  app = buildMinimalApp();
  await app.ready();
});

afterEach(async () => {
  await app.close();
});

describe('H4 — OPAQUE_ERROR_CODES set', () => {
  it('contains every attestation sub-check code that would otherwise leak the failure reason', () => {
    expect(OPAQUE_ERROR_CODES.has('ERR_APPLE_ATTEST_INVALID')).toBe(true);
    expect(OPAQUE_ERROR_CODES.has('ERR_APPLE_ATTEST_COUNTER')).toBe(true);
    expect(OPAQUE_ERROR_CODES.has('ERR_GOOGLE_INTEGRITY_INVALID')).toBe(true);
    expect(OPAQUE_ERROR_CODES.has('ERR_GOOGLE_INTEGRITY_NONCE')).toBe(true);
    expect(OPAQUE_ERROR_CODES.has('ERR_GOOGLE_INTEGRITY_LOW')).toBe(true);
    expect(OPAQUE_ERROR_CODES.has('ERR_INVALID_SIGNATURE')).toBe(true);
  });

  it('does NOT collapse benign codes whose detail reveals nothing sensitive (so SDK UX stays helpful)', () => {
    // SCHEMA_INVALID carries field names the SDK displays; CHALLENGE_REUSED
    // tells a legitimate device "retry with a fresh challenge". Collapsing
    // these would make debugging a client bug impossible.
    expect(OPAQUE_ERROR_CODES.has('ERR_SCHEMA_INVALID')).toBe(false);
    expect(OPAQUE_ERROR_CODES.has('ERR_CHALLENGE_REUSED')).toBe(false);
    expect(OPAQUE_ERROR_CODES.has('ERR_SESSION_NOT_FOUND')).toBe(false);
    expect(OPAQUE_ERROR_CODES.has('ERR_RATE_LIMITED')).toBe(false);
  });
});

describe('H4 — error handler returns generic message for opaque codes', () => {
  it('replaces the Apple sub-check reason with "attestation rejected"', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/boom',
      payload: { code: 'ERR_APPLE_ATTEST_INVALID', detail: 'certificate validity window' },
    });
    const body = res.json();
    expect(body.error_code).toBe('ERR_APPLE_ATTEST_INVALID');
    expect(body.message).toBe('attestation rejected');
    // The internal detail must not leak.
    expect(body.message).not.toMatch(/certificate|validity|window/);
  });

  it('replaces the Google nonce reason', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/boom',
      payload: { code: 'ERR_GOOGLE_INTEGRITY_NONCE', detail: 'nonce mismatch after GETDEL' },
    });
    const body = res.json();
    expect(body.error_code).toBe('ERR_GOOGLE_INTEGRITY_NONCE');
    expect(body.message).toBe('attestation rejected');
    expect(body.message).not.toMatch(/nonce|GETDEL/);
  });

  it('replaces the signature-verify reason', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/boom',
      payload: { code: 'ERR_INVALID_SIGNATURE', detail: 'ECDSA verify returned false' },
    });
    const body = res.json();
    expect(body.error_code).toBe('ERR_INVALID_SIGNATURE');
    expect(body.message).toBe('attestation rejected');
  });

  it('preserves the detail for non-opaque codes', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/boom',
      payload: { code: 'ERR_SCHEMA_INVALID', detail: 'challenge_response missing' },
    });
    const body = res.json();
    expect(body.error_code).toBe('ERR_SCHEMA_INVALID');
    expect(body.message).toBe('challenge_response missing');
  });

  it('preserves the configured HTTP status for the opaque code', async () => {
    // The opacity treatment must not disturb status mapping; a 400 stays 400.
    const res = await app.inject({
      method: 'POST',
      url: '/boom',
      payload: { code: 'ERR_APPLE_ATTEST_INVALID', detail: 'anything' },
    });
    expect(res.statusCode).toBe(400);
  });
});
