package app.instaaddict.runner.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.instaaddict.runner.adb.AdbLink
import app.instaaddict.runner.adb.PairingCenter
import app.instaaddict.runner.core.Prefs
import app.instaaddict.runner.ui2.UiServer
import kotlinx.coroutines.launch

private fun openDevSettings(ctx: Context) {
    try {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}

private fun notificationsAllowed(ctx: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun instagramInstalled(ctx: Context): Boolean = try {
    ctx.packageManager.getPackageInfo("com.instagram.android", 0)
    true
} catch (_: Exception) {
    false
}

private fun batteryFree(ctx: Context): Boolean = try {
    (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
} catch (_: Exception) {
    false
}

@Composable
fun SetupScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected by AdbLink.connected.collectAsState()
    val helper by UiServer.status.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var showOptions by remember { mutableStateOf(false) }
    var helperBusy by remember { mutableStateOf(false) }

    // re-check things when the user comes back from a system screen
    val activity = ctx as? ComponentActivity
    DisposableEffect(activity) {
        val obs = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                tick++
            }
        }
        activity?.lifecycle?.addObserver(obs)
        onDispose { activity?.lifecycle?.removeObserver(obs) }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    @Suppress("UNUSED_EXPRESSION")
    tick
    val igOk = instagramInstalled(ctx)
    val batteryOk = batteryFree(ctx)
    val notifOk = notificationsAllowed(ctx)
    val helperReady = helper is UiServer.Status.Ready

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Setup", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Do these once. Tasks check them again every time you tap Start.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SetupItem(
            title = "1. Phone connection",
            ok = connected,
            okText = "Connected (${Prefs.mode})",
            badText = "Not connected yet",
        ) {
            if (!connected) {
                Text("I need a one-time permission from your phone. Choose one of two ways.", fontSize = 14.sp)
                SoftButton(if (showOptions) "Hide the two ways" else "Show the two ways", onClick = { showOptions = !showOptions })
                if (showOptions) ConnectionOptions(onConnected = { tick++ })
            } else {
                SoftButton("Disconnect", onClick = { AdbLink.disconnect() })
            }
        }

        SetupItem(
            title = "2. Helper tools",
            ok = helperReady,
            okText = "Ready",
            badText = when (val h = helper) {
                is UiServer.Status.Working -> h.message
                is UiServer.Status.Failed -> h.message
                else -> "Not installed or not running yet"
            },
        ) {
            Text("Small helper packages (the same ones InstaAddict uses) let me tap and scroll. They install silently.", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("Install / start", enabled = connected && !helperBusy, onClick = {
                    helperBusy = true
                    scope.launch {
                        UiServer.ensureReady(ctx.applicationContext)
                        helperBusy = false
                    }
                })
                SoftButton("Repair", enabled = connected && !helperBusy, onClick = {
                    helperBusy = true
                    scope.launch {
                        UiServer.repair(ctx.applicationContext)
                        helperBusy = false
                    }
                })
            }
            if (!connected) Text("Connect your phone first (step 1).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SetupItem(
            title = "3. Instagram",
            ok = igOk,
            okText = "Installed",
            badText = "Not installed",
        ) {
            Text("Log in with your spare account and set the Instagram language to English (the task looks for English words).", fontSize = 13.sp)
        }

        SetupItem(
            title = "4. Keep running in the background",
            ok = batteryOk,
            okText = "Allowed",
            badText = "Phone may stop the app",
        ) {
            if (!batteryOk) {
                SoftButton("Allow", onClick = {
                    try {
                        ctx.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                        try {
                            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {
                        }
                    }
                })
            }
        }

        SetupItem(
            title = "5. Notifications",
            ok = notifOk,
            okText = "Allowed",
            badText = "Needed for the pairing code box and the running notice",
        ) {
            if (!notifOk && Build.VERSION.SDK_INT >= 33) {
                SoftButton("Allow notifications", onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) })
            }
        }
    }
}

@Composable
private fun SetupItem(
    title: String,
    ok: Boolean,
    okText: String,
    badText: String,
    body: @Composable () -> Unit,
) {
    AppCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            if (ok) Pill("✓ Done", OkColor) else Pill("To do", WarnColor)
        }
        Text(if (ok) okText else badText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        body()
    }
}

