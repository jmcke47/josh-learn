# Privacy policy — Josh Learn

**Last updated:** 12 November 2026.

This policy describes how the Josh Learn Android app (“the app”) handles information. The app is built for learning on your phone. It does not require an account with us, and we do not run a backend that collects your lessons or keys.

**This page is a draft.** It must be hosted at a public HTTPS URL (for example GitHub Pages) before you can complete a Play listing. Suggested path once you approve hosting: a `privacy.html` file on the existing `jmcke47/josh-learn` GitHub Pages site, or any site you control. Until it is hosted, do not submit the app to Play.

## Who we are

Josh Learn is an app published by the developer who releases it on Google Play (and, separately, as a sideload build on GitHub). Contact details will appear on the Play listing and should also appear on the hosted policy page.

## What the app stores on your device

All of the following stay on your phone (app private storage) unless you choose to export or share them:

- Lessons, quizzes, flashcards, learning paths and topics you create or import
- Display settings (theme, text size) and other preferences
- Personal-best scores for challenge mode and, if you use them, XP totals
- A local record of whether you have unlocked Josh Learn Premium

API keys you enter for a cloud provider (Google Gemini, xAI Grok, OpenAI, Anthropic, or a custom endpoint) are encrypted with a key held in the Android Keystore and stored on the device. They are never logged, and they never leave the phone except when the app sends a request to the provider you selected.

The app sets `allowBackup` to false, so Android backup does not include this data.

If you import a Grok data export or a topics file, the file is read on the device. The app keeps only the topic names and sub-topics you confirm; it does not keep the original chats.

## What is sent over the network — and only when you opt in

**Default / free use.** Creating lessons with the on-device (offline) model happens on your phone. The model file is downloaded from a public Hugging Face repository when you tap Download, and the file’s checksum is verified. After that, prompts and answers for that model do not leave the device.

**Cloud AI (Premium, optional).** If you add an API key and switch to Gemini, Grok, ChatGPT, Claude or a custom OpenAI-compatible server, the app sends your prompt and enough lesson context for the task (a new lesson, a quiz, a chat message, and so on) to that provider, over HTTPS, using the key you supplied. We do not operate those services. Their own privacy policies apply to what they receive:

- Google Gemini — Google AI Studio / Gemini API
- xAI Grok — xAI
- OpenAI ChatGPT — OpenAI
- Anthropic Claude — Anthropic
- A custom endpoint you configure — whoever runs that server

You can remove a key in Settings at any time. You can keep using the offline model instead.

**Google Play Billing.** If you install from Google Play and buy or restore the one-time Premium unlock (`premium_unlock`), Google Play processes the payment. The app receives a purchase token and an entitlement from Play in order to unlock features and to restore a purchase on another device signed into the same Google account. We do not receive your payment card details.

**App updates (Play build).** Updates are delivered by Google Play. The Play build does not check a private update server and cannot install an APK itself.

**GitHub sideload build only.** That separate build may check a public `update.json` on GitHub when you open the app (you can turn this off) and, if you choose Update, download and install an APK. That channel is not used in the Play edition.

The app needs the Internet and Network State permissions for the optional network uses above, and to download an offline model if you ask it to. A foreground-service notification may appear only while that model is downloading, because Android requires it; it is removed when the download ends.

## What we do not collect

- We do not have user accounts in the app.
- We do not include advertising SDKs, analytics SDKs, crash-reporting SDKs or tracking pixels.
- We do not sell your information.
- We do not use your lessons or prompts to train our own models (we do not operate a model training service). A cloud provider you opt into may handle prompts under that provider’s policy.

## Children

Josh Learn is a general education app and is not directed at children under 13. It does not knowingly collect personal information from children. You should not enter another person’s personal data into lessons or prompts.

## Your choices

- Delete a lesson, topic, imported topic list or API key in the app.
- Uninstall the app to remove all local data.
- Turn off cloud AI and use the on-device model only.
- Request deletion or ask a question by using the contact email on the Play listing (once published) or the GitHub repository for the sideload build.

Premium purchases are managed by Google Play (including refunds under Google’s policies).

## Changes

We may update this policy. The “Last updated” date at the top will change, and a revised copy will be published at the same URL.

## Hosting note (for the developer)

An HTML version ready to host is in `privacy-policy.html` in this folder. GitHub Pages on `jmcke47/josh-learn` (for example `https://jmcke47.github.io/josh-learn/privacy`) is a reasonable option, but hosting is not set up by this work and needs your approval.
