package app.yoru.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

data class AnimeQuery(
    val search: String? = null,
    val status: String? = null,
    val kind: String? = null,
    val season: String? = null,
    val genre: String? = null,
    val order: String = "popularity",
    val ids: List<Long>? = null,
)

/**
 * Shikimori (shikimori.io): OAuth2 с redirect `urn:ietf:wg:oauth:2.0:oob`, право только user_rates.
 * Каждый запрос — с User-Agent = названию приложения; лимит 5/с и 90/мин на IP.
 */
class Shiki(private val http: Http, private val prefs: Prefs) {
    val base = "https://shikimori.io"
    private val limiter = RateLimiter()
    private val refreshMutex = Mutex()

    private fun headers(bearer: String?): Map<String, String> = buildMap {
        put("User-Agent", prefs.appName)
        if (bearer != null) put("Authorization", "Bearer $bearer")
    }

    // ------------------------------------------------------------------ транспорт

    suspend fun request(method: String, path: String, json: String? = null, auth: Boolean = false): String {
        if (auth) ensureFresh()
        var attempt = 0
        while (true) {
            limiter.acquire()
            val token = if (auth) prefs.auth.value?.accessToken else null
            if (auth && token == null) throw ApiError(401, "Shikimori не подключён")
            val url = if (path.startsWith("http")) path else base + path
            val r = http.send(url, method, json, headers(token))
            when {
                r.code in 200..299 -> return r.body
                r.code == 429 && attempt < 3 -> {
                    attempt++
                    delay(1200L * attempt)
                }
                r.code == 401 && auth && attempt == 0 -> {
                    attempt++
                    if (!refresh(force = true, usedToken = token)) {
                        throw ApiError(401, "Вход в Shikimori истёк. Подключите аккаунт заново.")
                    }
                }
                r.code == 404 -> throw ApiError(404, "Shikimori: не найдено")
                r.code == 429 -> throw ApiError(429, "Shikimori просит подождать. Попробуйте через минуту.")
                else -> throw ApiError(r.code, "Shikimori ответил ошибкой ${r.code}")
            }
        }
    }

    suspend fun gql(query: String): JsonObject {
        val body = buildJsonObject { put("query", query) }.toString()
        val text = request("POST", "/api/graphql", body)
        val root = parseOff(text).obj ?: throw ApiError(-1, "Shikimori GraphQL: пустой ответ")
        val data = root.o("data")
        val err = root.a("errors").objects().firstOrNull()?.str("message")
        if (data == null || (err != null && data.values.all { it is JsonNull })) {
            throw ApiError(-1, "Shikimori GraphQL: ${err ?: "нет данных"}")
        }
        return data
    }

    // ------------------------------------------------------------------ OAuth

    val redirectOob = "urn:ietf:wg:oauth:2.0:oob"

    fun authorizeUrl(clientId: String = prefs.clientId): String =
        "$base/oauth/authorize?client_id=${enc(clientId)}&redirect_uri=${enc(redirectOob)}&response_type=code&scope=user_rates"

