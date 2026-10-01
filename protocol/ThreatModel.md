# openliveness — Threat Model
# CDL Protocol Security Properties

---

## ATTACKS DEFEATED

| Attack | How Defeated |
|--------|-------------|
| Photo attack (printed face held up) | Layer 4 LBP detects flat texture |
| Screen replay (video on phone/tablet held up) | Layer 4 Fourier detects pixel grid + Layer 3 detects no live pulse |
| Pre-recorded video injected via virtual camera (mobile) | Layer 1: hardware attestation proves frames from physical camera. Virtual camera cannot forge signed attestation. |
| Static AI-generated image | Layer 4 Fourier artifacts + Layer 3 no pulse |
| Virtual camera driver on phone | Rooted device fails hardware attestation check |
| Session replay (reuse old attestation) | Timestamp freshness (120s) + single-use challenge_id |
| Attestation forgery without device | ES256 signature requires private key in device Secure Enclave |
| Man-in-the-middle (sniff attestation) | Attestation contains no biometric data. Replay still prevented by single-use challenge. |
| QR code sniffing | Attacker would need to complete liveness on their own device. Challenge is bound to session. |

---

## RESIDUAL ATTACK SURFACE

**Real-time face-swap with cooperating accomplice**: an attacker using
real-time face-swap software on a physical unmodified phone, with a
human accomplice present, could theoretically pass liveness detection.
This attack is not automated, not scalable, and requires the accomplice
to accept criminal liability. It must also defeat a separate face-matching
step — meaning the face-swap must reproduce a specific target face in
real-time on mobile hardware while completing a randomized challenge
sequence. No freely available tool does this reliably today. This is
the same threat profile as physical identity fraud — possible in theory,
rare and expensive in practice, and a fundamentally different risk class
than the automated injection attacks this system eliminates.

**Browser-only liveness (no CDL)**: if a relying party accepts
browser-only liveness without CDL mobile handoff, virtual camera
injection is possible. CDL solves this by requiring mobile. If the
relying party accepts browser-only, CDL is not involved.

**Sophisticated rooted device hiding root**: advanced malware may
pass integrity checks. Requiring MEETS_STRONG_INTEGRITY (not just
MEETS_DEVICE_INTEGRITY) reduces this risk.

---

## WHAT THIS SYSTEM DOES NOT CLAIM TO SOLVE

This system proves a live human was present. It does NOT prove that
human is the right person.

For full KYC, a separate document verification and face matching step
is required. openliveness covers the liveness portion only.

Social engineering — where a real person is coerced into completing
liveness on behalf of a fraudster — is outside the scope of any
technical liveness system.

---

## ASSURANCE LEVELS

| Level | Meaning | When Assigned |
|-------|---------|---------------|
| IAL2 | Hardware attestation present + all layers pass | Recommended for bank KYC, government identity proofing |
| IAL1 | Software liveness only, layers passed | Acceptable for age verification, step-up auth |
| software_only | No hardware attestation available | Relying party decides acceptance. Not suitable for IAL2 use cases. |

---

## SECURITY PROPERTIES THE RELAY SERVER ENFORCES

1. Session expiry: all sessions expire 5 minutes after initiation
2. Single-use challenge: challenge_id can only be used once
3. Attestation freshness: reject if timestamp > 120 seconds old
4. Signature verification: mandatory, reject if invalid
5. Hardware attestation verification: required for IAL2
6. Replay prevention: used session_ids stored 24 hours, duplicates rejected
7. Rate limiting: max 10 session initiations per IP per minute
8. Session binding: session_id in QR must match session_id in attestation