/** The two ways to give the app its one-time permission. Used in Setup and in the pre-task dialog. */
@Composable
fun ConnectionOptions(onConnected: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pairState by PairingCenter.state.collectAsState()
    var cableMsg by remember { mutableStateOf<String?>(null) }
    var cableBusy by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Text("Pick ONE of these:", fontWeight = FontWeight.Bold, fontSize = 16.sp)

    // ---- Option 1 ----
    AppCard(container = MaterialTheme.colorScheme.primaryContainer) {
        Text("Option 1: Wireless pairing (no laptop)", fontWeight = FontWeight.Bold)
        Text("Needs Android 11 or newer and Wi-Fi turned on (it does not need internet).", fontSize = 13.sp)
        StepRow(1, "Tap 'Open Developer options'. If you do not see it: Settings > About phone > tap Build number 7 times.")
        StepRow(2, "Turn ON 'Wireless debugging'. Allow it on your Wi-Fi if asked.")
        StepRow(3, "Tap 'Pair device with pairing code'. Leave that window open.")
        StepRow(4, "Pull down the notification bar, tap 'Enter code' and type the 6 digits.")
        SoftButton("Open Developer options", onClick = { openDevSettings(ctx) }, modifier = Modifier.fillMaxWidth())
        BigButton("Start pairing", onClick = {
            if (!notificationsAllowed(ctx) && Build.VERSION.SDK_INT >= 33) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            PairingCenter.start(ctx.applicationContext)
        })
        val msg = when (val s = pairState) {
            is PairingCenter.State.Idle -> null
            is PairingCenter.State.Searching -> "Looking for the pairing window… open 'Pair device with pairing code' now."
            is PairingCenter.State.Found -> "Found it! Pull down the notification bar and tap 'Enter code' (or type the code below)."
            is PairingCenter.State.Pairing -> "Pairing…"
            is PairingCenter.State.Paired -> "Paired! Connecting…"
            is PairingCenter.State.Failed -> s.message
        }
        if (msg != null) Text(msg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (pairState is PairingCenter.State.Found) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("6-digit code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SoftButton("Pair with this code", enabled = code.length == 6, onClick = {
                PairingCenter.submit(ctx.applicationContext, code) { onConnected() }
            })
        }
    }

    // ---- Option 2 ----
    AppCard(container = MaterialTheme.colorScheme.secondaryContainer) {
        Text("Option 2: Cable, one time (needs a laptop)", fontWeight = FontWeight.Bold)
        Text("Works on every Android phone. Lasts until the phone restarts.", fontSize = 13.sp)
        StepRow(1, "Turn on Developer options and USB debugging (Settings > Developer options).")
        StepRow(2, "Plug the phone into your laptop and tap Allow on 'Allow USB debugging?'.")
        StepRow(3, "On the laptop, open a terminal in the platform-tools folder and run:")
        CommandBox("adb tcpip 5555", onCopy = { copyText(ctx, "command", "adb tcpip 5555") })
        StepRow(4, "Unplug the cable, come back here and tap 'Connect now'. When the phone asks 'Allow USB debugging?' tick 'Always allow' and tap Allow.")
        BigButton(if (cableBusy) "Connecting…" else "Connect now", enabled = !cableBusy, onClick = {
            cableBusy = true
            cableMsg = "Connecting… watch your phone for an 'Allow USB debugging?' box."
            Prefs.mode = "cable"
            scope.launch {
                when (val r = AdbLink.ensureConnected(interactive = true)) {
                    is AdbLink.LinkResult.Connected -> {
                        cableMsg = "Connected!"
                        onConnected()
                    }
                    is AdbLink.LinkResult.Failed -> {
                        if (Prefs.mode == "cable" && !Prefs.paired) Prefs.mode = "none"
                        cableMsg = when (r.issue) {
                            AdbLink.Issue.CABLE_NOT_READY ->
                                "Nothing answered on port 5555. Plug the phone in and run 'adb tcpip 5555' on the laptop again, then retry."
                            AdbLink.Issue.DENIED, AdbLink.Issue.NOT_PAIRED ->
                                "The phone did not allow it. Tap Connect now again and press Allow on the phone."
                            AdbLink.Issue.TIMEOUT -> "It took too long. Try again and answer the phone's question quickly."
                            else -> "Could not connect: ${r.detail}"
                        }
                    }
                }
                cableBusy = false
            }
        })
        cableMsg?.let { Text(it, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
    }
}
