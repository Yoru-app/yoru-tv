package app.yoru.tv.data

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** code = 0 — нет сети; остальное — HTTP-код или код ошибки API. */
class ApiError(val code: Int, message: String, cause: Throwable? = null) : Exception(message, cause)

class HttpResult(val code: Int, val body: String)

class Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /** Отдельный клиент с короткими таймаутами — для замера скорости CDN. */
    val probeClient: OkHttpClient = client.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResult =
        exec(Request.Builder().url(url).get(), headers)

    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): HttpResult =
        exec(Request.Builder().url(url).post(json.toRequestBody(JSON)), headers)

    suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): HttpResult {
        val b = FormBody.Builder()
        form.forEach { (k, v) -> b.add(k, v) }
        return exec(Request.Builder().url(url).post(b.build()), headers)
    }

    suspend fun send(url: String, method: String, json: String?, headers: Map<String, String> = emptyMap()): HttpResult {
        val body: RequestBody? = json?.toRequestBody(JSON)
        return exec(Request.Builder().url(url).method(method, body), headers)
    }

    private suspend fun exec(rb: Request.Builder, headers: Map<String, String>): HttpResult = withContext(Dispatchers.IO) {
        headers.forEach { (k, v) -> rb.header(k, v) }
        if (!headers.keys.any { it.equals("Accept", true) }) rb.header("Accept", "application/json")
        try {
            client.newCall(rb.build()).execute().use { r -> HttpResult(r.code, r.body?.string() ?: "") }
        } catch (e: IOException) {
            throw ApiError(0, "Нет связи: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}

/**
 * Лимит Shikimori: 5 запросов в секунду и 90 в минуту на IP.
 * Держим запас: не чаще раза в 220 мс и не больше 85 в минуту.
 */
class RateLimiter(private val minGapMs: Long = 220, private val perMinute: Int = 85) {
    private val mutex = Mutex()
    private val stamps = ArrayDeque<Long>()

    suspend fun acquire() {
        mutex.withLock {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                while (stamps.isNotEmpty() && now - stamps.first() > 60_000) stamps.removeFirst()
                val gapWait = if (stamps.isEmpty()) 0L else minGapMs - (now - stamps.last())
                val minuteWait = if (stamps.size >= perMinute) 60_000L - (now - stamps.first()) + 10 else 0L
                val wait = maxOf(gapWait, minuteWait)
                if (wait <= 0) {
                    stamps.addLast(now)
                    break
                }
                delay(wait)
            }
        }
    }
}
