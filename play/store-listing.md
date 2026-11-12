# Google Play store listing (draft — not submitted)

Do not upload this until Josh has a Play developer account and has approved the copy, screenshots and privacy policy hosting.

## App name

Josh Learn

(Open decision: keep the personal name, or use something more store-generic later. Changing the name in Play does not require a package-name change.)

## Default language

English (Australia) recommended, or English (United States).

## Short description (80 characters max)

```
Learn any topic with lessons, quizzes and flashcards, even offline.
```

Character count: 67.

## Full description (4,000 characters max)

```
Josh Learn turns any topic into a short lesson you can actually finish.

Type a subject, paste an article, or share a link from another app. The app writes a structured lesson with an overview, key concepts, a quiz and flashcards, then schedules the cards with spaced repetition so they stick.

Learn on the train, offline. Download an on-device AI model once and you can create lessons and quiz yourself without an internet connection. Prefer a cloud model? Premium unlocks Gemini, Grok, ChatGPT or Claude with your own API key — the key is stored encrypted on your phone and never sent anywhere except the provider you chose.

Free, and stays free:
• Lessons at the depth you want (from “like I’m 5” to expert)
• Quizzes and a tutor chat about the lesson
• Flashcards with a proven SM-2 schedule
• A built-in catalogue of topics (science, history, languages, music and more)
• Topics you create yourself
• Offline AI on this phone
• Dark, light and system themes

Josh Learn Premium is a one-time purchase (not a subscription). It unlocks:
• Cloud AI and one-tap switching between providers
• Unlimited learning paths (a sequence of lessons that build on each other)
• The Old School RuneScape-inspired theme and an AMOLED black theme
• 60-second challenge mode
• XP and skill extras
• A home-screen widget for your topics
• Library export, and import from a Grok data export or a topics file

Your lessons, flashcards and API keys stay on your device. There are no ads and no analytics. Updates, when you install from Google Play, come from Play itself.

Requires Android 8.0 or later. The optional on-device model runs on 64-bit ARM phones.
```

## Application type / category

- Application (not a game), unless you later decide the OSRS theme makes “game” a better fit — **Education** is the intended category.
- Tags to consider: Education, Tools.

## Contact

Use Josh's own email and a support URL once the developer account exists. A privacy policy URL is required (see privacy-policy.md).

## Graphic assets

- Feature graphic (1024 × 500): `play/feature-graphic.png`
- Phone screenshots (1080 × 1920, generic demo topics only): `play/listing-screenshots/`
  - `01-home.png` — home, built-in catalogue
  - `02-library.png` — library with generic lessons
  - `03-lesson.png` — a lesson (Black holes)
  - `04-review.png` — flashcard review
  - `05-premium-upsell.png` — Premium screen
  - `06-home-osrs.png` — home in the Old School theme
  - `07-lesson-osrs.png` — lesson in the Old School theme
  - `08-settings.png` — settings (no notification options)
- High-res icon: the existing launcher mipmaps can be exported at 512 × 512 when you set up the listing. Not generated here.

## Package name

`com.josh.learn`

Sideloaded GitHub builds are signed with `release.jks`. The Play upload key is different, so a GitHub install will **not** be upgraded by the Play build — Android treats them as different apps that cannot replace each other. The package name must also be unused on Play; please confirm it is free before creating the listing.

## Pricing

Free to download, with one in-app product: `premium_unlock` (managed product, one-time, non-consumable). Price is an open decision for Play Console.
