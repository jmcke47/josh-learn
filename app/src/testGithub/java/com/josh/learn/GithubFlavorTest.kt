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
class GithubFlavorTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun isNotPlayDistribution() {
        assertFalse(BuildConfig.PLAY_DISTRIBUTION)
        assertTrue(BuildConfig.UPDATE_MANIFEST_URL.isNotBlank())
    }

    @Test fun everythingUnlockedWithoutPurchase() {
        Entitlements.clearForTests(ctx)
        Premium.resetTestHooks()
        assertTrue(Premium.unlocked(ctx))
        Premium.Feature.entries.forEach { assertTrue(it.name, Premium.allowed(ctx, it)) }
    }

    @Test fun stillRequestsInstallPackages() {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(pi.requestedPermissions.orEmpty().contains("android.permission.REQUEST_INSTALL_PACKAGES"))
    }
}
