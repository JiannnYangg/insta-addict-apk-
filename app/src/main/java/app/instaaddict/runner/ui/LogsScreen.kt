package app.instaaddict.runner.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.instaaddict.runner.core.AppLog
import app.instaaddict.runner.core.Level
import app.instaaddict.runner.core.LogLine

@Composable
fun LogsScreen() {
    val ctx = LocalContext.current
    val all by AppLog.lines.collectAsState()
    var filter by remember { mutableIntStateOf(0) } // 0 all, 1 warnings+, 2 errors
    val shown = when (filter) {
        1 -> all.filter { it.level == Level.WARN || it.level == Level.ERROR }
        2 -> all.filter { it.level == Level.ERROR }
        else -> all
    }
    val listState = rememberLazyListState()
    LaunchedEffect(shown.size) {
        if (shown.isNotEmpty()) listState.scrollToItem(shown.size - 1)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Live log", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filter == 0, onClick = { filter = 0 }, label = { Text("All") })
            FilterChip(selected = filter == 1, onClick = { filter = 1 }, label = { Text("Warnings") })
            FilterChip(selected = filter == 2, onClick = { filter = 2 }, label = { Text("Errors") })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton("Copy all", onClick = { copyText(ctx, "log", AppLog.text().takeLast(200_000)) })
            SoftButton("Copy problems", onClick = {
                val t = AppLog.text { it.level == Level.WARN || it.level == Level.ERROR }
                copyText(ctx, "log", t.ifBlank { "No warnings or errors." })
            })
            SoftButton("Share", onClick = {
                try {
                    val i = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, AppLog.text().takeLast(200_000))
                    ctx.startActivity(Intent.createChooser(i, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    copyText(ctx, "log", AppLog.text().takeLast(200_000))
                }
            })
            SoftButton("Clear", onClick = { AppLog.clear() })
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            items(shown, key = { it.id }) { l -> LogRow(l) }
        }
    }
}

@Composable
private fun LogRow(l: LogLine) {
    val color: Color = when (l.level) {
        Level.ERROR -> BadColor
        Level.WARN -> WarnColor
        Level.INFO -> MaterialTheme.colorScheme.onSurface
        Level.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        AppLog.format(l),
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        modifier = Modifier.padding(vertical = 1.dp),
    )
}
