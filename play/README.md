# Google Play preparation (not submitted)

Josh does not have a Play developer account yet. Nothing in this folder has been uploaded to Google Play.

- [PREMIUM_SPLIT.md](PREMIUM_SPLIT.md) — proposed free vs Premium split (edit `Premium.gated` to change it)
- [store-listing.md](store-listing.md) — name, descriptions, assets
- [privacy-policy.md](privacy-policy.md) and [privacy-policy.html](privacy-policy.html) — draft policy; hosting is still needed
- [data-safety.md](data-safety.md) — Data safety and content-rating answers from the code
- [PLAY_SIGNING.md](PLAY_SIGNING.md) — Play upload key (not the GitHub sideload key)
- [feature-graphic.png](feature-graphic.png) — 1024×500
- [listing-screenshots/](listing-screenshots/) — 1080×1920 phone shots with generic demo topics only

The signed AAB is built locally to `release/play/josh-learn-play.aab` (gitignored). Build with `./gradlew bundlePlayRelease`.

The GitHub sideload channel is unchanged in behaviour: `./release.sh` now runs `assembleGithubRelease` (signed with `release.jks`) and `./publish.sh` still publishes that APK. Do not run those for this Play work unless Josh asks for a GitHub release.
