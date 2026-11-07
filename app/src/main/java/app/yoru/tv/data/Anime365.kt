package app.yoru.tv.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Anime365 API: /api/series, /api/series/{id}, /api/episodes/{id},
 * /api/translations?feed=recent, /api/translations/embed/{id} (нужен access_token и подписка).
 * Ошибки приходят с HTTP 200 в виде {error:{code,message}}.
 */
class Anime365(private val http: Http, private val prefs: Prefs) {
    val site: String get() = prefs.site

    val mediaHeaders: Map<String, String>
        get() = mapOf("Referer" to "$site/", "User-Agent" to Http.BROWSER_UA)

    fun absUrl(u: String): String = when {
        u.startsWith("http://") || u.startsWith("https://") -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> site + u
        else -> "$site/$u"
    }

    fun withToken(url: String, token: String = prefs.token): String {
        if (token.isBlank() || url.contains("access_token=")) return url
        return url + (if (url.contains('?')) "&" else "?") + "access_token=" + enc(token)
    }

    suspend fun api(path: String, params: List<Pair<String, String>> = emptyList(), token: String = prefs.token): JsonElement {
        val q = buildList {
            addAll(params.map { "${enc(it.first)}=${enc(it.second)}" })
            if (token.isNotBlank()) add("access_token=${enc(token)}")
        }.joinToString("&")
        val url = "$site/api$path" + if (q.isEmpty()) "" else "?$q"
        val r = http.get(url, mapOf("User-Agent" to Http.BROWSER_UA))
        val j = try {
            parseOff(r.body)
        } catch (_: Exception) {
            throw ApiError(r.code, "Anime365 ответил ${r.code}, но не JSON")
        }
        j.obj?.o("error")?.let { e ->
            throw ApiError(e.int("code") ?: r.code, e.str("message") ?: "Ошибка API Anime365")
        }
        if (r.code !in 200..299) throw ApiError(r.code, "Ошибка Anime365 ${r.code}")
        return j.obj?.get("data") ?: j
    }

    suspend fun seriesIdByMal(malId: Long): Long? =
        (api("/series", listOf("myAnimeListId" to malId.toString(), "limit" to "1")) as? JsonArray)
            .objects().firstOrNull()?.long("id")

    suspend fun series(id: Long): A365Series =
        api("/series/$id").obj?.let { parseSeries(it) } ?: throw ApiError(404, "Тайтл не найден на Anime365")

    suspend fun episode(id: Long): A365Episode =
        api("/episodes/$id").obj?.let { parseEpisode(it, 0) } ?: throw ApiError(404, "Серия не найдена на Anime365")

    suspend fun feed(limit: Int = 80): List<FeedItem> =
        (api("/translations", listOf("feed" to "recent", "limit" to limit.toString())) as? JsonArray).objects().mapNotNull { o ->
            val tr = parseTranslation(o) ?: return@mapNotNull null
            val series = o.o("series")
            val ep = o.o("episode")
            if (ep != null && parseEpisode(ep, 0).isTrailer) return@mapNotNull null
            val sid = o.long("seriesId") ?: series?.long("id") ?: ep?.long("seriesId") ?: return@mapNotNull null
            val label = ep?.let { parseEpisode(it, sid).label } ?: "Новая серия"
            FeedItem(
                translation = tr,
                seriesId = sid,
                malId = series?.long("myAnimeListId"),
                episodeId = tr.episodeId.takeIf { it > 0 } ?: ep?.long("id") ?: return@mapNotNull null,
                episodeLabel = label,
                seriesTitle = series?.let { s -> s.o("titles")?.str("ru") ?: s.str("title") },
                poster = series?.str("posterUrl")?.let { absUrl(it) },
            )
        }

