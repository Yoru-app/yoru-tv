package app.yoru.tv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.yoru.tv.YoruApp
import app.yoru.tv.channels.TvChannels
import app.yoru.tv.data.A365Episode
import app.yoru.tv.data.A365Series
import app.yoru.tv.data.Anime
import app.yoru.tv.data.ApiError
import app.yoru.tv.data.CdnResult
import app.yoru.tv.data.Cue
import app.yoru.tv.data.HistoryItem
import app.yoru.tv.data.Http
import app.yoru.tv.data.Stream
import app.yoru.tv.data.Subtitles
import app.yoru.tv.data.Translation
import app.yoru.tv.data.fmtTime
import app.yoru.tv.data.normAuthor
import app.yoru.tv.ui.Route
import app.yoru.tv.ui.errorText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MsgKind { RETRY, AUTH, NO_TRANSLATION, NOT_FOUND }

data class PlayerMsg(val title: String, val text: String, val kind: MsgKind, val detail: String? = null)

/**
 * Состояние плеера: серия, переводы, потоки, CDN, субтитры, прогресс.
 * Живёт, пока экран плеера в стеке; ExoPlayer освобождается в release().
 */
@OptIn(UnstableApi::class)
class PlayerState(ctx: Context, private val route: Route.Watch, private val scope: CoroutineScope) {
    private val app = YoruApp.instance
    val malId = route.malId
    val exo: ExoPlayer

    var anime by mutableStateOf<Anime?>(app.repo.cachedAnime(route.malId))
    var series by mutableStateOf<A365Series?>(null)
    var episode by mutableStateOf<A365Episode?>(null)
    var translations by mutableStateOf<List<Translation>>(emptyList())
    var current by mutableStateOf<Translation?>(null)
    var streams by mutableStateOf<List<Stream>>(emptyList())
    var stream by mutableStateOf<Stream?>(null)
    var cands by mutableStateOf<List<CdnResult>>(emptyList())
    var candIdx by mutableIntStateOf(0)
    var measuring by mutableStateOf(false)
    var cues by mutableStateOf<List<Cue>>(emptyList())

    var loading by mutableStateOf(true)
    var status by mutableStateOf<String?>("Загружаем…")
    var msg by mutableStateOf<PlayerMsg?>(null)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var pos by mutableLongStateOf(0L)
    var dur by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(0L)
    var flash by mutableStateOf<Pair<Long, String>?>(null)
    var upNext by mutableStateOf<A365Episode?>(null)
    var upNextAuto by mutableStateOf(false)
    var speed by mutableStateOf(app.prefs.s.rate)
    /** Логотип названия из TMDB — показываем по центру, когда видна панель (как на AniScroll). */
    var logo by mutableStateOf(app.art.cachedLogo(route.malId))

    private val marked = HashSet<Long>()
    private val failedHeights = HashSet<Int>()
    private var loadSeq = 0
    private var released = false
    /** Для этой серии уже сверились с сервером — второй раз перед запуском не ждём. */
    private var pulledEp = -1L

    /** Превью кадров при перемотке. */
    val thumbs = SeekThumbs(scope)
    /** Куда перематываем и когда нажимали последний раз — для показа миниатюры. */
    var seekTarget by mutableLongStateOf(0L)
    var seekAt by mutableLongStateOf(0L)

