package app.instaaddict.runner.bot

import android.content.Context
import app.instaaddict.runner.adb.AdbLink
import app.instaaddict.runner.core.AppLog
import app.instaaddict.runner.ui2.UiServer
import kotlinx.coroutines.delay

enum class ItemState { PENDING, CHECKING, OK, FAILED }

data class PreflightItem(val label: String, val state: ItemState)

enum class IssueKind { CONNECTION, HELPER, INSTAGRAM, SCREEN, OTHER }

data class Issue(
    val kind: IssueKind,
    val title: String,
    val message: String,
    val detail: String = "",
)

/** Runs before every task. Returns null when everything is fine, or the one thing to fix. */
object Preflight {
    private val labels = listOf(
        "Phone connection",
        "Helper tools",
        "Instagram installed",
        "Screen is on",
    )

    suspend fun run(ctx: Context, onUpdate: (List<PreflightItem>) -> Unit): Issue? {
        val states = MutableList(labels.size) { ItemState.PENDING }
        fun push() = onUpdate(labels.mapIndexed { i, l -> PreflightItem(l, states[i]) })

        // 1. connection
        states[0] = ItemState.CHECKING; push()
        when (val r = AdbLink.ensureConnected(false)) {
            is AdbLink.LinkResult.Connected -> states[0] = ItemState.OK
            is AdbLink.LinkResult.Failed -> {
                states[0] = ItemState.FAILED; push()
                AppLog.w("Preflight", "Connection check failed: ${r.issue} ${r.detail}")
                return Issue(
                    IssueKind.CONNECTION,
                    "Let's connect your phone",
                    "Before I can do tasks, I need a one-time permission from your phone. Pick any ONE of the two ways below.",
                    "${r.issue}: ${r.detail}",
                )
            }
        }
        push()

        // 2. helper
        states[1] = ItemState.CHECKING; push()
        val helperErr = UiServer.ensureReady(ctx)
        if (helperErr != null) {
            states[1] = ItemState.FAILED; push()
            return Issue(IssueKind.HELPER, "The helper tools need attention", helperErr, helperErr)
        }
        states[1] = ItemState.OK; push()

        // 3. Instagram
        states[2] = ItemState.CHECKING; push()
        val pk = AdbLink.shell("pm list packages com.instagram.android", 15_000L).out
        if (!pk.contains("package:com.instagram.android")) {
            states[2] = ItemState.FAILED; push()
            return Issue(
                IssueKind.INSTAGRAM,
                "Instagram is not installed",
                "Install Instagram from the Play Store, log in with your spare account, set the language to English, then come back.",
            )
        }
        states[2] = ItemState.OK; push()

        // 4. screen
        states[3] = ItemState.CHECKING; push()
        var awake = isAwake()
        if (!awake) {
            AdbLink.shell("input keyevent KEYCODE_WAKEUP", 8_000L)
            delay(900)
            AdbLink.shell("wm dismiss-keyguard", 8_000L)
            delay(700)
            awake = isAwake()
        }
        if (!awake) {
            states[3] = ItemState.FAILED; push()
            return Issue(
                IssueKind.SCREEN,
                "The screen is off",
                "Turn the screen on and unlock the phone, then tap Try again. The screen has to stay on while a task runs.",
            )
        }
        states[3] = ItemState.OK; push()
        return null
    }

    private suspend fun isAwake(): Boolean {
        val out = AdbLink.shell("dumpsys power | grep mWakefulness=", 10_000L).out
        return out.contains("Awake", ignoreCase = true) || out.isBlank()
    }
}
