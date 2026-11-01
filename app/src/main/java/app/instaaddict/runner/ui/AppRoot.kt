package app.instaaddict.runner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.instaaddict.runner.adb.AdbLink
import app.instaaddict.runner.bot.Engine
import app.instaaddict.runner.bot.EngineState
import app.instaaddict.runner.bot.IssueKind
import app.instaaddict.runner.ui2.UiServer
import kotlinx.coroutines.launch

@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val state by Engine.state.collectAsState()
    val connected by AdbLink.connected.collectAsState()

    // When the connection comes alive while the friendly dialog is open, just continue.
    LaunchedEffect(connected, state) {
        val s = state
        if (connected && s is EngineState.Blocked && s.issue.kind == IssueKind.CONNECTION) Engine.retry()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Tasks") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Filled.Star, null) }, label = { Text("Metrics") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Filled.List, null) }, label = { Text("Logs") })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Setup") })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> TasksScreen(onOpenSetup = { tab = 3 })
                1 -> MetricsScreen()
                2 -> LogsScreen()
                else -> SetupScreen()
            }
        }
    }

    when (val s = state) {
        is EngineState.Checking -> CheckingDialog(s)
        is EngineState.Blocked -> BlockedDialog(s)
        else -> {}
    }
}

@Composable
private fun CheckingDialog(s: EngineState.Checking) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Checking your phone…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Making sure everything is ready before ${s.task.title}.", fontSize = 14.sp)
                s.items.forEach { ChecklistRow(it) }
            }
        },
        confirmButton = { TextButton(onClick = { Engine.stop() }) { Text("Cancel") } },
    )
}

@Composable
private fun BlockedDialog(s: EngineState.Blocked) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Dialog(
        onDismissRequest = { Engine.dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 640.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(s.issue.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(s.issue.message, fontSize = 15.sp)
                if (s.items.isNotEmpty()) {
                    AppCard { s.items.forEach { ChecklistRow(it) } }
                }
                when (s.issue.kind) {
                    IssueKind.CONNECTION -> ConnectionOptions(onConnected = { Engine.retry() })
                    IssueKind.HELPER -> {
                        BigButton("Repair helper tools", onClick = {
                            scope.launch {
                                UiServer.repair(ctx.applicationContext)
                                Engine.retry()
                            }
                        })
                        SoftButton("Try again", onClick = { Engine.retry() }, modifier = Modifier.fillMaxWidth())
                    }
                    else -> BigButton("Try again", onClick = { Engine.retry() })
                }
                if (s.issue.detail.isNotBlank()) {
                    Text("Details: ${s.issue.detail}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { Engine.dismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
}
