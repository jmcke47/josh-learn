# Play upload signing (do not commit passwords)

- Keystore (Play only): `keystore/play-upload.jks` (backed up at `/home/box/backups/learn-app-play-upload/`).
- Passwords: `keystore/play-upload.properties` (mode 600). Never commit or print them.
- Alias: `play-upload`
- Upload certificate SHA-256: `63b05b2b6cf9e96f5a8fff22057dfab3a3bbf78162d2da4a4101a81ee12663c3`
- Build: `./gradlew bundlePlayRelease` → `release/play/josh-learn-play.aab`

Do **not** use this key for the GitHub sideload channel. That channel keeps `keystore/release.jks` (cert SHA-256 `cdb5137e4c0327597977be9ebde5afd9de01171038c6051b2aef20fb0db3b90f`) and `./release.sh` / `./publish.sh`.

When you create the Play app, enroll Play App Signing and upload this key as the **upload key**. Play will issue an app signing key. Existing GitHub installs cannot be updated by the Play build (different signing key, even though the package name is the same `com.josh.learn`).
