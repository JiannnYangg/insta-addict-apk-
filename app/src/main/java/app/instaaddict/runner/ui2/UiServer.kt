package app.instaaddict.runner.ui2

import android.content.Context
import app.instaaddict.runner.adb.AdbLink
import app.instaaddict.runner.core.AppLog
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The "helper tools": the same two small packages InstaAddict's uiautomator2 installs
 * (com.github.uiautomator and its test package). This object installs them through the in-app adb
 * link and starts the server, so nothing has to be done in Termux.
 */
object UiServer {
    private const val PKG_APP = "com.github.uiautomator"
    private const val PKG_TEST = "com.github.uiautomator.test"
    private const val RUNNER = "androidx.test.runner.AndroidJUnitRunner"

    sealed class Status {
        object Unknown : Status()
        data class Working(val message: String) : Status()
        object Ready : Status()
        data class Failed(val message: String) : Status()
    }

    val status = MutableStateFlow<Status>(Status.Unknown)

    private val startLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var holder: AdbStream? = null

    /** Returns null when the helper is ready, or a plain-language reason when it is not. */
    suspend fun ensureReady(ctx: Context): String? = startLock.withLock {
        try {
            if (Rpc.ping()) {
                status.value = Status.Ready
                return@withLock null
            }
            status.value = Status.Working("Checking helper tools...")
            val (hasApp, hasTest) = installedState()
            if (!hasApp || !hasTest) {
                val err = install(ctx)
                if (err != null) {
                    status.value = Status.Failed(err)
                    return@withLock err
                }
            }
            status.value = Status.Working("Starting helper...")
            val err = start()
            if (err != null) {
                status.value = Status.Failed(err)
                return@withLock err
            }
            status.value = Status.Ready
            null
        } catch (e: Exception) {
            AppLog.e("Helper", "Unexpected helper error", e)
            val msg = "Helper error: ${e.message ?: e.javaClass.simpleName}"
            status.value = Status.Failed(msg)
            msg
        }
    }

    /** Removes and reinstalls the helper (the "Repair" button). */
    suspend fun repair(ctx: Context): String? {
        status.value = Status.Working("Removing old helper...")
        stop()
        AdbLink.shell("pm uninstall $PKG_TEST", 30_000L)
        AdbLink.shell("pm uninstall $PKG_APP", 30_000L)
        return ensureReady(ctx)
    }

    fun stop() {
        val s = holder
        holder = null
        if (s != null) runCatching { s.close() }
    }

    private suspend fun installedState(): Pair<Boolean, Boolean> {
        val out = AdbLink.shell("pm list packages $PKG_APP", 15_000L).out
        val lines = out.lines().map { it.trim() }
        return Pair(lines.contains("package:$PKG_APP"), lines.contains("package:$PKG_TEST"))
    }

    private suspend fun install(ctx: Context): String? {
        for (name in listOf("app-uiautomator.apk", "app-uiautomator-test.apk")) {
            status.value = Status.Working("Installing helper ($name)...")
            val bytes = try {
                ctx.assets.open(name).use { it.readBytes() }
            } catch (e: Exception) {
                return "A helper file ($name) is missing inside this app build. Please rebuild the app."
            }
            val r = AdbLink.execWithInput("pm install -r -t -S ${bytes.size}", bytes, 180_000L)
            AppLog.i("Helper", "install $name -> ${r.out.trim().take(300)}")
            if (!r.out.contains("Success")) return explainInstallFailure(r.out)
        }
        return null
    }

    private fun explainInstallFailure(out: String): String {
        val o = out.trim()
        return when {
            o.contains("USER_RESTRICTED", ignoreCase = true) || o.contains("INSTALL_FAILED_USER_RESTRICTED") ->
                "Your phone blocked the silent install. In Developer options turn on 'Install via USB' and " +
                    "'USB debugging (Security settings)' (Xiaomi, Redmi, Poco, Oppo, Vivo, Realme), then try again."
            o.contains("VERIFICATION", ignoreCase = true) ->
                "The phone's install checker stopped the helper. Try again, or turn off 'Verify apps over USB' in Developer options."
            o.contains("INSUFFICIENT_STORAGE", ignoreCase = true) ->
                "Not enough free storage to install the helper. Free up some space and try again."
            else -> "The helper could not be installed: ${o.take(200).ifEmpty { "no answer from the phone" }}"
        }
    }

    private suspend fun start(): String? {
        stop()
        AdbLink.shell("am force-stop $PKG_TEST", 8_000L)
        val cmd = "am instrument -w -r -e debug false -e class com.github.uiautomator.stub.Stub $PKG_TEST/$RUNNER"
        val stream = try {
            withContext(Dispatchers.IO) { AdbLink.openStream("shell:$cmd") }
        } catch (e: Exception) {
            return "Could not start the helper: ${e.message ?: e.javaClass.simpleName}"
        }
        holder = stream
        scope.launch {
            try {
                stream.openInputStream().bufferedReader().forEachLine { AppLog.d("Helper", it.take(200)) }
            } catch (_: Exception) {
            }
            if (holder === stream) {
                holder = null
                AppLog.w("Helper", "The helper process ended")
                if (status.value is Status.Ready) status.value = Status.Unknown
            }
        }
        for (i in 1..30) {
            delay(500)
            if (Rpc.ping()) return null
        }
        return "The helper started but did not answer. Tap Repair in Setup, or check the Logs tab."
    }
}
