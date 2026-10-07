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
    issuer: host,
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
    httpBase: `${secure ? 'https' : 'http'}://${hostWithPort}`,
    wsBase: `${secure ? 'wss' : 'ws'}://${hostWithPort}`,
  };
}
