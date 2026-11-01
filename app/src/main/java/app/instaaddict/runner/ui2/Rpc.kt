package app.instaaddict.runner.ui2

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

class RpcException(message: String, val connection: Boolean = false) : Exception(message)

/**
 * Talks to the same UI automation server InstaAddict uses (uiautomator2's helper), which listens on
 * 127.0.0.1:9008 inside the phone once it has been started.
 */
object Rpc {
    private const val BASE = "http://127.0.0.1:9008"
    private val ids = AtomicInteger(1)

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try {
            val c = URL("$BASE/ping").openConnection() as HttpURLConnection
            c.connectTimeout = 1500
            c.readTimeout = 2500
            val ok = c.responseCode == 200 &&
                c.inputStream.bufferedReader().readText().trim().equals("pong", ignoreCase = true)
            c.disconnect()
            ok
        } catch (_: Exception) {
            false
        }
    }

    suspend fun call(method: String, params: List<Any?> = emptyList(), timeoutMs: Int = 30_000): Any? =
        withContext(Dispatchers.IO) {
            val arr = JSONArray()
            for (p in params) arr.put(p ?: JSONObject.NULL)
            val body = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", ids.getAndIncrement())
                .put("method", method)
                .put("params", arr)
                .toString()
            var conn: HttpURLConnection? = null
            try {
                val c = URL("$BASE/jsonrpc/0").openConnection() as HttpURLConnection
                conn = c
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 3000
                c.readTimeout = timeoutMs
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader()?.readText().orEmpty()
                if (code != 200) throw RpcException("Helper answered HTTP $code: ${text.take(160)}")
                val json = JSONObject(text)
                if (json.has("error") && !json.isNull("error")) {
                    val err = json.optJSONObject("error")
                    throw RpcException(err?.optString("message") ?: "Helper reported an error")
                }
                json.opt("result")
            } catch (e: RpcException) {
                throw e
            } catch (e: ConnectException) {
                throw RpcException("Cannot reach the helper: ${e.message}", connection = true)
            } catch (e: Exception) {
                throw RpcException("${e.javaClass.simpleName}: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
}
