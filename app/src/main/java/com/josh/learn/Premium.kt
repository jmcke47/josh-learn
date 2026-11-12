package com.josh.learn

import android.app.Activity
import android.content.Context

/**
 * Central feature-gate map for the free / premium split (Play flavor).
 *
 * Change [gated] after Josh approves the split in play/PREMIUM_SPLIT.md — every call site
 * goes through [allowed] / [canCreatePath], so moving a feature in or out of [gated] is enough.
 *
 * GitHub sideload builds unlock everything (Josh already has the full app).
 * Play builds unlock only after a one-time `premium_unlock` purchase, cached locally so it works offline.
 */
object Premium {
    /** One-time non-consumable product id, to be created in Play Console. */
    const val PRODUCT_ID = "premium_unlock"

    /** Free users may keep this many learning paths; premium is unlimited. */
    const val FREE_PATHS = 1

    enum class Feature(val title: String, val blurb: String) {
        CLOUD_AI("Cloud AI", "Use Gemini, Grok, ChatGPT or Claude — and switch with one tap in the toolbar"),
        LEARNING_PATHS("Learning paths", "Unlimited multi-lesson paths that build on each other"),
        THEME_OSRS("Old School theme", "The carved wood, parchment and pixel-font look"),
        THEME_AMOLED("AMOLED black", "A true-black theme that saves battery on OLED screens"),
        CHALLENGE("60-second challenge", "A timed quiz with a ticking orb and a personal best"),
        XP_EXTRAS("XP and skills", "XP drops, skill levels and level-up fanfare"),
        WIDGET("Home screen widget", "Your topics as tiles you can tap from the home screen"),
        EXPORT("Export and import", "Export your library, or import a Grok history / topics file"),
    }

    /**
     * Features that require Premium on the Play flavor. This is the one map to edit.
     * Everything not listed here stays free (lessons, quizzes, flashcards, offline AI, your own topics, …).
     */
    val gated: Set<Feature> = setOf(
        Feature.CLOUD_AI,
        Feature.LEARNING_PATHS,
        Feature.THEME_OSRS,
        Feature.THEME_AMOLED,
        Feature.CHALLENGE,
        Feature.XP_EXTRAS,
        Feature.WIDGET,
        Feature.EXPORT,
    )

    /** Test hooks (null = use the real flavor / entitlement). */
    @Volatile var testPlayDistribution: Boolean? = null
    @Volatile var testUnlocked: Boolean? = null
    fun resetTestHooks() { testPlayDistribution = null; testUnlocked = null }

    val playDistribution: Boolean get() = testPlayDistribution ?: BuildConfig.PLAY_DISTRIBUTION

    fun unlocked(c: Context): Boolean = testUnlocked ?: when {
        !playDistribution -> true
        else -> Entitlements.hasPremium(c)
    }

    fun allowed(c: Context, feature: Feature): Boolean = unlocked(c) || feature !in gated

    /** True if the user may create another learning path (free allowance or premium / ungated). */
    fun canCreatePath(c: Context): Boolean = allowed(c, Feature.LEARNING_PATHS) || Repo.paths.size < FREE_PATHS

    fun lockLabel(c: Context, feature: Feature, label: String): String =
        if (allowed(c, feature)) label else "🔒 $label"
}

/** SharedPreferences cache of the Play purchase. Survives offline and process death. Never cleared on a failed query. */
object Entitlements {
    private const val PREFS = "billing"
    private const val KEY_PREMIUM = "premium_unlock"
    private const val KEY_PENDING = "premium_pending"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasPremium(c: Context) = prefs(c).getBoolean(KEY_PREMIUM, false)
    fun isPending(c: Context) = prefs(c).getBoolean(KEY_PENDING, false) && !hasPremium(c)

    fun setPremium(c: Context, on: Boolean) = prefs(c).edit()
        .putBoolean(KEY_PREMIUM, on).putBoolean(KEY_PENDING, if (on) false else prefs(c).getBoolean(KEY_PENDING, false)).apply()

    fun setPending(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_PENDING, on).apply()

    fun clearForTests(c: Context) = prefs(c).edit().clear().commit()
}

/** Open the upsell (or run [onAllowed]) from any screen. */
fun Activity.requirePremium(feature: Premium.Feature, onAllowed: () -> Unit) {
    if (Premium.allowed(this, feature)) onAllowed()
    else PremiumActivity.show(this, feature)
}

fun Activity.requirePathSlot(onAllowed: () -> Unit) {
    if (Premium.canCreatePath(this)) onAllowed()
    else PremiumActivity.show(this, Premium.Feature.LEARNING_PATHS)
}
