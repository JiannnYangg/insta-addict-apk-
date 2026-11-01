package app.instaaddict.runner.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Small settings store. Connection mode is "none", "wireless" or "cable". */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    var mode: String
        get() = sp.getString("mode", "none") ?: "none"
        set(v) {
            sp.edit().putString("mode", v).apply()
        }

    var paired: Boolean
        get() = sp.getBoolean("paired", false)
        set(v) {
            sp.edit().putBoolean("paired", v).apply()
        }

    var setupSeen: Boolean
        get() = sp.getBoolean("setup_seen", false)
        set(v) {
            sp.edit().putBoolean("setup_seen", v).apply()
        }

    var cablePort: Int
        get() = sp.getInt("cable_port", 5555)
        set(v) {
            sp.edit().putInt("cable_port", v).apply()
        }

    fun taskSettings(taskId: String): Map<String, Int> {
        val raw = sp.getString("task_$taskId", null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            val out = HashMap<String, Int>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = o.getInt(k)
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun saveTaskSetting(taskId: String, key: String, value: Int) {
        val current = taskSettings(taskId).toMutableMap()
        current[key] = value
        val o = JSONObject()
        for ((k, v) in current) o.put(k, v)
        sp.edit().putString("task_$taskId", o.toString()).apply()
    }
}
