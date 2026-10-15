export type BrowserMessage =
  | { type: 'phone_connected'; timestamp: string }
  | { type: 'liveness_started'; timestamp: string }
  | { type: 'processing_update'; progress: number; current_layer: string; timestamp: string }
  | {
      type: 'session_complete';
      liveness_decision: string;
      assurance_level: string;
      attestation_token: string;
      timestamp: string;
    }
  | {
      type: 'session_failed';
      reason: string;
      liveness_decision: string | null;
      retry_allowed: boolean;
      timestamp: string;
    }
  | { type: 'session_expired'; reason: string; timestamp: string }
  | { type: 'error'; error_code: string; message: string; timestamp: string };

export type PhoneMessage =
  | {
      type: 'session_acknowledged';
      challenge_sequence: string[];
      challenge_id: string;
      session_id: string;
      expires_at: string;
      timestamp: string;
    }
  | { type: 'session_invalid'; reason: string; timestamp: string }
  | { type: 'attestation_received'; timestamp: string }
  | { type: 'attestation_rejected'; error_code: string; timestamp: string };

// Messages the phone is allowed to send, everything else is dropped.
export interface PhoneInbound {
  type: 'liveness_started' | 'processing_update';
  progress?: number;
  current_layer?: string;
}

export const now = () => new Date().toISOString();
