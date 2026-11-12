# Google Play Data safety form — answers from the actual code

Fill this in Play Console from these answers. They reflect the **Play flavor** of Josh Learn as of this preparation (no analytics or ads SDKs are in the project). Re-check if you add a library later.

## Data collection / sharing overview

- **Does the app collect or share user data?** Yes, in a limited, optional way. See below. There is no developer-operated backend that receives lessons, keys or identifiers from the app.
- **Account creation:** No.
- **Encrypted in transit:** Yes, where data leaves the device (HTTPS to an AI provider you opt into, and Play Billing).
- **Users can request deletion:** Yes, by deleting data in the app or uninstalling. There is no copy on a server we control to delete.

## Data types

### App activity / in-app messages (prompts and lesson text)

- **Collected by us?** No (not sent to a server we operate).
- **Shared?** Only if the user opts into a **cloud AI provider**. Then the prompt and lesson context for that request are sent to Google, xAI, OpenAI, Anthropic, or a custom endpoint the user typed in. This is user-initiated, optional, and a Premium feature.
- **On-device processing:** Yes, when the offline model is used. Prompts do not leave the device.
- **Purpose:** App functionality (generating lessons, quizzes and chat).
- **Required or optional:** Optional (the app works with the on-device model).

### Financial info / purchase history

- **Collected / shared via Play Billing:** The app uses Google Play Billing for the one-time product `premium_unlock`. Google processes the payment. The app stores a local yes/no entitlement (and handles a pending state) so Premium works offline. It does not store card numbers.
- **Purpose:** App functionality (unlocking Premium).
- **Required:** Optional (the core app is free).

### Personal info / name, email

- **Not collected.** There is no sign-in. If a user pastes personal text into a lesson, that text stays on device unless they opt into cloud AI, in which case it is part of the prompt they sent to their chosen provider.

### Photos, video, contacts, location, SMS, files, calendar, health, contacts

- **Not collected.** The file picker is used only when the user imports a topics file or a Grok export; the content is read on device. A library export is a file the user chooses to share.

### App info and performance / crash logs / diagnostics / device or other IDs

- **Not collected.** No analytics, crash-reporting or advertising SDK is included.
- `allowBackup` is false.

### API keys

- Stored only on the device, encrypted with the Android Keystore. Not listed as a collected data type sent to us. When used, the key is sent to the provider the user chose (as that provider’s API requires).

### On-device AI model download

- A model file is fetched from Hugging Face when the user taps Download. This is a user-initiated download, not data collection about the user.

## Security practices

- Data is encrypted in transit (HTTPS / Play Billing).
- Users can request that data be deleted (local deletion / uninstall; we hold no server copy).
- The app is not designed to collect data from children (see content rating).

## Advertising / selling

- **No ads.** The app does not use advertising ID.
- **Data is not sold.**
- **No “legitimate interest” advertising.**

## Content rating and target audience (questionnaires)

These are the intended answers; you still have to complete the Play questionnaires.

- **Target age group:** 18 and over is the conservative default (the app is a general education tool, not designed as a children’s app, and cloud prompts could include whatever the user types). If you later want a broader rating, consider 16+ or “not specifically for children,” but do **not** tick “designed for children” or include the app in Designed for Families without a review of the content and of Grok-import / user prompts.
- **PEGI / ESRB style:** Expected to land as a low-maturity **Education / Everyone** (or PEGI 3) title if you don’t include the OSRS theme as “gambling-like” — the Old School theme is cosmetic (wood, parchment, XP), not real-money gambling. Mention the theme in the questionnaire notes if asked about game-like elements.
- **Violence, sex, drugs, language:** Not present in the built-in catalogue. User-generated / AI-generated lessons could in theory mention sensitive topics; the app does not add such content itself. The Grok importer already skips some personal/sensitive chats when building topic names.
- **Category:** Education.

## Privacy policy

A public URL is required. Host `play/privacy-policy.html` (see privacy-policy.md). Hosting is not done in this work and needs your approval.
