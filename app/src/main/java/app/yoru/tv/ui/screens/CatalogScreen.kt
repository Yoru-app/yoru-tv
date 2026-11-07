package app.yoru.tv.ui.screens

import androidx.compose.ui.Alignment
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.yoru.tv.YoruApp
import app.yoru.tv.data.Anime
import app.yoru.tv.data.AnimeQuery
import app.yoru.tv.data.Genre
import app.yoru.tv.data.toCard
import app.yoru.tv.ui.Chip
import app.yoru.tv.ui.Empty
import app.yoru.tv.ui.Entry
import app.yoru.tv.ui.ErrorBox
import app.yoru.tv.ui.Ic
import app.yoru.tv.ui.Loading
import app.yoru.tv.ui.Navigator
import app.yoru.tv.ui.PosterCard
import app.yoru.tv.ui.Route
import app.yoru.tv.ui.T
import app.yoru.tv.ui.TvButton
import app.yoru.tv.ui.TvTextInput
import app.yoru.tv.ui.TvDropdown
import app.yoru.tv.ui.Ty
import app.yoru.tv.ui.Yc
import app.yoru.tv.ui.memo
import app.yoru.tv.ui.rememberStartFocus
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CatalogState(private val scope: CoroutineScope) {
    private val app = YoruApp.instance
    var query by mutableStateOf("")
    var order by mutableStateOf("popularity")
    var status by mutableStateOf<String?>(null)
    var kind by mutableStateOf<String?>(null)
    var season by mutableStateOf<String?>(null)
    var genres by mutableStateOf<Set<Long>>(emptySet())
    var genreList by mutableStateOf<List<Genre>>(emptyList())
    val items = mutableStateListOf<Anime>()
    var loading by mutableStateOf(false)
    var done by mutableStateOf(false)
    var error by mutableStateOf<Throwable?>(null)
    val grid = LazyGridState()
    private var page = 1
    private var seq = 0
    private var job: Job? = null
    private var searchJob: Job? = null
    var started = false

    fun start() {
        started = true
        scope.launch {
            try {
                genreList = app.repo.genres().filter { it.kind == "genre" || it.kind == null }
            } catch (_: Exception) {
            }
        }
        reset()
    }

    fun reset() {
        seq++
        job?.cancel()
        items.clear()
        page = 1
        done = false
        error = null
        loading = false
        loadMore()
    }

    fun typed(v: String) {
        query = v
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(700)
            reset()
        }
    }

    fun loadMore() {
        if (loading || done) return
        val my = seq
        loading = true
        job = scope.launch {
            try {
                val q = AnimeQuery(
                    search = query.trim().ifBlank { null },
                    status = status,
                    kind = kind,
                    season = season,
                    genre = genres.takeIf { it.isNotEmpty() }?.joinToString(","),
                    order = order,
                )
                val list = app.repo.animes(q, page, 30)
                if (my != seq) return@launch
                val seen = items.map { it.id }.toHashSet()
                items.addAll(list.filter { seen.add(it.id) })
                page++
                if (list.size < 30) done = true
            } catch (e: Exception) {
                if (my == seq) {
                    error = e
                    done = true
                }
            } finally {
                if (my == seq) loading = false
            }
        }
    }
}

/** Годы и текущий сезон, как в каталоге веба. */
private val YEARS: List<Pair<String?, String>> by lazy {
    val now = java.time.LocalDate.now()
    val y = now.year
    val name = listOf("winter" to "Зима", "spring" to "Весна", "summer" to "Лето", "fall" to "Осень")[(now.monthValue - 1) / 3]
    buildList<Pair<String?, String>> {
        add(null to "Любой год")
        add("${name.first}_$y" to "${name.second} $y")
        for (i in y downTo y - 30) add(i.toString() to i.toString())
        add("1990_${y - 31}" to "1990–${y - 31}")
        add("1980_1989" to "1980-е")
    }
}

