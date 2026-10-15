const STATUS: Record<string, number> = {
  ERR_SESSION_NOT_FOUND: 404,
  ERR_SESSION_EXPIRED: 410,
  ERR_SESSION_ALREADY_COMPLETE: 409,
  ERR_SESSION_WRONG_STATE: 409,
  ERR_INVALID_POLL_TOKEN: 401,
  ERR_RATE_LIMITED: 429,
  ERR_RELAY_UNAVAILABLE: 503,
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