    /** Обменять код со страницы Shikimori на токены и узнать, кто вошёл. */
    suspend fun exchangeCode(code: String): ShikiAuth {
        if (prefs.clientId.isBlank() || prefs.clientSecret.isBlank()) {
            throw ApiError(-1, "Не заданы client_id и client_secret приложения Shikimori")
        }
        limiter.acquire()
        val r = http.postForm(
            "$base/oauth/token",
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to prefs.clientId,
                "client_secret" to prefs.clientSecret,
                "code" to code.trim(),
                "redirect_uri" to redirectOob,
            ),
            headers(null),
        )
        if (r.code !in 200..299) throw ApiError(r.code, "Shikimori не принял код: ${oauthError(r.body)}")
        val t = parseJson(r.body).obj ?: throw ApiError(-1, "Пустой ответ /oauth/token")
        val access = t.str("access_token") ?: throw ApiError(-1, "Нет access_token в ответе")
        var auth = ShikiAuth(
            accessToken = access,
            refreshToken = t.str("refresh_token") ?: "",
            expiresAt = expiresAt(t),
        )
        limiter.acquire()
        val me = http.get("$base/api/users/whoami", headers(access))
        if (me.code in 200..299) {
            val u = parseJson(me.body).obj
            if (u != null) {
                auth = auth.copy(
                    userId = u.long("id") ?: 0,
                    nickname = u.str("nickname") ?: "",
                    avatar = u.o("image")?.str("x160") ?: u.str("avatar"),
                )
            }
        }
        if (auth.userId == 0L) throw ApiError(me.code, "Не удалось узнать пользователя Shikimori (${me.code})")
        prefs.setAuth(auth)
        return auth
    }

    /** Действующий access_token (обновит при необходимости) — им телевизор входит на сервер Yoru. */
    suspend fun freshToken(): String? {
        if (prefs.auth.value == null) return null
        try {
            ensureFresh()
        } catch (_: Exception) {
        }
        return prefs.auth.value?.accessToken
    }

    private suspend fun ensureFresh() {
        val a = prefs.auth.value ?: throw ApiError(401, "Shikimori не подключён")
        if (a.expiresAt - System.currentTimeMillis() < 120_000) refresh(force = false, usedToken = a.accessToken)
    }

    /** refresh_token меняется при каждом обновлении — сохраняем новый. */
    private suspend fun refresh(force: Boolean, usedToken: String?): Boolean = refreshMutex.withLock {
        val a = prefs.auth.value ?: return@withLock false
        if (usedToken != null && a.accessToken != usedToken) return@withLock true // уже обновил другой запрос
        if (!force && a.expiresAt - System.currentTimeMillis() > 120_000) return@withLock true
        if (a.refreshToken.isBlank()) {
            prefs.setAuth(null)
            return@withLock false
        }
        limiter.acquire()
        val r = try {
            http.postForm(
                "$base/oauth/token",
                mapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to prefs.clientId,
                    "client_secret" to prefs.clientSecret,
                    "refresh_token" to a.refreshToken,
                ),
                headers(null),
            )
        } catch (e: ApiError) {
            return@withLock false
        }
        if (r.code in 200..299) {
            val t = parseJson(r.body).obj
            val access = t?.str("access_token")
            if (t != null && access != null) {
                prefs.setAuth(a.copy(accessToken = access, refreshToken = t.str("refresh_token") ?: a.refreshToken, expiresAt = expiresAt(t)))
                return@withLock true
            }
        }
        if (r.code == 400 || r.code == 401) prefs.setAuth(null)
        false
    }

    private fun expiresAt(t: JsonObject): Long {
        val created = t.long("created_at")?.let { it * 1000 } ?: System.currentTimeMillis()
        val ttl = (t.long("expires_in") ?: 86400L) * 1000
        return created + ttl
    }

    private fun oauthError(body: String): String = try {
        val o = parseJson(body).obj
        o?.str("error_description") ?: o?.str("error") ?: body.take(200)
    } catch (_: Exception) {
        body.take(200)
    }

    // ------------------------------------------------------------------ тайтлы (GraphQL)

    suspend fun animes(q: AnimeQuery, page: Int = 1, limit: Int = 30, full: Boolean = false): List<Anime> {
        val args = buildList {
            add("page: $page")
            add("limit: ${limit.coerceIn(1, 50)}")
            add("order: ${q.order}")
            q.ids?.takeIf { it.isNotEmpty() }?.let { add("ids: ${gqlString(it.joinToString(","))}") }
            q.search?.takeIf { it.isNotBlank() }?.let { add("search: ${gqlString(it)}") }
            q.status?.let { add("status: ${gqlString(it)}") }
            q.kind?.let { add("kind: ${gqlString(it)}") }
            q.season?.let { add("season: ${gqlString(it)}") }
            q.genre?.let { add("genre: ${gqlString(it)}") }
        }.joinToString(", ")
        val data = try {
            gql("{ animes($args) { ${if (full) FULL else CARD} } }")
        } catch (e: ApiError) {
            if (!full || e.code != -1) throw e
            gql("{ animes($args) { $SAFE_FULL } }") // запасной набор полей
        }
        return data.a("animes").objects().mapNotNull { parseGql(it) }
    }

    suspend fun anime(id: Long): Anime? = try {
        animes(AnimeQuery(ids = listOf(id)), limit = 1, full = true).firstOrNull()
    } catch (primary: Exception) {
        // GraphQL иногда отдельно попадает под защиту/временный сбой Shikimori.
        // Полная REST-карточка содержит достаточно данных для страницы тайтла.
        try {
            parseOff(request("GET", "/api/animes/$id")).obj?.let { parseRest(it) }
        } catch (_: Exception) {
            throw primary
        }
    }

    // ------------------------------------------------------------------ REST

    suspend fun similar(id: Long): List<Anime> =
        parseOff(request("GET", "/api/animes/$id/similar")).let { (it as? kotlinx.serialization.json.JsonArray).objects() }
            .mapNotNull { parseRest(it) }

    suspend fun related(id: Long): List<Related> =
        (parseOff(request("GET", "/api/animes/$id/related")) as? kotlinx.serialization.json.JsonArray).objects().mapNotNull { r ->
            val a = r.o("anime")?.let { parseRest(it) } ?: return@mapNotNull null
            Related(r.str("relation_russian") ?: r.str("relation") ?: "", a)
        }

    /** Хронология франшизы: GraphQL `chronology`, при ошибке — REST /franchise. По дате выхода. */
    suspend fun chronology(id: Long): List<Anime> {
        val list = try {
            gql("{ animes(ids: ${gqlString(id.toString())}, limit: 1) { chronology { $CARD } } }")
                .a("animes").objects().firstOrNull()?.a("chronology").objects().mapNotNull { parseGql(it) }
        } catch (_: Exception) {
            null
        } ?: franchise(id)
        return list.sortedWith(compareBy<Anime>({ it.airedOn ?: "9999" }, { it.id }))
    }

    private suspend fun franchise(id: Long): List<Anime> {
        val nodes = parseOff(request("GET", "/api/animes/$id/franchise")).obj?.a("nodes").objects()
        val ids = nodes.mapNotNull { it.long("id") }
        if (ids.isEmpty()) return emptyList()
        val date = nodes.associate { (it.long("id") ?: 0L) to it.long("date") }
        return ids.chunked(50).flatMap { animes(AnimeQuery(ids = it), limit = it.size) }.map { a ->
            if (a.airedOn != null) a else a.copy(airedOn = date[a.id]?.let { d -> java.time.Instant.ofEpochSecond(d).toString().take(10) })
        }
    }

    /** Персонажи: сначала главные. */
    suspend fun characters(id: Long): List<CharacterRole> =
        gql("{ animes(ids: ${gqlString(id.toString())}, limit: 1) { characterRoles { id rolesRu rolesEn character { id name russian poster { mainUrl } } } } }")
            .a("animes").objects().firstOrNull()?.a("characterRoles").objects().mapNotNull { r ->
                val c = r.o("character") ?: return@mapNotNull null
                val en = r.a("rolesEn")?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
                CharacterRole(
                    id = c.long("id") ?: return@mapNotNull null,
                    name = c.str("russian") ?: c.str("name") ?: "",
                    poster = c.o("poster")?.str("mainUrl"),
                    // rolesRu у Shikimori часто приходит по-английски — переводим сами
                    roles = (r.a("rolesRu")?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty().ifEmpty { en })
                        .map { ROLES_RU[it] ?: it },
                    main = "Main" in en,
                )
            }.sortedByDescending { it.main }

    suspend fun calendar(): List<CalendarEntry> =
        (parseOff(request("GET", "/api/calendar")) as? kotlinx.serialization.json.JsonArray).objects().mapNotNull { c ->
            val a = c.o("anime")?.let { parseRest(it) } ?: return@mapNotNull null
            val at = c.str("next_episode_at")?.let { parseTime(it) } ?: return@mapNotNull null
            CalendarEntry(a, c.int("next_episode") ?: (a.episodesAired + 1), at)
        }

    suspend fun genres(): List<Genre> =
        (parseOff(request("GET", "/api/genres")) as? kotlinx.serialization.json.JsonArray).objects()
            .filter { (it.str("entry_type") ?: "Anime") == "Anime" }
            .map { Genre(it.long("id") ?: 0, it.str("name") ?: "", it.str("russian"), it.str("kind")) }

    // ------------------------------------------------------------------ список пользователя

    suspend fun rates(userId: Long): List<RateItem> = withContext(Dispatchers.Default) {
        (parseOff(request("GET", "/api/users/$userId/anime_rates?limit=5000", auth = true)) as? kotlinx.serialization.json.JsonArray)
            .objects().mapNotNull { r ->
                val a = r.o("anime") ?: return@mapNotNull null
                val an = parseRest(a) ?: return@mapNotNull null
                RateItem(
                    rateId = r.long("id") ?: return@mapNotNull null,
                    malId = an.id,
                    status = r.str("status") ?: "planned",
                    episodes = r.int("episodes") ?: 0,
                    score = r.int("score") ?: 0,
                    updatedAt = r.str("updated_at") ?: "",
                    title = an.title,
                    alt = an.alt,
                    kind = an.kind,
                    total = an.episodes,
                    aired = an.episodesAired,
                    animeStatus = an.status,
                    poster = an.poster,
                )
            }
    }

    suspend fun createRate(userId: Long, malId: Long, status: String, episodes: Int, score: Int): Long? {
        val body = buildJsonObject {
            put("user_rate", buildJsonObject {
                put("user_id", userId)
                put("target_id", malId)
                put("target_type", "Anime")
                put("status", status)
                put("episodes", episodes)
                put("score", score)
            })
        }.toString()
        return parseOff(request("POST", "/api/v2/user_rates", body, auth = true)).obj?.long("id")
    }

    suspend fun updateRate(rateId: Long, status: String, episodes: Int, score: Int) {
        val body = buildJsonObject {
            put("user_rate", buildJsonObject {
                put("status", status)
                put("episodes", episodes)
                put("score", score)
            })
        }.toString()
        request("PATCH", "/api/v2/user_rates/$rateId", body, auth = true)
    }

    suspend fun deleteRate(rateId: Long) {
        request("DELETE", "/api/v2/user_rates/$rateId", null, auth = true)
    }

    // ------------------------------------------------------------------ разбор

    private fun parseGql(o: JsonObject): Anime? {
        val id = o.long("id") ?: return null
        val poster = o.o("poster")
        return Anime(
            id = id,
            name = o.str("name") ?: "",
            russian = o.str("russian"),
            english = o.str("english"),
            japanese = o.str("japanese"),
            kind = o.str("kind"),
            score = o.double("score")?.takeIf { it > 0 },
            status = o.str("status"),
            episodes = o.int("episodes") ?: 0,
            episodesAired = o.int("episodesAired") ?: 0,
            duration = o.int("duration"),
            year = o.o("airedOn")?.int("year"),
            airedOn = o.o("airedOn")?.str("date"),
            releasedOn = o.o("releasedOn")?.str("date"),
            season = o.str("season"),
            poster = poster?.str("mainUrl") ?: poster?.str("originalUrl"),
            posterBig = poster?.str("originalUrl") ?: poster?.str("mainUrl"),
            genres = o.a("genres").objects().map { Genre(it.long("id") ?: 0, it.str("name") ?: "", it.str("russian"), it.str("kind")) },
            studios = o.a("studios").objects().mapNotNull { it.str("name") },
            description = cleanDescription(o.str("description")),
            nextEpisodeAt = o.str("nextEpisodeAt"),
            screenshots = o.a("screenshots").objects().mapNotNull { it.str("originalUrl") ?: it.str("x332Url") },
        )
    }

    /** REST-объект аниме. Постеры там часто заглушка `missing_original` — их потом берём из GraphQL. */
    fun parseRest(o: JsonObject): Anime? {
        val id = o.long("id") ?: return null
        val img = o.o("image")?.let { it.str("original") ?: it.str("preview") }
        val poster = img?.takeUnless { it.contains("missing") }?.let { if (it.startsWith("http")) it else base + it }
        return Anime(
            id = id,
            name = o.str("name") ?: "",
            russian = o.str("russian"),
            kind = o.str("kind"),
            score = o.double("score")?.takeIf { it > 0 },
            status = o.str("status"),
            episodes = o.int("episodes") ?: 0,
            episodesAired = o.int("episodes_aired") ?: 0,
            year = o.str("aired_on")?.take(4)?.toIntOrNull(),
            airedOn = o.str("aired_on"),
            releasedOn = o.str("released_on"),
            poster = poster,
            posterBig = poster,
        )
    }

    companion object {
        private val ROLES_RU = mapOf("Main" to "Главная роль", "Supporting" to "Второстепенная роль")

        const val CARD = "id name russian kind score status episodes episodesAired airedOn { year date } season poster { mainUrl }"
        const val SAFE_FULL = "id name russian english japanese kind score status episodes episodesAired duration " +
            "airedOn { year date } releasedOn { year date } season poster { mainUrl originalUrl } " +
            "genres { id name russian kind } description"
        const val FULL = "$SAFE_FULL studios { name } nextEpisodeAt screenshots { originalUrl x332Url }"

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        fun parseTime(s: String): Long? = try {
            java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }

        fun cleanDescription(s: String?): String? {
            if (s.isNullOrBlank()) return null
            var t: String = s
            val paired = Regex("\\[(\\w+)(?:=[^\\]]*)?\\](.*?)\\[/\\1\\]", RegexOption.DOT_MATCHES_ALL)
            repeat(3) { t = paired.replace(t, "$2") }
            t = t.replace(Regex("\\[/?[^\\]]{1,60}\\]"), "")
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace(Regex("\n{3,}"), "\n\n")
            return t.trim().ifBlank { null }
        }
    }
}
