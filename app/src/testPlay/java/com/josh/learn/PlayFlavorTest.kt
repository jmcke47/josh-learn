package com.josh.learn

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = LearnApp::class)
class PlayFlavorTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun isPlayDistribution() {
        assertTrue(BuildConfig.PLAY_DISTRIBUTION)
        assertTrue(BuildConfig.UPDATE_MANIFEST_URL.isEmpty())
        assertEquals("com.josh.learn", ctx.packageName)
    }

    @Test fun noRequestInstallPackages() {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        assertFalse(pi.requestedPermissions.orEmpty().contains("android.permission.REQUEST_INSTALL_PACKAGES"))
    }

    @Test fun startsLockedUntilCachedPurchase() {
        Entitlements.clearForTests(ctx)
        Premium.resetTestHooks()
        assertFalse(Premium.unlocked(ctx))
        Entitlements.setPremium(ctx, true)
        assertTrue(Premium.unlocked(ctx))
    }
}
