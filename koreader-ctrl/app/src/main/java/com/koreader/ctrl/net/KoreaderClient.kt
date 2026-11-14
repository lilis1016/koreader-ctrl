package com.koreader.ctrl.net

import android.util.Log
import com.koreader.ctrl.model.Action
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * KOReader HTTP Inspector 客户端。
 *
 * 协议与原始 HTML 完全一致，未做任何改动：
 *   GET http://<KINDLE_IP>:8080/koreader/event/<EventName>
 *
 * 原 HTML 使用 fetch(mode="no-cors")，浏览器拿不到响应体。
 * 原生应用不使用 CORS，因此可以直接读取 HTTP 状态码与响应体，
 * 这让「Kindle 是否真的收到指令」可以被验证 —— 属于增强，不是协议变更。
 */
object KoreaderClient {

    private const val TAG = "KoreaderClient"
    private const val PORT = 8080
    private const val TIMEOUT_MS = 4000

    // KOReader 的 HTTP Inspector 对亮度增量事件是有状态的。并发 GET 可能在 Kindle 端
    // 交错处理，表现为第一次生效、后续亮度事件丢失。统一串行发送，保持输入顺序。
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "koreader-http-queue").apply { isDaemon = true }
    }

    fun buildUrl(ip: String, eventName: String): String {
        val clean = ip.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')
        return "http://$clean:$PORT/koreader/event/$eventName"
    }

    /** 异步发送，返回回调在后台线程触发 */
    fun sendAsync(ip: String, eventName: String, onResult: (Boolean, String) -> Unit) {
        executor.execute {
            val (ok, msg) = send(ip, eventName)
            onResult(ok, msg)
        }
    }

    /** 同步发送，返回 (是否成功, 描述信息) */
    fun send(ip: String, eventName: String): Pair<Boolean, String> {
        if (ip.isBlank()) return false to "IP 为空"

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