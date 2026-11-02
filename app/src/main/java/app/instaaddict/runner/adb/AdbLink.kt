package app.instaaddict.runner.adb

import android.content.Context
import android.os.Build
import app.instaaddict.runner.core.AppLog
import app.instaaddict.runner.core.Prefs
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone talks to its own debug service (adbd) from inside this app. Two ways to get the
 * permission, both without root:
 *   - "wireless": pair once with Wireless debugging (Android 11+, needs Wi-Fi). After pairing the
 *     app finds the changing port by itself (or you type it once).
 *   - "cable": run `adb tcpip 5555` from a laptop once; the app then connects to 127.0.0.1:5555
 *     until the phone restarts.
 */
object AdbLink {
    private lateinit var ctx: Context
    private lateinit var keys: AdbKeys
    private lateinit var manager: AbsAdbConnectionManager

    private val connectLock = Mutex()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    data class ShellResult(val ok: Boolean, val out: String)

    enum class Issue { NOT_SET_UP, NOT_PAIRED, CABLE_NOT_READY, WIRELESS_NOT_FOUND, DENIED, TIMEOUT, OTHER }

    sealed class LinkResult {
        object Connected : LinkResult()
        data class Failed(val issue: Issue, val detail: String) : LinkResult()
    }

    fun init(context: Context) {
        ctx = context.applicationContext
        keys = AdbKeys(ctx)
        manager = object : AbsAdbConnectionManager() {
            init {
                setApi(Build.VERSION.SDK_INT)
            }

            override fun getPrivateKey(): PrivateKey = keys.privateKey()
            override fun getCertificate(): Certificate = keys.certificate()
            override fun getDeviceName(): String = "InstaAddict"
        }
    }

    private fun linkPrefs() = ctx.getSharedPreferences("link", Context.MODE_PRIVATE)

    private var wirelessPort: Int
        get() = linkPrefs().getInt("wireless_port", 0)
        set(v) {
            linkPrefs().edit().putInt("wireless_port", v).apply()
        }

    // ---- connecting ---------------------------------------------------------------------------

