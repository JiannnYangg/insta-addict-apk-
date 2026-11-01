package app.instaaddict.runner.bot

import app.instaaddict.runner.adb.AdbLink
import app.instaaddict.runner.core.AppLog
import app.instaaddict.runner.ui2.Hierarchy
import app.instaaddict.runner.ui2.Rpc
import app.instaaddict.runner.ui2.RpcException
import app.instaaddict.runner.ui2.UiNode
import app.instaaddict.runner.ui2.UiServer
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

data class LikeTarget(val node: UiNode, val name: String, val key: String, val isAd: Boolean)

/**
 * Building blocks for Instagram tasks. The element ids and the "open with am start" method are the
 * same ones InstaAddict uses (see views.py / resources.py in InstaAddict).
 */
class Ig(private val ctx: RunContext) {
    companion object {
        const val PKG = "com.instagram.android"
        private val BLOCK_WORDS = Regex(
            "(try again later|action blocked|we limit how often|temporarily blocked|suspicious activity)",
            RegexOption.IGNORE_CASE,
        )
    }

    private var screenW = 1080
    private var screenH = 2400
    private var relaunches = 0

    // ---- reading the screen -------------------------------------------------------------------

    suspend fun readScreenSize() {
        try {
            val info = Rpc.call("deviceInfo") as? JSONObject
            if (info != null) {
                screenW = info.optInt("displayWidth", screenW)
                screenH = info.optInt("displayHeight", screenH)
            }
            AppLog.d("Ig", "Screen is ${screenW}x$screenH")
        } catch (e: RpcException) {
            AppLog.w("Ig", "Could not read the screen size, using defaults: ${e.message}")
        }
    }

    suspend fun dump(): List<UiNode> {
        var lastError = "no answer"
        for (attempt in 1..3) {
            ctx.checkCancelled()
            try {
                val xml = Rpc.call("dumpWindowHierarchy", listOf(false, null), 40_000) as? String
                if (!xml.isNullOrBlank()) return Hierarchy.parse(xml)
                lastError = "empty screen dump"
            } catch (e: RpcException) {
                lastError = e.message ?: "helper error"
                AppLog.w("Ig", "Screen read failed (attempt $attempt): $lastError")
                if (e.connection) {
                    val err = UiServer.ensureReady(ctx.app)
                    if (err != null) ctx.fail(err)
                }
            }
            delay(1000)
        }
        ctx.fail("I could not read the screen ($lastError). Open the Logs tab, then try again.")
    }

    // ---- opening Instagram --------------------------------------------------------------------

    private suspend fun launch() {
        val resolved = AdbLink.shell("cmd package resolve-activity --brief $PKG", 15_000L).out
            .lines().map { it.trim() }
            .lastOrNull { it.contains("/") && !it.contains(" ") }
        val r = if (resolved != null) {
            // The same launch InstaAddict needed on this phone: am start with the real activity name.
            AdbLink.shell(
                "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $resolved",
                20_000L,
            )
        } else {
            AdbLink.shell("monkey -p $PKG -c android.intent.category.LAUNCHER 1", 30_000L)
        }
        AppLog.d("Ig", "launch via ${resolved ?: "monkey"}: ${r.out.trim().take(160)}")
    }