    suspend fun embed(translationId: Long, token: String = prefs.token): Embed {
        val o = api("/translations/embed/$translationId", token = token).obj ?: throw ApiError(-1, "Пустой ответ embed")
        var streams = o.a("stream").objects().mapNotNull { s ->
            val urls = (s["urls"] as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?.filter { it.isNotBlank() }?.map { absUrl(it) }.orEmpty()
            if (urls.isEmpty()) null else Stream(s.int("height") ?: 0, urls)
        }
        // файлы «скачать»: среди них бывают разрешения выше 1080p, которых нет в потоках
        val downloads = o.a("download").objects().mapNotNull { d ->
            d.str("url")?.let { u ->
                val abs = absUrl(u)
                // ссылки на сам сайт требуют токен, сторонние CDN — нет
                Stream(d.int("height") ?: 0, listOf(if (abs.startsWith(site)) withToken(abs, token) else abs))
            }
        }
        if (streams.isEmpty()) {
            streams = downloads
        } else {
            val have = streams.map { it.height }.toSet()
            streams = streams + downloads.filter { it.height > 0 && it.height !in have }
        }
        val subs = o.str("subtitlesVttUrl") ?: o.str("subtitlesUrl")
        return Embed(streams.sortedByDescending { it.height }, subs?.let { absUrl(it) })
    }

    /** Файл субтитров (VTT или ASS) — с токеном, как это делал сервер Yoru. */
    suspend fun subtitles(url: String): String? {
        val r = http.get(withToken(absUrl(url)), mediaHeaders + ("Accept" to "*/*"))
        return if (r.code in 200..299 && r.body.isNotBlank()) r.body else null
    }

    /** Проверка токена так же, как в веб-версии: берём свежий перевод и просим embed. */
    suspend fun checkToken(token: String): String {
        val first = (api("/translations", listOf("feed" to "recent", "limit" to "1"), token = "") as? JsonArray)
            .objects().firstOrNull()?.long("id") ?: throw ApiError(-1, "Не удалось получить пример перевода")
        try {
            embed(first, token)
            return "Токен работает: видео доступно."
        } catch (e: ApiError) {
            val m = e.message ?: ""
            throw ApiError(
                e.code,
                when {
                    Regex("subscri|подпис|premium", RegexOption.IGNORE_CASE).containsMatchIn(m) -> "Токен принят, но видео доступно только с подпиской. Ответ API: $m"
                    e.code == 401 || e.code == 403 -> "Anime365 не принял токен. Ответ API: $m"
                    else -> "Не удалось проверить: $m"
                },
            )
        }
    }

    // ------------------------------------------------------------------ разбор

    private fun parseSeries(o: JsonObject): A365Series {
        val id = o.long("id") ?: 0
        // трейлеры, PV и тизеры в список серий и в учёт просмотренного не попадают — как в вебе
        val eps = o.a("episodes").objects().mapNotNull { e -> parseEpisode(e, id).takeIf { active(e) && !it.isTrailer } }
        return A365Series(
            id = id,
            malId = o.long("myAnimeListId"),
            title = o.o("titles")?.str("ru") ?: o.str("title") ?: "",
            url = o.str("url")?.let { absUrl(it) },
            poster = o.str("posterUrl")?.let { absUrl(it) },
            episodes = sortEpisodes(eps),
        )
    }

    private fun parseEpisode(o: JsonObject, seriesId: Long): A365Episode = A365Episode(
        id = o.long("id") ?: 0,
        seriesId = o.long("seriesId") ?: seriesId,
        episodeInt = o.str("episodeInt"),
        episodeFull = o.str("episodeFull"),
        episodeType = o.str("episodeType") ?: "tv",
        title = o.str("episodeTitle"),
        translations = o.a("translations").objects().filter { active(it) }.mapNotNull { parseTranslation(it) },
    )

    private fun parseTranslation(o: JsonObject): Translation? {
        val id = o.long("id") ?: return null
        val type = o.str("type") ?: ""
        val kind = when (val k = o.str("typeKind")) {
            "voice", "sub", "raw" -> k
            else -> when {
                type.startsWith("voice") -> "voice"
                type.startsWith("sub") -> "sub"
                else -> "raw"
            }
        }
        if (!active(o)) return null
        return Translation(
            id = id,
            episodeId = o.long("episodeId") ?: 0,
            kind = kind,
            lang = o.str("typeLang"),
            authors = o.str("authorsSummary"),
            height = o.int("height"),
            url = o.str("url")?.let { absUrl(it) },
            addedAt = o.str("addedDateTime") ?: o.str("activeDateTime"),
        )
    }

    private fun active(o: JsonObject): Boolean = o.bool("isActive") != false

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        fun sortEpisodes(eps: List<A365Episode>): List<A365Episode> = eps.sortedWith(
            compareBy<A365Episode>({ if (it.n.isNaN()) 1e9 else it.n }, { it.id }),
        )

        /** Время Anime365 — московское, без зоны. */
        fun parseMsk(s: String?): Long {
            if (s.isNullOrBlank()) return 0
            return try {
                LocalDateTime.parse(s.trim().replace(' ', 'T')).toInstant(ZoneOffset.ofHours(3)).toEpochMilli()
            } catch (_: Exception) {
                0
            }
        }
    }
}
