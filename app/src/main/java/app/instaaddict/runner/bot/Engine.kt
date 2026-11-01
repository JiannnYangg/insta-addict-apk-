package app.instaaddict.runner.bot

import android.content.Context
import android.os.PowerManager
import app.instaaddict.runner.core.AppLog
import app.instaaddict.runner.core.MetricsStore
import app.instaaddict.runner.core.Prefs
import app.instaaddict.runner.core.RunRecord
import app.instaaddict.runner.core.RunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

sealed class EngineState {
    object Idle : EngineState()
    data class Checking(val task: BotTask, val items: List<PreflightItem>) : EngineState()
    data class Blocked(val task: BotTask, val issue: Issue, val items: List<PreflightItem>) : EngineState()
    data class Running(val task: BotTask, val progress: Progress) : EngineState()
}

/** Runs one task at a time: pre-task check, then the task, then saves the metrics. */
object Engine {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cancel = AtomicBoolean(false)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    val state = MutableStateFlow<EngineState>(EngineState.Idle)
    val lastResult = MutableStateFlow<RunRecord?>(null)

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    fun start(task: BotTask) {
        val cur = state.value
        if (cur is EngineState.Checking || cur is EngineState.Running) return
        cancel.set(false)
        lastResult.value = null
        state.value = EngineState.Checking(task, emptyList())
        job = scope.launch { execute(task) }
    }

    fun stop() {
        cancel.set(true)
        if (state.value is EngineState.Checking) job?.cancel()
    }

    fun dismiss() {
        if (state.value is EngineState.Blocked) state.value = EngineState.Idle
    }

    fun retry() {
        val cur = state.value
        if (cur is EngineState.Blocked) start(cur.task)
    }

    private fun acquireWake() {
        try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InstaAddict:run")
            wl.setReferenceCounted(false)
            wl.acquire(70 * 60 * 1000L)
            wakeLock = wl
        } catch (e: Exception) {
            AppLog.w("Engine", "Could not keep the phone awake: ${e.message}")
        }
    }

    private fun releaseWake() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private suspend fun execute(task: BotTask) {
        val startedAt = System.currentTimeMillis()
        acquireWake()
        RunnerService.start(app, task.title)
        try {
            AppLog.i("Engine", "Starting task: ${task.title}")
            var items: List<PreflightItem> = emptyList()
            val issue = Preflight.run(app) { list ->
                items = list
                state.value = EngineState.Checking(task, list)
            }
            if (issue != null) {
                AppLog.w("Engine", "Blocked before start: ${issue.title}")
                state.value = EngineState.Blocked(task, issue, items)
                return
            }
            state.value = EngineState.Running(task, Progress())
            val rc = RunContext(app, task, Prefs.taskSettings(task.id), cancel) { p ->
                state.value = EngineState.Running(task, p)
            }
            var status = RunStatus.SUCCESS
            var message = ""
            try {
                task.run(rc)
                message = rc.finalNote
            } catch (e: StopRequested) {
                status = RunStatus.STOPPED
                message = "You stopped the task."
            } catch (e: TaskFailure) {
                status = RunStatus.FAILED
                message = e.message ?: "The task failed."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                status = RunStatus.FAILED
                message = "Something unexpected went wrong: ${e.javaClass.simpleName}: ${e.message}"
                AppLog.e("Engine", "Unexpected error in task", e)
            }
            val p = rc.progress
            val rec = RunRecord(
                id = startedAt,
                taskId = task.id,
                taskTitle = task.title,
                startedAt = startedAt,
                endedAt = System.currentTimeMillis(),
                status = status,
                likes = p.likes,
                postsSeen = p.seen,
                scrolls = p.scrolls,
                adsSkipped = p.adsSkipped,
                message = message,
            )
            MetricsStore.add(rec)
            lastResult.value = rec
            AppLog.i("Engine", "Finished: $status - $message")
            state.value = EngineState.Idle
        } finally {
            releaseWake()
            RunnerService.stop(app)
            val s = state.value
            if (s is EngineState.Checking || s is EngineState.Running) state.value = EngineState.Idle
        }
    }
}
