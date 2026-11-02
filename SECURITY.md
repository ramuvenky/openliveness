# Security

openliveness governs whether a real human is present during identity
verification, so security issues get priority over everything else.
If you've found one, please report it privately before posting
anywhere public — a public issue gives an attacker time to weaponize
before downstream integrators can patch.

## Private reporting

Use GitHub's private vulnerability reporting. Go to the Security tab
on this repo and click **Report a vulnerability** — the thread stays
visible only to maintainers.

Useful things to include if you have them:

- The component — `android-sdk`, `ios-sdk`, `relay`, `verify`,
  `web-sdk`, `core`, `protocol`, or one of the samples.
- The commit hash or version you tested.
- Repro steps or a proof of concept.
- Any environmental conditions (rooted device, specific OS version,
  jailbroken emulator, etc.).

You don't need to map the issue to a specific threat-model property
— I'll do that during triage.

## Response times

openliveness is run by a lead maintainer plus outside contributors,
without paid security engineers on call. I take reports seriously
but can't honestly promise same-day response the way a funded
product org can. Realistic targets:

- Acknowledgement: within about a week.
- Triage and severity call: within two weeks.
- Fix for Critical or High: within 30 days of confirmation, or I'll
  write back with why it needs longer. Something like a hardware
  attestation bypass compresses this.
- Fix for Medium or Low: next release.
- Public disclosure: coordinated with you. Default is 90 days from
  acknowledgement, or a week after the fix ships — whichever is
  sooner.

If a report describes something being actively exploited, say so
explicitly in the first message. Timelines compress.

## What's in scope

Anything shipped from this repo. In rough priority order of what
I'd most want to know about:

- Bypasses of the five-layer liveness detection that let a
  non-live presentation pass as live.
- Attestation verification flaws — forged attestations, replayed
  attestations, confusion between Apple and Google attestation
  paths.
- The stub-mode gate failing open, so stub scores reach the relay
  in a release build. This has happened before, which is why the
  gate exists at all.
- Cryptographic mistakes — signature algorithm misuse, nonce reuse,
  key confusion between device signing keys and relay keys.
- Session hijacking, cross-session replay, challenge replay.
- Server-side problems in the relay — RCE, SSRF, injection, data
  leakage, state corruption via malformed WS frames.
- Supply-chain concerns in the bundled TFLite model or the
  MediaPipe assets.

The sample apps are dev harnesses, not shippable templates. Some of
their looseness is intentional (permanent red "DEV HARNESS" banner,
dev-mode App Attest entitlement, debug-only stub flag). If you're
not sure whether a looseness is intentional, report anyway.

## What's out of scope

- Vulnerabilities in third-party dependencies that aren't reachable
  through our code. Please report those upstream.
- Deployments operated by third parties. If you find an issue in
  somebody else's hosted openliveness, report it to them. I can
  help you reach them if that helps.
- DoS without a novel vector. "You can send a lot of requests" is
  not a report. An amplification or asymmetric-work finding is.
- Social engineering of maintainers.
- The real-time face-swap-plus-accomplice vector documented as
  residual in `protocol/ThreatModel.md`. Better defenses here are
  welcome as feature contributions, but this attack is understood
  and not treated as a bug.

## Safe harbor

I won't pursue legal action against security researchers who report
in good faith through the channels above, avoid data destruction and
privacy violations, don't exfiltrate beyond what's needed to
demonstrate the issue, and give a reasonable window before public
disclosure. If you're uncertain whether a specific action is okay,
ask first on the private channel.

## Credit

If you want credit in the release notes of the fix, say so. If
you'd rather stay anonymous, say that instead. Both are fine.

## Supported versions

The project is pre-v0.1.0 at the time of writing. Fixes land on
`main`. Once v0.1.0 ships, this section will carry a real version
table. Until then, "run the latest `main`" is the honest answer.
