package app.instaaddict.runner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.instaaddict.runner.bot.BotTask
import app.instaaddict.runner.bot.Engine
import app.instaaddict.runner.bot.EngineState
import app.instaaddict.runner.bot.TaskRegistry
import app.instaaddict.runner.core.AppLog
import app.instaaddict.runner.core.Prefs
import app.instaaddict.runner.core.RunRecord
import app.instaaddict.runner.core.RunStatus

@Composable
fun TasksScreen(onOpenSetup: () -> Unit) {
    val ctx = LocalContext.current
    val state by Engine.state.collectAsState()
    val last by Engine.lastResult.collectAsState()
    var crash by remember { mutableStateOf(AppLog.lastCrash()) }
    val busy = state is EngineState.Running || state is EngineState.Checking

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("InstaAddict", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("One button per task. Tap Start and I check everything first.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val c = crash
        if (c != null) {
            AppCard(container = MaterialTheme.colorScheme.errorContainerSafe()) {
                Text("The app closed unexpectedly last time", fontWeight = FontWeight.Bold)
                Text("Copy the report and send it to Claude so it can be fixed.", fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton("Copy report", onClick = { copyText(ctx, "crash", c) })
                    SoftButton("Dismiss", onClick = { AppLog.clearCrash(); crash = null })
                }
            }
        }

        if (Prefs.mode == "none") {
            AppCard(container = MaterialTheme.colorScheme.primaryContainer) {
                Text("First time here?", fontWeight = FontWeight.Bold)
                Text("Do the quick one-time setup so tasks can run. It takes about two minutes.", fontSize = 14.sp)
                BigButton("Open Setup", onClick = onOpenSetup)
            }
        }

        val s = state
        if (s is EngineState.Running) RunningCard(s)

        val r = last
        if (r != null && !busy) ResultCard(r)

        TaskRegistry.all.forEach { task ->
            TaskCard(task, enabled = !busy, onStart = { Engine.start(task) })
        }

        Text(
            "Use a spare account only. Automation can get an Instagram account limited or blocked.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun androidx.compose.material3.ColorScheme.errorContainerSafe() =
    androidx.compose.ui.graphics.Color(0xFFFFDAD6).let { if (this.background.red < 0.3f) androidx.compose.ui.graphics.Color(0xFF5C1A15) else it }

@Composable
private fun RunningCard(s: EngineState.Running) {
    val p = s.progress
    AppCard(container = MaterialTheme.colorScheme.primaryContainer) {
        Text("Running: ${s.task.title}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(p.step, fontSize = 14.sp)
        val frac = if (p.target > 0) (p.likes.toFloat() / p.target).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Likes", "${p.likes}/${p.target}")
            Stat("Posts seen", p.seen.toString())
            Stat("Scrolls", p.scrolls.toString())
            Stat("Ads skipped", p.adsSkipped.toString())
        }
        BigButton("Stop", onClick = { Engine.stop() }, color = BadColor)
    }
}

@Composable
fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResultCard(r: RunRecord) {
    val (label, color) = when (r.status) {
        RunStatus.SUCCESS -> "Done" to OkColor
        RunStatus.STOPPED -> "Stopped" to WarnColor
        RunStatus.FAILED -> "Problem" to BadColor
    }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(label, color)
            Text(r.taskTitle, fontWeight = FontWeight.SemiBold)
        }
        Text(r.message, fontSize = 14.sp)
        Text("${r.likes} like(s) • ${r.postsSeen} posts seen • ${r.scrolls} scrolls • ${fmtDuration(r.durationMs)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (r.status == RunStatus.FAILED) {
            Text("Open the Logs tab to copy what happened.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TaskCard(task: BotTask, enabled: Boolean, onStart: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val values = remember(task.id) {
        mutableStateMapOf<String, Int>().apply {
            putAll(task.params.associate { it.key to it.default })
            putAll(Prefs.taskSettings(task.id))
        }
    }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(task.emoji, fontSize = 30.sp)
            Column(Modifier.weight(1f)) {
                Text(task.title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(task.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        BigButton("Start", onClick = onStart, enabled = enabled)
        if (task.params.isNotEmpty()) {
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide settings" else "Settings") }
            if (open) {
                task.params.forEach { p ->
                    val v = values[p.key] ?: p.default
                    Stepper(p.label, v, if (p.unit.isEmpty()) "" else " ${p.unit}", p.min, p.max, p.step) { nv ->
                        values[p.key] = nv
                        Prefs.saveTaskSetting(task.id, p.key, nv)
                    }
                }
            }
        }
    }
}

fun fmtDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}
