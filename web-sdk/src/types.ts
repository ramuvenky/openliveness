export interface CDLOptions {
  /** Relay host or url, e.g. "relay.openliveness.dev" or "http://localhost:3000". */
  relayUrl: string;
  /** Who the token is issued for. Defaults to the current page hostname. */
  relyingParty?: string;
  assuranceRequired?: 'IAL2' | 'IAL1' | 'any';
  /** Optional url the relay calls when the session finishes. */
  webhookUrl?: string;
  onQRReady?: (qrDataUrl: string) => void;
  onPhoneConnected?: () => void;
  onProcessing?: (progress: number, layer: string) => void;
  /** Overall limit for the whole flow. Defaults to the session lifetime plus a minute. */
  timeoutMs?: number;
  /** Stop waiting and reject. */
  signal?: AbortSignal;
}

export interface CDLResult {
  decision: 'pass' | 'fail' | 'inconclusive';
  assuranceLevel: 'IAL2' | 'IAL1' | 'software_only';
  token: string;
  sessionId: string;
}

export class CDLError extends Error {
  readonly code: string;
  constructor(code: string, message?: string) {
    super(message ?? code);
    this.name = 'CDLError';
    this.code = code;
  }
}

/** The session failed but the user can start over by calling verify() again. */
export class CDLRetryableError extends CDLError {
  constructor(reason: string) {
    super(reason);
    this.name = 'CDLRetryableError';
  }
}

export interface InitiateResponse {
  session_id: string;
  challenge_id: string;
  challenge_sequence: string[];
  qr_payload: string;
  poll_token: string;
  expires_at: string;
  ws_url: string;
}
