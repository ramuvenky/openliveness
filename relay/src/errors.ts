const STATUS: Record<string, number> = {
  ERR_SESSION_NOT_FOUND: 404,
  ERR_SESSION_EXPIRED: 410,
  ERR_SESSION_ALREADY_COMPLETE: 409,
  ERR_SESSION_WRONG_STATE: 409,
  ERR_INVALID_POLL_TOKEN: 401,
  // state=complete but the one-time in-memory token handoff has already
  // expired (or the relay restarted). Distinct from ERR_SESSION_EXPIRED
  // (session lifetime) and ERR_INTERNAL (actual bug) so an SDK can tell
  // "stop polling, token is gone" from "something is broken".
  ERR_ATTESTATION_TOKEN_UNAVAILABLE: 410,
  ERR_RATE_LIMITED: 429,
  ERR_RELAY_UNAVAILABLE: 503,
  // Server-side policy refusal: the submitter is authenticated and the
  // request is well-formed, but the relying_party is not on the stub-mode
  // allowlist. 403 (not 400) so an operator reading the HTTP log can tell
  // "RP forbidden from this operation" from "client sent garbage."
  ERR_STUB_MODE_FORBIDDEN: 403,
  ERR_INTERNAL: 500,
};

export class CDLError extends Error {
  readonly code: string;

  constructor(code: string, message?: string) {
    super(message ?? code);
    this.code = code;
  }

  // Everything not listed above is a client mistake, so 400.
  get status(): number {
    return STATUS[this.code] ?? 400;
  }
}
