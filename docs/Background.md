# openliveness — Project Background
# Why We Built This, What Problem It Solves, and Who It Helps

---

## THE PROBLEM IN ONE SENTENCE

When you open a bank account, apply for a loan, or verify your identity
online today, the system has no reliable way to confirm that a real live
human — and not a photograph, recorded video, or AI-generated face — is
sitting in front of the camera.

---

## WHAT IS HAPPENING TODAY (WITHOUT THIS PROJECT)

### How Online Identity Verification Works Today

When a financial institution, government service, or any regulated
business needs to verify that you are who you say you are, they follow
a process called KYC (Know Your Customer). This typically involves:

1. You take a photo of your government-issued ID
2. You take a selfie or record a short video of your face
3. The system compares the two
4. The system tries to confirm you are a live person (liveness detection)

The liveness detection step — step 4 — is where the system today is
fundamentally broken for a specific class of attack.

### The Injection Attack — The Real Threat Today

Most people imagine that fooling a liveness check means holding up a
printed photo or playing a video on a phone in front of a webcam.
Liveness systems have been reasonably good at catching those attacks
for years.

The attack that works today is different. It is called an injection
attack. The attacker does not fool the camera. They bypass the camera
entirely.

Here is how it works:

```
Normal flow (what the liveness system expects):
  Physical camera → captures real face → sends to liveness algorithm

Injection attack (what actually happens):
  Attacker installs virtual camera driver (e.g., OBS Virtual Camera)
  Virtual camera intercepts the camera feed
  Attacker feeds AI-generated or pre-recorded video instead
  Liveness algorithm receives what it thinks is a real camera feed
  The algorithm never sees a fake — it sees what appears to be
  a genuine camera stream
```

The liveness algorithm is not fooled by a fake face. It is bypassed
entirely. It processes a real-looking video feed and has no way to
know the physical camera was never involved.

This attack works on any system that runs liveness verification in a
web browser. Web browsers provide no way to verify that a camera stream
came from real physical hardware. The browser cannot distinguish between
a genuine camera and a virtual camera driver.

### Real-World Fraud This Enables

**The Hong Kong Deepfake Case (February 2024 — Verified)**

A finance employee at a multinational company in Hong Kong was invited
to a video conference call. On the call were what appeared to be the
company's CFO and several other colleagues — all of whose faces the
employee recognized. The employee was instructed to transfer funds.

The CFO and all colleagues were AI-generated deepfakes.

The employee transferred HK$200 million — approximately US$25.6 million.

This case was reported by Hong Kong police and confirmed by Reuters,
BBC, and CNN. It is the largest single confirmed deepfake financial
fraud incident on public record.

**Account Takeover via KYC Bypass**

Fraudsters use injection attacks to open new accounts in real people's
names, bypass age verification on regulated platforms, apply for loans
and credit in stolen identities, and access financial services using
synthetic identities (combinations of real and fabricated personal
information).

**Why This Is Growing**

Three converging factors are making this worse every year:

1. AI video generation quality is improving rapidly. Generating a
   convincing moving face now requires consumer hardware and
   freely available software.

2. Remote-first business processes accelerated during 2020-2022
   and are now permanent. More identity verification happens
   remotely than ever before.

3. Virtual camera tools are standard software used by millions of
   legitimate users (streamers, video conference participants).
   They cannot be blocked.

---

## WHY EXISTING SOLUTIONS DO NOT SOLVE THIS

### The Technical Gap

Liveness detection algorithms — the software that analyzes a video
feed to determine if a real person is present — have become
increasingly sophisticated. They can detect:

- Printed photographs held in front of a camera
- Video replays on a screen held in front of a camera
- Many types of AI-generated synthetic faces

But they all share one fundamental weakness: they trust that the
video feed they receive came from a real physical camera. Injection
attacks exploit this trust.

No algorithm, however sophisticated, can detect an injection attack
if the injected video is high quality. The algorithm is analyzing
the right kind of data — it just does not know that data was
fabricated before it arrived.

### The Privacy Gap

Current commercial liveness verification services solve this by
moving processing to their own cloud infrastructure. Your video,
your face, your biometric data travels over the network to their
servers.

This creates three problems:

**Privacy problem**: Your biometric data — one of the most sensitive
categories of personal information — is stored on someone else's
servers. Under GDPR (Europe), BIPA (Illinois), and similar laws
worldwide, biometric data is classified as special category data
requiring explicit consent, strict handling, and significant
compliance burden.

