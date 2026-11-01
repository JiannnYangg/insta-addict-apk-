package app.instaaddict.runner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.instaaddict.runner.core.MetricsStore
import app.instaaddict.runner.core.RunStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MetricsScreen() {
    val runs by MetricsStore.runs.collectAsState()
    val fmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Metrics", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (runs.isEmpty()) {
            AppCard { Text("No runs yet. Start a task and the numbers show up here.") }
        } else {
            val ok = runs.count { it.status == RunStatus.SUCCESS }
            AppCard(container = MaterialTheme.colorScheme.primaryContainer) {
                Text("All time", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Runs", runs.size.toString())
                    Stat("Succeeded", ok.toString())
                    Stat("Likes", runs.sumOf { it.likes }.toString())
                    Stat("Time", fmtDuration(runs.sumOf { it.durationMs }))
                }
            }
            runs.forEach { r ->
                val (label, color) = when (r.status) {
                    RunStatus.SUCCESS -> "Done" to OkColor
                    RunStatus.STOPPED -> "Stopped" to WarnColor
                    RunStatus.FAILED -> "Problem" to BadColor
                }
                AppCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(r.taskTitle, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Pill(label, color)
                    }
                    Text(fmt.format(Date(r.startedAt)) + " • " + fmtDuration(r.durationMs), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${r.likes} likes • ${r.postsSeen} posts seen • ${r.scrolls} scrolls • ${r.adsSkipped} ads skipped", fontSize = 13.sp)
                    if (r.message.isNotBlank()) Text(r.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SoftButton("Clear history", onClick = { MetricsStore.clear() })
        }
    }
}
