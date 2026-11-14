package ai.kkaebi.app

import ai.kkaebi.app.rest.DemoAccessPolicy
import org.junit.Assert.*
import org.junit.Test

class DemoAccessPolicyTest {
    private fun enter(active: Boolean = true, parked: Boolean = true, running: Boolean = false,
                      alarm: String = "idle", pending: Boolean = false, stopUnknown: Boolean = false) =
        DemoAccessPolicy.canEnter(active, parked, running, alarm, pending, stopUnknown)

    @Test fun onlyKnownSafeInactiveStateAllowsEnteringDemo() {
        assertTrue(enter())
        assertTrue(enter(alarm = "failed"))
        assertFalse(enter(active = false))
        assertFalse(enter(parked = false))
        assertFalse(enter(running = true))
        assertFalse(enter(pending = true))
        assertFalse(enter(stopUnknown = true))
        listOf("scheduled", "ringing", "unknown", "").forEach { assertFalse(enter(alarm = it)) }
    }

    @Test fun interruptionCannotSilentlyResumeWhenParkingOrForegroundReturns() {
        assertFalse(DemoAccessPolicy.interrupted(false, true, true))
        assertTrue(DemoAccessPolicy.interrupted(false, false, true))
        assertTrue(DemoAccessPolicy.interrupted(false, true, false))
        assertTrue(DemoAccessPolicy.interrupted(true, true, true))
    }

    @Test fun exitIsAnExactDemoMainFrameCommandOnly() {
        assertTrue(DemoAccessPolicy.isExit(true, true, "kkaebi://exit-demo"))
        assertFalse(DemoAccessPolicy.isExit(false, true, "kkaebi://exit-demo"))
        assertFalse(DemoAccessPolicy.isExit(true, false, "kkaebi://exit-demo"))
        listOf("kkaebi://exit-demo/", "kkaebi://exit-demo?command=startRest", "kkaebi://applyRest", "https://exit-demo").forEach {
            assertFalse(DemoAccessPolicy.isExit(true, true, it))
        }
    }

    @Test fun eachWebViewCanOnlyLoadItsOwnHtmlEntry() {
        assertTrue(DemoAccessPolicy.allowsAsset(false, "file", "", "/android_asset/web/index.html"))
        assertFalse(DemoAccessPolicy.allowsAsset(false, "file", "", "/android_asset/web/demo/index.html"))
        assertTrue(DemoAccessPolicy.allowsAsset(true, "file", "", "/android_asset/web/demo/index.html"))
        assertFalse(DemoAccessPolicy.allowsAsset(true, "file", "", "/android_asset/web/index.html"))
        assertFalse(DemoAccessPolicy.allowsAsset(true, "file", "", "/android_asset/web/other.html"))
        assertTrue(DemoAccessPolicy.allowsAsset(true, "file", "", "/android_asset/web/scene.js"))
        assertTrue(DemoAccessPolicy.allowsAsset(true, "file", "", "/android_asset/character/hello.webp"))
    }

    @Test fun networkOtherFilesAndAmbiguousPathsAreBlocked() {
        assertFalse(DemoAccessPolicy.allowsAsset(true, "https", "example.com", "/android_asset/web/scene.js"))
        assertFalse(DemoAccessPolicy.allowsAsset(true, "content", null, "/android_asset/web/scene.js"))
        assertFalse(DemoAccessPolicy.allowsAsset(true, "file", "remote", "/android_asset/web/scene.js"))
        listOf("/data/data/secret", "/android_asset/web/../secret", "/android_asset/web/%2e%2e/secret", "/android_asset/web/\\secret").forEach {
            assertFalse(DemoAccessPolicy.allowsAsset(true, "file", "", it))
        }
        assertFalse(DemoAccessPolicy.allowsAsset(true, "file", null, null))
    }
    @Test fun storyIsDefaultAndOnlyTwoExactDemoUrlsAreRecognized() {
        assertEquals("file:///android_asset/web/demo/journey.html", DemoAccessPolicy.DEMO_PAGE)
        assertTrue(DemoAccessPolicy.isDemoPage(DemoAccessPolicy.DEMO_PAGE))
        assertTrue(DemoAccessPolicy.isDemoPage(DemoAccessPolicy.LEGACY_DEMO_PAGE))
        for (url in listOf(null, DemoAccessPolicy.LIVE_PAGE, DemoAccessPolicy.DEMO_PAGE + "?run=1",
                DemoAccessPolicy.DEMO_PAGE + "#start", "file://remote/android_asset/web/demo/journey.html",
                "file:///android_asset/web/demo/../demo/journey.html", "file:///android_asset/web/demo/%6Aourney.html",
                "https://example.com/journey.html", "javascript:startRest()")) assertFalse(DemoAccessPolicy.isDemoPage(url))
    }

    @Test fun betweenDemoPagesRequiresCurrentSafeMainFrameAndCannotResetInterruption() {
        for (url in listOf(DemoAccessPolicy.DEMO_PAGE, DemoAccessPolicy.LEGACY_DEMO_PAGE)) {
            assertTrue(DemoAccessPolicy.canNavigateDemo(true, true, true, url))
            assertFalse(DemoAccessPolicy.canNavigateDemo(false, true, true, url))
            assertFalse(DemoAccessPolicy.canNavigateDemo(true, false, true, url))
            assertFalse(DemoAccessPolicy.canNavigateDemo(true, true, false, url))
            val latched = DemoAccessPolicy.interrupted(true, true, true)
            assertFalse(DemoAccessPolicy.canNavigateDemo(true, true, !latched, url))
        }
        assertFalse(DemoAccessPolicy.canNavigateDemo(true, true, true, DemoAccessPolicy.LIVE_PAGE))
    }

    @Test fun storyAssetAllowanceDoesNotGiveLiveViewAccessOrBroadenOrigins() {
        assertTrue(DemoAccessPolicy.allowsAsset(true, "file", "", "/android_asset/web/demo/journey.html"))
        assertFalse(DemoAccessPolicy.allowsAsset(false, "file", "", "/android_asset/web/demo/journey.html"))
        assertFalse(DemoAccessPolicy.allowsAsset(true, "file", "remote", "/android_asset/web/demo/journey.html"))
        assertFalse(DemoAccessPolicy.allowsAsset(true, "https", "example.com", "/android_asset/web/demo/journey.html"))
        assertFalse(DemoAccessPolicy.allowsAsset(true, "file", "", "/android_asset/web/demo/other.html"))
    }

}