**Breach risk**: A database of face biometrics is an extremely
high-value target. Unlike passwords, you cannot change your face
after a breach.

**Audit problem**: The algorithms processing your biometric data
are proprietary and closed source. You cannot verify what is done
with your data, how long it is retained, or whether it is used
for other purposes.

### The Access Gap

Commercial liveness verification services are priced for enterprise
adoption. This pricing puts reliable liveness verification out of
reach for:

- Startups building identity-verified products
- Non-profit organizations
- Government agencies in emerging markets
- Researchers studying identity verification
- Developers building open-source identity tools

---

## WHAT WE ARE BUILDING

openliveness is an open-source liveness verification system built
on a protocol called Cross-Device Liveness (CDL).

### The Core Insight

Mobile phones — iPhones and Android devices — have a capability that
web browsers fundamentally lack: they can cryptographically prove that
a video frame came from their physical hardware camera.

This is called hardware attestation. Apple provides it through a
system called App Attest. Google provides it through the Play Integrity
API. When a liveness check runs on a mobile device and is signed with
hardware attestation, the result carries a cryptographic proof that
the video was captured by a real physical camera on a specific
unmodified device.

A virtual camera driver cannot produce this proof. An injection attack
cannot forge it. It requires the device's secure hardware chip —
technology that exists in every modern smartphone.

### How CDL Works

The Cross-Device Liveness protocol solves the web browser problem by
routing liveness verification through the user's phone:

```
Step 1: User starts identity verification on any web browser
Step 2: Browser displays a QR code
Step 3: User scans QR code with their phone
Step 4: Phone runs liveness verification locally:
          - Active challenge (blink, look left, smile)
          - Physiological signal detection (blood pulse via rPPG)
          - Texture analysis (print and screen replay detection)
          - Behavioral analysis (micro-movements, eye tracking)
          - Hardware attestation (cryptographic camera proof)
Step 5: Phone sends only a signed result to the server
          No video. No face images. No biometric features.
          Only: "a live human was verified on this hardware device"
Step 6: Server verifies the cryptographic proof
Step 7: Browser session completes — identity verified
```

This is conceptually similar to how passkeys work for login — the
browser hands off a security-critical operation to the phone, which
handles it using hardware-backed cryptography. CDL applies the same
pattern to liveness verification.
### What Never Leaves the Device

This is the most important property of CDL:

```
Raw video frames      — never transmitted
Face images           — never transmitted
Face embeddings       — never transmitted
Biometric features    — never transmitted
```

What does cross the network is a JSON object containing:
- Numerical scores (0.0 to 1.0) from each detection layer
- A pass/fail decision
- A hardware attestation token from Apple or Google
- A cryptographic signature

These scores cannot be used to reconstruct a face. They are
not biometric data in any legal or technical sense. The server
knows a live human was verified. It does not know what that
human looks like.

---

## THE FIVE DETECTION LAYERS

openliveness combines five independent detection methods.
An attacker must defeat all five simultaneously.

**Layer 1 — Hardware Attestation**
The phone's operating system cryptographically signs the result,
proving it came from a real unmodified device's physical camera.
What it stops: virtual camera injection, rooted device attacks.

**Layer 2 — Active Liveness Challenge**
The user is asked to perform a random sequence of actions — blink,
look left, smile. The sequence is generated fresh for each session
and must be completed within a time limit.
What it stops: static photos, pre-recorded videos that do not
contain the correct action sequence.

**Layer 3 — Physiological Signal (rPPG)**
The camera detects micro-variations in skin color caused by blood
flow. Every living person has a pulse. This signal is extracted
from the video stream and verified as genuine.
What it stops: synthetic AI-generated video (which has no pulse
signal), static images rendered as video.

**Layer 4 — Texture and Frequency Analysis**
Algorithms analyze the texture of the face region and the
frequency characteristics of the video for artifacts that
indicate a printed photograph or a screen replay attack.
What it stops: printed photo attacks, video played on a tablet
or phone held up to the camera.

**Layer 5 — Behavioral Biometrics**
Real people have involuntary micro-movements caused by breathing
and heartbeat. Real eyes have involuntary rapid movements called
microsaccades. These are analyzed across frames.
What it stops: suspiciously static synthetic video, AI-generated
faces that lack natural movement.

---

## WHAT THIS DOES NOT CLAIM TO SOLVE

Honesty about limitations is as important as documenting capabilities.

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

**Face matching**: openliveness proves a live human is present. It does
not prove that human is the right person. A separate document
verification and face matching step is required for full KYC. These
are different problems.

