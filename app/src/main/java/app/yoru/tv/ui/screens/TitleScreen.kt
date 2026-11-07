package app.yoru.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.yoru.tv.YoruApp
import app.yoru.tv.data.A365Episode
import app.yoru.tv.data.A365Series
import app.yoru.tv.data.Anime
import app.yoru.tv.data.Card
import app.yoru.tv.data.CharacterRole
import app.yoru.tv.data.chronoCounts
import app.yoru.tv.data.chronoPosition
import app.yoru.tv.data.plural
import app.yoru.tv.ui.Badge
import app.yoru.tv.data.EP_TYPES
import app.yoru.tv.data.FavItem
import app.yoru.tv.data.KIND_TITLES
import app.yoru.tv.data.Library
import app.yoru.tv.data.RATE_STATUSES
import app.yoru.tv.data.Related
import app.yoru.tv.data.STATUS_TITLES
import app.yoru.tv.data.epWord
import app.yoru.tv.data.metaLine
import app.yoru.tv.data.rateStatusTitle
import app.yoru.tv.data.seasonTitle
import app.yoru.tv.data.toCard
import app.yoru.tv.ui.Chip
import app.yoru.tv.ui.DialogItem
import app.yoru.tv.ui.Empty
import app.yoru.tv.ui.Entry
import app.yoru.tv.ui.ErrorBox
import app.yoru.tv.ui.Focusable
import app.yoru.tv.ui.Ic
import app.yoru.tv.ui.Loading
import app.yoru.tv.ui.Navigator
import app.yoru.tv.ui.Poster
import app.yoru.tv.ui.ProgressLine
import app.yoru.tv.ui.Route
import app.yoru.tv.ui.T
import app.yoru.tv.ui.TextDialog
import app.yoru.tv.ui.Toaster
import app.yoru.tv.ui.TvButton
import app.yoru.tv.ui.TvDialog
import app.yoru.tv.ui.Ty
import app.yoru.tv.ui.Yc
import app.yoru.tv.ui.errorText
import app.yoru.tv.ui.memo
import app.yoru.tv.ui.rememberStartFocus
import app.yoru.tv.ui.tryFocus
import app.yoru.tv.ui.focusWhenReady
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import app.yoru.tv.ui.liteMode
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class TitleState(val malId: Long, private val scope: CoroutineScope) {
    private val app = YoruApp.instance
    // Если Shikimori временно недоступен, локальной истории достаточно, чтобы
    // показать заголовок/постер и продолжить отдельно загружать серии Anime365.
    private fun historyFallback(): Anime? = app.library.lastFor(malId)?.let { h ->
        Anime(id = malId, name = h.title, russian = h.title, poster = h.poster, posterBig = h.poster)
    }
    var anime by mutableStateOf<Anime?>(app.repo.cachedAnime(malId) ?: historyFallback())
    var error by mutableStateOf<Throwable?>(null)
    var series by mutableStateOf<A365Series?>(null)
    var seriesState by mutableStateOf("loading") // loading | ok | none | error
    var seriesError by mutableStateOf<String?>(null)
    var similar by mutableStateOf<List<Anime>>(emptyList())
    var related by mutableStateOf<List<Related>>(emptyList())
    var chrono by mutableStateOf<List<Anime>>(emptyList())
    var characters by mutableStateOf<List<CharacterRole>>(emptyList())
    var image by mutableStateOf<String?>(null)
    /** Картинка-название с TMDB над заголовком; null — нет ключа, логотипа или не загрузилась. */
    var logo by mutableStateOf(app.art.cachedLogo(malId))
    var epType by mutableStateOf<String?>(null)
    var epChunk by mutableStateOf(-1)
    val list = LazyListState()
    val epRow = LazyListState()
    private val sectionFr = HashMap<String, FocusRequester>()
    fun fr(key: String) = sectionFr.getOrPut(key) { FocusRequester() }
    val epFocus = RowFocus()
    val relFocus = RowFocus()
    val simFocus = RowFocus()
    val simRow = LazyListState()
    val relRow = LazyListState()
    val chronoFocus = RowFocus()
    val chronoRow = LazyListState()
    val charFocus = RowFocus()
    val charRow = LazyListState()
    val shotFocus = RowFocus()
    val shotRow = LazyListState()
    var loaded = false

    fun load() {
        loaded = true
        scope.launch {
            error = null
            try {
                anime = app.repo.anime(malId)
            } catch (e: Exception) {
                if (anime == null) anime = historyFallback()
                if (anime == null) error = e
            }
            // на слабых ТВ шапка без фона и логотипа: лишние запросы и картинки ни к чему
            if (liteMode) return@launch
            try {
                image = app.repo.backdrops(listOf(malId))[malId]
            } catch (_: Exception) {
            }
            try {
                app.art.tmdb(malId)?.logo?.let { logo = it }
            } catch (_: Exception) {
            }
        }
        loadSeries()
        scope.launch {
            try {
                related = app.repo.related(malId)
            } catch (_: Exception) {
            }
        }
        scope.launch {
            try {
                similar = app.repo.similar(malId)
            } catch (_: Exception) {
            }
        }
        scope.launch {
            try {
                chrono = app.repo.chronology(malId)
            } catch (_: Exception) {
            }
        }
        scope.launch {
            try {
                characters = app.repo.characters(malId)
            } catch (_: Exception) {
            }
        }
    }

    fun loadSeries() {
        scope.launch {
            seriesState = "loading"
            try {
                val s = app.repo.seriesFor(malId)
                series = s
                seriesState = if (s == null) "none" else "ok"
            } catch (e: Exception) {
                seriesError = errorText(e)
                seriesState = "error"
            }
        }
    }
}

