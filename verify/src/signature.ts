import { createLocalJWKSet, createRemoteJWKSet, jwtVerify, JSONWebKeySet, JWTPayload } from 'jose';

const remoteSets = new Map<string, ReturnType<typeof createRemoteJWKSet>>();

// Remote key sets cache keys internally, so keep one per url.
function keySet(url: URL, local?: JSONWebKeySet) {
  if (local) return createLocalJWKSet(local);
  const key = url.toString();
  let set = remoteSets.get(key);
  if (!set) {
    set = createRemoteJWKSet(url);
    remoteSets.set(key, set);
  }
  return set;
}

export async function verifySignedToken(
  jwt: string,
  opts: { jwksUrl: URL; jwks?: JSONWebKeySet; issuer: string; audience: string; clockTolerance: number },
): Promise<JWTPayload> {
  const { payload } = await jwtVerify(jwt, keySet(opts.jwksUrl, opts.jwks), {
    issuer: opts.issuer,
    audience: opts.audience,
    algorithms: ['ES256'],
    clockTolerance: opts.clockTolerance,
  });
  return payload;
}
