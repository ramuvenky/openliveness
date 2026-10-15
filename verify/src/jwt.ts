// Small helpers shared by the verifier.

export type Assurance = 'IAL2' | 'IAL1' | 'software_only' | 'any';

const RANK: Record<string, number> = { any: 0, software_only: 0, IAL1: 1, IAL2: 2 };

export function meetsAssurance(actual: string, required: Assurance): boolean {
  return (RANK[actual] ?? -1) >= RANK[required];
}

// Users pass either "relay.openliveness.dev" or a full url, so accept both.
export function normalizeRelayUrl(input: string): URL {
  const withScheme = /^https?:\/\//i.test(input)
    ? input
    : `${/^(localhost|127\.|\[::1\])/.test(input) ? 'http' : 'https'}://${input}`;
  return new URL(withScheme);
}

// The relay puts its configured host in `iss`, which has no scheme or port.
export function defaultIssuer(relayUrl: string): string {
  return normalizeRelayUrl(relayUrl).hostname;
}

export function jwksUrl(relayUrl: string): URL {
  const base = normalizeRelayUrl(relayUrl);
  return new URL('/cdl/.well-known/jwks.json', base.origin);
}
