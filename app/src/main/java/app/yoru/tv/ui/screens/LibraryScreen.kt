package app.yoru.tv.ui.screens

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import app.yoru.tv.YoruApp
import app.yoru.tv.data.Card
import app.yoru.tv.data.CardAction
import app.yoru.tv.data.KIND_TITLES
import app.yoru.tv.data.Library
import app.yoru.tv.data.RATE_STATUSES
import app.yoru.tv.ui.Chip
import app.yoru.tv.ui.Empty
import app.yoru.tv.ui.Entry
import app.yoru.tv.ui.Ic
import app.yoru.tv.ui.Navigator
import app.yoru.tv.ui.PosterCard
import app.yoru.tv.ui.Route
import app.yoru.tv.ui.T
import app.yoru.tv.ui.Toaster
import app.yoru.tv.ui.TvButton
import app.yoru.tv.ui.Ty
import app.yoru.tv.ui.Yc
import app.yoru.tv.ui.errorText
import androidx.compose.ui.focus.FocusRequester
import app.yoru.tv.ui.focusWhenReady
import app.yoru.tv.ui.DialogItem
import app.yoru.tv.ui.TvDialog
import app.yoru.tv.ui.memo
import app.yoru.tv.ui.rememberStartFocus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val LIB_SORTS = listOf("updated" to "Недавно изменённые", "title" to "По названию", "score" to "По моей оценке", "progress" to "По прогрессу")

class LibraryState(private val scope: CoroutineScope) {
    private val app = YoruApp.instance
    var tab by mutableStateOf(if (app.repo.loggedIn) "watching" else "history")
    var refreshing by mutableStateOf(false)
    val grid = LazyGridState()

