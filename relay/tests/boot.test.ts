import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { buildServer } from '../src/server';

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
