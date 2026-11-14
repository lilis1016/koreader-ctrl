package com.koreader.ctrl.net

import android.util.Log
import android.os.SystemClock
import com.koreader.ctrl.model.Action
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * KOReader HTTP Inspector 客户端。
 *
 * 通过 KOReader HTTP Inspector 发送事件：
 *   普通事件：GET /koreader/event/<EventName>
 *   亮度事件：GET /koreader/broadcast/<EventName>
 * 亮度使用 broadcast，是为了让全局 DeviceListener 在其他弹层位于顶部时也能收到。
 *
 * 原 HTML 使用 fetch(mode="no-cors")，浏览器拿不到响应体。
 * 原生应用不使用 CORS，因此可以直接读取 HTTP 状态码与响应体，
 * 这让「Kindle 是否真的收到指令」可以被验证 —— 属于增强，不是协议变更。
 */
object KoreaderClient {

    private const val TAG = "KoreaderClient"
    private const val PORT = 8080
    private const val TIMEOUT_MS = 5000
    private const val BRIGHTNESS_MIN_INTERVAL_MS = 180L

    @Volatile
    private var lastBrightnessRequestAt = 0L

    // 串行发送并对亮度事件限速，避免连续请求在 KOReader UIManager 的 nextTick 队列中挤在一起。
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "koreader-http-queue").apply { isDaemon = true }
    }

    fun buildUrl(ip: String, eventName: String): String {
        val clean = ip.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')
        val endpoint = if (isBrightnessEvent(eventName)) "broadcast" else "event"
        return "http://$clean:$PORT/koreader/$endpoint/$eventName"
    }

    private fun isBrightnessEvent(eventName: String): Boolean =
        eventName.startsWith("IncreaseFlIntensity/") ||
            eventName.startsWith("DecreaseFlIntensity/")

    /** 异步发送：全局单线程队列，亮度事件额外留出 UIManager 处理间隔。 */
    fun sendAsync(ip: String, eventName: String, onResult: (Boolean, String) -> Unit) {
        executor.execute {
            val (ok, msg) = send(ip, eventName)
            try { onResult(ok, msg) } catch (e: Exception) {
                Log.w(TAG, "result callback failed", e)
            }
        }
    }

    /** 同步发送，返回 (是否成功, 描述信息) */
    fun send(ip: String, eventName: String): Pair<Boolean, String> {
        if (ip.isBlank()) return false to "IP 为空"

        if (isBrightnessEvent(eventName)) {
            val now = SystemClock.elapsedRealtime()
            val waitMs = (BRIGHTNESS_MIN_INTERVAL_MS - (now - lastBrightnessRequestAt)).coerceAtLeast(0L)
            if (waitMs > 0L) SystemClock.sleep(waitMs)
            lastBrightnessRequestAt = SystemClock.elapsedRealtime()
        }

        val url = buildUrl(ip, eventName)
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Connection", "close")
            conn.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
            conn.setRequestProperty("Pragma", "no-cache")
            conn.setRequestProperty("Accept", "*/*")
            // 与原 HTML 的 cache:"no-store" 对齐，避免增量事件被中间缓存复用。
            conn.useCaches = false

            val code = conn.responseCode
            val body = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.use(BufferedReader::readText)?.trim().orEmpty()
            } catch (e: Exception) {
                ""
            }

            if (code in 200..299) {
                Log.d(TAG, "OK $url -> $code $body")
                true to "HTTP $code${if (body.isNotEmpty()) " · $body" else ""}"
            } else {
                Log.w(TAG, "FAIL $url -> $code")
                false to "HTTP $code"
            }
        } catch (e: Exception) {
            Log.w(TAG, "ERR $url", e)
            false to (e.message ?: e.javaClass.simpleName)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    fun sendAction(ip: String, action: Action, onResult: (Boolean, String) -> Unit) {
        sendAsync(ip, action.eventName, onResult)
    }
}