    fun refresh(force: Boolean) {
        if (!app.repo.loggedIn) return
        scope.launch {
            refreshing = true
            try {
                app.repo.refreshList(force)
                if (force) Toaster.show("Список обновлён")
            } catch (e: Exception) {
                Toaster.show(errorText(e))
            } finally {
                refreshing = false
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LibraryScreen(entry: Entry, nav: Navigator) {
    val st = remember(entry) { entry.state { LibraryState(entry.scope) } }
    val app = YoruApp.instance
    val mem = entry.focus
    val snap by app.repo.list.collectAsState()
    val auth by app.prefs.auth.collectAsState()
    val history by app.library.history.collectAsState()
    val favs by app.library.favs.collectAsState()
    @Suppress("UNUSED_VARIABLE") val pv by app.library.progressVersion.collectAsState()

    LaunchedEffect(Unit) { st.refresh(false) }
    val start = rememberStartFocus(mem)

    val tabs = buildList {
        if (auth != null) RATE_STATUSES.forEach { (k, l) -> add(Triple(k, l, snap?.items?.count { it.status == k } ?: 0)) }
        add(Triple("history", "Продолжить", history.size))
        add(Triple("favs", "Избранное", favs.size))
    }
    val tab = tabs.firstOrNull { it.first == st.tab }?.first ?: tabs.first().first
    val appSettings by app.prefs.settings.collectAsState()
    val libSort = appSettings.libSort
    var sortOpen by remember { mutableStateOf(false) }
    var sortTouched by remember { mutableStateOf(false) }
    val sortFr = remember { FocusRequester() }
    if (sortOpen) {
        TvDialog(onDismiss = { sortOpen = false }, title = "Сортировка", width = 440.dp) {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { first.focusWhenReady() }
            LIB_SORTS.forEach { (k, l) ->
                DialogItem(
                    l, k == libSort,
                    {
                        app.prefs.update { it.copy(libSort = k) }
                        sortOpen = false
                    },
                    modifier = if (k == libSort) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
    }
    // окно закрылось — курсор обратно на кнопку сортировки
    LaunchedEffect(sortOpen) {
        if (sortOpen) sortTouched = true else if (sortTouched) sortFr.focusWhenReady()
    }

    // тысячи записей: считаем и сортируем только когда что-то изменилось, а не при каждой перерисовке
    val cards: List<Card> = remember(tab, snap, history, favs, libSort, pv) { when (tab) {
        "history" -> history.map { h ->
            val p = app.library.pct(h.episodeId)
            Card(
                malId = h.malId, title = h.title, poster = h.poster,
                meta = h.epLabel + if (p in 1 until Library.SEEN_PCT) " · $p%" else "",
                progress = p.takeIf { it > 0 }?.let { it / 100f },
                action = CardAction.Watch(h.malId, episodeId = h.episodeId),
            )
        }
        "favs" -> favs.map { Card(malId = it.malId, title = it.title, poster = it.poster, meta = it.meta) }
        else -> snap?.items?.filter { it.status == tab }.orEmpty().let { list ->
            when (libSort) {
                "title" -> list.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale("ru"))) { it.title })
                "score" -> list.sortedWith(compareByDescending<app.yoru.tv.data.RateItem> { it.score }.thenByDescending { it.updatedAt })
                "progress" -> list.sortedByDescending { it.episodes / (if (it.total > 0) it.total else 99).toFloat() }
                else -> list.sortedByDescending { it.updatedAt }
            }
        }.map { r ->
            val total = if (r.total > 0) r.total.toString() else "?"
            Card(
                malId = r.malId, title = r.title, alt = r.alt, poster = r.poster,
                meta = if (r.status == "planned") (KIND_TITLES[r.kind] ?: "") else "${r.episodes} / $total",
                topBadge = r.score.takeIf { it > 0 }?.toString(),
                badge = if (r.available > r.episodes && r.status in setOf("watching", "rewatching", "on_hold")) "+${r.available - r.episodes}" else null,
                progress = if (r.total > 0 && r.status != "planned") r.episodes / r.total.toFloat() else null,
            )
        }
    } }
    // постеры из GraphQL для записей списка без картинки
    var posterTick by remember { mutableStateOf(0) }
    LaunchedEffect(tab, cards.size) {
        val need = cards.filter { it.poster == null }.map { it.malId }
        if (need.isNotEmpty()) {
            try {
                app.repo.posters(need)
                posterTick++
            } catch (_: Exception) {
            }
        }
    }
    val tick = posterTick
    val cardsWithPosters = remember(cards, tick) { cards.map { c -> if (c.poster == null) c.copy(poster = app.repo.cachedAnime(c.malId)?.poster) else c } }

    LazyVerticalGrid(
        columns = GridCells.Fixed(gridColumns(appSettings.coverSize)),
        state = st.grid,
        contentPadding = PaddingValues(start = PAD, end = PAD, top = 28.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "head") {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        T("Моё", Ty.page)
                        T(
                            auth?.let { "Список ${it.nickname} на Shikimori и то, что вы смотрели на этом телевизоре." }
                                ?: "Начатые сериалы и избранное с этого телевизора. Подключите Shikimori, чтобы видеть весь список.",
                            Ty.body, Yc.Muted,
                        )
                    }
                    if (auth != null) {
                        TvButton(if (st.refreshing) "Обновляем…" else "Обновить", { st.refresh(true) }, Modifier.memo(mem, "refresh"), icon = Ic.refresh, small = true)
                    } else {
                        TvButton("Подключить Shikimori", { nav.push(Route.Connect) }, Modifier.memo(mem, "connect"), icon = Ic.phone, small = true)
                    }
                }
                Spacer(Modifier.height(18.dp))
                // внутренний отступ: чип в фокусе увеличивается и не должен обрезаться краем ряда и меню
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp).focusRestorer().focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    tabs.forEach { (k, l, n) ->
                        Chip(
                            l, k == tab, { st.tab = k },
                            count = if (n > 0) n.toString() else null,
                            modifier = (if (k == tab) Modifier.focusRequester(start) else Modifier).memo(mem, "tab:$k"),
                        )
                    }
                }
                if (tab != "history" && tab != "favs") {
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        T("Сортировка", Ty.small, Yc.Faint, modifier = Modifier.padding(end = 10.dp))
                        // раскрывающийся список: OK — окно с вариантами
                        Chip(
                            "${LIB_SORTS.firstOrNull { it.first == libSort }?.second ?: LIB_SORTS.first().second}  ▾",
                            false, { sortOpen = true },
                            modifier = Modifier.focusRequester(sortFr).memo(mem, "sort"),
                        )
                        Spacer(Modifier.width(10.dp))
                        CardSizeDropdown(mem)
                    }
                } else {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.padding(start = 10.dp)) { CardSizeDropdown(mem) }
                }
            }
        }
        items(cardsWithPosters, key = { "${tab}:${it.malId}" }) { c ->
            PosterCard(c, onClick = { nav.open(c.action) }, width = null, focusModifier = Modifier.memo(mem, "c:$tab:${c.malId}"))
        }
        if (cardsWithPosters.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                val label = tabs.firstOrNull { it.first == tab }?.second ?: ""
                when (tab) {
                    "history" -> Empty("Здесь появятся начатые сериалы", "Включите любую серию, и она будет ждать вас здесь с того же места.") {
                        TvButton("Выбрать аниме", { nav.switchTo(Route.Catalog) }, icon = Ic.search)
                    }
                    "favs" -> Empty("Избранное пустое", "Нажмите «В избранное» на странице тайтла, чтобы вернуться к нему позже.")
                    else -> Empty("В «$label» пусто", "Добавляйте тайтлы в этот список на странице тайтла, и они появятся здесь.")
                }
            }
        }
    }
}
