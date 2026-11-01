package app.instaaddict.runner.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class RunStatus { SUCCESS, STOPPED, FAILED }

data class RunRecord(
    val id: Long,
    val taskId: String,
    val taskTitle: String,
    val startedAt: Long,
    val endedAt: Long,
    val status: RunStatus,
    val likes: Int,
    val postsSeen: Int,
    val scrolls: Int,
    val adsSkipped: Int,
    val message: String,
) {
    val durationMs: Long get() = endedAt - startedAt

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("taskId", taskId)
        .put("taskTitle", taskTitle)
        .put("startedAt", startedAt)
        .put("endedAt", endedAt)
        .put("status", status.name)
        .put("likes", likes)
        .put("postsSeen", postsSeen)
        .put("scrolls", scrolls)
        .put("adsSkipped", adsSkipped)
        .put("message", message)

    companion object {
        fun fromJson(o: JSONObject): RunRecord = RunRecord(
            id = o.optLong("id"),
            taskId = o.optString("taskId"),
            taskTitle = o.optString("taskTitle"),
            startedAt = o.optLong("startedAt"),
            endedAt = o.optLong("endedAt"),
            status = try {
                RunStatus.valueOf(o.optString("status", "FAILED"))
            } catch (_: Exception) {
                RunStatus.FAILED
            },
            likes = o.optInt("likes"),
            postsSeen = o.optInt("postsSeen"),
            scrolls = o.optInt("scrolls"),
            adsSkipped = o.optInt("adsSkipped"),
            message = o.optString("message"),
        )
    }
}

/** Every run is saved here (newest first, last 500) so the Metrics screen survives restarts. */
object MetricsStore {
    private var file: File? = null
    private val _runs = MutableStateFlow<List<RunRecord>>(emptyList())
    val runs: StateFlow<List<RunRecord>> = _runs

    fun init(dir: File) {
        file = File(dir, "runs.json")
        load()
    }

    private fun load() {
        try {
            val f = file ?: return
            if (!f.isFile) return
            val arr = JSONArray(f.readText())
            val list = ArrayList<RunRecord>()
            for (i in 0 until arr.length()) list.add(RunRecord.fromJson(arr.getJSONObject(i)))
            _runs.value = list
        } catch (e: Exception) {
            AppLog.w("Metrics", "Could not read saved runs: ${e.message}")
        }
    }

    @Synchronized
    fun add(r: RunRecord) {
        val list = (listOf(r) + _runs.value).take(500)
        _runs.value = list
        save(list)
    }

    @Synchronized
    fun clear() {
        _runs.value = emptyList()
        save(emptyList())
    }

    private fun save(list: List<RunRecord>) {
        try {
            val f = file ?: return
            val arr = JSONArray()
            for (r in list) arr.put(r.toJson())
            f.writeText(arr.toString())
        } catch (e: Exception) {
            AppLog.w("Metrics", "Could not save runs: ${e.message}")
        }
    }
}
