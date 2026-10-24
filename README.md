# Josh Learn

Native Android (Kotlin, Material 3) app that teaches any topic with AI-generated lessons, quizzes,
a tutor chat, flashcards with spaced repetition (SM-2) and learning paths. Uses your own API key
(Google Gemini free tier by default; xAI Grok, OpenAI, or any OpenAI-compatible server optional).

- Build a signed release: `./release.sh [patch|minor|major|X.Y.Z|current] "notes"`
- Publish to GitHub Releases: `./publish.sh minor "notes"` (repo jmcke47/josh-learn must exist)
- The app checks `https://github.com/jmcke47/josh-learn/releases/latest/download/update.json`.

Signing keystore lives in `keystore/` (git-ignored). Backup: `/home/box/backups/learn-app-keystore/`.
Never regenerate it, or updates won't install over the existing app.
