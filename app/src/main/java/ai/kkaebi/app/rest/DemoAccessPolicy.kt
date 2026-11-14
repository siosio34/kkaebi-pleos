package ai.kkaebi.app.rest

/** The demo has no command bridge. This policy only controls access to its local presentation. */
object DemoAccessPolicy {
    const val LIVE_PAGE = "file:///android_asset/web/index.html"
    const val DEMO_PAGE = "file:///android_asset/web/demo/story.html"
    const val LEGACY_DEMO_PAGE = "file:///android_asset/web/demo/index.html"
    const val EXIT_DEMO = "kkaebi://exit-demo"

    fun canEnter(active: Boolean, parked: Boolean, controlRunning: Boolean,
                 alarmState: String, pendingRestore: Boolean, stopUnconfirmed: Boolean) =
        active && parked && !controlRunning && alarmState in setOf("idle", "failed") && !pendingRestore && !stopUnconfirmed

    /** Once interrupted, only leaving and explicitly opening a new demo permits it again. */
    fun interrupted(previously: Boolean, active: Boolean, parked: Boolean) = previously || !active || !parked

    fun isExit(demo: Boolean, mainFrame: Boolean, url: String) = demo && mainFrame && url == EXIT_DEMO

    fun isDemoPage(url: String?) = url == DEMO_PAGE || url == LEGACY_DEMO_PAGE

    /** Local presentation navigation never creates a bridge or resets the interruption latch. */
    fun canNavigateDemo(demo: Boolean, mainFrame: Boolean, interactiveAllowed: Boolean, url: String?) =
        demo && mainFrame && interactiveAllowed && isDemoPage(url)

    fun allowsAsset(demo: Boolean, scheme: String?, authority: String?, path: String?): Boolean {
        if (scheme != "file" || !authority.isNullOrEmpty() || path == null || path.contains("..") ||
            path.contains('\\') || path.contains('%')) return false
        if (!path.startsWith("/android_asset/web/") && !path.startsWith("/android_asset/character/")) return false
        val allowedHtml = if (demo) setOf("/android_asset/web/demo/story.html", "/android_asset/web/demo/index.html")
            else setOf("/android_asset/web/index.html")
        return !path.endsWith(".html", ignoreCase = true) || path in allowedHtml
    }
}
