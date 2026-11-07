package app.yoru.tv.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class AiredState(val aired: Int, val status: String?)

/**
 * Всё, что нужно экранам и каналам: тайтлы Shikimori, видео Anime365, список пользователя,
 * самодельные уведомления и подборки для хиро/каналов. Кэширует в памяти.
 */
class Repo(
    private val prefs: Prefs,
    val shiki: Shiki,
    val a365: Anime365,
    val banners: Banners,
    val library: Library,
    val art: Art,
) {
    private class Cached<T>(val value: T, val at: Long) {
        fun fresh(ttl: Long) = System.currentTimeMillis() - at < ttl
    }

    private val full = ConcurrentHashMap<Long, Cached<Anime>>()
    private val light = ConcurrentHashMap<Long, Anime>()
    private val queries = ConcurrentHashMap<String, Cached<List<Anime>>>()
    private val similarCache = ConcurrentHashMap<Long, Cached<List<Anime>>>()
    private val relatedCache = ConcurrentHashMap<Long, Cached<List<Related>>>()
    private val chronoCache = ConcurrentHashMap<Long, Cached<List<Anime>>>()
    private val charCache = ConcurrentHashMap<Long, Cached<List<CharacterRole>>>()
    private var calendarCache: Cached<List<CalendarEntry>>? = null
    private var genresCache: List<Genre>? = null

    private val seriesIds = ConcurrentHashMap<Long, Cached<Long?>>()
    private val series = ConcurrentHashMap<Long, Cached<A365Series>>()
    private val episodes = ConcurrentHashMap<Long, Cached<A365Episode>>()
    private val seriesMal = ConcurrentHashMap<Long, Long?>()
    private var feedCache: Cached<List<FeedItem>>? = null

    // ------------------------------------------------------------------ Shikimori: тайтлы

    suspend fun anime(id: Long, force: Boolean = false): Anime {
        full[id]?.takeIf { !force && it.fresh(10 * MIN) }?.let { return it.value }
        val a = shiki.anime(id) ?: throw ApiError(404, "Тайтл не найден на Shikimori")
        full[id] = Cached(a, now())
        light[id] = a
        return a
    }

    fun cachedAnime(id: Long): Anime? = full[id]?.value ?: light[id]

    suspend fun animes(q: AnimeQuery, page: Int = 1, limit: Int = 30): List<Anime> {
        val key = "$q|$page|$limit"
        queries[key]?.takeIf { it.fresh(10 * MIN) }?.let { return it.value }
        val list = shiki.animes(q, page, limit)
        list.forEach { light[it.id] = it }
        queries[key] = Cached(list, now())
        return list
    }

    /** Постеры из GraphQL (в REST у новых тайтлов заглушка). */
    suspend fun posters(ids: Collection<Long>): Map<Long, String?> {
        val missing = ids.distinct().filter { light[it]?.poster == null && full[it]?.value?.poster == null }
        for (chunk in missing.chunked(50)) {
            try {
                shiki.animes(AnimeQuery(ids = chunk), limit = chunk.size).forEach { light[it.id] = it }
            } catch (_: Exception) {
            }
        }
        return ids.associateWith { light[it]?.poster ?: full[it]?.value?.poster }
    }

    private suspend fun withPosters(list: List<Anime>): List<Anime> {
        val need = list.filter { it.poster == null }.map { it.id }
        if (need.isEmpty()) return list
        val p = posters(need)
        return list.map { if (it.poster == null) it.copy(poster = p[it.id], posterBig = p[it.id]) else it }
    }

    suspend fun similar(id: Long): List<Anime> {
        similarCache[id]?.takeIf { it.fresh(30 * MIN) }?.let { return it.value }
        val list = withPosters(shiki.similar(id).take(24))
        similarCache[id] = Cached(list, now())
        return list
    }

    suspend fun related(id: Long): List<Related> {
        relatedCache[id]?.takeIf { it.fresh(30 * MIN) }?.let { return it.value }
        val raw = shiki.related(id)
        val p = posters(raw.filter { it.anime.poster == null }.map { it.anime.id })
        val list = raw.map { r -> if (r.anime.poster == null) r.copy(anime = r.anime.copy(poster = p[r.anime.id])) else r }
        relatedCache[id] = Cached(list, now())
        return list
    }

    /** Хронология франшизы; открытый тайтл в ней всегда есть. Части кэшируются для страниц тайтлов. */
    suspend fun chronology(id: Long): List<Anime> {
        chronoCache[id]?.takeIf { it.fresh(6 * HOUR) }?.let { return it.value }
        var list = withPosters(shiki.chronology(id))
        if (list.none { it.id == id }) cachedAnime(id)?.let { a -> list = (list + a).sortedWith(compareBy<Anime>({ it.airedOn ?: "9999" }, { it.id })) }
        list.forEach { if (!full.containsKey(it.id)) light[it.id] = it }
        // одна франшиза — одна хронология для всех её частей
        val c = Cached(list, now())
        list.forEach { chronoCache[it.id] = c }
        return list
    }

    suspend fun characters(id: Long): List<CharacterRole> {
        charCache[id]?.takeIf { it.fresh(6 * HOUR) }?.let { return it.value }
        val list = shiki.characters(id).take(30)
        charCache[id] = Cached(list, now())
        return list
    }

    /** «Очистить кэш» в настройках: сопоставления с Anime365, подборки и картинки начнут грузиться заново. */
    fun clearCaches() {
        queries.clear()
        similarCache.clear()
        relatedCache.clear()
        chronoCache.clear()
        charCache.clear()
        seriesIds.clear()
        series.clear()
        episodes.clear()
        feedCache = null
        calendarCache = null
        banners.clear()
        art.clearTmdbCache()
    }

    suspend fun calendar(): List<CalendarEntry> {
        calendarCache?.takeIf { it.fresh(15 * MIN) }?.let { return it.value }
        val raw = shiki.calendar()
        val p = posters(raw.filter { it.anime.poster == null }.map { it.anime.id })
        val list = raw.map { c -> if (c.anime.poster == null) c.copy(anime = c.anime.copy(poster = p[c.anime.id])) else c }
        calendarCache = Cached(list, now())
        return list
    }

    suspend fun genres(): List<Genre> {
        genresCache?.let { return it }
        val g = shiki.genres().sortedWith(compareBy<Genre>({ kindOrder(it.kind) }, { it.title }))
        genresCache = g
        return g
    }

    private fun kindOrder(k: String?) = when (k) {
        "genre" -> 0
        "theme" -> 1
        else -> 2
    }

    private val shots = ConcurrentHashMap<Long, String>()
    private val shotsTried = ConcurrentHashMap.newKeySet<Long>()

    /**
     * Горизонтальная картинка для хиро и каналов: баннер AniList/Kitsu,
     * если его нет — кадр из Shikimori (тоже 16:9).
     */
    suspend fun backdrops(ids: Collection<Long>): Map<Long, String?> {
        // TMDB (как на AniScroll), если выбран в настройках и задан ключ; чего нет — добираем AniList/Kitsu
        val fromTmdb: Map<Long, String?> = if (prefs.s.artSource == "tmdb" && art.tmdbEnabled) {
            try {
                art.tmdbMany(ids).mapValues { it.value?.backdrop }
            } catch (_: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }
        val rest = ids.filter { fromTmdb[it] == null }
        val b = try {
            if (rest.isEmpty()) emptyMap() else banners.get(rest)
        } catch (_: Exception) {
            emptyMap()
        }
        if (rest.isEmpty()) return ids.associateWith { fromTmdb[it] }
        val missing = rest.filter { b[it] == null && !shots.containsKey(it) && it !in shotsTried && full[it]?.value?.screenshots.isNullOrEmpty() }
        for (chunk in missing.chunked(50)) {
            shotsTried.addAll(chunk)
            try {
                val data = shiki.gql("{ animes(ids: ${gqlString(chunk.joinToString(","))}, limit: ${chunk.size}) { id screenshots { originalUrl } } }")
                data.a("animes").objects().forEach { o ->
                    val id = o.long("id") ?: return@forEach
                    o.a("screenshots").objects().firstOrNull()?.str("originalUrl")?.let { shots[id] = it }
                }
            } catch (_: Exception) {
            }
        }
        return ids.associateWith { fromTmdb[it] ?: b[it] ?: shots[it] ?: full[it]?.value?.screenshots?.firstOrNull() }
    }

    // ------------------------------------------------------------------ список Shikimori

    private val _list = MutableStateFlow<ListSnapshot?>(null)
    val list: StateFlow<ListSnapshot?> = _list.asStateFlow()
    private val listMutex = Mutex()

    private val _notices = MutableStateFlow<List<Notice>>(emptyList())
    val notices: StateFlow<List<Notice>> = _notices.asStateFlow()

    /** Список на тысячи записей читается в фоне: первый кадр приложения его не ждёт. */
    private val listLoaded = CompletableDeferred<Unit>()

    init {
        prefs.io.launch {
            _list.value = prefs.read<ListSnapshot>("listSnap")
            _notices.value = prefs.read<List<Notice>>("notices") ?: emptyList()
            listLoaded.complete(Unit)
        }
    }

    val loggedIn: Boolean get() = prefs.auth.value != null

    fun rate(malId: Long): RateItem? = _list.value?.items?.firstOrNull { it.malId == malId }

    suspend fun refreshList(force: Boolean = false): ListSnapshot? = listMutex.withLock {
        listLoaded.await()
        val auth = prefs.auth.value ?: run {
            _list.value = null
            return@withLock null
        }
        val cur = _list.value
        if (!force && cur != null && cur.userId == auth.userId && now() - cur.at < 10 * MIN) return@withLock cur
        val snap = withContext(Dispatchers.Default) {
            val items = shiki.rates(auth.userId).sortedByDescending { it.updatedAt }
            ListSnapshot(auth.userId, now(), items).also { diffNotices(it) }
        }
        _list.value = snap
        prefs.writeAsync("listSnap", snap)
        snap
    }

    fun clearList() {
        _list.value = null
        prefs.rawPut("listSnap", null)
    }

    /** status = null — убрать из списка. */
    suspend fun saveRate(malId: Long, status: String?, episodes: Int, score: Int, anime: Anime? = null) {
        val auth = prefs.auth.value ?: throw ApiError(401, "Подключите Shikimori в настройках")
        val cur = rate(malId)
        if (status == null) {
            if (cur != null) shiki.deleteRate(cur.rateId)
            replaceItem(malId, null)
            return
        }
        val a = anime ?: cachedAnime(malId)
        if (cur == null) {
            val id = shiki.createRate(auth.userId, malId, status, episodes, score) ?: 0L
            replaceItem(
                malId,
                RateItem(
                    rateId = id, malId = malId, status = status, episodes = episodes, score = score,
                    updatedAt = java.time.OffsetDateTime.now().toString(),
                    title = a?.title ?: "", alt = a?.alt, kind = a?.kind, total = a?.episodes ?: 0,
                    aired = a?.episodesAired ?: 0, animeStatus = a?.status, poster = a?.poster,
                ),
            )
            if (id == 0L) refreshList(force = true)
        } else {
            shiki.updateRate(cur.rateId, status, episodes, score)
            replaceItem(malId, cur.copy(status = status, episodes = episodes, score = score, updatedAt = java.time.OffsetDateTime.now().toString()))
        }
    }

    private fun replaceItem(malId: Long, item: RateItem?) {
        val cur = _list.value ?: return
        val rest = cur.items.filter { it.malId != malId }
        val snap = cur.copy(items = if (item == null) rest else listOf(item) + rest)
        _list.value = snap
        prefs.writeAsync("listSnap", snap)
    }

    /** Автоотметка после просмотра серии. Возвращает текст для всплывашки или null. */
    suspend fun markWatched(malId: Long, n: Int, anime: Anime?): String? {
        if (prefs.auth.value == null || !prefs.s.autoMark || n <= 0) return null
        val r = rate(malId)
        if (r != null && r.episodes >= n) return null
        val total = anime?.episodes?.takeIf { it > 0 } ?: r?.total ?: 0
        val done = total > 0 && n >= total && ((anime?.status ?: r?.animeStatus) == "released")
        val status = when {
            done -> "completed"
            r?.status == "rewatching" -> "rewatching"
            else -> "watching"
        }
        saveRate(malId, status, n, r?.score ?: 0, anime)
        return if (done) "Тайтл отмечен просмотренным на Shikimori" else "Shikimori: просмотрено $n ${plural(n, "серия", "серии", "серий")}"
    }

    // ------------------------------------------------------------------ уведомления

    /**
     * Право messages новым приложениям Shikimori не дают, поэтому, как и веб-Yoru,
     * собираем уведомления сами: сравниваем episodes_aired и статус с прошлой проверкой.
     */
    private fun diffNotices(snap: ListSnapshot) {
        val key = "aired_${snap.userId}"
        val old: Map<Long, AiredState> = prefs.read<Map<Long, AiredState>>(key) ?: emptyMap()
        val tracked = setOf("watching", "rewatching", "planned", "on_hold")
        val fresh = ArrayList<Notice>()
        val next = HashMap<Long, AiredState>()
        for (it in snap.items) {
            if (it.status !in tracked) continue
            next[it.malId] = AiredState(it.aired, it.animeStatus)
            val o = old[it.malId] ?: continue
            if (it.aired > o.aired && it.aired > it.episodes) {
                val text = if (it.aired - o.aired == 1) "Вышла ${it.aired} серия" else "Вышли серии ${o.aired + 1}–${it.aired}"
                fresh += Notice("${it.malId}-ep-${it.aired}", it.malId, it.title, text, it.poster, now(), episode = it.episodes + 1)
            }
            if (o.status == "anons" && it.animeStatus == "ongoing") {
                fresh += Notice("${it.malId}-start", it.malId, it.title, "Начался показ", it.poster, now())
            }
            if (o.status == "ongoing" && it.animeStatus == "released") {
                fresh += Notice("${it.malId}-done", it.malId, it.title, "Вышел полностью", it.poster, now())
            }
        }
        prefs.write<Map<Long, AiredState>>(key, next)
        if (fresh.isNotEmpty()) {
            val ids = fresh.map { it.id }.toSet()
            val merged = (fresh + _notices.value.filter { it.id !in ids }).take(150)
            _notices.value = merged
            prefs.write("notices", merged)
        }
    }

    fun markNoticesRead() {
        val v = _notices.value.map { it.copy(read = true) }
        _notices.value = v
        prefs.write("notices", v)
    }

    fun clearNotices() {
        _notices.value = emptyList()
        prefs.write("notices", emptyList<Notice>())
    }

    // ------------------------------------------------------------------ Anime365

    /** Серии Anime365 для тайтла Shikimori (у Shikimori и MAL одинаковые id). null — на Anime365 нет. */
    suspend fun seriesFor(malId: Long, force: Boolean = false): A365Series? {
        val idc = seriesIds[malId]
        val sid: Long? = if (idc != null && !force && idc.fresh(if (idc.value == null) 30 * MIN else 6 * HOUR)) {
            idc.value
        } else {
            a365.seriesIdByMal(malId).also { seriesIds[malId] = Cached(it, now()) }
        }
        if (sid == null) return null
        series[sid]?.takeIf { !force && it.fresh(5 * MIN) }?.let { return it.value }
        val s = a365.series(sid)
        series[sid] = Cached(s, now())
        seriesMal[sid] = malId
        return s
    }

    suspend fun episode(id: Long, force: Boolean = false): A365Episode {
        episodes[id]?.takeIf { !force && it.fresh(3 * MIN) }?.let { return it.value }
        val e = a365.episode(id)
        episodes[id] = Cached(e, now())
        return e
    }

    suspend fun feed(): List<FeedItem> {
        feedCache?.takeIf { it.fresh(5 * MIN) }?.let { return it.value }
        val raw = a365.feed(80)
        val bySeries = LinkedHashMap<Long, FeedItem>()
        for (f in raw) if (!bySeries.containsKey(f.seriesId)) bySeries[f.seriesId] = f
        val items = bySeries.values.take(24)
        val sem = Semaphore(4)
        val resolved = coroutineScope {
            items.map { f ->
                async {
                    if (f.malId != null) return@async f
                    val known = seriesMal[f.seriesId]
                    if (known != null) return@async f.copy(malId = known)
                    sem.withPermit {
                        try {
                            val s = a365.series(f.seriesId)
                            series[s.id] = Cached(s, now())
                            seriesMal[s.id] = s.malId
                            f.copy(malId = s.malId, poster = f.poster ?: s.poster, seriesTitle = f.seriesTitle ?: s.title)
                        } catch (_: Exception) {
                            f
                        }
                    }
                }
            }.awaitAll()
        }.filter { it.malId != null }
        feedCache = Cached(resolved, now())
        return resolved
    }

    // ------------------------------------------------------------------ подборки для хиро и каналов

    suspend fun source(src: HomeSource, limit: Int = 20): List<Card> {
        listLoaded.await()
        val cards: List<Card> = when (src) {
            HomeSource.CONTINUE -> library.history.value.take(limit).map { h ->
                val p = library.pct(h.episodeId)
                val watch = CardAction.Watch(h.malId, episodeId = h.episodeId)
                Card(
                    malId = h.malId, title = h.title, poster = h.poster,
                    meta = h.epLabel + if (p > 0) " · $p%" else "",
                    progress = p.takeIf { it > 0 }?.let { it / 100f },
                    kicker = "Продолжить просмотр", action = watch, play = watch,
                    playLabel = "Продолжить: ${h.epLabel.lowercase()}",
                )
            }
            HomeSource.NEW_EPISODES -> listItems("watching", "rewatching", "on_hold")
                .filter { it.available > it.episodes }
                .take(limit).map { r ->
                    val next = r.episodes + 1
                    val fresh = r.available - r.episodes
                    val watch = CardAction.Watch(r.malId, episodeNum = next.toDouble())
                    Card(
                        malId = r.malId, title = r.title, alt = r.alt, poster = r.poster,
                        meta = if (fresh == 1) "Новая серия: $next" else "Новых серий: $fresh",
                        badge = "+$fresh", kicker = "Вышла ${r.available} серия",
                        action = watch, play = watch, playLabel = "Смотреть $next серию",
                    )
                }
            HomeSource.WATCHING -> listItems("watching", "rewatching").take(limit).map { r -> rateCard(r, "Смотрю") }
            HomeSource.PLANNED -> listItems("planned").take(limit).map { r -> rateCard(r, "Запланировано") }
            HomeSource.FEED -> feed().take(limit).map { f ->
                val malId = f.malId ?: 0L
                val watch = CardAction.Watch(malId, episodeId = f.episodeId, translationId = f.translation.id)
                val kind = if (f.translation.kind == "sub") "субтитры" else "озвучка"
                Card(
                    malId = malId, title = f.seriesTitle ?: "", poster = f.poster,
                    badge = f.episodeLabel,
                    meta = listOfNotNull(f.translation.authors, relTime(Anime365.parseMsk(f.translation.addedAt)).ifBlank { null }).joinToString(" · "),
                    kicker = "${f.episodeLabel}: $kind ${f.translation.authors ?: ""}".trim(),
                    action = watch, play = watch, playLabel = "Смотреть",
                )
            }
            HomeSource.ONGOING -> animes(AnimeQuery(status = "ongoing", order = "popularity"), 1, limit).map { it.toCard().copy(kicker = "Сейчас выходит") }
            HomeSource.SEASON -> animes(AnimeQuery(season = currentSeason(), order = "popularity"), 1, limit).map { it.toCard().copy(kicker = seasonTitle(currentSeason(), null)) }
            HomeSource.TOP -> animes(AnimeQuery(order = "ranked", kind = "tv,movie"), 1, limit).map { it.toCard().copy(kicker = "Лучшее на Shikimori") }
        }
        // Shikimori-постеры для всех карточек, где их нет или они с Anime365
        val need = cards.filter { it.malId > 0 && (it.poster == null || src == HomeSource.FEED) }.map { it.malId }
        if (need.isEmpty()) return cards.filter { it.malId > 0 }
        val p = posters(need)
        return cards.filter { it.malId > 0 }.map { c -> p[c.malId]?.let { c.copy(poster = it) } ?: c }
    }

    private fun listItems(vararg statuses: String): List<RateItem> =
        _list.value?.items?.filter { it.status in statuses }?.sortedByDescending { it.updatedAt } ?: emptyList()

    private fun rateCard(r: RateItem, kicker: String): Card {
        val total = if (r.total > 0) r.total.toString() else "?"
        val next = r.episodes + 1
        val canPlay = r.status != "completed" && (r.total == 0 || r.episodes < r.total)
        return Card(
            malId = r.malId, title = r.title, alt = r.alt, poster = r.poster,
            meta = if (r.status == "planned") (KIND_TITLES[r.kind] ?: "") else "${r.episodes} / $total",
            topBadge = r.score.takeIf { it > 0 }?.toString(),
            progress = if (r.total > 0 && r.status != "planned") r.episodes / r.total.toFloat() else null,
            kicker = kicker,
            play = if (canPlay) CardAction.Watch(r.malId, episodeNum = next.toDouble()) else null,
            playLabel = if (canPlay) (if (r.episodes == 0) "Смотреть" else "Смотреть $next серию") else null,
        )
    }

    /** Сегодняшние серии из расписания. */
    suspend fun today(): List<CalendarEntry> {
        val zone = ZoneId.systemDefault()
        val d = LocalDate.now(zone)
        return calendar().filter { java.time.Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == d }
    }

    companion object {
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        fun now() = System.currentTimeMillis()

        fun currentSeason(): String {
            val d = LocalDate.now()
            val s = when (d.monthValue) {
                in 1..3 -> "winter"
                in 4..6 -> "spring"
                in 7..9 -> "summer"
                else -> "fall"
            }
            return "${s}_${d.year}"
        }
    }
}