    private suspend fun waitForeground(timeoutMs: Long): List<UiNode> {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val nodes = dump()
            if (nodes.firstOrNull()?.pkg == PKG) return nodes
            if (System.currentTimeMillis() > end) {
                ctx.fail("Instagram did not come to the front. Unlock the phone and make sure Instagram opens normally.")
            }
            ctx.pause(1200, 1800)
        }
    }

    suspend fun openAndGoHome(): List<UiNode> {
        readScreenSize()
        ctx.step("Opening Instagram")
        launch()
        waitForeground(25_000L)
        ctx.pause(1500, 2500)
        var nodes = dump()
        if (looksLoggedOut(nodes)) {
            ctx.fail("Instagram is not logged in. Log in with your spare account first, then try again.")
        }
        val home = findHomeTab(nodes)
            ?: ctx.fail(
                "I could not find Instagram's Home tab. Make sure Instagram is set to English, " +
                    "you are logged in, and the normal feed is showing."
            )
        ctx.step("Going to the Home feed")
        tap(home)
        ctx.pause(2000, 3500)
        nodes = dump()
        return nodes
    }

    private fun findHomeTab(nodes: List<UiNode>): UiNode? =
        nodes.firstOrNull { it.desc.trim().startsWith("Home", ignoreCase = true) && it.cy > screenH * 0.8 }

    private fun looksLoggedOut(nodes: List<UiNode>): Boolean {
        val texts = nodes.map { it.text.trim().lowercase() }
        val loginScreen = texts.contains("log in") || texts.contains("create new account")
        return loginScreen && findHomeTab(nodes) == null
    }

    // ---- safety -------------------------------------------------------------------------------

    /** Returns the warning text if Instagram is showing a limit / block message. */
    fun blockMessage(nodes: List<UiNode>): String? {
        if (nodes.any { it.shortId == "row_feed_button_like" }) return null
        for (n in nodes) {
            val t = (n.text + " " + n.desc).trim()
            if (t.isNotEmpty() && BLOCK_WORDS.containsMatchIn(t)) return t.take(120)
        }
        return null
    }

    suspend fun dismissPopup(nodes: List<UiNode>): Boolean {
        val b = nodes.firstOrNull {
            it.clickable && (it.text.equals("Not now", ignoreCase = true))
        } ?: return false
        AppLog.i("Ig", "Dismissing a popup (\"${b.text}\")")
        tap(b)
        return true
    }

    /** If Instagram is not in front (crash, another app, notification shade), bring it back. */
    suspend fun recoverIfNeeded(nodes: List<UiNode>): Boolean {
        val pkg = nodes.firstOrNull()?.pkg ?: ""
        if (pkg == PKG) return false
        if (relaunches >= 3) {
            ctx.fail("Instagram keeps closing, or another app keeps taking over. I stopped.")
        }
        relaunches++
        AppLog.w("Ig", "Instagram is not in front (now: ${pkg.ifEmpty { "unknown" }}). Reopening $relaunches/3")
        if (pkg == "com.android.systemui") {
            try {
                Rpc.call("pressKey", listOf("back"))
            } catch (_: RpcException) {
            }
        }
        launch()
        ctx.pause(3000, 4500)
        return true
    }

    // ---- the feed -----------------------------------------------------------------------------

    private fun actionBarBottom(nodes: List<UiNode>): Int =
        nodes.firstOrNull { it.shortId == "action_bar_container" }?.bottom ?: (screenH * 0.1).toInt()

    private fun tabBarTop(nodes: List<UiNode>): Int =
        nodes.firstOrNull { it.shortId == "tab_bar" }?.top ?: (screenH * 0.92).toInt()

    /** Posts on screen whose heart is fully visible and not yet pressed, top-most first. */
    fun likeTargets(nodes: List<UiNode>): List<LikeTarget> {
        val safeTop = max(actionBarBottom(nodes), (screenH * 0.07).toInt())
        val safeBottom = tabBarTop(nodes)
        val headers = nodes.filter { it.shortId == "row_feed_photo_profile_name" }
        val sponsored = nodes.filter { it.shortId == "secondary_label" && it.text.trim().equals("Sponsored", ignoreCase = true) }
        return nodes
            .filter {
                it.shortId == "row_feed_button_like" &&
                    it.desc.trim().equals("Like", ignoreCase = true) &&
                    it.top >= safeTop && it.bottom <= safeBottom
            }
            .sortedBy { it.top }
            .map { button ->
                val header = headers.filter { it.bottom <= button.top }.maxByOrNull { it.bottom }
                val name = header?.text?.trim().orEmpty()
                val isAd = header != null && sponsored.any { abs(it.cy - header.cy) < 140 }
                val key = if (name.isNotEmpty()) name else "pos${button.top / 40}"
                LikeTarget(button, name.ifEmpty { "a post" }, key, isAd)
            }
    }

    /** After tapping a heart: is a "Liked" heart now showing in the same spot? */
    fun isLiked(after: List<UiNode>, tapped: UiNode): Boolean =
        after.any {
            it.shortId == "row_feed_button_like" &&
                it.desc.trim().equals("Liked", ignoreCase = true) &&
                abs(it.top - tapped.top) < 160
        }

    suspend fun tap(n: UiNode) {
        val jx = if (n.width > 40) Random.nextInt(-n.width / 6, n.width / 6 + 1) else 0
        val jy = if (n.height > 40) Random.nextInt(-n.height / 6, n.height / 6 + 1) else 0
        Rpc.call("click", listOf(n.cx + jx, n.cy + jy))
    }

    /** A slow, slightly uneven swipe up, like a thumb. */
    suspend fun scrollFeed() {
        val x = (screenW * (0.45 + Random.nextDouble() * 0.12)).toInt()
        val y1 = (screenH * (0.68 + Random.nextDouble() * 0.08)).toInt()
        val dist = (screenH * (0.28 + Random.nextDouble() * 0.12)).toInt()
        val steps = Random.nextInt(80, 141)
        Rpc.call("swipe", listOf(x, y1, x + Random.nextInt(-25, 26), y1 - dist, steps))
    }

    fun signature(nodes: List<UiNode>): Int {
        var h = 17
        for (n in nodes) h = h * 31 + n.id.hashCode() + n.text.hashCode() * 7 + n.top
        return h
    }
}