    init {
        val site = app.a365.site
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.BROWSER_UA)
            .setDefaultRequestProperties(mapOf("Referer" to "$site/"))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)
        val lite = app.prefs.s.lite
        // Старт быстрее: воспроизведение начинается, когда накопилась секунда видео (было 2,5 с).
        // На слабых ТВ буфер меньше: памяти у них мало, а большой буфер приводит к остановкам сборщика мусора.
        val load = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(if (lite) 12_000 else 20_000, if (lite) 25_000 else 50_000, 1_000, 2_000)
            .apply { if (lite) setTargetBufferBytes(24 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false) }
            .build()
        exo = ExoPlayer.Builder(ctx, androidx.media3.exoplayer.DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(load)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (!isPlaying) {
                    // пауза — сразу отдать место другим устройствам
                    saveProgress()
                    app.sync.flushSoon()
                }
                if (isPlaying) {
                    upNext = null
                    status = null
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    loading = false
                    status = null
                }
                if (playbackState == Player.STATE_ENDED) onEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                failover("Поток не загрузился (${error.errorCodeName}).")
            }
        })
        scope.launch { start() }
        scope.launch { tick() }
    }

    // ------------------------------------------------------------------ сервисное

    fun flash(text: String) {
        flash = System.nanoTime() to text
    }

    val episodesOfType: List<A365Episode>
        get() {
            val all = series?.episodes.orEmpty()
            val t = episode?.episodeType ?: return all
            return all.filter { it.episodeType == t }.ifEmpty { all }
        }

    val nextEpisode: A365Episode?
        get() {
            val l = episodesOfType
            val i = l.indexOfFirst { it.id == episode?.id }
            return if (i >= 0) l.getOrNull(i + 1) else null
        }

    val prevEpisode: A365Episode?
        get() {
            val l = episodesOfType
            val i = l.indexOfFirst { it.id == episode?.id }
            return if (i > 0) l[i - 1] else null
        }

    val title: String get() = anime?.title ?: series?.title ?: ""

    // ------------------------------------------------------------------ загрузка

    private suspend fun start() {
        scope.launch {
            try {
                anime = app.repo.anime(malId)
            } catch (_: Exception) {
            }
            // тип тайтла уже известен — можно точнее найти логотип в TMDB
            try {
                app.art.tmdb(malId)?.logo?.let { logo = it }
            } catch (_: Exception) {
            }
        }
        var s = try {
            app.repo.seriesFor(malId)
        } catch (e: Exception) {
            loading = false
            msg = PlayerMsg("Anime365 не ответил", errorText(e), MsgKind.RETRY)
            return
        }
        if (s != null && route.episodeId != null && s.episodes.none { it.id == route.episodeId }) {
            // серия свежая, а список в кэше старый
            s = try {
                app.repo.seriesFor(malId, force = true)
            } catch (_: Exception) {
                s
            }
        }
        if (s == null) {
            loading = false
            msg = PlayerMsg("Нет на Anime365", "Видео для этого тайтла не нашлось на Anime365.", MsgKind.NOT_FOUND)
            return
        }
        series = s
        val eps = s.episodes
        val ep = route.episodeId?.let { id -> eps.firstOrNull { it.id == id } }
            ?: route.episodeNum?.let { n -> eps.firstOrNull { it.n == n } ?: eps.firstOrNull { !it.n.isNaN() && it.n >= n && it.episodeType != "preview" } }
            ?: eps.firstOrNull()
        if (ep == null) {
            loading = false
            msg = PlayerMsg("Серий пока нет", "Anime365 ещё не добавил эпизоды этого тайтла.", MsgKind.NOT_FOUND)
            return
        }
        openEpisode(ep, route.translationId, autoplay = app.prefs.s.autoplay)
    }

    fun retry() {
        msg = null
        if (series == null) {
            loading = true
            scope.launch { start() }
        } else {
            val ep = episode
            if (ep != null && current == null) openEpisode(ep, null) else play(pos.takeIf { it > 3000 }, true)
        }
    }

    /** autoplay = false — серия открывается на паузе (настройка «Запускать видео сразу»). */
    fun openEpisode(ep: A365Episode, trId: Long? = null, autoplay: Boolean = true) {
        saveProgress()
        val my = ++loadSeq
        upNext = null
        msg = null
        loading = true
        status = "Загружаем ${ep.label.lowercase()}…"
        exo.stop()
        cues = emptyList()
        streams = emptyList()
        stream = null
        cands = emptyList()
        pos = 0
        dur = 0
        episode = ep
        scope.launch {
            val full = try {
                app.repo.episode(ep.id)
            } catch (e: Exception) {
                if (my == loadSeq) {
                    loading = false
                    msg = PlayerMsg("Не удалось загрузить серию", errorText(e), MsgKind.RETRY)
                }
                return@launch
            }
            if (my != loadSeq) return@launch
            val trs = full.translations.sortedWith(
                compareBy<Translation>({ kindOrder(it.kind) }, { if (it.lang == "ru") 0 else 1 }, { -(it.height ?: 0) }),
            )
            translations = trs
            // сначала узнать, где и с какой озвучкой смотрели на другом устройстве (не дольше 2,5 с)
            if (trId == null && app.sync.enabled) {
                status = "Сверяем место просмотра…"
                app.sync.pullQuick()
                if (my != loadSeq) return@launch
                pulledEp = ep.id
            }
            // Перевод: выбранный в ленте → с которым уже смотрели эту серию → тот же, что в прошлой серии →
            // последний для этого тайтла (с любого устройства) → любимый → по типу и языку, как в вебе
            val prev = current
            val pref = app.library.trPref(malId)
            val lastTr = app.library.progressOf(ep.id)?.tr
            val same = { a: String?, b: String? -> normAuthor(a).let { it.isNotBlank() && it == normAuthor(b) } }
            val kindPref = app.prefs.s.trKind
            val order = if (kindPref == "sub") listOf("sub", "voice", "raw") else listOf("voice", "sub", "raw")
            val last = app.library.lastTrPref()
            val pick = trId?.let { id -> trs.firstOrNull { it.id == id } }
                ?: lastTr?.let { id -> trs.firstOrNull { it.id == id } }
                ?: prev?.let { c -> trs.firstOrNull { it.kind == c.kind && same(it.authors, c.authors) } }
                ?: pref?.let { p -> trs.firstOrNull { it.kind == p.kind && same(it.authors, p.author) } ?: trs.firstOrNull { same(it.authors, p.author) } }
                ?: app.library.pickTr(trs, kindPref)
                ?: pref?.let { p -> trs.firstOrNull { it.kind == p.kind && it.lang == p.lang } }
                ?: last?.takeIf { kindPref == "auto" }?.let { p -> trs.firstOrNull { it.kind == p.kind && it.lang == p.lang } }
                ?: order.firstNotNullOfOrNull { k -> trs.filter { it.kind == k }.let { g -> g.firstOrNull { it.lang == "ru" } ?: g.firstOrNull() } }
                ?: trs.firstOrNull()
            if (pick == null) {
                current = null
                loading = false
                msg = PlayerMsg(
                    "Переводов пока нет",
                    "Для этой серии на Anime365 ещё не добавили ни озвучки, ни субтитров. Загляните позже или выберите другую серию.",
                    MsgKind.NO_TRANSLATION,
                )
                return@launch
            }
            current = pick
            pushHistory(ep)
            play(null, autoplay)
        }
    }

    fun selectTranslation(t: Translation) {
        if (t.id == current?.id) return
        current = t
        app.library.setTrPref(malId, t)
        // выбранный вручную перевод тоже немного засчитывается в любимые
        app.library.learnTr(t, 0.5)
        // другие устройства продолжат с этой же озвучкой
        episode?.let { pushHistory(it) }
        val at = exo.currentPosition.takeIf { it > 3000 }
        play(at, true)
        flash("${Translation.KINDS[t.kind] ?: ""}: ${t.name}")
    }

    private fun play(startAtMs: Long?, autoplay: Boolean) {
        val my = ++loadSeq
        val tr = current ?: return
        val ep = episode ?: return
        msg = null
        loading = true
        status = "Получаем видео…"
        exo.stop()
        cues = emptyList()
        scope.launch {
            val embed = try {
                app.a365.embed(tr.id)
            } catch (e: Exception) {
                if (my == loadSeq) {
                    loading = false
                    msg = embedError(e)
                }
                return@launch
            }
            if (my != loadSeq) return@launch
            if (embed.streams.isEmpty()) {
                loading = false
                msg = PlayerMsg("Видео недоступно", "Anime365 не вернул ни одного потока для этого перевода. Выберите другой перевод.", MsgKind.RETRY)
                return@launch
            }
            // Потоки API Anime365 заканчиваются на 1080p, даже если перевод выложен в 2K/4K.
            // 2K и 4K сайт отдаёт отдельным MP4, но телевизор их не тянет — в списке только
            // классические 1080p, 720p, 480p и 360p.
            val all = embed.streams.filter { it.height in ALLOWED_HEIGHTS }
            if (all.isEmpty()) {
                loading = false
                msg = PlayerMsg(
                    "Нет совместимого качества",
                    "Для этой озвучки доступны только нестандартные или слишком тяжёлые потоки.",
                    MsgKind.RETRY,
                )
                return@launch
            }
            streams = all
            failedHeights.clear()
            val want = app.prefs.s.quality.takeIf { it < MAX_HEIGHT } ?: 0
            val s = (if (want > 0) all.firstOrNull { it.height == want } else null)
                ?: all.firstOrNull { it.height <= 1080 }
                ?: all.first()
            var start = startAtMs
            // вдруг эту серию досматривали в вебе — подтянуть место с сервера Yoru (не дольше 2,5 с)
            if (start == null && app.sync.enabled && pulledEp != ep.id) {
                status = "Сверяем место просмотра…"
                app.sync.pullQuick()
                if (my != loadSeq) return@launch
            }
            if (start == null) {
                val p = app.library.progressOf(ep.id)
                if (p != null && p.t > 10 && p.d > 0 && p.t < p.d - 40) {
                    start = p.t * 1000
                    flash("Продолжаем с ${fmtTime(p.t)}")
                }
            }
            // субтитры параллельно с запуском видео
            embed.subtitlesUrl?.let { u ->
                scope.launch {
                    val text = try {
                        app.a365.subtitles(u)
                    } catch (_: Exception) {
                        null
                    }
                    val parsed = withContext(Dispatchers.Default) { text?.let { Subtitles.parse(it) } ?: emptyList() }
                    if (my == loadSeq) cues = parsed
                }
            }
            useStream(s, start ?: 0L, autoplay, my)
        }
    }

    private suspend fun useStream(s: Stream, startMs: Long, autoplay: Boolean, my: Int) {
        stream = s
        var list = s.urls.map { CdnResult(it, null) }
        if (app.prefs.s.fastestCdn && s.urls.size > 1) {
            val recent = app.cdn.recentBest(s.urls)
            if (recent != null) {
                // недавно уже мерили: следующая серия запускается сразу с тем же сервером
                list = list.sortedByDescending { it.url == recent }
            } else {
                status = "Выбираем самый быстрый сервер…"
                measuring = true
                val m = app.cdn.measure(s.urls, app.a365.mediaHeaders)
                measuring = false
                if (my != loadSeq) return
                list = m.sortedByDescending { it.mbps ?: -1.0 }
                app.cdn.rememberBest(list.firstOrNull()?.takeIf { it.mbps != null }?.host)
            }
        }
        current?.let { tr ->
            val mp4 = mp4Url(tr.id, s.height)
            if (s.urls.none { it == mp4 }) list = list + CdnResult(mp4, null)
            // для превью — самый лёгкий файл этой же озвучки; файл открывается только при первой перемотке
            val small = streams.minByOrNull { it.height }?.height ?: s.height
            thumbs.setSource(mp4Url(tr.id, small), app.a365.mediaHeaders + ("User-Agent" to Http.BROWSER_UA))
        }
        cands = list
        candIdx = 0
        attach(startMs, autoplay)
    }

    private fun mp4Url(trId: Long, height: Int): String =
        app.a365.withToken("${app.a365.site}/translations/mp4/$trId?format=mp4&height=$height")

    fun isMp4Fallback(c: CdnResult): Boolean = c.url.contains("/translations/mp4/")

    private fun attach(startMs: Long, autoplay: Boolean) {
        val c = cands.getOrNull(candIdx) ?: return
        status = if (isMp4Fallback(c)) "Пробуем запасной MP4…" else "Подключаемся к ${c.host}…"
        loading = true
        val b = MediaItem.Builder().setUri(c.url)
        if (c.url.contains(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        exo.setMediaItem(b.build(), startMs)
        exo.setPlaybackSpeed(speed)
        exo.prepare()
        exo.playWhenReady = autoplay
    }

    private fun failover(reason: String) {
        if (released) return
        app.cdn.rememberBest(null)
        val t = exo.currentPosition
        val play = exo.playWhenReady
        if (candIdx + 1 < cands.size) {
            candIdx++
            flash("Сервер не ответил, пробуем другой")
            attach(t, play)
            return
        }
        stream?.let { failedHeights.add(it.height) }
        val other = streams.firstOrNull { it.height !in failedHeights }
        if (other != null) {
            flash("Переключаемся на ${other.height}p")
            val my = loadSeq
            scope.launch { useStream(other, t, play, my) }
            return
        }
        loading = false
        msg = PlayerMsg("Видео не воспроизводится", "$reason Попробуйте другой перевод или сервер.", MsgKind.RETRY)
    }

    private fun embedError(e: Exception): PlayerMsg {
        val m = e.message ?: ""
        val auth = (e is ApiError && (e.code == 401 || e.code == 403)) || Regex("login|авториз|войд", RegexOption.IGNORE_CASE).containsMatchIn(m)
        val sub = Regex("subscri|подпис|premium", RegexOption.IGNORE_CASE).containsMatchIn(m)
        val hasTok = app.prefs.token.isNotBlank()
        return when {
            sub -> PlayerMsg("Нужна подписка Anime365", "Сайт отдаёт видео только подписчикам. Токен принят, но у аккаунта, похоже, нет активной подписки.", MsgKind.AUTH, m)
            auth && !hasTok -> PlayerMsg("Добавьте токен API", "Ссылки на видео Anime365 отдаёт только авторизованным клиентам. Подключите токен — удобнее с телефона.", MsgKind.AUTH, m)
            auth -> PlayerMsg("Anime365 не принял токен", "Токен мог устареть или скопироваться не полностью. Видео также доступно только аккаунтам с подпиской.", MsgKind.AUTH, m)
            e is ApiError && e.code == 0 -> PlayerMsg("Нет связи", errorText(e), MsgKind.RETRY)
            else -> PlayerMsg("Не удалось получить видео", "Сервер Anime365 ответил ошибкой.", MsgKind.RETRY, m)
        }
    }

    // ------------------------------------------------------------------ управление

    fun toggle() {
        if (exo.playbackState == Player.STATE_ENDED) {
            exo.seekTo(0)
            exo.play()
        } else if (exo.isPlaying || (exo.playWhenReady && exo.playbackState == Player.STATE_BUFFERING)) {
            exo.pause()
        } else {
            exo.play()
        }
    }

    fun seekBy(ms: Long) {
        val d = exo.duration.takeIf { it > 0 } ?: return
        val t = (exo.currentPosition + ms).coerceIn(0L, d - 500)
        exo.seekTo(t)
        pos = t
        noteSeek(t)
    }

    fun seekToFraction(f: Float) {
        val d = exo.duration.takeIf { it > 0 } ?: return
        val t = (d * f).toLong()
        exo.seekTo(t)
        noteSeek(t)
    }

    private fun noteSeek(t: Long) {
        seekTarget = t
        seekAt = System.currentTimeMillis()
        if (app.prefs.s.scrubPreview) thumbs.request(t, exo.duration.coerceAtLeast(0))
    }

    fun setRate(r: Float) {
        speed = r
        exo.setPlaybackSpeed(r)
        app.prefs.update { it.copy(rate = r) }
        flash("Скорость ${r}×")
    }

    fun pickQuality(height: Int) {
        val s = streams.firstOrNull { it.height == height } ?: return
        if (s == stream) return
        app.prefs.update { it.copy(quality = height) }
        val t = exo.currentPosition
        val p = exo.playWhenReady
        val my = ++loadSeq
        scope.launch { useStream(s, t, p, my) }
        flash("${height}p")
    }

    fun pickServer(i: Int) {
        if (i == candIdx || i !in cands.indices) return
        candIdx = i
        attach(exo.currentPosition, exo.playWhenReady)
        flash("Сервер: ${if (isMp4Fallback(cands[i])) "запасной MP4" else cands[i].host}")
    }

    fun remeasure() {
        val s = stream ?: return
        if (measuring) return
        val my = loadSeq
        scope.launch {
            measuring = true
            val m = try {
                app.cdn.measure(s.urls, app.a365.mediaHeaders)
            } finally {
                if (my == loadSeq) measuring = false
            }
            if (my != loadSeq || stream !== s) return@launch
            val cur = cands.getOrNull(candIdx)?.url
            val list = m.sortedByDescending { it.mbps ?: -1.0 } + cands.filter { isMp4Fallback(it) }
            cands = list
            candIdx = list.indexOfFirst { it.url == cur }.coerceAtLeast(0)
            val best = list.firstOrNull()
            app.cdn.rememberBest(best?.takeIf { it.mbps != null }?.host)
            if (best?.mbps != null) flash("Быстрее всего: ${best.host}")
        }
    }

    fun goNext() {
        nextEpisode?.let { openEpisode(it) }
    }

    fun goPrev() {
        prevEpisode?.let { openEpisode(it) }
    }

    fun cancelUpNext() {
        upNext = null
    }

    // ------------------------------------------------------------------ прогресс

    private suspend fun tick() {
        var lastSave = 0L
        while (!released) {
            delay(200)
            pos = exo.currentPosition.coerceAtLeast(0)
            dur = exo.duration.takeIf { it > 0 } ?: 0L
            buffered = exo.bufferedPosition.coerceAtLeast(0)
            val now = System.currentTimeMillis()
            if (dur > 0 && pos > 3000 && now - lastSave > 5000 && exo.isPlaying) {
                lastSave = now
                saveProgress()
            }
            checkMark()
        }
    }

    fun saveProgress() {
        val ep = episode ?: return
        val d = exo.duration.takeIf { it > 0 } ?: return
        val t = exo.currentPosition
        if (t > 5000 || exo.playbackState == Player.STATE_ENDED) {
            app.library.saveProgress(ep.id, (if (exo.playbackState == Player.STATE_ENDED) d else t) / 1000, d / 1000, current?.id)
        }
    }

    private fun checkMark() {
        val ep = episode ?: return
        if (dur <= 0 || pos < dur * 0.88 || ep.id in marked) return
        marked += ep.id
        // досмотренная серия засчитывается переводу: так Yoru узнаёт любимые озвучки
        current?.let { app.library.learnTr(it, 1.0) }
        val n = ep.n
        if (n.isNaN() || n <= 0) return
        scope.launch {
            try {
                app.repo.markWatched(malId, n.toInt(), anime)?.let { flash(it) }
            } catch (e: Exception) {
                flash("Shikimori: ${errorText(e)}")
            }
        }
    }

    private fun onEnded() {
        saveProgress()
        checkMark()
        val next = nextEpisode
        if (next != null) {
            upNextAuto = app.prefs.s.autoNext
            upNext = next
        } else {
            flash("Это последняя серия")
        }
    }

    private fun pushHistory(ep: A365Episode) {
        val s = series ?: return
        app.library.pushHistory(
            HistoryItem(
                malId = malId,
                seriesId = s.id,
                episodeId = ep.id,
                episodeNum = ep.n.takeUnless { it.isNaN() } ?: 0.0,
                epLabel = ep.label,
                title = anime?.title ?: s.title,
                poster = anime?.poster ?: app.repo.cachedAnime(malId)?.poster ?: s.poster,
                at = System.currentTimeMillis(),
                trKind = current?.kind,
                trLang = current?.lang,
                trAuthor = current?.authors,
            ),
        )
    }

    /** Экран скрыт (ушли на главный экран ТВ или открыли что-то поверх). */
    fun onHidden() {
        if (released) return
        exo.pause()
        saveProgress()
        app.sync.flushSoon()
    }

    fun release() {
        if (released) return
        saveProgress()
        app.sync.flushSoon()
        released = true
        exo.release()
        thumbs.release()
        app.scope.launch {
            try {
                TvChannels.updateWatchNext(app)
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        /** Начиная с этой высоты (2K) потоки не показываем: телевизор их не воспроизводит. */
        const val MAX_HEIGHT = 1440
        val ALLOWED_HEIGHTS = setOf(360, 480, 720, 1080)

        fun kindOrder(k: String) = when (k) {
            "voice" -> 0
            "sub" -> 1
            else -> 2
        }
    }
}
