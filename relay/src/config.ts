// Reads env at call time so tests can change values between cases.
export function config() {
  const env = process.env;
  const port = Number(env.PORT ?? 3000);
  const host = env.RELAY_BASE_URL ?? 'localhost';
  const secure = env.NODE_ENV === 'production';

  // In dev we talk plain http/ws on the local port, in production we expect TLS in front.
  const hostWithPort = secure || host.includes(':') ? host : `${host}:${port}`;

  return {
    port,
    nodeEnv: env.NODE_ENV ?? 'development',
    // JWT iss should be a URL (RFC 7519 §4.1.1 recommends StringOrURI; the
    // broader JOSE ecosystem — Keycloak, Auth0, aud/iss validators that reuse
    // OIDC code paths — expects URL form so JWKS discovery etc. work). A
    // bare hostname breaks any downstream library that URL-parses it. Build
    // iss from the same scheme+host+port we expose in httpBase so the token
    // and the JWKS endpoint are self-consistent.
    issuer: `${secure ? 'https' : 'http'}://${hostWithPort}`,
    redisUrl: env.REDIS_URL ?? 'redis://localhost:6379',
    redisPassword: env.REDIS_PASSWORD || undefined,
    jwtPrivateKeyPath: env.JWT_PRIVATE_KEY_PATH ?? './keys/relay-private.pem',
    jwtPublicKeyPath: env.JWT_PUBLIC_KEY_PATH ?? './keys/relay-public.pem',
    appleTeamId: env.APPLE_TEAM_ID ?? '',
    appleBundleId: env.APPLE_APP_BUNDLE_ID ?? '',
    appleRootCaPath: env.APPLE_APP_ATTEST_ROOT_CA_PATH ?? '',
    googlePackageName: env.GOOGLE_PLAY_PACKAGE_NAME ?? '',
    integritySecret: env.INTEGRITY_NONCE_SECRET ?? '',
    sessionExpiryMinutes: Number(env.SESSION_EXPIRY_MINUTES ?? 5),
    attestationMaxAgeSeconds: Number(env.ATTESTATION_MAX_AGE_SECONDS ?? 120),
    hardwareMode: (env.HARDWARE_ATTESTATION ?? (secure ? 'enforce' : 'stub')) as 'stub' | 'enforce',
    // Allowlist of relying_party values permitted to submit attestations with
    // `stub_mode: true`. Mobile SDKs stamp that marker when their liveness
    // path fell back to the Phase-1 hardcoded scores (no real landmark
    // stream); accepting it from an arbitrary RP would let a hostile fork
    // ship an "always passes" build. Keep empty in production — populate only
    // for CI / integration RPs. CSV form so a single env var carries any
    // number of entries; empty strings are filtered out so a trailing comma
    // or an unset var cleanly means "nobody is allowed".
    stubModeRpAllowlist: (env.STUB_MODE_RP_ALLOWLIST ?? '')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean),
    // Shared secret the relay uses to sign webhook bodies (HMAC-SHA256 →
    // X-OpenLiveness-Signature). Required for webhook delivery; if unset the
    // relay logs and skips the webhook rather than sending an unauthenticated
    // body that a third party could forge.
    webhookSigningSecret: env.WEBHOOK_SIGNING_SECRET || '',
    // Keys the per-request IP log pseudonym. Keep it long-lived (rotate
    // only when you want to break historical correlation) and treat it as a
    // secret, since anyone with it can map log hashes back to IPs. In dev
    // we fall back to a fixed placeholder so logs are still stable.
    ipHashSecret: env.IP_HASH_SECRET || (secure ? '' : 'dev-ip-hash-secret'),
    // trustProxy must be an explicit deployment choice: enabling it when no
    // trusted reverse proxy is in front lets any client set X-Forwarded-For
    // and get fresh per-"IP" rate-limit buckets. Default is off; set
    // TRUST_PROXY=true behind a trusted LB / ingress / Fastly / etc.
    trustProxy: env.TRUST_PROXY === 'true',
    // Loopback webhooks (http://localhost, http://127.0.0.1, http://::1) are
    // a dev-only convenience: an RP running the relay locally wants to post
    // to a local receiver without TLS. Historically this was inferred from
    // NODE_ENV !== 'production', but NODE_ENV being unset or misspelled
    // ("prod", "staging") then quietly opened an SSRF path to loopback in a
    // real deploy. Require an explicit opt-in: ALLOW_LOOPBACK_WEBHOOKS=true.
    allowLoopbackWebhooks: env.ALLOW_LOOPBACK_WEBHOOKS === 'true',
    httpBase: `${secure ? 'https' : 'http'}://${hostWithPort}`,
    wsBase: `${secure ? 'wss' : 'ws'}://${hostWithPort}`,
  };
}