private val ORDERS = listOf("popularity" to "Популярные", "ranked" to "По оценке", "aired_on" to "Новые")
private val STATUSES = listOf(null to "Все", "ongoing" to "Выходят", "released" to "Вышли", "anons" to "Анонсы")
private val KINDS = listOf(null to "Любой тип", "tv" to "ТВ", "movie" to "Фильмы", "ova,ona" to "OVA и ONA", "special,tv_special" to "Спешлы")

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CatalogScreen(entry: Entry, nav: Navigator) {
    val st = remember(entry) { entry.state { CatalogState(entry.scope) } }
    val mem = entry.focus
    LaunchedEffect(Unit) { if (!st.started) st.start() }
    val start = rememberStartFocus(mem)
    val settings by YoruApp.instance.prefs.settings.collectAsState()

    LazyVerticalGrid(
        columns = GridCells.Fixed(gridColumns(settings.coverSize)),
        state = st.grid,
        contentPadding = PaddingValues(start = PAD, end = PAD, top = 28.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "head") {
            Column {
                T(if (st.query.isBlank()) "Поиск и каталог" else "Поиск: ${st.query}", Ty.page, maxLines = 1)
                Spacer(Modifier.height(16.dp))
                TvTextInput(
                    value = st.query,
                    onValueChange = { st.typed(it) },
                    placeholder = "Найти аниме: «Фрирен», «Steins;Gate», «Моб Психо»",
                    icon = Ic.search,
                    imeAction = ImeAction.Search,
                    onSubmit = { st.reset() },
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().memo(mem, "search"),
                    focusRequester = start,
                )
                Spacer(Modifier.height(18.dp))
                // раскрывающиеся списки вместо длинных рядов чипов
                var pendingGenres by remember { mutableStateOf<Set<Long>?>(null) }
                // фильтров больше, чем помещается в ширину: ряд прокручивается вслед за курсором
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp).focusRestorer().focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvDropdown(
                        "Сортировка", ORDERS.first { it.first == st.order }.second, ORDERS, setOf(st.order),
                        onPick = { st.order = it; st.reset() },
                        modifier = Modifier.memo(mem, "dd:order"),
                    )
                    TvDropdown(
                        "Статус", STATUSES.first { it.first == st.status }.second, STATUSES, setOf(st.status),
                        onPick = { st.status = it; st.reset() },
                        modifier = Modifier.memo(mem, "dd:status"),
                    )
                    TvDropdown(
                        "Тип", KINDS.first { it.first == st.kind }.second, KINDS, setOf(st.kind),
                        onPick = { st.kind = it; st.reset() },
                        modifier = Modifier.memo(mem, "dd:kind"),
                    )
                    TvDropdown(
                        "Год", YEARS.first { it.first == st.season }.second, YEARS, setOf(st.season),
                        onPick = { st.season = it; st.reset() },
                        modifier = Modifier.memo(mem, "dd:season"),
                    )
                    if (st.genreList.isNotEmpty()) {
                        val shown = pendingGenres ?: st.genres
                        TvDropdown(
                            "Жанры",
                            when (shown.size) {
                                0 -> "любые"
                                1 -> st.genreList.firstOrNull { it.id in shown }?.title ?: "1"
                                else -> "${shown.size} выбрано"
                            },
                            st.genreList.map { it.id to it.title },
                            shown,
                            onPick = { id ->
                                val cur = pendingGenres ?: st.genres
                                pendingGenres = if (id in cur) cur - id else cur + id
                            },
                            multi = true,
                            // жанры применяем при закрытии списка, а не на каждое нажатие
                            onClose = {
                                val p = pendingGenres
                                pendingGenres = null
                                if (p != null && p != st.genres) {
                                    st.genres = p
                                    st.reset()
                                }
                            },
                            modifier = Modifier.memo(mem, "dd:genres"),
                        )
                    }
                    CardSizeDropdown(mem)
                    if (st.genres.isNotEmpty() || st.status != null || st.kind != null || st.season != null || st.order != "popularity") {
                        TvButton(
                            "Сбросить",
                            {
                                st.genres = emptySet()
                                st.status = null
                                st.kind = null
                                st.season = null
                                st.order = "popularity"
                                st.reset()
                            },
                            Modifier.memo(mem, "dd:reset"),
                            icon = Ic.close,
                            small = true,
                        )
                    }
                }
            }
        }

        itemsIndexed(st.items, key = { _, a -> a.id }) { i, a ->
            if (i >= st.items.size - 12) {
                LaunchedEffect(st.items.size) { st.loadMore() }
            }
            PosterCard(
                a.toCard(),
                onClick = { nav.push(Route.Title(a.id)) },
                width = null,
                focusModifier = Modifier.memo(mem, "a:${a.id}"),
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }, key = "state") {
            val e = st.error
            when {
                st.loading -> Loading()
                e != null && st.items.isEmpty() -> ErrorBox(e, { st.reset() })
                st.items.isEmpty() -> Empty(
                    "Ничего не нашлось",
                    if (st.query.isNotBlank()) "Попробуйте другое написание: по-русски, латиницей или по-японски." else "Уберите часть фильтров.",
                ) {
                    TvButton("Сбросить всё", {
                        st.query = ""
                        st.genres = emptySet()
                        st.status = null
                        st.kind = null
                        st.season = null
                        st.reset()
                    }, icon = Ic.refresh)
                }
                else -> Box {}
            }
        }
    }
}
