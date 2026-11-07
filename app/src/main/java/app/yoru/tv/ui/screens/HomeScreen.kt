package app.yoru.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.key
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.yoru.tv.YoruApp
import app.yoru.tv.data.Anime
import app.yoru.tv.data.Card
import app.yoru.tv.data.CardAction
import app.yoru.tv.data.HomeSource
import app.yoru.tv.data.metaLine
import app.yoru.tv.ui.Entry
import app.yoru.tv.ui.Focusable
import app.yoru.tv.ui.Ic
import app.yoru.tv.ui.Icon
import app.yoru.tv.ui.Loading
import app.yoru.tv.ui.Navigator
import app.yoru.tv.ui.Poster
import app.yoru.tv.ui.Route
import app.yoru.tv.ui.Empty
import app.yoru.tv.ui.T
import app.yoru.tv.ui.TvButton
import app.yoru.tv.ui.Ty
import app.yoru.tv.ui.Yc
import app.yoru.tv.ui.ErrorBox
import app.yoru.tv.ui.memo
import app.yoru.tv.ui.rememberStartFocus
import app.yoru.tv.ui.tryFocus
import app.yoru.tv.ui.focusWhenReady
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.yoru.tv.ui.liteMode
import app.yoru.tv.ui.pageSpec
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@kotlinx.serialization.Serializable
data class HomeRow(val key: String, val title: String, val cards: List<Card>, val wide: Boolean = false)

/** Что было на главной в прошлый раз: показываем сразу при запуске. */
@kotlinx.serialization.Serializable
data class HomeCache(val userId: Long, val rows: List<HomeRow>, val hero: List<Card>, val images: Map<Long, String> = emptyMap())

private val todayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Карточки ряда «Сегодня выходит» — на главной и на его отдельной странице. */
fun todayCards(list: List<app.yoru.tv.data.CalendarEntry>): List<Card> = list.map { c ->
    Card(
        malId = c.anime.id, title = c.anime.title, alt = c.anime.alt, poster = c.anime.poster,
        badge = todayFmt.format(Instant.ofEpochMilli(c.at).atZone(ZoneId.systemDefault())),
        meta = "${c.nextEpisode} серия",
    )
}

class HomeState(private val scope: CoroutineScope) {
    private val app = YoruApp.instance
    var hero by mutableStateOf<List<Card>>(emptyList())
    var heroIndex by mutableIntStateOf(0)
    val details = mutableStateMapOf<Long, Anime>()
    val images = mutableStateMapOf<Long, String?>()
    /** Логотипы названий с TMDB для большого блока; null — логотипа нет (или не загрузился). */
    val logos = mutableStateMapOf<Long, String?>()
    var rows by mutableStateOf<List<HomeRow>>(emptyList())
    var loading by mutableStateOf(true)
    var error by mutableStateOf<Throwable?>(null)
    var loadedAt = 0L
    /** С какими выключенными рядами собрана главная — если в настройках поменяли, пересобираем. */
    var loadedHidden: Set<String> = emptySet()
    /** Выключили «Для слабых ТВ» — нужно догрузить баннеры, которые этот режим не запрашивал. */
    var loadedLite = false
    /** Вертикальная прокрутка главной и где начинается каждая секция (px от верха). */
    val scroll = androidx.compose.foundation.ScrollState(0)
    val sectionY = HashMap<String, Int>()
    var navJob: kotlinx.coroutines.Job? = null
    val rowStates = HashMap<String, LazyListState>()
    val modernList = LazyListState()
    val modernSelected = mutableStateMapOf<String, Int>()

    fun rowState(key: String) = rowStates.getOrPut(key) { LazyListState() }

    /** Точка входа фокуса в каждую секцию главной: хиро, плашка подключения, ряды. */
    private val sectionFr = HashMap<String, FocusRequester>()
    fun fr(key: String) = sectionFr.getOrPut(key) { FocusRequester() }
    private val rowFocus = HashMap<String, RowFocus>()
    fun rowFocus(key: String) = rowFocus.getOrPut(key) { RowFocus() }

