package app.yoru.tv.data

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URL

/** Результат замера: скорость в Мбит/с или null, если адрес не ответил. */
data class CdnResult(val url: String, val mbps: Double?) {
    val host: String get() = try { URL(url).host } catch (_: Exception) { url.take(40) }
}

/**
 * Канал CDN через API Anime365 не переключить, но в stream[].urls приходит несколько адресов.
 * Как и веб-Yoru, качаем с каждого по полмегабайта и выбираем самый быстрый.
 */
class CdnProbe(private val http: Http) {
    private var best: Pair<String, Long>? = null

    /** Самый быстрый адрес из недавнего замера, если он есть среди этих: следующая серия запустится без нового замера. */
    fun recentBest(urls: List<String>): String? {
        val (host, at) = best ?: return null
        if (SystemClock.elapsedRealtime() - at > 30 * 60_000L) return null
        return urls.firstOrNull { CdnResult(it, null).host == host }
    }

    fun rememberBest(host: String?) {
        best = host?.let { it to SystemClock.elapsedRealtime() }
    }

    suspend fun measure(urls: List<String>, headers: Map<String, String>): List<CdnResult> = coroutineScope {
        urls.map { u -> async { measureOne(u, headers) } }.awaitAll()
    }

    private suspend fun measureOne(url: String, headers: Map<String, String>): CdnResult = withContext(Dispatchers.IO) {
        try {
            var target = url
            for (i in 0 until 2) {
                if (!target.contains(".m3u8")) break
                val text = fetchText(target, headers) ?: return@withContext CdnResult(url, null)
                val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                    ?: return@withContext CdnResult(url, null)
                target = URL(URL(target), line).toString()
            }
            val rb = Request.Builder().url(target).header("Range", "bytes=0-524287")
            headers.forEach { (k, v) -> rb.header(k, v) }
            val t0 = SystemClock.elapsedRealtime()
            var bytes = 0L
            http.probeClient.newCall(rb.build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext CdnResult(url, null)
                val src = r.body?.byteStream() ?: return@withContext CdnResult(url, null)
                val buf = ByteArray(16 * 1024)
                while (bytes < 524288) {
                    val n = src.read(buf)
                    if (n < 0) break
                    bytes += n
                }
            }
            val ms = maxOf(1L, SystemClock.elapsedRealtime() - t0)
            CdnResult(url, if (bytes > 0) bytes * 8.0 / ms / 1000.0 else null)
        } catch (_: Exception) {
            CdnResult(url, null)
        }
    }

    private fun fetchText(url: String, headers: Map<String, String>): String? {
        val rb = Request.Builder().url(url)
        headers.forEach { (k, v) -> rb.header(k, v) }
        http.probeClient.newCall(rb.build()).execute().use { r ->
            return if (r.isSuccessful) r.body?.string() else null
        }
    }
}
