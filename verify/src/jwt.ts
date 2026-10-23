// Small helpers shared by the verifier.

export type Assurance = 'IAL2' | 'IAL1' | 'software_only' | 'any';

const RANK: Record<string, number> = { any: 0, software_only: 0, IAL1: 1, IAL2: 2 };

export function meetsAssurance(actual: string, required: Assurance): boolean {
  return (RANK[actual] ?? -1) >= RANK[required];
}

function isLoopbackHostLike(host: string): boolean {
  // Matches localhost (any casing), 127.0.0.0/8, and bracketed/unbracketed
  // IPv6 loopback. Used for two things: (1) deciding whether to default to
  // http:// when the caller passed a bare host, and (2) deciding whether an
  // explicit http:// URL should be accepted.
  const h = host.replace(/^\[|\]$/g, '').toLowerCase();
  if (h === 'localhost') return true;
  if (/^127\./.test(h)) return true;
  if (h === '::1') return true;
  return false;
}

// Users pass either "relay.openliveness.dev" or a full url, so accept both.
// For public hosts http:// is rejected: fetching JWKS over cleartext lets a
// network attacker swap the signing keys. Loopback hosts are allowed for
// local testing against a dev relay.
export function normalizeRelayUrl(input: string): URL {
  const hasScheme = /^https?:\/\//i.test(input);
  const withScheme = hasScheme
    ? input
    : `${isLoopbackHostLike(input.split(/[:/]/)[0] ?? input) ? 'http' : 'https'}://${input}`;
  const url = new URL(withScheme);
  if (url.protocol === 'http:' && !isLoopbackHostLike(url.hostname)) {
    throw new Error(
      `verify: refusing http:// for non-loopback relay "${url.host}" — JWKS must be fetched over https`,
    );
  }
  return url;
}

// The relay sets `iss` to the full origin (scheme://host[:port]) so OIDC /
// JOSE validators that URL-parse iss keep working. Match that exactly here,
// otherwise jwtVerify rejects every relay-issued token for issuer mismatch.
export function defaultIssuer(relayUrl: string): string {
  return normalizeRelayUrl(relayUrl).origin;
}

export function jwksUrl(relayUrl: string): URL {
  const base = normalizeRelayUrl(relayUrl);
  return new URL('/cdl/.well-known/jwks.json', base.origin);
}
