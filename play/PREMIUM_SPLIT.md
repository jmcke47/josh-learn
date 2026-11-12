# Josh Learn — proposed free vs Premium split

This is a proposal, implemented as a single map so it is easy to change after you approve it.

**Edit one place:** `Premium.gated` in `app/src/main/java/com/josh/learn/Premium.kt`.
Every lock, upsell and allowance goes through `Premium.allowed()` / `Premium.canCreatePath()`.
Moving a feature in or out of that set is enough; no other call sites need to change.

Play product id (one-time, non-consumable): **`premium_unlock`**.
Create this exact id in Play Console when the developer account is ready. Price is your decision (not set in the app; the upsell shows Play's price string).

## Free (core learning loop)

These stay free on the Play build:

- Built-in topic catalogue ("Science", "History", "Space", …) — generic, not personal
- Your own topics (name a topic, pick a symbol)
- Lessons (any length / depth), including from a pasted article or a shared link
- 2-minute lessons
- Quizzes and tutor chat
- Flashcards with SM-2 scheduling and the Review tab
- Offline AI (download a model once, then learn without internet)
- System / Light / Dark themes and text size
- One learning path (a short sequence of lessons)
- Privacy: API keys stay on the phone, encrypted

## Premium (one-time unlock)

These are gated on the Play flavor until `premium_unlock` is purchased and acknowledged:

| Feature in the map | What the user gets |
|---|---|
| `CLOUD_AI` | Gemini, Grok, ChatGPT, Claude or a custom endpoint, plus one-tap switching in the toolbar |
| `LEARNING_PATHS` | Unlimited learning paths (free users keep **1**) |
| `THEME_OSRS` | Old School (OSRS) theme |
| `THEME_AMOLED` | AMOLED black theme |
| `CHALLENGE` | 60-second challenge mode |
| `XP_EXTRAS` | XP drops, skill levels and level-up fanfare (XP is still recorded in the background so it appears if they later unlock) |
| `WIDGET` | Home-screen topic widget (without Premium the widget shows an unlock message) |
| `EXPORT` | Export the library as JSON, and import a Grok history or a topics file |

## GitHub sideload flavor (`github`)

Every feature is unlocked. No purchase, no Play Billing connection. Self-updater, `REQUEST_INSTALL_PACKAGES` and the existing `release.jks` signing stay as they are.

## Play flavor when Billing is missing

The app stays free. Settings and the upsell show a short "Purchases aren't available on this device. You need the Play Store." message. There is also a **Restore purchases** button (calls `queryPurchasesAsync`).

A successful purchase is acknowledged and cached in SharedPreferences so Premium keeps working offline. A failed / offline query never clears a cached unlock. A successful query that returns no purchase (refund or not owned) does clear it.

## Changing the split later

1. Edit `Premium.gated` (and `FREE_PATHS` if you want a different free path allowance).
2. Update this file so it still matches.
3. Run the unit tests.

No new activities or billing product ids are required unless you add a second paid product.