    private var cacheTried = false
    private var loadJob: kotlinx.coroutines.Job? = null
    private val imagesAsked = HashSet<Long>()

    /** Прошлая главная с диска — показывается сразу, пока идёт обновление. */
    private suspend fun restoreCache() {
        val uid = app.prefs.auth.value?.userId ?: 0L
        val c = withContext(Dispatchers.IO) { runCatching { app.prefs.read<HomeCache>("homeCache") }.getOrNull() } ?: return
        if (c.userId != uid || rows.isNotEmpty()) return
        val hidden = app.prefs.s.homeHidden.toSet()
        rows = c.rows.filter { it.key !in hidden }
        hero = c.hero
        images.putAll(c.images)
        if (hero.isNotEmpty() || rows.isNotEmpty()) loading = false
    }

    private fun buildRows(got: Map<String, List<Card>>, hidden: Set<String>): List<HomeRow> {
        val old = rows.associateBy { it.key }
        val r = ArrayList<HomeRow>()
        fun add(key: String, title: String, wide: Boolean = false) {
            if (key in hidden) return
            val c = got[key] ?: old[key]?.cards
            if (c.isNullOrEmpty()) return
            // ряд без изменений — тот же объект: Compose его не перерисовывает
            r += old[key]?.takeIf { it.cards == c } ?: HomeRow(key, title, c, wide)
        }
        add(HomeSource.CONTINUE.name, HomeSource.CONTINUE.title, wide = true)
        add(HomeSource.NEW_EPISODES.name, HomeSource.NEW_EPISODES.title, wide = true)
        add(HomeSource.WATCHING.name, "Смотрю на Shikimori")
        add(HomeSource.FEED.name, "Свежие переводы Anime365")
        add("TODAY", "Сегодня выходит")
        add(HomeSource.ONGOING.name, HomeSource.ONGOING.title)
        add(HomeSource.SEASON.name, HomeSource.SEASON.title)
        add(HomeSource.PLANNED.name, HomeSource.PLANNED.title)
        add(HomeSource.TOP.name, HomeSource.TOP.title)
        return r
    }

    /** Хиро: выбранные источники, по очереди по одному тайтлу из каждого. */
    private fun updateHero(got: Map<String, List<Card>>, final: Boolean) {
        if (hero.isNotEmpty() && !final) return
        val picked = app.prefs.s.heroSources.mapNotNull { got[it] }.filter { it.isNotEmpty() }
        val next = ArrayList<Card>()
        val seen = HashSet<Long>()
        var i = 0
        while (next.size < 8 && picked.any { i < it.size }) {
            for (l in picked) {
                val c = l.getOrNull(i) ?: continue
                if (seen.add(c.malId) && next.size < 8) next += c
            }
            i++
        }
        if (next.isEmpty() && final) next += got[HomeSource.ONGOING.name].orEmpty().take(6)
        if (next.isEmpty() || next == hero) return
        // тайтл на экране остаётся на месте: под ним может стоять курсор, и кнопки не должны исчезнуть
        val cur = hero.getOrNull(heroIndex)
        val at = cur?.let { c -> next.indexOfFirst { it.malId == c.malId } } ?: -1
        when {
            cur == null -> {
                hero = next
                heroIndex = 0
            }
            at >= 0 -> {
                hero = next
                heroIndex = at
            }
            else -> {
                hero = (listOf(cur) + next).take(8)
                heroIndex = 0
            }
        }
    }

