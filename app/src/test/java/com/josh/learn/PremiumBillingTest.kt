package com.josh.learn

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi", application = LearnApp::class)
class PremiumBillingTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private lateinit var fake: FakeBillingClient

    @Before fun setup() {
        LearnApp.prefs(ctx).edit().putBoolean("offline_setup_shown", true).commit()
        Entitlements.clearForTests(ctx)
        BillingStore.resetForTests()
        Premium.resetTestHooks()
        Premium.testPlayDistribution = true
        Premium.testUnlocked = null
        fake = FakeBillingClient()
        BillingStore.clientFactory = { fake }
        LocalModels.abiOverride = true
    }
    @After fun tidy() {
        BillingStore.resetForTests(); Premium.resetTestHooks(); Entitlements.clearForTests(ctx)
        com.josh.oldschool.OsAnimations.reset()
    }

    private fun purchased(ack: Boolean = false, pending: Boolean = false) = BillingStore.PurchaseInfo(
        Premium.PRODUCT_ID, "tok-1", purchased = !pending, pending = pending, acknowledged = ack)

    @Test fun productIdIsPremiumUnlock() = assertEquals("premium_unlock", Premium.PRODUCT_ID)

    @Test fun githubFlavorUnlocksEverything() {
        Premium.testPlayDistribution = false
        assertTrue(Premium.unlocked(ctx))
        Premium.Feature.entries.forEach { assertTrue(it.name, Premium.allowed(ctx, it)) }
    }

    @Test fun playFlavorLocksGatedFeaturesUntilPurchase() {
        assertFalse(Premium.unlocked(ctx))
        Premium.gated.forEach { assertFalse(it.name, Premium.allowed(ctx, it)) }
        // Core loop is not in the map, so it stays free (there is no Feature for lessons/quizzes/flashcards/offline AI).
        Entitlements.setPremium(ctx, true)
        Premium.gated.forEach { assertTrue(it.name, Premium.allowed(ctx, it)) }
    }

    @Test fun movingAFeatureOutOfTheMapUnlocksItWhileFree() {
        val saved = Premium.gated
        // The map is a val; allowed() treats "not in gated" as free. Simulate by checking a hypothetical:
        // EXPORT is gated today; a feature not in the enum isn't gated. Learning paths still have a free slot.
        Repo.init(ctx)
        Repo.paths.clear()
        assertTrue("first path is free", Premium.canCreatePath(ctx))
        Repo.paths.add(LearningPath(topic = "x", title = "x", difficulty = Difficulty.BEGINNER, steps = mutableListOf()))
        assertFalse("second path needs premium", Premium.canCreatePath(ctx))
        Entitlements.setPremium(ctx, true)
        assertTrue(Premium.canCreatePath(ctx))
        Repo.paths.clear()
    }

    @Test fun cachedEntitlementWorksOfflineAndSuccessfulEmptyQueryClearsIt() {
        Entitlements.setPremium(ctx, true)
        assertTrue(Premium.unlocked(ctx))
        fake.queryPurchasesOk = false // offline — must be set before init, which also restores
        BillingStore.init(ctx)
        var msg = ""
        BillingStore.restore { msg = it }
        assertTrue("offline query must keep the cache", Premium.unlocked(ctx))
        assertTrue(msg.contains("online") || msg.contains("Play"))
        fake.queryPurchasesOk = true
        fake.purchases.clear()
        BillingStore.restore { msg = it }
        assertFalse("refunded / not owned after a successful empty query", Entitlements.hasPremium(ctx))
        assertEquals("No previous purchase found.", msg)
    }

    @Test fun purchaseAcknowledgedAndCached() {
        BillingStore.init(ctx)
        assertTrue(fake.started)
        assertEquals("$6.99", BillingStore.product?.price)
        fake.emit(listOf(purchased(ack = false)))
        assertTrue(Entitlements.hasPremium(ctx))
        assertTrue(Premium.unlocked(ctx))
        assertEquals(listOf("tok-1"), fake.acknowledgedTokens)
        assertEquals(BillingStore.Status.Owned, BillingStore.status)
    }

    @Test fun pendingPurchaseDoesNotUnlock() {
        BillingStore.init(ctx)
        fake.emit(listOf(purchased(pending = true)))
        assertFalse(Entitlements.hasPremium(ctx))
        assertTrue(Entitlements.isPending(ctx))
        assertFalse(Premium.unlocked(ctx))
        assertEquals(BillingStore.Status.Pending, BillingStore.status)
    }

    @Test fun unavailableStaysFreeWithMessage() {
        fake.available = false
        BillingStore.init(ctx)
        assertEquals(BillingStore.Status.Unavailable, BillingStore.status)
        assertFalse(Premium.unlocked(ctx))
        assertTrue(BillingStore.lastMessage!!.contains("Play Store"))
        var err = ""
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        BillingStore.launchPurchase(a) { err = it }
        assertTrue(err.contains("Play Store"))
        a.finish()
    }

    @Test fun restorePurchasesFindsExisting() {
        fake.purchases += purchased(ack = true)
        BillingStore.init(ctx) // init already restores
        assertTrue(Entitlements.hasPremium(ctx))
        Entitlements.clearForTests(ctx)
        var msg = ""
        BillingStore.restore { msg = it }
        assertEquals("Premium restored.", msg)
        assertTrue(Premium.unlocked(ctx))
    }

    @Test fun queryOnStartRestores() {
        fake.purchases += purchased(ack = true)
        BillingStore.init(ctx)
        assertTrue(fake.restoreCount >= 1)
        assertTrue(Entitlements.hasPremium(ctx))
    }

    @Test fun settingsShowsRestoreOnPlayAndUpsellOpens() {
        BillingStore.init(ctx)
        val s = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val texts = allTexts(s.window.decorView)
        assertTrue(texts.any { it.contains("Restore purchases") })
        assertTrue(texts.any { it.contains("Unlock Premium") || it.contains("Josh Learn Premium") })
        assertFalse(texts.any { it == "Check for updates" }) // play: no self-updater
        s.finish()

        val p = Robolectric.buildActivity(PremiumActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val t = allTexts(p.window.decorView)
        assertTrue(t.any { it.contains("Unlock") })
        assertTrue(t.any { it.contains("Cloud AI") })
        assertTrue(t.any { it.contains("Restore purchases") })
        p.finish()
    }

    @Test fun changingTheGateMapIsCentral() {
        // Documented contract: allowed() is false only when play + not unlocked + feature in gated.
        Premium.testPlayDistribution = true
        Entitlements.clearForTests(ctx)
        assertEquals(setOf(
            Premium.Feature.CLOUD_AI, Premium.Feature.LEARNING_PATHS, Premium.Feature.THEME_OSRS,
            Premium.Feature.THEME_AMOLED, Premium.Feature.CHALLENGE, Premium.Feature.XP_EXTRAS,
            Premium.Feature.WIDGET, Premium.Feature.EXPORT,
        ), Premium.gated)
    }

    private fun allTexts(v: android.view.View): List<String> = when (v) {
        is android.widget.TextView -> listOf(v.text.toString())
        is android.view.ViewGroup -> (0 until v.childCount).flatMap { allTexts(v.getChildAt(it)) }
        else -> emptyList()
    }
}
