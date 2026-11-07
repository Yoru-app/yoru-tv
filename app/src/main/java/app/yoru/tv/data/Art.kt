package app.yoru.tv.data

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Картинки TMDB для тайтла: фон 16:9 и логотип названия (как на AniScroll). */
@Serializable
data class TmdbEntry(
    val tmdbId: Long? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val at: Long = 0,
)

/**
 * Альтернативные источники картинок:
 * - обложки AniList (coverImage) вместо постеров Shikimori;
 * - фоны и логотипы TMDB. MAL id → TMDB id берём из открытых таблиц соответствий
 *   (arm.haglund.dev, запасной — animeapi.my.id), сами картинки — из TMDB API по ключу пользователя.
 */
class Art(context: Context, private val http: Http, private val prefs: Prefs) {
    private val dir = context.filesDir
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Подсказка типа тайтла ("movie" / "tv"…) из кэша Shikimori, чтобы не перепутать фильм и сериал в TMDB. */
    var kindHint: (Long) -> String? = { null }

    // ------------------------------------------------------------------ обложки AniList

    private val coverSer = MapSerializer(Long.serializer(), String.serializer())
    private val coverFile = File(dir, "covers.json")

    /** Наблюдаемая карта malId → обложка; постеры перерисуются сами, когда она пополнится. */
    val covers = mutableStateMapOf<Long, String>()
    private val coverTried = ConcurrentHashMap.newKeySet<Long>()
    private val pending = ConcurrentHashMap.newKeySet<Long>()
    private var batch: Job? = null

    val coversEnabled: Boolean get() = prefs.s.coverSource == "anilist"

