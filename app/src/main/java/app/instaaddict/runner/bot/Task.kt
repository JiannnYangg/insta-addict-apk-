package app.instaaddict.runner.bot

import android.content.Context
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** A number the user can adjust for a task (shown as a - / + stepper). */
data class Param(
    val key: String,
    val label: String,
    val default: Int,
    val min: Int,
    val max: Int,
    val step: Int = 1,
    val unit: String = "",
)

/**
 * One button in the app. To add a task: create an object that extends BotTask and add it to
 * [TaskRegistry]. Its [params] show up as settings automatically.
 */
abstract class BotTask {
    abstract val id: String
    abstract val title: String
    abstract val description: String
    abstract val emoji: String
    abstract val params: List<Param>
    abstract suspend fun run(ctx: RunContext)
}

object TaskRegistry {
    val all: List<BotTask> = listOf(HomeFeedLikeTask)
}

class StopRequested : Exception("Stopped by you")
class TaskFailure(message: String) : Exception(message)

data class Progress(
    val likes: Int = 0,
    val target: Int = 0,
    val scrolls: Int = 0,
    val seen: Int = 0,
    val adsSkipped: Int = 0,
    val step: String = "Starting...",
)

/** Everything a running task needs: settings, stop checks, human-like pauses and progress. */
class RunContext(
    val app: Context,
    val task: BotTask,
    private val settings: Map<String, Int>,
    private val cancelFlag: AtomicBoolean,
    private val onProgress: (Progress) -> Unit,
) {
    var progress: Progress = Progress()
        private set

    var finalNote: String = ""

    fun setting(key: String): Int =
        settings[key] ?: task.params.firstOrNull { it.key == key }?.default ?: 0

    fun update(block: (Progress) -> Progress) {
        progress = block(progress)
        onProgress(progress)
    }

    fun step(text: String) {
        update { it.copy(step = text) }
        app.instaaddict.runner.core.AppLog.i("Run", text)
    }

    fun checkCancelled() {
        if (cancelFlag.get()) throw StopRequested()
    }

    fun fail(message: String): Nothing = throw TaskFailure(message)

    fun randomInt(min: Int, max: Int): Int = if (max <= min) min else Random.nextInt(min, max + 1)

    /** Waits a random time between [minMs] and [maxMs], but reacts to Stop within a quarter second. */
    suspend fun pause(minMs: Long, maxMs: Long) {
        val total = if (maxMs <= minMs) minMs else Random.nextLong(minMs, maxMs + 1)
        var left = total
        while (left > 0) {
            checkCancelled()
            val slice = if (left < 250L) left else 250L
            delay(slice)
            left -= slice
        }
        checkCancelled()
    }
}
