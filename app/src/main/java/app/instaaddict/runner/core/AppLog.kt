package app.instaaddict.runner.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class Level(val short: String) { DEBUG("D"), INFO("I"), WARN("W"), ERROR("E") }

data class LogLine(
    val id: Long,
    val time: Long,
    val level: Level,
    val tag: String,
    val msg: String,
)

/**
 * One place for everything the app does. The Logs screen shows [lines] live, and the same text is
 * appended to a file so it survives a crash. Copy it from the Logs screen to troubleshoot.
 */
object AppLog {
    private const val MAX_LINES = 1200
    private const val MAX_FILE_BYTES = 1_000_000L

    private val seq = AtomicLong(0)
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines

    private var dir: File? = null
    private val fileLock = Any()

    private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    fun init(filesDir: File) {
        dir = File(filesDir, "logs").apply { mkdirs() }
    }

    fun d(tag: String, msg: String) = add(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = add(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = add(Level.WARN, tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) {
        val extra = if (t == null) "" else " (${t.javaClass.simpleName}: ${t.message})"
        add(Level.ERROR, tag, msg + extra)
    }

    fun add(level: Level, tag: String, msg: String) {
        val line = LogLine(seq.incrementAndGet(), System.currentTimeMillis(), level, tag, msg)
        _lines.update { old ->
            if (old.size >= MAX_LINES) old.subList(old.size - MAX_LINES + 1, old.size) + line else old + line
        }
        writeToFile(line)
    }

    fun format(l: LogLine): String {
        val t = timeFormat.get()?.format(Date(l.time)) ?: ""
        return "$t ${l.level.short}/${l.tag}: ${l.msg}"
    }

    fun text(filter: (LogLine) -> Boolean = { true }): String =
        _lines.value.filter(filter).joinToString("\n") { format(it) }

    fun clear() {
        _lines.value = emptyList()
    }

    private fun writeToFile(line: LogLine) {
        val d = dir ?: return
        try {
            synchronized(fileLock) {
                val f = File(d, "app.log")
                if (f.length() > MAX_FILE_BYTES) {
                    val old = File(d, "app.log.1")
                    old.delete()
                    f.renameTo(old)
                }
                f.appendText(format(line) + "\n")
            }
        } catch (_: Throwable) {
            // logging must never crash the app
        }
    }

    // ---- crash report -------------------------------------------------------------------------

    fun recordCrash(thread: Thread, e: Throwable) {
        val d = dir ?: return
        try {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val recent = text().takeLast(6000)
            File(d, "crash.txt").writeText(
                "Crash on thread ${thread.name} at ${Date()}\n$sw\n--- recent log ---\n$recent"
            )
        } catch (_: Throwable) {
        }
    }

    fun lastCrash(): String? {
        val f = dir?.let { File(it, "crash.txt") } ?: return null
        return if (f.isFile) runCatching { f.readText() }.getOrNull() else null
    }

    fun clearCrash() {
        dir?.let { File(it, "crash.txt").delete() }
    }
}
