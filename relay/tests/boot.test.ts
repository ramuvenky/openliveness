import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  WEAK_INTEGRITY_SECRETS,
  buildServer,
  isWeakIntegritySecret,
} from '../src/server';

// M5 — HARDWARE_ATTESTATION=stub turns verifyHardware into an unconditional
// pass. In dev that's fine, but a deploy that ships NODE_ENV=production with
// the stub left on would rubber-stamp every attestation while looking healthy
// (only a boot warn would surface the mistake). The server must refuse to
// start in that combination so an operator either flips NODE_ENV (a louder
// choice) or removes the stub.

const SAVED = { ...process.env };

beforeEach(() => {
  process.env = { ...SAVED };
  // Keep JWT path env out of this test so buildServer's registerSecurity
  // branches don't trip on missing keys. We never ready() the app, only
  // call buildServer, and buildServer throws before registerSecurity when
  // the guard triggers.
});

afterEach(() => {
  process.env = { ...SAVED };
});

describe('M5 — buildServer production + stub refusal', () => {
  it('refuses to start when NODE_ENV=production and HARDWARE_ATTESTATION=stub', async () => {
    process.env.NODE_ENV = 'production';
    process.env.HARDWARE_ATTESTATION = 'stub';
    await expect(buildServer()).rejects.toThrow(/HARDWARE_ATTESTATION=stub.*NODE_ENV=production/);
  });

  it('allows HARDWARE_ATTESTATION=stub outside production (dev / CI)', async () => {
    process.env.NODE_ENV = 'development';
    process.env.HARDWARE_ATTESTATION = 'stub';
    const app = await buildServer();
    try {
      expect(app).toBeDefined();
    } finally {
      await app.close();
    }
  });

  it('allows HARDWARE_ATTESTATION=enforce in production', async () => {
    process.env.NODE_ENV = 'production';
    process.env.HARDWARE_ATTESTATION = 'enforce';
    process.env.INTEGRITY_NONCE_SECRET = 'strong-rotated-' + 'a'.repeat(32);
    // registerSecurity + the other plugins don't need live Redis to .ready(),
    // but we only care that the guard does not fire — so just build and close.
    const app = await buildServer();
    try {
      expect(app).toBeDefined();
    } finally {
      await app.close();
    }
  });
});

// N-e — in production the relay refuses to start with an empty or well-known
// INTEGRITY_NONCE_SECRET. The default compose file ships a documented
// "dev-secret-change-in-production" placeholder; a deploy that copies the
// compose into production without rotating the secret would otherwise inherit
// a nonce HMAC key anyone with the repo can reproduce.
describe('N-e — INTEGRITY_NONCE_SECRET weak-value refusal in production', () => {
  it('classifies the shipped compose placeholder as weak', () => {
    expect(isWeakIntegritySecret('dev-secret-change-in-production')).toBe(true);
    expect(isWeakIntegritySecret('DEV-SECRET-CHANGE-IN-PRODUCTION')).toBe(true);
    expect(isWeakIntegritySecret('  dev-secret-change-in-production  ')).toBe(true);
  });

  it('classifies common placeholders (change-me, password, empty) as weak', () => {
    for (const s of ['', 'change-me', 'changeme', 'password', 'secret', 'dev-secret']) {
      expect(isWeakIntegritySecret(s)).toBe(true);
    }
  });

  it('accepts a strong, high-entropy secret', () => {
    // 64 hex chars (openssl rand -hex 32)
    const strong = 'a3f8e7c21b4d5a90f2e8c17b4a9d8e2f1c6b5a908f7e6d5c4b3a291807e6d5c4';
    expect(isWeakIntegritySecret(strong)).toBe(false);
    expect(WEAK_INTEGRITY_SECRETS.has(strong)).toBe(false);
  });

  it('refuses to boot when NODE_ENV=production and the secret is empty', async () => {
    process.env.NODE_ENV = 'production';
    process.env.HARDWARE_ATTESTATION = 'enforce';
    process.env.INTEGRITY_NONCE_SECRET = '';
    await expect(buildServer()).rejects.toThrow(/INTEGRITY_NONCE_SECRET.*placeholder/);
  });

  it('refuses to boot when NODE_ENV=production and the secret is the compose placeholder', async () => {
    process.env.NODE_ENV = 'production';
    process.env.HARDWARE_ATTESTATION = 'enforce';
    process.env.INTEGRITY_NONCE_SECRET = 'dev-secret-change-in-production';
    await expect(buildServer()).rejects.toThrow(/INTEGRITY_NONCE_SECRET.*placeholder/);
  });

  it('tolerates a weak secret in development (operator-ergonomic default)', async () => {
    // Dev workflows (local run, CI with the stub) must keep working with
    // the compose placeholder. The refusal is scoped to production.
    process.env.NODE_ENV = 'development';
    process.env.HARDWARE_ATTESTATION = 'stub';
    process.env.INTEGRITY_NONCE_SECRET = 'dev-secret-change-in-production';
    const app = await buildServer();
    try {
      expect(app).toBeDefined();
    } finally {
      await app.close();
    }
  });

  it('boots in production when the secret is strong', async () => {
    process.env.NODE_ENV = 'production';
    process.env.HARDWARE_ATTESTATION = 'enforce';
    process.env.INTEGRITY_NONCE_SECRET = 'a3f8e7c21b4d5a90f2e8c17b4a9d8e2f1c6b5a908f7e6d5c4b3a291807e6d5c4';
    const app = await buildServer();
    try {
      expect(app).toBeDefined();
    } finally {
      await app.close();
    }
  });
});