    /** Картинки для большого блока и широких карточек — как только появились тайтлы. */
    private fun requestImages(got: Map<String, List<Card>>) {
        // на слабых ТВ только постеры: ни большого блока, ни широких карточек
        if (liteMode) return
        val wide = got[HomeSource.CONTINUE.name].orEmpty() + got[HomeSource.NEW_EPISODES.name].orEmpty()
        val ids = (hero + wide).map { it.malId }.filter { imagesAsked.add(it) }
        if (ids.isEmpty()) return
        scope.launch {
            try {
                images.putAll(app.repo.backdrops(ids))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Ряды появляются по мере загрузки, а не когда готово всё сразу: один медленный источник больше не держит экран.
     * Пока источник не ответил (или ответил ошибкой), остаётся его прошлый ряд.
     */
    fun load(force: Boolean = false) {
        loadJob?.cancel()
        loadJob = scope.launch {
            error = null
            if (!cacheTried) {
                cacheTried = true
                restoreCache()
            }
            if (rows.isEmpty() && hero.isEmpty()) loading = true
            val hidden = app.prefs.s.homeHidden.toSet()
            loadedHidden = hidden
            loadedLite = liteMode
            rows = rows.filter { it.key !in hidden }
            val got = HashMap<String, List<Card>>()
            fun publish(final: Boolean = false) {
                rows = buildRows(got, hidden)
                updateHero(got, final)
                requestImages(got)
                if (loading && (hero.isNotEmpty() || final || ((!app.prefs.s.heroOn || liteMode) && rows.isNotEmpty()))) loading = false
            }
            suspend fun fetch(src: HomeSource) {
                val c = try {
                    if (src.needsLogin && !app.repo.loggedIn) emptyList() else app.repo.source(src, 20)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (c != null) {
                    got[src.name] = c
                    publish()
                }
            }
            try {
                coroutineScope {
                    launch {
                        // что начато здесь — сразу, а что смотрели в вебе — как только ответит сервер
                        fetch(HomeSource.CONTINUE)
                        if (app.sync.enabled) {
                            app.sync.pullQuick(3000)
                            fetch(HomeSource.CONTINUE)
                        }
                    }
                    launch {
                        if (app.repo.loggedIn) {
                            try {
                                app.repo.refreshList(force)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) {
                            }
                        }
                        fetch(HomeSource.NEW_EPISODES)
                        fetch(HomeSource.WATCHING)
                        fetch(HomeSource.PLANNED)
                    }
                    listOf(HomeSource.FEED, HomeSource.ONGOING, HomeSource.SEASON, HomeSource.TOP).forEach { src -> launch { fetch(src) } }
                    launch {
                        try {
                            got["TODAY"] = todayCards(app.repo.today())
                            publish()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                }
                publish(final = true)
                loadedAt = System.currentTimeMillis()
                app.prefs.writeAsync(
                    "homeCache",
                    HomeCache(app.prefs.auth.value?.userId ?: 0L, rows, hero, images.toMap().mapNotNull { (k, v) -> v?.let { k to it } }.toMap()),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
                loading = false
            }
        }
    }

    /** После плеера обновляем только «Продолжить просмотр» — без перезагрузки всей главной. */
    fun refreshContinue() {
        scope.launch {
            val cards = try {
                app.repo.source(HomeSource.CONTINUE, 20)
            } catch (_: Exception) {
                return@launch
            }
            val key = HomeSource.CONTINUE.name
            if (key in app.prefs.s.homeHidden) return@launch
            val cur = rows
            // ничего не поменялось — не пересоздаём ряд, иначе карточка под курсором исчезает вместе с фокусом
            val old = cur.firstOrNull { it.key == key }?.cards
            if (old != null && old == cards) return@launch
            rows = when {
                cards.isEmpty() -> cur.filter { it.key != key }
                cur.any { it.key == key } -> cur.map { if (it.key == key) it.copy(cards = cards) else it }
                else -> listOf(HomeRow(key, HomeSource.CONTINUE.title, cards, wide = true)) + cur
            }
            val need = cards.map { it.malId }.filter { images[it] == null }
            if (need.isNotEmpty()) {
                try {
                    images.putAll(app.repo.backdrops(need))
                } catch (_: Exception) {
                }
            }
        }
    }

    fun loadLogo(id: Long) {
        if (logos.containsKey(id) || !app.art.tmdbEnabled) return
        app.art.cachedLogo(id)?.let {
            logos[id] = it
            return
        }
        scope.launch {
            logos[id] = try {
                app.art.tmdb(id)?.logo
            } catch (_: Exception) {
                null
            }
        }
    }

    fun loadDetails(id: Long) {
        if (details.containsKey(id)) return
        scope.launch {
            try {
                details[id] = app.repo.anime(id)
                if (images[id] == null) images.putAll(app.repo.backdrops(listOf(id)))
            } catch (_: Exception) {
            }
        }
    }
}

@Composable
fun HomeScreen(entry: Entry, nav: Navigator) {
    val st = remember(entry) { entry.state { HomeState(entry.scope) } }
    val mem = entry.focus
    val scope = rememberCoroutineScope()
    val app = YoruApp.instance

    LaunchedEffect(Unit) {
        if (st.loadedAt == 0L || System.currentTimeMillis() - st.loadedAt > 5 * 60_000 ||
            app.prefs.s.homeHidden.toSet() != st.loadedHidden || liteMode != st.loadedLite
        ) st.load()
    }
    // история могла измениться, пока мы были в плеере
    LaunchedEffect(Unit) {
        app.library.history.collect { if (st.loadedAt > 0) st.refreshContinue() }
    }

    val settings by app.prefs.settings.collectAsState()
    val startFocus = rememberStartFocus(mem, ready = !st.loading)
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    LaunchedEffect(st.loading) { if (!st.loading) activity?.reportFullyDrawn() }
    BackHandler(enabled = st.scroll.value > 0) {
        scope.launch { interruptible { st.scroll.animateScrollTo(0, pageSpec(420)) } }
        startFocus.tryFocus()
    }

    if (st.loading && st.rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Loading() }
        return
    }
    st.error?.let { e ->
        if (st.rows.isEmpty()) {
            val fr = remember { FocusRequester() }
            LaunchedEffect(e) { fr.tryFocus() }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ErrorBox(e, { st.load(true) }, focus = fr) }
            return
        }
    }

    // Modern имеет отдельную ленивую полноэкранную поверхность: классическая Column
    // намеренно не используется, чтобы фоны всех разделов не жили в памяти одновременно.
    if (settings.homeVisual == "modern" && !settings.lite && st.rows.isNotEmpty()) {
        // Фокус карточки не должен сам сдвигать вертикальную страницу: разделы
        // переключаются только нашей функцией go() и всегда выравниваются по верху.
        StaticPage { ModernHome(entry, st, nav, mem, startFocus) }
        return
    }

    // Большой блок можно выключить в настройках — тогда главная начинается с рядов
    val heroShown = settings.heroOn && !settings.lite && st.hero.isNotEmpty()

    // Секции сверху вниз
    val sections = buildList {
        if (heroShown) add("hero")
        if (!app.repo.loggedIn) add("connect")
        st.rows.forEach { add(it.key) }
    }

    suspend fun focusSection(key: String): Boolean = when (key) {
        "hero" -> startFocus.focusWhenReady()
        "connect" -> st.fr("connect").focusWhenReady()
        else -> st.rowFocus(key).enter(st.rowState(key))
    }

    /**
     * Перейти к секции i. Все секции главной всегда собраны (а не создаются при прокрутке),
     * поэтому курсор встаёт сразу, а страница в это же время плавно доезжает до секции.
     */
    fun go(i: Int) {
        if (i !in sections.indices) return
        val key = sections[i]
        // новое нажатие отменяет недоделанный переход, чтобы две прокрутки не дрались
        st.navJob?.cancel()
        st.navJob = scope.launch {
            val target = if (i == 0) 0 else (st.sectionY[key] ?: 0)
            launch { interruptible { st.scroll.animateScrollTo(target, pageSpec(380)) } }
            if (!focusSection(key) && i != 0) {
                // курсор не должен пропадать — возвращаем его наверх страницы
                interruptible { st.scroll.scrollTo(0) }
                if (!startFocus.focusWhenReady()) sections.firstOrNull()?.let { focusSection(it) }
            }
        }
    }

    // если фокус всё же потерялся, первое нажатие пульта вернёт его в секцию, которая сейчас наверху экрана
    val goNow = androidx.compose.runtime.rememberUpdatedState({ i: Int -> go(i) })
    val sectionsNow = androidx.compose.runtime.rememberUpdatedState(sections)
    androidx.compose.runtime.DisposableEffect(entry) {
        entry.recover = {
            val y = st.scroll.value + 80
            val list = sectionsNow.value
            var best = 0
            list.forEachIndexed { idx, k -> if (idx > 0 && (st.sectionY[k] ?: Int.MAX_VALUE) <= y) best = idx }
            goNow.value(best)
            true
        }
        onDispose { entry.recover = null }
    }

    // Главная сама ведёт вертикальную прокрутку (go), поэтому системная «подтяжка» элемента в фокусе
    // здесь выключена: из-за неё страница подрагивала при движении по ряду влево-вправо.
    StaticPage {
    Column(Modifier.fillMaxSize().verticalScroll(st.scroll)) {
        if (heroShown) {
            Hero(st, nav, mem, startFocus, logoOn = settings.heroLogo, fit = settings.heroFit, align = settings.heroAlign, onDown = { go(1) })
        } else {
            Spacer(Modifier.height(if (settings.heroOn && !settings.lite) 30.dp else 12.dp))
        }
        val connectAt = sections.indexOf("connect")
        if (!app.repo.loggedIn) {
            Box(
                Modifier
                    .onGloballyPositioned { st.sectionY["connect"] = it.positionInParent().y.toInt() }
                    .padding(horizontal = PAD)
                    .verticalNav(onUp = if (connectAt > 0) ({ go(connectAt - 1) }) else null, onDown = { go(connectAt + 1) }),
            ) {
                Empty(
                    "Подключите Shikimori",
                    "Тогда здесь появятся «Смотрю», новые серии из вашего списка и уведомления. Код удобно ввести с телефона по QR-коду.",
                ) {
                    TvButton(
                        "Подключить", { nav.push(Route.Connect) },
                        Modifier.focusRequester(st.fr("connect")).then(if (connectAt == 0) Modifier.focusRequester(startFocus) else Modifier).memo(mem, "connect"),
                        icon = Ic.phone, primary = true,
                    )
                }
            }
        }
        st.rows.forEachIndexed { idx, row ->
            key(row.key) {
                val itemIndex = sections.indexOf(row.key)
                // на слабых ТВ только постеры: широкие карточки тянут за собой большие баннеры
                val wide = row.wide && !liteMode
                // «По ширине экрана»: сколько помещается и плитка «Все»
                val fitRows = settings.rowsMode == "page"
                val fitCount = if (wide) 3 else 5
                CardRow(
                    title = row.title,
                    rowKey = row.key,
                    cards = if (fitRows) row.cards.take(fitCount) else row.cards,
                    mem = mem,
                    state = st.rowState(row.key),
                    onClick = { nav.open(it.action) },
                    modifier = Modifier.onGloballyPositioned { st.sectionY[row.key] = it.positionInParent().y.toInt() },
                    wide = wide,
                    images = st.images,
                    continueStyle = row.key == HomeSource.CONTINUE.name,
                    rowFocus = st.rowFocus(row.key),
                    // самый верхний ряд: ↑ — в меню сверху (если оно сверху), иначе курсор остаётся в ряду
                    onUp = if (itemIndex > 0) ({ go(itemIndex - 1) }) else null,
                    onDown = { if (itemIndex < sections.lastIndex) go(itemIndex + 1) },
                    posterWidth = 132.dp,
                    firstFocus = if (itemIndex == 0) startFocus else null,
                    onAll = if (fitRows && row.cards.size > fitCount) ({ nav.push(Route.RowPage(row.key, row.title)) }) else null,
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(PAD, 30.dp, PAD, 60.dp)) {
            Column {
                if (settings.homeHidden.isNotEmpty() || !settings.heroOn) {
                    T("Скрытые ряды и большой блок возвращаются в «Настройки → Главный экран».", Ty.small, Yc.Muted, modifier = Modifier.padding(bottom = 6.dp))
                }
                T("Данные: Shikimori, видео: Anime365, баннеры: AniList и Kitsu. Yoru — неофициальный клиент.", Ty.small, Yc.Faint)
            }
        }
        // запас снизу, чтобы последний ряд тоже мог встать наверх экрана
        Spacer(Modifier.height(320.dp))
    }
    }
}

/**
 * Полноэкранный вариант главной. Вертикальный LazyColumn держит в композиции только
 * текущий раздел и ближайшие страницы; выбранная карточка определяет фон и описание.
 */
@Composable
private fun ModernHome(
    entry: Entry,
    st: HomeState,
    nav: Navigator,
    mem: app.yoru.tv.ui.FocusMemory,
    startFocus: FocusRequester,
) {
    val app = YoruApp.instance
    val settings by app.prefs.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val list = st.modernList

    fun go(index: Int) {
        if (index !in st.rows.indices) return
        st.navJob?.cancel()
        st.navJob = scope.launch {
            interruptible { list.animateScrollToItem(index) }
            st.rowFocus(st.rows[index].key).enter(st.rowState(st.rows[index].key))
        }
    }

    BackHandler(enabled = list.firstVisibleItemIndex > 0) {
        go(0)
    }

    androidx.compose.runtime.DisposableEffect(entry, st.rows) {
        // FocusGuard возвращает курсор в видимый полноэкранный раздел.
        val old = entry.recover
        entry.recover = {
            val i = list.firstVisibleItemIndex.coerceIn(st.rows.indices)
            go(i)
            true
        }
        onDispose { if (entry.recover != old) entry.recover = old }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageHeight = maxHeight
        LazyColumn(
            state = list,
            userScrollEnabled = false,
            modifier = Modifier.fillMaxSize().clipToBounds(),
        ) {
            itemsIndexed(st.rows, key = { _, row -> row.key }) { page, row ->
                val selected = (st.modernSelected[row.key] ?: 0).coerceIn(row.cards.indices)
                val card = row.cards[selected]
                val detail = st.details[card.malId]
                val image = st.images[card.malId]
                LaunchedEffect(card.malId) { st.loadDetails(card.malId) }
                LaunchedEffect(card.malId, settings.heroLogo) {
                    if (settings.heroLogo) st.loadLogo(card.malId)
                }

                Box(Modifier.fillMaxWidth().height(pageHeight)) {
                    if (image != null) {
                        BannerArt(image, settings.heroFit, settings.heroAlign)
                    } else if (card.poster != null) {
                        AsyncImage(
                            model = card.poster,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().blur(28.dp),
                        )
                    }
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.horizontalGradient(
                                0f to Color(0xF2141020),
                                0.48f to Color(0xA6141020),
                                1f to Color(0x40141020),
                            ),
                        ),
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(0f to Color(0x30141020), 0.58f to Color(0x80141020), 1f to Yc.Night),
                        ),
                    )

                    // Название раздела не участвует в вертикальной раскладке описания и ряда:
                    // оно всегда остаётся в верхней safe-зоне полноэкранной страницы.
                    Row(
                        Modifier.align(Alignment.TopStart).padding(start = PAD, top = 28.dp, end = PAD),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).background(Yc.Lantern, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        T(row.title, Ty.h3, Yc.Text, maxLines = 1)
                    }

                    if (page > 0) {
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .zIndex(2f)
                                .padding(top = 10.dp)
                                .size(28.dp)
                                .background(Color(0x80141020), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Ic.chevron, Yc.TextSoft, 16.dp, Modifier.rotate(-90f))
                        }
                    }
                    if (page < st.rows.lastIndex) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .zIndex(2f)
                                .padding(bottom = 8.dp)
                                .size(28.dp)
                                .background(Color(0x80141020), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Ic.chevron, Yc.TextSoft, 16.dp, Modifier.rotate(90f))
                        }
                    }

                    Column(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.padding(start = PAD, end = PAD, top = 82.dp).weight(1f),
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            val logo = if (settings.heroLogo) st.logos[card.malId] else null
                            if (logo != null) {
                                AsyncImage(
                                    model = logo,
                                    contentDescription = card.title,
                                    contentScale = ContentScale.Fit,
                                    alignment = Alignment.BottomStart,
                                    onError = { st.logos[card.malId] = null },
                                    modifier = Modifier.height(96.dp).widthIn(max = 520.dp),
                                )
                                T(
                                    card.title,
                                    Ty.label,
                                    Yc.TextSoft,
                                    maxLines = 1,
                                    modifier = Modifier.padding(top = 6.dp).widthIn(max = 650.dp),
                                )
                            } else {
                                T(card.title, Ty.hero, maxLines = 2, modifier = Modifier.widthIn(max = 650.dp))
                            }
                            val meta = detail?.metaLine() ?: card.meta
                            if (!meta.isNullOrBlank()) {
                                T(meta, Ty.body, Yc.TextSoft, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
                            }
                            detail?.description?.takeIf { it.isNotBlank() }?.let {
                                T(it, Ty.body, Yc.TextSoft, maxLines = 2, modifier = Modifier.padding(top = 10.dp).widthIn(max = 680.dp))
                            }
                        }
                        CardRow(
                            title = row.title,
                            rowKey = "modern:${row.key}",
                            cards = row.cards,
                            mem = mem,
                            state = st.rowState(row.key),
                            onClick = { nav.open(it.action) },
                            wide = row.wide,
                            images = st.images,
                            continueStyle = row.key == HomeSource.CONTINUE.name,
                            rowFocus = st.rowFocus(row.key),
                            onUp = if (page > 0) ({ go(page - 1) }) else null,
                            onDown = if (page < st.rows.lastIndex) ({ go(page + 1) }) else null,
                            firstFocus = if (page == 0) startFocus else null,
                            onCardFocus = { index, focused ->
                                st.modernSelected[row.key] = index
                                st.loadDetails(focused.malId)
                            },
                            showTitle = false,
                            showCardLabels = false,
                            wideWidth = 224.dp,
                            posterWidth = if (row.wide) 132.dp else 112.dp,
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(
    st: HomeState,
    nav: Navigator,
    mem: app.yoru.tv.ui.FocusMemory,
    startFocus: FocusRequester,
    logoOn: Boolean,
    fit: String,
    align: String,
    onDown: () -> Unit,
) {
    val idx = st.heroIndex.coerceIn(0, st.hero.lastIndex)
    val card = st.hero[idx]
    var focusInside by remember { mutableStateOf(false) }
    var pickerFocused by remember { mutableStateOf(false) }

    LaunchedEffect(card.malId) { st.loadDetails(card.malId) }
    // логотипы всех тайтлов блока — заранее, чтобы при переключении не мигало название
    LaunchedEffect(logoOn, st.hero.size) { if (logoOn) st.hero.forEach { st.loadLogo(it.malId) } }
    // автопрокрутка, пока пользователь не трогает хиро
    LaunchedEffect(idx, focusInside, st.hero.size) {
        if (focusInside || st.hero.size < 2) return@LaunchedEffect
        delay(9000)
        st.heroIndex = (idx + 1) % st.hero.size
    }

    val d = st.details[card.malId]
    val image = st.images[card.malId]

    Box(
        Modifier
            .fillMaxWidth()
            .height(450.dp)
            // ↓ с миниатюр (или с кнопок, если миниатюр нет) — к первому ряду
            .verticalNav(
                // с кнопок большого блока ↑ ведёт в меню сверху, если оно включено
                onUp = null,
                onDown = if (pickerFocused || st.hero.size < 2) onDown else null,
            )
            .onFocusChanged { focusInside = it.hasFocus }
            .focusGroup(),
    ) {
        AnimatedContent(
            targetState = card.malId,
            transitionSpec = { fadeIn(tween(380)) togetherWith fadeOut(tween(380)) },
            label = "heroBg",
            modifier = Modifier.fillMaxSize(),
        ) { id ->
            val img = st.images[id]
            val poster = st.hero.firstOrNull { it.malId == id }?.poster
            Box(Modifier.fillMaxSize()) {
                if (img != null) {
                    BannerArt(img, fit, align)
                } else if (poster != null) {
                    AsyncImage(
                        model = poster, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().blur(40.dp),
                    )
                    Box(Modifier.fillMaxSize().background(Color(0x99141020)))
                }
            }
        }
        // затемнение под текст
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Color(0xE6141020), 0.38f to Color(0x99141020), 0.62f to Color(0x26141020), 1f to Color.Transparent),
            ),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Yc.Night)))

        Row(
            Modifier.fillMaxSize().padding(start = PAD, end = PAD + 12.dp, top = 30.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(Yc.Lantern, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    T(card.kicker ?: "Подборка", Ty.label, Yc.Lantern, maxLines = 1)
                }
                Spacer(Modifier.height(10.dp))
                val logo = if (logoOn) st.logos[card.malId] else null
                if (logo != null) {
                    // логотип названия, как в плеере; само название — маленькой строкой под ним
                    AsyncImage(
                        model = logo, contentDescription = card.title,
                        contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                        onError = { st.logos[card.malId] = null },
                        modifier = Modifier.height(104.dp).widthIn(max = 460.dp),
                    )
                    T(card.title, Ty.label, Yc.TextSoft, maxLines = 1, modifier = Modifier.padding(top = 8.dp).widthIn(max = 600.dp))
                } else {
                    T(card.title, Ty.hero, maxLines = 2, modifier = Modifier.widthIn(max = 600.dp))
                }
                val sub = listOfNotNull(card.alt ?: d?.alt, d?.metaLine() ?: card.meta).filter { it.isNotBlank() }.joinToString(" / ")
                if (sub.isNotBlank()) T(sub, Ty.body, Yc.Muted, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
                val desc = d?.description
                Box(Modifier.height(66.dp).padding(top = 10.dp).widthIn(max = 600.dp)) {
                    if (desc != null) T(desc, Ty.body, Yc.TextSoft, maxLines = 2)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val play = card.play
                    if (play != null) {
                        TvButton(
                            card.playLabel ?: "Смотреть", { nav.open(play) },
                            Modifier.focusRequester(startFocus).memo(mem, "hero:play"),
                            icon = Ic.play, primary = true,
                        )
                        TvButton("Подробнее", { nav.push(Route.Title(card.malId)) }, Modifier.memo(mem, "hero:more"), icon = Ic.info)
                    } else {
                        TvButton(
                            "Подробнее", { nav.push(Route.Title(card.malId)) },
                            Modifier.focusRequester(startFocus).memo(mem, "hero:more"),
                            icon = Ic.info, primary = true,
                        )
                    }
                }
                if (st.hero.size > 1) {
                    Spacer(Modifier.height(18.dp))
                    // переключатель — миниатюры баннеров, как в вебе
                    HorizontalRow {
                    LazyRow(
                        state = st.rowState("hero:picker"),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth().onFocusChanged { pickerFocused = it.hasFocus }.focusGroup(),
                    ) {
                        itemsIndexed(st.hero, key = { _, h -> h.malId }) { i, h ->
                            Focusable(
                                onClick = { nav.open(h.play ?: h.action) },
                                modifier = Modifier.requiredSize(width = 128.dp, height = 72.dp).memo(mem, "hero:pick:${h.malId}"),
                                shape = RoundedCornerShape(8.dp),
                                focusedScale = 1.12f,
                                onFocus = { st.heroIndex = i },
                            ) { f ->
                                Poster(st.images[h.malId] ?: h.poster, h.malId, h.title, Modifier.fillMaxSize(), cover = st.images[h.malId] == null)
                                if (i != idx && !f) Box(Modifier.fillMaxSize().background(Color(0x99141020)))
                                if (i == idx && !f) Box(Modifier.fillMaxSize().border(2.dp, Yc.Lantern, RoundedCornerShape(8.dp)))
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}
