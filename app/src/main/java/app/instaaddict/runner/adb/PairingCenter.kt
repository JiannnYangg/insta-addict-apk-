package app.instaaddict.runner.adb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import app.instaaddict.runner.R
import app.instaaddict.runner.core.AppLog
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Wireless-debugging pairing. The pairing code window in Settings closes if you leave it, so the
 * code can also be typed into a notification from this app while that window stays open.
 */
object PairingCenter {
    const val KEY_CODE = "pairing_code"
    const val ACTION_PAIR = "app.instaaddict.runner.PAIR"
    private const val CHANNEL = "pairing"
    private const val NOTIF_ID = 42

    sealed class State {
        object Idle : State()
        object Searching : State()
        data class Found(val port: Int) : State()
        object Pairing : State()
        object Paired : State()
        data class Failed(val message: String) : State()
    }

    val state = MutableStateFlow<State>(State.Idle)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mdns: AdbMdns? = null

    @Volatile
    private var foundPort = -1

    @Volatile
    private var foundHost: String? = null

    /** Starts listening for the "Pair device with pairing code" window of Wireless debugging. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        stopSearch()
        foundPort = -1
        foundHost = null
        state.value = State.Searching
        try {
            val m = AdbMdns(app, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { address, port ->
                if (address != null && port > 0) {
                    foundPort = port
                    foundHost = address.hostAddress
                    AppLog.i("Pairing", "Found pairing window on port $port")
                    state.value = State.Found(port)
                    postCodeNotification(app, "Enter the pairing code", "Type the 6-digit code from Settings here.")
                }
            }
            m.start()
            mdns = m
        } catch (e: Exception) {
            AppLog.e("Pairing", "Could not start searching", e)
            state.value = State.Failed("Could not look for the pairing window: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun stopSearch() {
        runCatching { mdns?.stop() }
        mdns = null
    }

    fun reset() {
        stopSearch()
        state.value = State.Idle
    }

    /** [portOverride] is for typing the port by hand if automatic search does not find it. */
    fun submit(ctx: Context, code: String, portOverride: Int? = null, onDone: () -> Unit = {}) {
        val app = ctx.applicationContext
        val clean = code.filter { it.isDigit() }
        if (clean.length != 6) {
            state.value = State.Failed("The pairing code has 6 digits.")
            onDone()
            return
        }
        val port = portOverride ?: foundPort
        if (port <= 0) {
            state.value = State.Failed(
                "I have not found the pairing window yet. Keep the 'Pair device with pairing code' window open, then tap Start pairing."
            )
            onDone()
            return
        }
        state.value = State.Pairing
        scope.launch {
            try {
                var result = AdbLink.pair("127.0.0.1", port, clean)
                if (result.isFailure) {
                    val h = foundHost
                    if (h != null && h != "127.0.0.1") result = AdbLink.pair(h, port, clean)
                }
                if (result.isSuccess) {
                    state.value = State.Paired
                    postResultNotification(app, "Paired", "Connecting now...")
                    delay(800)
                    AdbLink.ensureConnected(interactive = true)
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "The phone rejected the code."
                    state.value = State.Failed("Pairing did not work: $msg. Check the code and try again.")
                    postResultNotification(app, "Pairing failed", "Open the app and try again.")
                }
            } catch (e: Exception) {
                AppLog.e("Pairing", "Unexpected pairing error", e)
                state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                stopSearch()
                onDone()
            }
        }
    }

    // ---- notifications ------------------------------------------------------------------------

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Pairing", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun postCodeNotification(ctx: Context, title: String, text: String) {
        try {
            ensureChannel(ctx)
            val remoteInput = RemoteInput.Builder(KEY_CODE).setLabel("6-digit pairing code").build()
            val intent = Intent(ctx, PairReceiver::class.java).setAction(ACTION_PAIR)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(ctx, 1, intent, flags)
            val action = NotificationCompat.Action.Builder(R.drawable.ic_notification, "Enter code", pi)
                .addRemoteInput(remoteInput)
                .build()
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(true)
                .addAction(action)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        } catch (e: Exception) {
            AppLog.w("Pairing", "Could not show the code notification: ${e.message}")
        }
    }

    private fun postResultNotification(ctx: Context, title: String, text: String) {
        try {
            ensureChannel(ctx)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        } catch (_: Exception) {
        }
    }
}