private val SEEN_BG = Color(0xFF1D2433)
private val SEEN_LINE = Color(0x668FD9A8)
private val SEEN_INK = Color(0xFF0C2A16)

/** Куда ведёт кнопка «Смотреть»: как в вебе — продолжение, следующая после Shikimori или первая непросмотренная. */
private data class PlayTarget(val ep: A365Episode, val label: String)

private fun playTarget(malId: Long, eps: List<A365Episode>, watchedOnShiki: Int): PlayTarget? {
    if (eps.isEmpty()) return null
    val lib = YoruApp.instance.library
    val hist = lib.lastFor(malId)
    val resume = hist?.let { h -> eps.firstOrNull { it.id == h.episodeId } }
    if (resume != null) {
        if (!lib.seen(resume.id)) return PlayTarget(resume, "Продолжить: ${resume.label.lowercase()}")
        val next = eps.getOrNull(eps.indexOf(resume) + 1)
        if (next != null) return PlayTarget(next, "Смотреть: ${next.label.lowercase()}")
    }
    if (watchedOnShiki > 0) {
        val next = eps.firstOrNull { !it.n.isNaN() && it.n > watchedOnShiki && it.episodeType != "preview" }
        if (next != null) return PlayTarget(next, "Смотреть: ${next.label.lowercase()}")
    }
    val first = eps.firstOrNull { !lib.seen(it.id) && it.episodeType != "preview" } ?: eps.first()
    return PlayTarget(first, if (eps.size > 1 && first == eps.first()) "Смотреть с первой серии" else "Смотреть: ${first.label.lowercase()}")
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TitleScreen(entry: Entry, nav: Navigator, malId: Long) {
    val st = remember(entry) { entry.state { TitleState(malId, entry.scope) } }
    val app = YoruApp.instance
    val mem = entry.focus
    val scope = rememberCoroutineScope()
    val listSnap by app.repo.list.collectAsState()
    val progressVersion by app.library.progressVersion.collectAsState()
    val favs by app.library.favs.collectAsState()
    var dialog by remember { mutableStateOf<String?>(null) }
    val rateFr = remember { FocusRequester() }
    val descFr = remember { FocusRequester() }
    var returnTo by remember { mutableStateOf<FocusRequester?>(null) }
    val settings by app.prefs.settings.collectAsState()
    var shot by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) { if (!st.loaded) st.load() }
    // после закрытия окна вернуть фокус на кнопку, которая его открыла
    LaunchedEffect(dialog) {
        if (dialog == null) {
            returnTo?.let {
                kotlinx.coroutines.delay(80)
                it.tryFocus()
            }
            returnTo = null
        }
    }

    val a = st.anime
    val start = rememberStartFocus(mem, ready = a != null)

    if (a == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val e = st.error
            if (e != null) {
                val fr = remember { FocusRequester() }
                LaunchedEffect(e) { fr.tryFocus() }
                ErrorBox(e, { st.load() }, focus = fr)
            } else {
                Loading()
            }
        }
        return
    }

    val rate = listSnap?.items?.firstOrNull { it.malId == malId }
    val eps = st.series?.episodes.orEmpty()
    @Suppress("UNUSED_VARIABLE") val pv = progressVersion // перерисовать прогресс после плеера
    val target = playTarget(malId, eps, rate?.episodes ?: 0)
    val isFav = favs.any { it.malId == malId }

    val chronoPos = chronoPosition(st.chrono, malId, settings.chronoMode)
    val shots = a.screenshots.take(18)

    // секции сверху вниз = элементы LazyColumn (порядок вкладок веба)
    val sections = buildList {
        add("head")
        add("eps")
        if (st.chrono.size > 1) add("chrono")
        if (st.related.isNotEmpty()) add("rel")
        if (st.similar.isNotEmpty()) add("sim")
        if (st.characters.isNotEmpty()) add("chars")
        if (shots.isNotEmpty()) add("shots")
    }

    var navJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun go(i: Int) {
        if (i !in sections.indices) return
        navJob?.cancel()
        navJob = scope.launch {
            val visible = st.list.layoutInfo.visibleItemsInfo.any { it.index == i }
            if (!visible) interruptible { st.list.animateScrollToItem(i) }
            // фокус на конкретную кнопку или карточку, не на группу
            suspend fun focusIt(): Boolean = when (sections[i]) {
                "head" -> start.focusWhenReady()
                "eps" -> st.epFocus.enter(st.epRow)
                "chrono" -> st.chronoFocus.enter(st.chronoRow)
                "rel" -> st.relFocus.enter(st.relRow)
                "sim" -> st.simFocus.enter(st.simRow)
                "chars" -> st.charFocus.enter(st.charRow)
                "shots" -> st.shotFocus.enter(st.shotRow)
                else -> false
            }
            var ok = focusIt()
            if (!ok) {
                interruptible { st.list.scrollToItem(i) }
                ok = focusIt()
            }
            if (!ok) {
                interruptible { st.list.scrollToItem(0) }
                start.focusWhenReady()
                return@launch
            }
            if (visible) {
                withFrameNanos { }
                withFrameNanos { }
                interruptible { st.list.smoothScrollTo(i) }
            }
        }
    }

    fun up(key: String): () -> Unit = { go(sections.indexOf(key) - 1) }
    fun down(key: String): (() -> Unit)? = sections.indexOf(key).let { i -> if (i < sections.lastIndex) ({ go(i + 1) }) else null }

    VerticalPage {
    LazyColumn(state = st.list, modifier = Modifier.fillMaxSize()) {
        // ---------------- шапка ----------------
        item(key = "head") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (liteMode) 400.dp else 440.dp)
                    .onFocusChanged { if (it.hasFocus) scope.scrollAfterFocus(st.list, 0) }
                    .focusRequester(st.fr("head"))
                    .focusGroup(),
            ) {
                val img = st.image
                // на слабых ТВ шапка без фона: только постер и текст на ровном цвете
                if (!liteMode) {
                    if (img != null) {
                        BannerArt(img, settings.titleFit, settings.titleAlign)
                    } else if (a.poster != null) {
                        AsyncImage(a.posterBig ?: a.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(50.dp))
                    }
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.horizontalGradient(0f to Color(0xF5141020), 0.5f to Color(0xD9141020), 1f to Color(0x66141020)),
                        ),
                    )
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Yc.Night)))
                }

                Row(
                    Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 40.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                ) {
                    Box(
                        Modifier
                            .size(width = 210.dp, height = 315.dp)
                            .clip(RoundedCornerShape(20.dp)),
                    ) {
                        Poster(a.posterBig ?: a.poster, a.id, a.title, Modifier.fillMaxSize())
                        // номер в хронологии франшизы, как на постере в вебе
                        if (chronoPos != null) {
                            Badge(
                                "${if (chronoPos.seasons) "Сезон" else "Часть"} ${chronoPos.n} из ${chronoPos.total}",
                                Modifier.align(Alignment.BottomStart).padding(10.dp),
                            )
                        }
                    }

                    Column(Modifier.weight(1f)) {
                        // логотип вместо названия, а само название — строкой под ним, как в вебе 2.8
                        val logo = st.logo?.takeIf { settings.titleLogo && !liteMode }
                        logo?.let { url ->
                            AsyncImage(
                                model = url, contentDescription = a.title,
                                contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                                onError = { st.logo = null },
                                modifier = Modifier.height(84.dp).widthIn(max = 440.dp).padding(bottom = 10.dp),
                            )
                        }
                        if (logo != null) T(a.title, Ty.h3, maxLines = 1) else T(a.title, Ty.page, maxLines = 2)
                        val alt = listOfNotNull(a.alt, a.japanese).joinToString(" / ")
                        if (alt.isNotBlank()) T(alt, Ty.body, Yc.Muted, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                        Spacer(Modifier.height(14.dp))
                        Facts(a, rate?.let { "${rateStatusTitle(it.status)} · ${it.episodes}${if (it.score > 0) " · ★ ${it.score}" else ""}" })
                        Spacer(Modifier.height(18.dp))
                        Row(Modifier.focusRestorer().focusGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            when {
                                target != null -> TvButton(
                                    target.label,
                                    { nav.push(Route.Watch(malId, episodeId = target.ep.id)) },
                                    Modifier.focusRequester(start).memo(mem, "play"),
                                    icon = Ic.play, primary = true,
                                )
                                st.seriesState == "loading" -> TvButton("Ищем видео…", {}, Modifier.focusRequester(start).memo(mem, "play"), icon = Ic.play, primary = true, enabled = true)
                                else -> TvButton(
                                    "Нет видео",
                                    { Toaster.show(if (st.seriesState == "error") st.seriesError ?: "Ошибка Anime365" else "Этого тайтла нет на Anime365") },
                                    Modifier.focusRequester(start).memo(mem, "play"),
                                    icon = Ic.info,
                                )
                            }
                            TvButton(
                                if (rate != null) rateStatusTitle(rate.status) else "В список",
                                {
                                    if (app.repo.loggedIn) {
                                        returnTo = rateFr
                                        dialog = "rate"
                                    } else {
                                        nav.push(Route.Connect)
                                    }
                                },
                                Modifier.focusRequester(rateFr).memo(mem, "rate"),
                                icon = if (rate != null) Ic.check else Ic.plus,
                                selected = rate != null,
                            )
                            TvButton(
                                if (isFav) "В избранном" else "В избранное",
                                {
                                    val on = app.library.toggleFav(FavItem(malId, a.title, a.poster, a.metaLine()))
                                    Toaster.show(if (on) "Добавлено в избранное" else "Убрано из избранного")
                                },
                                Modifier.memo(mem, "fav"),
                                icon = if (isFav) Ic.heartFill else Ic.heart,
                                selected = isFav,
                            )
                        }
                        val desc = a.description
                        if (desc != null) {
                            Spacer(Modifier.height(16.dp))
                            Focusable(
                                onClick = {
                                    returnTo = descFr
                                    dialog = "desc"
                                },
                                modifier = Modifier.widthIn(max = 720.dp).focusRequester(descFr).memo(mem, "desc"),
                                shape = RoundedCornerShape(12.dp),
                                focusedScale = 1.01f,
                            ) { f ->
                                Column(Modifier.background(if (f) Yc.Night3 else Color.Transparent).padding(if (f) 10.dp else 0.dp)) {
                                    T(desc, Ty.body, Yc.TextSoft, maxLines = 3)
                                    if (f) T("OK — читать полностью", Ty.small, Yc.Lantern, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                        if (a.genres.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            T(a.genres.joinToString(" · ") { it.title }, Ty.small, Yc.Muted, maxLines = 1)
                        }
                    }
                }
            }
        }

        // ---------------- серии ----------------
        item(key = "eps") {
            Column(
                Modifier
                    .padding(top = 12.dp)
                    .onFocusChanged { if (it.hasFocus) scope.scrollAfterFocus(st.list, 1) }
                    .focusRequester(st.fr("eps"))
                    .focusGroup(),
            ) {
                SectionHead("Серии", trailing = if (eps.isNotEmpty()) epWord(eps.size) else null)
                when (st.seriesState) {
                    "loading" -> Loading()
                    "none" -> Box(Modifier.padding(horizontal = PAD)) {
                        Empty("Нет на Anime365", "Видео для этого тайтла не нашлось. Список и оценки на Shikimori всё равно можно вести.")
                    }
                    "error" -> Box(Modifier.padding(horizontal = PAD)) {
                        Empty("Не удалось получить серии", st.seriesError) {
                            TvButton("Повторить", { st.loadSeries() }, icon = Ic.refresh)
                        }
                    }
                    else -> if (eps.isEmpty()) {
                        Box(Modifier.padding(horizontal = PAD)) { Empty("Серий пока нет", "Anime365 ещё не добавил эпизоды этого тайтла.") }
                    } else {
                        Episodes(
                            st, eps, mem, nav, malId, target?.ep?.id,
                            watched = rate?.episodes ?: 0,
                            total = maxOf(a.episodes, a.episodesAired),
                            inList = rate != null,
                            onUp = { go(0) }, onDown = { down("eps")?.invoke() },
                        )
                    }
                }
            }
        }

        if ("chrono" in sections) {
            item(key = "chrono") {
                // в счёт «Сезон N из M» идут не все части — остальные без номера
                var k = 0
                val counted = st.chrono.count { chronoCounts(settings.chronoMode, it) }
                CardRow(
                    title = "Хронология", rowKey = "chrono",
                    trailing = "${st.chrono.size} ${plural(st.chrono.size, "часть", "части", "частей")}, в счёт идут $counted",
                    cards = st.chrono.map { x ->
                        val inPool = chronoCounts(settings.chronoMode, x)
                        if (inPool) k++
                        val r = listSnap?.items?.firstOrNull { it.malId == x.id }
                        x.toCard().copy(
                            topBadge = if (inPool) k.toString() else "·",
                            badge = if (x.id == malId) "Вы здесь" else r?.let { rateStatusTitle(it.status) },
                            meta = listOfNotNull(KIND_TITLES[x.kind] ?: x.kind, x.year?.toString() ?: "дата неизвестна", x.episodes.takeIf { it > 1 }?.let { epWord(it) }).joinToString(" · "),
                        )
                    },
                    mem = mem, state = st.chronoRow,
                    onClick = { if (it.malId != malId) nav.push(Route.Title(it.malId)) else Toaster.show("Вы на этой странице") },
                    rowFocus = st.chronoFocus,
                    onUp = up("chrono"),
                    onDown = down("chrono"),
                )
            }
        }
        if (st.related.isNotEmpty()) {
            item(key = "rel") {
                CardRow(
                    title = "Связанное", rowKey = "rel",
                    cards = st.related.map { r -> r.anime.toCard().copy(meta = listOfNotNull(r.relation, KIND_TITLES[r.anime.kind], r.anime.year?.toString()).joinToString(" · ")) },
                    mem = mem, state = st.relRow,
                    onClick = { nav.push(Route.Title(it.malId)) },
                    rowFocus = st.relFocus,
                    onUp = up("rel"),
                    onDown = down("rel"),
                )
            }
        }
        if (st.similar.isNotEmpty()) {
            item(key = "sim") {
                CardRow(
                    title = "Похожее", rowKey = "sim",
                    cards = st.similar.map { it.toCard() },
                    mem = mem, state = st.simRow,
                    onClick = { nav.push(Route.Title(it.malId)) },
                    rowFocus = st.simFocus,
                    onUp = up("sim"),
                    onDown = down("sim"),
                )
            }
        }
        if ("chars" in sections) {
            item(key = "chars") {
                Characters(st, mem, onUp = up("chars"), onDown = down("chars"))
            }
        }
        if ("shots" in sections) {
            item(key = "shots") {
                Shots(st, shots, mem, onOpen = { shot = it }, onUp = up("shots"))
            }
        }
        item(key = "pad") { Spacer(Modifier.height(80.dp)) }
    }
    }

    shot?.let { i -> ShotViewer(shots, i, onIndex = { shot = it }, onClose = { shot = null }) }

    when (dialog) {
        "desc" -> TextDialog(a.title, a.description ?: "", onDismiss = { dialog = null })
        "rate" -> RateDialog(a, onDismiss = { dialog = null })
    }
}