    /** Returns quickly if the link is already alive; otherwise tries to bring it back. */
    suspend fun ensureConnected(interactive: Boolean = false): LinkResult = connectLock.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (isAlive()) {
                    _connected.value = true
                    LinkResult.Connected
                } else {
                    _connected.value = false
                    runCatching { manager.disconnect() }
                    connectOnce(interactive)
                }
            } catch (e: Exception) {
                AppLog.e("Link", "Unexpected connect error", e)
                LinkResult.Failed(Issue.OTHER, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Wireless debugging shows its connect port on its main screen ("IP address & Port").
     * Typing that number is the fallback when automatic search cannot find it.
     */
    suspend fun connectToPort(port: Int): LinkResult = connectLock.withLock {
        withContext(Dispatchers.IO) {
            try {
                runCatching { manager.disconnect() }
                manager.setTimeout(15L, TimeUnit.SECONDS)
                val ok = manager.connect("127.0.0.1", port)
                if (ok || manager.isConnected) {
                    wirelessPort = port
                    Prefs.mode = "wireless"
                    _connected.value = true
                    AppLog.i("Link", "Connected on port $port")
                    LinkResult.Connected
                } else {
                    LinkResult.Failed(Issue.DENIED, "The phone did not accept the connection")
                }
            } catch (e: AdbPairingRequiredException) {
                LinkResult.Failed(Issue.NOT_PAIRED, e.message ?: "pairing required")
            } catch (e: Exception) {
                AppLog.w("Link", "Connect on port $port failed: ${e.javaClass.simpleName}: ${e.message}")
                LinkResult.Failed(Issue.OTHER, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun connectOnce(interactive: Boolean): LinkResult {
        val first = if (Prefs.mode == "wireless") "wireless" else "cable"
        val order = listOf(first, if (first == "cable") "wireless" else "cable")
        var firstFailure: LinkResult.Failed? = null
        for (m in order) {
            val failure = if (m == "cable") tryCable(interactive) else tryWireless()
            if (failure == null) {
                Prefs.mode = m
                _connected.value = true
                AppLog.i("Link", "Connected using $m mode")
                return LinkResult.Connected
            }
            AppLog.w("Link", "$m attempt failed: ${failure.issue} - ${failure.detail}")
            if (firstFailure == null) firstFailure = failure
        }
        return if (Prefs.mode == "none") {
            LinkResult.Failed(Issue.NOT_SET_UP, "No connection has been set up yet")
        } else {
            firstFailure ?: LinkResult.Failed(Issue.OTHER, "Unknown")
        }
    }

    private fun tryCable(interactive: Boolean): LinkResult.Failed? {
        return try {
            manager.setTimeout(if (interactive) 45L else 8L, TimeUnit.SECONDS)
            manager.setHostAddress("127.0.0.1")
            val ok = manager.connect("127.0.0.1", Prefs.cablePort)
            if (ok || manager.isConnected) null
            else LinkResult.Failed(Issue.DENIED, "The phone did not accept the connection")
        } catch (e: AdbPairingRequiredException) {
            LinkResult.Failed(Issue.NOT_PAIRED, e.message ?: "pairing required")
        } catch (e: IOException) {
            LinkResult.Failed(Issue.CABLE_NOT_READY, e.message ?: "connection refused")
        } catch (e: InterruptedException) {
            LinkResult.Failed(Issue.TIMEOUT, e.message ?: "timed out")
        } catch (e: Exception) {
            LinkResult.Failed(Issue.OTHER, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun tryWireless(): LinkResult.Failed? {
        // 1. the port that worked last time (it only changes when Wireless debugging is toggled)
        val saved = wirelessPort
        if (saved > 0) {
            try {
                manager.setTimeout(6L, TimeUnit.SECONDS)
                if (manager.connect("127.0.0.1", saved) || manager.isConnected) return null
            } catch (e: Exception) {
                AppLog.d("Link", "Saved port $saved did not work: ${e.message}")
            }
        }
        // 2. search for it on the network
        return try {
            manager.setTimeout(10L, TimeUnit.SECONDS)
            val searchMs = if (Prefs.mode == "none") 4000L else 7000L
            val ok = manager.autoConnect(ctx, searchMs)
            if (ok || manager.isConnected) null
            else LinkResult.Failed(Issue.DENIED, "The phone did not accept the connection")
        } catch (e: AdbPairingRequiredException) {
            LinkResult.Failed(Issue.NOT_PAIRED, e.message ?: "pairing required")
        } catch (e: InterruptedException) {
            LinkResult.Failed(Issue.WIRELESS_NOT_FOUND, e.message ?: "not found")
        } catch (e: IOException) {
            LinkResult.Failed(Issue.WIRELESS_NOT_FOUND, e.message ?: "not found")
        } catch (e: Exception) {
            LinkResult.Failed(Issue.OTHER, e.message ?: e.javaClass.simpleName)
        }
    }

    fun disconnect() {
        runCatching { manager.disconnect() }
        _connected.value = false
    }

    private suspend fun isAlive(): Boolean {
        if (!manager.isConnected) return false
        val r = runStream("shell:echo ok", 4000L, null)
        return r.ok && r.out.trim() == "ok"
    }

    suspend fun pair(host: String, port: Int, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val ok = manager.pair(host, port, code)
            if (ok) {
                Prefs.paired = true
                Prefs.mode = "wireless"
                AppLog.i("Link", "Pairing succeeded")
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("The phone rejected the pairing code"))
            }
        } catch (e: Exception) {
            AppLog.w("Link", "Pairing failed: ${e.javaClass.simpleName}: ${e.message}")
            Result.failure(e)
        }
    }

    // ---- running commands on the phone --------------------------------------------------------

    suspend fun shell(cmd: String, timeoutMs: Long = 20_000L): ShellResult =
        runStream("shell:$cmd", timeoutMs, null)

    suspend fun execWithInput(cmd: String, input: ByteArray, timeoutMs: Long): ShellResult =
        runStream("exec:$cmd", timeoutMs, input)

    /** Opens a long-running stream (used to keep the helper process alive). Call from a background thread. */
    fun openStream(destination: String): AdbStream = manager.openStream(destination)

    private suspend fun runStream(dest: String, timeoutMs: Long, stdin: ByteArray?): ShellResult =
        withContext(Dispatchers.IO) {
            val stream = try {
                manager.openStream(dest)
            } catch (e: Exception) {
                return@withContext ShellResult(false, "open failed: ${e.message ?: e.javaClass.simpleName}")
            }
            val timedOut = AtomicBoolean(false)
            val watchdog = launch {
                delay(timeoutMs)
                timedOut.set(true)
                runCatching { stream.close() }
            }
            // Read what the phone prints bit by bit. When the phone finishes a command it closes the
            // stream, and the library reports that as a "Stream closed" error. That is the NORMAL
            // end of a command, so we keep everything read so far instead of throwing it away.
            val collected = ByteArrayOutputStream()
            try {
                if (stdin != null) {
                    val os = stream.openOutputStream()
                    os.write(stdin)
                    os.flush()
                }
                val input = stream.openInputStream()
                val buf = ByteArray(4096)
                try {
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        collected.write(buf, 0, n)
                    }
                } catch (e: IOException) {
                    val closedNormally = !timedOut.get() &&
                        (e.message ?: "").contains("closed", ignoreCase = true)
                    if (!closedNormally) throw e
                }
                ShellResult(!timedOut.get(), collected.toString("UTF-8"))
            } catch (e: Exception) {
                val why = if (timedOut.get()) "timed out" else (e.message ?: e.javaClass.simpleName)
                val partial = collected.toString("UTF-8")
                ShellResult(false, if (partial.isNotEmpty()) partial else why)
            } finally {
                watchdog.cancel()
                runCatching { stream.close() }
            }
        }
}
