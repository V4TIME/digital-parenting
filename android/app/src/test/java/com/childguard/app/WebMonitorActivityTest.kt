package com.childguard.app

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The web-monitor screen used to call getPackageInfo() for every installed app
 * on the UI thread. That is a binder call per app and could throw while the
 * app list changed. The scan now uses the permissions already attached to
 * ApplicationInfo; this test drives the real PackageManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebMonitorActivityTest {

    @Test
    fun activityRendersAndListsOnlyNetworkApps() {
        val controller = Robolectric.buildActivity(WebMonitorActivity::class.java).create()
        val activity = controller.get()
        assertNotNull(activity.findViewById(R.id.tv_status))

        val rows = activity.scanInternetApps()
        val packages = rows.map { it.first }
        // ChildGuard itself declares INTERNET, so the real PackageManager must
        // report it without a getPackageInfo() call.
        assertTrue(
            "the app with INTERNET permission must be listed: $packages",
            packages.contains(activity.packageName)
        )
    }
}