@Composable
private fun Facts(a: Anime, mine: String?) {
    val facts = listOfNotNull(
        a.score?.let { "Оценка" to String.format(java.util.Locale.US, "%.2f", it) },
        KIND_TITLES[a.kind]?.let { "Тип" to it },
        seasonTitle(a.season, a.year).takeIf { it.isNotBlank() }?.let { "Сезон" to it },
        when {
            a.episodes > 0 && a.status == "ongoing" -> "Серий" to "${a.episodesAired} из ${a.episodes}"
            a.episodes > 0 -> "Серий" to a.episodes.toString()
            a.episodesAired > 0 -> "Серий" to a.episodesAired.toString()
            else -> null
        },
        STATUS_TITLES[a.status]?.let { "Статус" to it },
        a.studios.firstOrNull()?.let { "Студия" to it },
        mine?.let { "В моём списке" to it },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
        facts.forEach { (k, v) ->
            Column {
                T(k, Ty.small, Yc.Faint, maxLines = 1)
                T(v, if (k == "Оценка") Ty.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) else Ty.label, if (k == "Оценка") Yc.Lantern else Yc.Text, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Episodes(
    st: TitleState,
    eps: List<A365Episode>,
    mem: app.yoru.tv.ui.FocusMemory,
    nav: Navigator,
    malId: Long,
    current: Long?,
    watched: Int,
    total: Int,
    inList: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    val lib = YoruApp.instance.library
    val types = eps.map { it.episodeType }.distinct()
    val type = st.epType?.takeIf { it in types } ?: types.first()
    val shown = eps.filter { it.episodeType == type }
    // Просмотренное по списку Shikimori. Спешлы и клипы нумеруются отдельно, их номера со счётчиком серий не сравниваем
    fun marked(e: A365Episode) = watched > 0 && e.n > 0 && e.n <= watched && e.episodeType !in setOf("special", "music")
    // как в вебе: больше 75 серий — группы по 50, сразу открыта группа со следующей серией
    val chunks = if (shown.size > 75) shown.chunked(50) else listOf(shown)
    val curChunk = if (st.epChunk in chunks.indices) st.epChunk else maxOf(0, chunks.indexOfFirst { c -> c.any { it.id == current } })
    val inChunk = chunks.getOrElse(curChunk) { emptyList() }

    if (inList) {
        Row(Modifier.padding(start = PAD, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            app.yoru.tv.ui.Icon(Ic.check, Yc.Ok, 16.dp)
            Spacer(Modifier.width(8.dp))
            T("Просмотрено по Shikimori: $watched из ${if (total > 0) total else "?"}", Ty.small, Yc.Muted, maxLines = 1)
        }
    }
    val hasChips = types.size > 1 || chunks.size > 1
    if (hasChips) {
        // ряд групп сразу показывает ту, что открыта, а не начало списка
        val chipRow = rememberLazyListState()
        LaunchedEffect(curChunk, chunks.size) {
            if (chunks.size > 1) chipRow.scrollToItem((curChunk + if (types.size > 1) types.size else 0 - 1).coerceAtLeast(0))
        }
        HorizontalRow {
        LazyRow(
            state = chipRow,
            contentPadding = PaddingValues(horizontal = PAD),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            // ↑ — к шапке, ↓ — к сериям (обычный переход)
            modifier = Modifier.padding(bottom = 6.dp).verticalNav(onUp = onUp, onDown = null).focusRestorer().focusGroup(),
        ) {
            if (types.size > 1) {
                items(types) { t ->
                    Chip(
                        EP_TYPES[t] ?: t, t == type,
                        { st.epType = t; st.epChunk = 0 },
                        count = eps.count { it.episodeType == t }.toString(),
                        modifier = Modifier.memo(mem, "type:$t"),
                    )
                }
            }
            if (chunks.size > 1) {
                items(chunks.indices.toList()) { i ->
                    val c = chunks[i]
                    Chip(
                        "${c.first().short}–${c.last().short}" + if (c.all { marked(it) }) "  ✓" else "",
                        i == curChunk, { st.epChunk = i }, modifier = Modifier.memo(mem, "chunk:$i"),
                    )
                }
            }
        }
        }
    }
    val rowState = st.epRow
    LaunchedEffect(type, curChunk) {
        val i = inChunk.indexOfFirst { it.id == current }
        if (i > 3) rowState.scrollToItem(i - 3)
    }
    HorizontalRow {
    LazyRow(
        state = rowState,
        contentPadding = PaddingValues(horizontal = PAD, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .verticalNav(onUp = if (hasChips) null else onUp, onDown = onDown)
            .focusRestorer()
            .focusGroup(),
    ) {
        itemsIndexed(inChunk, key = { _, e -> e.id }) { index, e ->
            val p = lib.pct(e.id)
            val seen = p >= Library.SEEN_PCT
            val isCur = e.id == current
            val mark = marked(e)
            Focusable(
                onClick = { nav.push(Route.Watch(malId, episodeId = e.id)) },
                modifier = Modifier
                    .size(width = 74.dp, height = 58.dp)
                    .memo(mem, "ep:${e.id}")
                    .focusRequester(st.epFocus.fr(index))
                    .onFocusChanged { st.epFocus.onFocus(index, it.isFocused) },
                shape = RoundedCornerShape(12.dp),
                focusedScale = 1.1f,
            ) { f ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            when {
                                f -> Yc.Lantern
                                isCur -> Yc.Night3
                                mark -> SEEN_BG
                                else -> Yc.Night2
                            },
                        )
                        .border(
                            1.dp,
                            when {
                                isCur && !f -> Yc.Lantern
                                mark -> SEEN_LINE
                                else -> Yc.Line
                            },
                            RoundedCornerShape(12.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    T(
                        e.short, Ty.h3,
                        when {
                            f -> Yc.LanternInk
                            isCur -> Yc.Lantern
                            mark -> Yc.Muted
                            seen -> Yc.Faint
                            else -> Yc.Text
                        },
                        maxLines = 1,
                    )
                    if (mark) {
                        // галочка, как в вебе: серия отмечена на Shikimori
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(5.dp).size(16.dp).background(if (f) Yc.LanternInk else Yc.Ok, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { app.yoru.tv.ui.Icon(Ic.check, if (f) Yc.Lantern else SEEN_INK, 11.dp) }
                    } else if (seen) {
                        Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(6.dp).background(if (f) Yc.LanternInk else Yc.Faint, CircleShape))
                    }
                    if (p in 1 until Library.SEEN_PCT && !mark) ProgressLine(p / 100f, Modifier.align(Alignment.BottomCenter), 3.dp)
                }
            }
        }
    }
    }
    val focusedTitle = inChunk.firstOrNull { it.id == current }?.title
    if (focusedTitle != null) T(focusedTitle, Ty.small, Yc.Faint, modifier = Modifier.padding(start = PAD, top = 4.dp), maxLines = 1)
}

/** «Мой список»: статус, просмотрено серий, оценка — всё сразу уходит на Shikimori. */
@Composable
private fun RateDialog(a: Anime, onDismiss: () -> Unit) {
    val app = YoruApp.instance
    val listSnap by app.repo.list.collectAsState()
    val rate = listSnap?.items?.firstOrNull { it.malId == a.id }
    var busy by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }

    fun save(status: String?, episodes: Int, score: Int) {
        if (busy) return
        busy = true
        app.scope.launch {
            try {
                app.repo.saveRate(a.id, status, episodes, score, a)
                Toaster.show(if (status == null) "Убрано из списка" else "Сохранено на Shikimori")
            } catch (e: Exception) {
                Toaster.show(errorText(e))
            } finally {
                busy = false
            }
        }
    }

    val total = maxOf(a.episodes, a.episodesAired)
    TvDialog(onDismiss, "Мой список", a.title, width = 640.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f)) {
                RATE_STATUSES.forEachIndexed { i, (k, label) ->
                    DialogItem(
                        label, rate?.status == k,
                        {
                            val eps = when {
                                k == "completed" && total > 0 -> total
                                else -> rate?.episodes ?: 0
                            }
                            save(k, eps, rate?.score ?: 0)
                        },
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    )
                }
                if (rate != null) {
                    DialogItem("Убрать из списка", false, { save(null, 0, 0) }, icon = Ic.trash)
                }
            }
            Column(Modifier.width(220.dp)) {
                T("Просмотрено серий", Ty.small, Yc.Faint)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val cur = rate?.episodes ?: 0
                    TvButton("", { save(rate?.status ?: "watching", maxOf(0, cur - 1), rate?.score ?: 0) }, icon = Ic.minus, small = true, enabled = cur > 0)
                    T(if (total > 0) "$cur / $total" else "$cur", Ty.h3, maxLines = 1)
                    TvButton("", { save(rate?.status ?: "watching", if (total > 0) minOf(total, cur + 1) else cur + 1, rate?.score ?: 0) }, icon = Ic.plus, small = true)
                }
                Spacer(Modifier.height(18.dp))
                T("Оценка", Ty.small, Yc.Faint)
                Spacer(Modifier.height(8.dp))
                val score = rate?.score ?: 0
                listOf(listOf(10, 9, 8, 7, 6), listOf(5, 4, 3, 2, 1)).forEach { rowVals ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                        rowVals.forEach { v ->
                            Chip(v.toString(), v == score, { save(rate?.status ?: "completed", rate?.episodes ?: 0, if (v == score) 0 else v) })
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            kotlinx.coroutines.delay(60)
            first.tryFocus()
        }
    }
}

/** Персонажи: портрет, имя и роль. Главные — первыми. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Characters(st: TitleState, mem: app.yoru.tv.ui.FocusMemory, onUp: () -> Unit, onDown: (() -> Unit)?) {
    Column(Modifier.padding(top = 22.dp).verticalNav(onUp, onDown).focusGroup()) {
        SectionHead("Персонажи")
        HorizontalRow {
            LazyRow(
                state = st.charRow,
                contentPadding = PaddingValues(horizontal = PAD, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.focusRestorer().focusGroup(),
            ) {
                itemsIndexed(st.characters, key = { _, c -> c.id }) { i, c ->
                    Column(Modifier.width(112.dp)) {
                        Focusable(
                            onClick = { Toaster.show(listOf(c.name, c.roles.joinToString(", ")).filter { it.isNotBlank() }.joinToString(" — ")) },
                            modifier = Modifier
                                .size(width = 112.dp, height = 150.dp)
                                .memo(mem, "char:${c.id}")
                                .focusRequester(st.charFocus.fr(i))
                                .onFocusChanged { st.charFocus.onFocus(i, it.isFocused) },
                        ) {
                            if (c.poster != null) {
                                AsyncImage(c.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            } else {
                                app.yoru.tv.ui.Placeholder(c.id, c.name, Modifier.fillMaxSize())
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        T(c.name, Ty.cardTitle, maxLines = 2)
                        if (c.roles.isNotEmpty()) T(c.roles.joinToString(", "), Ty.small, if (c.main) Yc.Lantern else Yc.Muted, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Кадры из Shikimori: OK — на весь экран. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Shots(st: TitleState, shots: List<String>, mem: app.yoru.tv.ui.FocusMemory, onOpen: (Int) -> Unit, onUp: () -> Unit) {
    Column(Modifier.padding(top = 22.dp).verticalNav(onUp, null).focusGroup()) {
        SectionHead("Кадры", trailing = "${shots.size}")
        HorizontalRow {
            LazyRow(
                state = st.shotRow,
                contentPadding = PaddingValues(horizontal = PAD, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.focusRestorer().focusGroup(),
            ) {
                itemsIndexed(shots) { i, url ->
                    Focusable(
                        onClick = { onOpen(i) },
                        modifier = Modifier
                            .width(252.dp)
                            .height(142.dp)
                            .memo(mem, "shot:$i")
                            .focusRequester(st.shotFocus.fr(i))
                            .onFocusChanged { st.shotFocus.onFocus(i, it.isFocused) },
                        focusedScale = 1.05f,
                    ) {
                        AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().background(Yc.Night2))
                    }
                }
            }
        }
    }
}

/** Кадр на весь экран: ←/→ — соседние, «Назад» — закрыть. */
@Composable
private fun ShotViewer(shots: List<String>, index: Int, onIndex: (Int) -> Unit, onClose: () -> Unit) {
    val fr = remember { FocusRequester() }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent ev.key == Key.Back || ev.key == Key.Escape
                    when (ev.key) {
                        Key.DirectionLeft -> { if (index > 0) onIndex(index - 1); true }
                        Key.DirectionRight -> { if (index < shots.lastIndex) onIndex(index + 1); true }
                        Key.Back, Key.Escape -> { onClose(); true }
                        Key.DirectionCenter, Key.Enter -> { onClose(); true }
                        else -> true
                    }
                }
                .focusRequester(fr)
                .focusable(),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(shots[index], null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            T(
                "${index + 1} из ${shots.size}  ·  ←/→ — листать, «Назад» — закрыть",
                Ty.small, Yc.Muted,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)
                    .background(Color(0xB3141020), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
    LaunchedEffect(Unit) { fr.focusWhenReady() }
}
