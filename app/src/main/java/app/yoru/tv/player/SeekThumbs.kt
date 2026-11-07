package app.yoru.tv.player

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Превью кадров при перемотке.
 * Anime365 не отдаёт готовых раскадровок, поэтому берём кадр из самого лёгкого MP4 этой серии:
 * MediaMetadataRetriever читает файл кусками (HTTP Range) и достаёт ближайший ключевой кадр.
 *
 * Шкала поделена на ячейки по 15 с, на каждую — один кадр. Пока перематывают, сразу показывается ближайший
 * уже загруженный кадр, а один загрузчик берёт следующую ячейку: сначала ту, куда пришли, затем несколько впереди
 * по ходу перемотки. Устаревшие запросы не копятся, а весь файл заранее не читается: загрузка идёт, только пока
 * перематывают. Если файл недоступен — превью просто нет, перемотка работает как обычно.
 */
class SeekThumbs(private val scope: CoroutineScope) {
    /** Последний показанный кадр и его ячейка (мс); imageAt < 0 — показывать нечего. */
    var image by mutableStateOf<ImageBitmap?>(null)
        private set
    var imageAt by mutableLongStateOf(-1L)
        private set

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "yoru-thumbs").apply { isDaemon = true } }
    private val io = worker.asCoroutineDispatcher()
    private val cache = object : LruCache<Long, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: ImageBitmap) = value.asAndroidBitmap().allocationByteCount
    }
    private val failed = HashSet<Long>()
    private var url: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var retriever: MediaMetadataRetriever? = null
    @Volatile
    private var broken = false
    private var job: Job? = null

    /** Ячейка, к которой сейчас перематывают; -1 — не перематывают. */
    private var wanted = -1L
    private var forward = true
    private var duration = 0L

    /** Новая серия или перевод — другой файл. */
    fun setSource(newUrl: String?, newHeaders: Map<String, String>) {
        if (newUrl == url) return
        job?.cancel()
        url = newUrl
        headers = newHeaders
        broken = false
        wanted = -1
        image = null
        imageAt = -1
        failed.clear()
        cache.evictAll()
        val old = retriever
        retriever = null
        if (old != null) worker.execute { runCatching { old.release() } }
    }

    /** Показать кадр около [ms]; вызывается на каждое нажатие при перемотке. */
    fun request(ms: Long, durationMs: Long) {
        if (url == null || broken) return
        val key = (ms / STEP) * STEP
        if (wanted >= 0 && key != wanted) forward = key > wanted
        wanted = key
        duration = durationMs
        showNearest(key)
        if (job?.isActive != true) job = scope.launch { load() }
    }

    /** Перемотка закончилась: убрать кадр и не грузить дальше. */
    fun clear() {
        wanted = -1
        imageAt = -1
    }

    /** Ближайший готовый кадр. Если рядом ничего нет, остаётся прежний: время над ним всё равно верное. */
    private fun showNearest(key: Long) {
        for (d in 0..NEAR) {
            for (k in if (d == 0) listOf(key) else listOf(key - d * STEP, key + d * STEP)) {
                val b = cache.get(k) ?: continue
                image = b
                imageAt = k
                return
            }
        }
    }

    /** Следующая ячейка: куда пришли, затем вперёд по ходу перемотки и одна позади. */
    private fun nextKey(w: Long): Long? {
        val sign = if (forward) 1 else -1
        for (d in listOf(0, 1, 2, 3, 4, -1)) {
            val k = w + d * sign * STEP
            if (k < 0 || (duration > 0 && k >= duration) || k in failed || cache.get(k) != null) continue
            return k
        }
        return null
    }

    private suspend fun load() {
        while (true) {
            val w = wanted
            if (w < 0 || broken) return
            val src = url ?: return
            val key = nextKey(w) ?: return
            val bmp = withContext(io) { frame(src, key + STEP / 2) }
            if (src != url) return
            if (bmp != null) cache.put(key, bmp) else failed += key
            if (wanted >= 0) showNearest(wanted)
        }
    }

    private fun frame(src: String, ms: Long): ImageBitmap? {
        if (broken) return null
        return try {
            val r = retriever ?: MediaMetadataRetriever().also {
                it.setDataSource(src, headers)
                retriever = it
            }
            val us = ms * 1000
            val b: Bitmap? = when {
                // 16 бит на точку: хватает для маленького кадра, а в памяти вдвое меньше
                Build.VERSION.SDK_INT >= 30 -> r.getScaledFrameAtTime(
                    us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, W, H,
                    MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.RGB_565 },
                )
                Build.VERSION.SDK_INT >= 27 -> r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, W, H)
                else -> r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                    Bitmap.createScaledBitmap(full, W, (W * full.height / full.width.coerceAtLeast(1)).coerceAtLeast(1), true)
                        .also { if (it !== full) full.recycle() }
                }
            }
            b?.asImageBitmap()
        } catch (_: Exception) {
            // файл не открылся (нет доступа, другой формат) — больше не пытаемся для этой серии
            broken = true
            runCatching { retriever?.release() }
            retriever = null
            null
        }
    }

    fun release() {
        job?.cancel()
        val r = retriever
        retriever = null
        worker.execute { runCatching { r?.release() } }
        worker.shutdown()
    }

    companion object {
        /** Один кадр на 15 секунд шкалы. */
        private const val STEP = 15_000L
        /** На сколько ячеек в сторону можно показать уже готовый кадр. */
        private const val NEAR = 2
        private const val W = 240
        private const val H = 135
    }
}
