package app.yoru.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class BannerEntry(val url: String? = null, val at: Long = 0)

/**
 * Горизонтальные баннеры: AniList (до 50 тайтлов за запрос) — основной источник,
 * Kitsu coverImage — запасной. Кэш на диске, «нет баннера» тоже запоминаем на 3 дня.
 */
class Banners(context: Context, private val http: Http, appScope: CoroutineScope) {
    private val file = File(context.filesDir, "banners.json")
    private val map = ConcurrentHashMap<Long, BannerEntry>()
    private val mutex = Mutex()
    private val ser = MapSerializer(Long.serializer(), BannerEntry.serializer())

    /** Кэш с диска читается в фоне, чтобы не задерживать запуск приложения. */
    private val loaded = kotlinx.coroutines.CompletableDeferred<Unit>()

    init {
        appScope.launch(Dispatchers.IO) {
            try {
                if (file.exists()) map.putAll(JsonCfg.decodeFromString(ser, file.readText()))
            } catch (_: Exception) {
            } finally {
                loaded.complete(Unit)
            }
        }
    }

    fun cached(id: Long): String? = map[id]?.url

    /** Забыть все найденные и ненайденные баннеры — при следующем показе поищем заново. */
    fun clear() {
        map.clear()
        runCatching { file.delete() }
    }

    suspend fun get(ids: Collection<Long>): Map<Long, String?> {
        loaded.await()
        val now = System.currentTimeMillis()
        val need = ids.distinct().filter { id ->
            val e = map[id]
            e == null || (e.url == null && now - e.at > 3 * DAY) || now - e.at > 30 * DAY
        }
        if (need.isNotEmpty()) {
            mutex.withLock {
                val still = need.filter { map[it] == null || now - (map[it]?.at ?: 0) > 3 * DAY }
                if (still.isNotEmpty()) fetch(still)
            }
        }
        return ids.associateWith { map[it]?.url }
    }

    private suspend fun fetch(ids: List<Long>) {
        val now = System.currentTimeMillis()
        for (chunk in ids.chunked(50)) {
            val found = try {
                anilist(chunk)
            } catch (_: Exception) {
                null
            }
            if (found != null) {
                for (id in chunk) found[id]?.let { map[id] = BannerEntry(it, now) }
            }
            // Kitsu — по одному тайтлу за запрос, поэтому ограничиваем число обращений за раз
            val missing = chunk.filter { found?.get(it) == null }.take(16)
            for (id in missing) {
                val url = try {
                    kitsu(id)
                } catch (_: Exception) {
                    continue
                }
                map[id] = BannerEntry(url, now)
            }
        }
        save()
    }

    private suspend fun anilist(ids: List<Long>): Map<Long, String> {
        val body = buildJsonObject {
            put("query", "query (\$ids: [Int]) { Page(perPage: 50) { media(idMal_in: \$ids, type: ANIME) { idMal bannerImage } } }")
            put("variables", buildJsonObject { putJsonArray("ids") { ids.forEach { add(it) } } })
        }.toString()
        val r = http.postJson("https://graphql.anilist.co", body)
        if (r.code !in 200..299) throw ApiError(r.code, "AniList ${r.code}")
        val media = parseJson(r.body).obj?.o("data")?.o("Page")?.a("media").objects()
        return media.mapNotNull { m ->
            val id = m.long("idMal") ?: return@mapNotNull null
            val b = m.str("bannerImage") ?: return@mapNotNull null
            id to b
        }.toMap()
    }

    private suspend fun kitsu(id: Long): String? {
        val url = "https://kitsu.app/api/edge/mappings?filter%5BexternalSite%5D=myanimelist%2Fanime&filter%5BexternalId%5D=$id&include=item"
        val r = http.get(url, mapOf("Accept" to "application/vnd.api+json"))
        if (r.code !in 200..299) throw ApiError(r.code, "Kitsu ${r.code}")
        val inc = (parseJson(r.body).obj?.get("included") as? JsonArray).objects()
        val cover = inc.firstOrNull()?.o("attributes")?.o("coverImage") ?: return null
        return cover.str("original") ?: cover.str("large")
    }

    private suspend fun save() = withContext(Dispatchers.IO) {
        try {
            file.writeText(JsonCfg.encodeToString(ser, HashMap(map)))
        } catch (_: Exception) {
        }
    }

    companion object {
        const val DAY = 24 * 3600 * 1000L
    }
}
