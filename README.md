# Agent Hita — Android

**Real-time on-device protection from grooming, sextortion, financial scams, and manipulation.**

Agent Hita is a patent-pending Android app that detects harmful conversation patterns in messaging apps before they escalate. All analysis runs entirely on the device. No message content is ever stored, transmitted, or shared. When a risk is detected, the app alerts the user and — with explicit consent — notifies a trusted guardian.

→ **Website:** [agenthita.org](https://www.agenthita.org)
→ **Architecture:** [agenthita.org/architecture.html](https://www.agenthita.org/architecture.html)
→ **Trust & Consent Specification:** [agenthita.org/consent.html](https://www.agenthita.org/consent.html)
→ **Privacy & Terms:** [agenthita.org/privacy.html](https://www.agenthita.org/privacy.html)

---

## How it works

Agent Hita is built around four layers:

| Layer | What it does |
|---|---|
| **Connector** | Reads permitted communication surfaces via Android Accessibility Service — WhatsApp, Instagram DM, SMS, and more |
| **Local Understanding** | On-device models and rule systems classify threats: grooming, sextortion, coercion, financial scams, identity phishing, luring, harassment |
| **Temporal Risk Engine** | Tracks escalation patterns across a conversation over time — urgency, secrecy, dependency, extraction — not just individual messages |
| **Policy & Disclosure Engine** | Decides the right action: a local nudge, a check-in prompt, a summary entry, or a guardian alert — enforcing minimal disclosure at every step |

### Privacy properties

- Message text is analysed in memory only and never written to disk
- The local database stores only scored risk events: category, risk level, score, signal types — never raw content
- Conversation keys are SHA-256 hashes of sender identifiers — raw names are never stored
- Guardian alerts contain risk category and severity only — no message excerpts
- All inference runs on-device via MediaPipe / Gemma — no cloud processing
- The codebase is publicly available for independent security audit

---

## Tech stack

| Component | Technology |
|---|---|
| Language | Kotlin |
| Min SDK | Android 8.0 (API 26) |
| Target SDK | Android 14 (API 34) |
| Message reading | Android Accessibility Service |
| On-device AI | MediaPipe LLM Inference (Gemma) |
| Local storage | Room + SQLCipher (encrypted) |
| Secure preferences | EncryptedSharedPreferences |
| Background work | WorkManager |
| Remote config | CloudFront-hosted JSON with local fallback |

---

## Building locally

### Prerequisites

- Android Studio Hedgehog or later
- JDK 17
- Android SDK API 34

### Clone and build

```bash
git clone https://github.com/Agent-Hita/AgentHitaAndroid.git
cd AgentHitaAndroid
```

Create a `secrets.properties` file in the project root (see `secrets.properties.example`):

```properties
FEEDBACK_API_KEY=your_key_here
FEEDBACK_API_URL=https://api.agenthita.org/feedback
ALERT_API_URL=https://api.agenthita.org/alert
TELEMETRY_API_URL=https://api.agenthita.org/telemetry
```

Open in Android Studio and run on a device or emulator (API 26+).

### Run unit tests

```bash
./gradlew testDebugUnitTest
```

89 tests across detection logic, configuration defaults, conversation buffering, and component existence guards.

---

## Contributing

Contributions are welcome from approved developers. The process:

1. Open an issue describing what you want to fix or add
2. Wait for acknowledgement before writing code
3. Fork the repo and submit a pull request against `main`
4. All PRs require review and approval by Agent Hita LLC before merging

By submitting a pull request you agree that Agent Hita LLC may use your contribution under the terms of the [LICENSE](LICENSE) file.

For contributor access or to discuss a contribution before opening an issue, email [admin@agenthita.org](mailto:admin@agenthita.org).

---

## Target apps

Agent Hita monitors the following apps when granted Accessibility Service permission:

- WhatsApp (`com.whatsapp`, `com.whatsapp.w4b`)
- Instagram (`com.instagram.android`)
- Google Messages (`com.google.android.apps.messaging`)
- Samsung Messages (`com.samsung.android.messaging`)
- Android Messages (`com.android.messaging`, `com.android.mms`)

Group chats are deliberately excluded. Only 1-to-1 conversations are analysed.

---

## Detected threat categories

| Category | Examples |
|---|---|
| Grooming | Age probing, boundary testing, secrecy requests, location isolation |
| Sextortion | Explicit content requests, exposure threats, blackmail language |
| Financial Scam | Urgency, crypto/wire transfer requests, authority impersonation |
| Romance Scam | Crisis framing, premature intimacy, financial dependency |
| Identity Phishing | Password/OTP/SSN requests, credential harvesting |
| Luring | False opportunity offers, modelling/casting scams, meet-up pressure |
| Harassment | Threats, stalking language, doxxing, coercive control |
| Disappearing Messages | Requests to enable ephemeral messaging, secrecy signalling |

---

## License

This software is source available — not open source.

- The source code is publicly available for transparency and security auditing
- Free for individual, non-commercial use
- Commercial or enterprise use requires a written license from Agent Hita LLC

See [LICENSE](LICENSE) for full terms.
Contact [admin@agenthita.org](mailto:admin@agenthita.org) for commercial licensing.

---

## Patents

One or more patent applications are pending with the United States Patent and Trademark Office covering technology implemented in this software.

See [PATENTS](PATENTS) for details.

---

© 2026 Agent Hita LLC. All rights reserved.