    /** Вызывается из постеров; запросы копятся и уходят пачкой до 50 тайтлов. */
    fun requestCover(id: Long) {
        if (!coversEnabled || id <= 0 || covers.containsKey(id) || !coverTried.add(id)) return
        pending += id
        if (batch?.isActive == true) return
        batch = main.launch {
            diskLoaded.await()
            delay(150)
            while (pending.isNotEmpty()) {
                val chunk = pending.take(50)
                pending.removeAll(chunk.toSet())
                val found = withContext(Dispatchers.IO) {
                    try {
                        anilistCovers(chunk)
                    } catch (_: Exception) {
                        chunk.forEach { coverTried.remove(it) } // попробуем в другой раз
                        emptyMap()
                    }
                }
                covers.putAll(found)
            }
            val snapshot = HashMap(covers)
            withContext(Dispatchers.IO) {
                try {
                    coverFile.writeText(JsonCfg.encodeToString(coverSer, snapshot))
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun anilistCovers(ids: List<Long>): Map<Long, String> {
        val body = buildJsonObject {
            put("query", "query (\$ids: [Int]) { Page(perPage: 50) { media(idMal_in: \$ids, type: ANIME) { idMal coverImage { extraLarge large } } } }")
            put("variables", buildJsonObject { putJsonArray("ids") { ids.forEach { add(it) } } })
        }.toString()
        val r = http.postJson("https://graphql.anilist.co", body)
        if (r.code !in 200..299) throw ApiError(r.code, "AniList ${r.code}")
        return parseJson(r.body).obj?.o("data")?.o("Page")?.a("media").objects().mapNotNull { m ->
            val id = m.long("idMal") ?: return@mapNotNull null
            val c = m.o("coverImage")
            val url = c?.str("extraLarge") ?: c?.str("large") ?: return@mapNotNull null
            id to url
        }.toMap()
    }

    // ------------------------------------------------------------------ TMDB

    private val tmdbSer = MapSerializer(Long.serializer(), TmdbEntry.serializer())
    private val tmdbFile = File(dir, "tmdb.json")
    private val tmdbCache = ConcurrentHashMap<Long, TmdbEntry>()

    /** Кэши с диска читаются в фоне: запуск приложения их не ждёт. */
    private val diskLoaded = kotlinx.coroutines.CompletableDeferred<Unit>()

    init {
        main.launch {
            val saved = withContext(Dispatchers.IO) {
                val c = try {
                    if (coverFile.exists()) JsonCfg.decodeFromString(coverSer, coverFile.readText()) else emptyMap()
                } catch (_: Exception) {
                    emptyMap()
                }
                val t = try {
                    if (tmdbFile.exists()) JsonCfg.decodeFromString(tmdbSer, tmdbFile.readText()) else emptyMap()
                } catch (_: Exception) {
                    emptyMap()
                }
                c to t
            }
            covers.putAll(saved.first)
            tmdbCache.putAll(saved.second)
            diskLoaded.complete(Unit)
        }
    }
    private val tmdbLimit = Semaphore(4)
    private var saveJob: Job? = null

    val tmdbEnabled: Boolean get() = prefs.tmdbToken.isNotBlank()

    fun cachedLogo(malId: Long): String? = tmdbCache[malId]?.logo

    suspend fun tmdb(malId: Long): TmdbEntry? {
        if (!tmdbEnabled || malId <= 0) return null
        diskLoaded.await()
        val now = System.currentTimeMillis()
        tmdbCache[malId]?.let { e ->
            val ttl = if (e.backdrop == null && e.logo == null) 3 * DAY else 30 * DAY
            if (now - e.at < ttl) return e
        }
        val e = tmdbLimit.withPermit {
            try {
                fetchTmdb(malId)
            } catch (_: Exception) {
                null
            }
        } ?: return tmdbCache[malId]
        tmdbCache[malId] = e
        scheduleSave()
        return e
    }

    suspend fun tmdbMany(ids: Collection<Long>): Map<Long, TmdbEntry?> = coroutineScope {
        ids.distinct().map { id -> async { id to tmdb(id) } }.awaitAll().toMap()
    }

    private suspend fun fetchTmdb(malId: Long): TmdbEntry? {
        val tid = mapToTmdb(malId) ?: return TmdbEntry(null, at = System.currentTimeMillis())
        val kind = kindHint(malId)
        val types = when (kind) {
            "movie" -> listOf("movie")
            null -> listOf("tv", "movie")
            else -> listOf("tv")
        }
        for (type in types) {
            val r = http.get(tmdbUrl("/3/$type/$tid/images", "include_image_language=en,ru,ja,null"), tmdbHeaders())
            if (r.code == 404) continue
            if (r.code == 401) throw ApiError(401, "TMDB не принял ключ")
            if (r.code !in 200..299) return null
            val o = parseJson(r.body).obj ?: return null
            return TmdbEntry(
                tmdbId = tid,
                backdrop = pickBackdrop(o)?.let { "https://image.tmdb.org/t/p/w1280$it" },
                logo = pickLogo(o)?.let { "https://image.tmdb.org/t/p/w500$it" },
                at = System.currentTimeMillis(),
            )
        }
        return TmdbEntry(tid, at = System.currentTimeMillis())
    }

    /** Фон без надписей (язык null) с лучшей оценкой, иначе любой. */
    private fun pickBackdrop(o: JsonObject): String? {
        val all = (o["backdrops"] as? JsonArray).objects()
        val textless = all.filter { it.str("iso_639_1") == null }
        return (textless.ifEmpty { all }).maxByOrNull { it.double("vote_average") ?: 0.0 }?.str("file_path")
    }

    /** Логотип названия: английский, как на AniScroll, затем русский, без языка, японский. */
    private fun pickLogo(o: JsonObject): String? {
        val all = (o["logos"] as? JsonArray).objects()
        for (lang in listOf("en", "ru", null, "ja")) {
            val best = all.filter { it.str("iso_639_1") == lang }.maxByOrNull { it.double("vote_average") ?: 0.0 }
            if (best != null) return best.str("file_path")
        }
        return all.firstOrNull()?.str("file_path")
    }

    private suspend fun mapToTmdb(malId: Long): Long? {
        try {
            val r = http.get("https://arm.haglund.dev/api/v2/ids?source=myanimelist&id=$malId")
            if (r.code in 200..299) parseJson(r.body).obj?.long("themoviedb")?.let { return it }
        } catch (_: Exception) {
        }
        try {
            val r = http.get("https://animeapi.my.id/myanimelist/$malId")
            if (r.code in 200..299) parseJson(r.body).obj?.long("themoviedb")?.let { return it }
        } catch (_: Exception) {
        }
        return null
    }

    /** Длинный «API Read Access Token» идёт заголовком, короткий API Key — параметром. */
    private fun tmdbUrl(path: String, query: String): String {
        val t = prefs.tmdbToken
        val key = if (t.length <= 40) "&api_key=$t" else ""
        return "https://api.themoviedb.org$path?$query$key"
    }

    private fun tmdbHeaders(): Map<String, String> {
        val t = prefs.tmdbToken
        return if (t.length > 40) mapOf("Authorization" to "Bearer $t") else emptyMap()
    }

    /** Проверка ключа для страницы подключения. */
    suspend fun checkTmdb(): String {
        val r = http.get(tmdbUrl("/3/configuration", "language=en"), tmdbHeaders())
        return when (r.code) {
            in 200..299 -> "Ключ TMDB работает."
            401 -> throw ApiError(401, "TMDB не принял ключ.")
            else -> throw ApiError(r.code, "TMDB ответил ${r.code}.")
        }
    }

    fun clearTmdbCache() {
        tmdbCache.clear()
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = main.launch {
            delay(2000)
            val snapshot = HashMap(tmdbCache)
            withContext(Dispatchers.IO) {
                try {
                    tmdbFile.writeText(JsonCfg.encodeToString(tmdbSer, snapshot))
                } catch (_: Exception) {
                }
            }
        }
    }

    companion object {
        const val DAY = 24 * 3600 * 1000L
    }
}
