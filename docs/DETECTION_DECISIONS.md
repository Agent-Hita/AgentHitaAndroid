# Detection design decisions

A running log of deliberate product/detection decisions, so future changes don't
accidentally relitigate or reverse them. Each entry: the decision, why, and what
enforces it. Newest first.

## 2026-07-18 — False-positive hunt (post-launch, first day with Gemma on a real device)

Context: the first device with the Gemma model installed surfaced four
independent false-positive classes in one day. Rules-only scoring had silently
tolerated several latent bugs because garbage input scored NONE; a 1B LLM
classifies whatever it is given, which made every latent extraction bug audible.

### 1. Group chats: layered skip, never a blanket "skip when unknown"
- WhatsApp group detection is subtitle-based, but the subtitle populates
  asynchronously — the first window after chat-open can score before the group
  is recognisable (caused 2 launch-day alerts from residents' groups).
- **Decision**: three layers — (a) structural signal: per-bubble sender-name
  views (`waGroupSenderNameId`, OTA-patchable) exist only in groups; (b)
  subtitle keywords incl. "tap here for group info"; (c) alert-time re-check:
  re-identify the window before notifying and drop the alert only on a
  POSITIVE group identification AND a conversation-hash match.
- A blank subtitle must NEVER be treated as "group" — 1-1 chats legitimately
  have blank subtitles, and treating blank as group would suppress real child
  alerts. Enforced by `WhatsAppGroupSubtitleTest`.

### 2. Structural-fallback extraction must filter chrome
- The full-tree fallback (added 2026-07-09, not in prod 1.0.22) scrapes date
  separators ("December 4, 2025"), header chrome ("tap here for contact info"),
  and call rows ("Voice call", "Missed voice call") whenever the message list
  transiently has 0 text nodes (voice-only chats, during/after calls). Gemma
  hallucinated harm categories on that junk.
- **Decision**: filter whole-line date separators, header chrome, and call
  chrome in `isUIChrome`; drop the toolbar contact name from fallback results;
  debug builds log every fallback-collected string. Whole-line matches only —
  "voice call me tomorrow" and "meet me on December 4" must still score.
  Enforced by `MessageFiltersTest`.
- **2026-07-19 addendum — structural, not string-matching**: text patterns are
  whack-a-mole (next live FP: `out_of_chat_title` — WhatsApp's banner for a
  message from ANOTHER chat — carried a contact's bare name, scored as
  IDENTITY_PHISHING). The fallback walk now excludes nodes by view-ID prefix
  (`waFallbackExcludedIdPrefixes`, OTA-patchable: out_of_chat, call_log,
  conversation_row_date, conversation_contact, info, date, entry — all
  verified via on-device hierarchy dump). Text filters stay as a second layer.
  When a new fallback FP appears: dump the hierarchy, add the view-ID prefix —
  don't add another text pattern.

### 3. Unprompted credential shares: MEDIUM warning, not guardian email
- A [USER] line sharing a card number / SSN / password / PIN / CVV may be
  coerced via a channel we cannot see (voice call), so it must warn even with
  no visible request — but a guardian email about a possibly-benign transfer is
  the costlier error.
- **Decision**: `credential_shared` floors at 0.72 (MEDIUM for every user
  category — user-facing warning, no guardian email). With a visible request
  (same window or prior context) it floors at 0.87 (HIGH, like an OTP
  handover). Wifi/streaming/door-code and postal-PIN shares excluded; [CONTACT]
  self-shares don't fire. The share line itself must never double as the
  "request" evidence. Enforced by `CredentialShareTest` and two
  `ChildAlertInvariantsTest` scenarios.

### 4. Transactional notifications (UPI/bank alerts): prompt-only, no scoring gate
- Gemma mislabels automated bank/UPI notifications ("Your UPI-Mandate is
  cancelled … A/c No. XXXXXX7899") as identity phishing.
- **Decision (owner: Femina)**: handle in the Gemma prompt ("bank transaction
  alerts … to the user = NONE"), NOT with scoring-pipeline heuristics. A
  proposed transactional-text gate in RiskScorer was explicitly rejected.
  The prompt instruction is pinned by `GemmaPromptContentTest` (present in the
  built prompt and survives worst-case trimming); actual model obedience is
  verified on-device via the "Gemma → NONE" log line.

### 5. Response parser: NONE terminates the scan
- The token-scanning parser skipped NONE and kept scanning, so a chatty safe
  answer ("NONE. THIS IS A BANK ALERT, NOT PHISHING.") parsed as
  IDENTITY_PHISHING, and "NONE (NOT HIGH RISK)" as severity HIGH — the parser
  could manufacture alerts out of safe verdicts. This also made benign chatty
  answers disproportionately land on IDENTITY_PHISHING (the "PHISHING" token
  alias), explaining why false alerts kept wearing that tag.
- **Decision**: an explicit NONE verdict (category or severity) terminates
  parsing → safe. Category-only answers still default severity to MEDIUM.
  Enforced by `GemmaResponseParserTest`.

### 6. Direction guard: Gemma-only contact-actor verdicts need an incoming line
- Gemma deterministically returned IDENTITY_PHISHING HIGH for a harmless
  OUTGOING romanised-Telugu message ("Aa phone charge ayipoyindi."),
  ignoring three separate prompt instructions, and later SEXTORTION HIGH for
  the user's own outgoing birthday wishes (displayed as "Sexual Manipulation").
  Prompt wording alone does not restrain the 1B model for this class.
- **Decision** (initially IDENTITY_PHISHING-only, widened same day after the
  SEXTORTION misfire): a pure-Gemma verdict (zero rule signals) on a window
  containing only [USER] lines does not alert, for every category except
  HARASSMENT — the threat actor for all other categories is the contact, and a
  contact who said nothing in the window cannot be victimising the user.
  HARASSMENT stays exempt (flagged in either direction). Rule-detected outgoing
  risks (otp_shared / credential_shared) bypass the guard and stay HIGH.
  Context corroboration must not resurrect a guard-suppressed result.
  Enforced by `GemmaDirectionGuardTest`.

### 7. Telemetry event names are allowlisted — add the name WITH the tracking call
- `parsing_failed_instagram_empty` (from the `_empty` per-app tracking added in
  fabe744) was never added to `ALLOWED_EVENT_NAMES`; every empty extraction
  threw IllegalArgumentException, aborting that window's processing and losing
  the metric. All seven `parsing_failed_<app>_empty` names are now allowlisted.
- **Lesson**: `TelemetryManager.track` hard-fails on unknown names by design;
  any new `track(...)` call must land in the same commit as its
  `ALLOWED_EVENT_NAMES` entry.

### Working agreements surfaced today
- **Risk-scoring changes require Femina's explicit approval**; prompt wording,
  parsing fixes, and extraction/chrome filtering are engineering territory.
  When a new FP class appears, propose options (prompt vs rules vs scoring)
  and let her choose.
- Pure-Gemma detections stay capped at MEDIUM (pre-existing); MEDIUM = local
  notification, HIGH = guardian email. "Guardian email about a benign event"
  is treated as the costlier error throughout.
- Child-alert invariants are a floor, never weakened — new attack scenarios are
  ADDED to `ChildAlertInvariantsTest` with every detection change.

## Earlier decisions (pre-2026-07-18, recorded elsewhere)
- Seen-set is not cleared when Gemma hot-loads; messages first seen during the
  rules-only window stay rules-scored (accepted behaviour, not a bug).
- Group chats are never analysed; only 1-1 conversations.
- Pure-Gemma (rules-NONE) detections are capped at MEDIUM to guard
  hallucination; self-protecting adults additionally require Gemma HIGH.