**Social engineering**: If a real person is coerced or deceived into
completing liveness verification on behalf of a fraudster, the system
correctly identifies them as live. Human trust problems cannot be
solved by technical systems alone.

---

## WHO CAN USE THIS AND HOW

### Financial Institutions

Banks, credit unions, fintech companies, payment processors, and
lending platforms need KYC onboarding that meets regulatory
standards (NIST SP 800-63-3 IAL2, eIDAS 2.0, regional equivalents).

With openliveness they can:
- Self-host the relay server on their own infrastructure
- Keep biometric data within their regulatory boundary
- Reduce vendor compliance risk
- Integrate with existing identity proofing workflows

### Government and Public Sector

Benefits agencies, tax authorities, voter registration systems,
and public services increasingly require remote identity proofing
for digital services. An open-source solution with published
algorithms and auditable code is easier to procure, easier to
assess for security, and easier to maintain long-term.

### Healthcare

Patient identity verification, prescription authorization, and
telehealth platforms require identity assurance. A system where
biometric data never leaves the patient's device reduces HIPAA
and GDPR compliance burden materially.

### Sharing Economy and Gig Platforms

Driver verification, host verification, and freelancer identity
platforms all require periodic re-verification at high volume.
An open-source self-hosted solution fits the economics of
high-volume, low-transaction-value use cases.

### Researchers and Developers

Security researchers studying identity verification currently have
no open-source reference implementation to evaluate, compare
against, or build upon. Developers building identity-verified
applications currently have no viable free option.

openliveness provides a foundation that both groups can use,
study, and improve.

### Regulators and Auditors

An open-source reference implementation with published algorithms,
a public threat model, and a standardized benchmark allows
regulators to establish minimum standards with technical grounding.

---

## WHAT HAPPENS TODAY WITHOUT THIS

A realistic scenario that occurs today, without openliveness:

```
A fraudster wants to open a bank account in a stolen identity.

They have:
  - A stolen identity (name, address, ID document number)
  - A high-quality AI-generated face that matches the stolen ID photo
  - A virtual camera driver (free software)
  - Access to the bank's online account opening form

They open the account opening form in a browser.
They install their virtual camera as the default webcam.
The bank's KYC system asks for liveness verification.
The virtual camera feeds AI-generated video of the face.
The liveness algorithm analyzes the video.
The algorithm detects: movement, correct challenge response,
  no obvious artifacts.
The algorithm returns: pass.
The bank approves the account.
The fraudster has a bank account in a stolen identity.
```

This is not a hypothetical. Injection attacks against KYC systems
are a documented fraud vector used in production today.

---

## HOW THIS PROJECT CHANGES THAT

With openliveness CDL:

```
Same fraudster attempts the same attack.

Bank's KYC form asks for liveness verification.
Browser displays QR code.
Fraudster does not have the victim's phone.

If fraudster uses their own phone:
  Phone runs hardware attestation.
  Device identity is recorded in the attestation object.
  The biometric data does not match the stolen identity.
  The face matching step fails.

If fraudster attempts to bypass the mobile step:
  The CDL protocol requires a valid signed attestation.
  No signed attestation = no liveness result accepted.
  The browser session cannot complete without the phone.

If fraudster uses a rooted phone to forge attestation:
  Play Integrity detects rooted device.
  assurance_level = software_only.
  Bank's policy requires IAL2.
  Session rejected.
```

The attack that was trivially easy through a browser becomes
significantly harder. The fraudster must compromise the hardware
security of a physical smartphone — a fundamentally different
and much more costly attack.

---

## THE OPEN SOURCE RATIONALE

Security systems derive trust from transparency. A liveness
algorithm whose source code cannot be inspected requires
users to trust that the algorithm works as claimed, the biometric
data is handled as promised, and the system has no hidden weaknesses.

openliveness is built on the principle that security-critical
identity verification infrastructure should be auditable by
the organizations that deploy it, the researchers who study it,
and the regulators who govern it.

Open source in security is not a weakness. It is how trust
is established at scale.

---

## PROJECT STATUS

openliveness is currently in active development.

Version 0.1.0 (target: end of 2026):
- Core CDL protocol implementation
- iOS SDK with all 5 detection layers
- Android SDK with all 5 detection layers
- Node.js relay server
- Attestation verification library (npm)
- Web SDK (framework-agnostic + React wrapper)
- ISO/IEC 30107-3 benchmark harness
- End-to-end demo application
