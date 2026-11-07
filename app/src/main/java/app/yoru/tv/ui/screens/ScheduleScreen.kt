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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.yoru.tv.YoruApp
import app.yoru.tv.data.Anime365
import app.yoru.tv.data.CalendarEntry
import app.yoru.tv.data.Card
import app.yoru.tv.data.CardAction
import app.yoru.tv.data.relTime
import app.yoru.tv.ui.Chip
import app.yoru.tv.ui.Empty
import app.yoru.tv.ui.Entry
import app.yoru.tv.ui.ErrorBox
import app.yoru.tv.ui.Focusable
import app.yoru.tv.ui.Ic
import app.yoru.tv.ui.Loading
import app.yoru.tv.ui.Navigator
import app.yoru.tv.ui.Poster
import app.yoru.tv.ui.Route
import app.yoru.tv.ui.T
import app.yoru.tv.ui.Toaster
import app.yoru.tv.ui.TvButton
import app.yoru.tv.ui.Ty
import app.yoru.tv.ui.Yc
import app.yoru.tv.ui.errorText
import app.yoru.tv.ui.memo
import app.yoru.tv.ui.rememberStartFocus
import app.yoru.tv.ui.tryFocus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// =====================================================================
// Расписание
// =====================================================================

class ScheduleState(private val scope: CoroutineScope) {
    private val app = YoruApp.instance
    var items by mutableStateOf<List<CalendarEntry>?>(null)
    var error by mutableStateOf<Throwable?>(null)
    var mine by mutableStateOf(false)
    val list = LazyListState()
    val rows = HashMap<String, LazyListState>()

    fun load() {
        scope.launch {
            error = null
            try {
                items = app.repo.calendar()
            } catch (e: Exception) {
                error = e
            }
        }
    }
}

private val RU = Locale("ru")

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ScheduleScreen(entry: Entry, nav: Navigator) {
    val st = remember(entry) { entry.state { ScheduleState(entry.scope) } }
    val app = YoruApp.instance
    val mem = entry.focus
    val scope = rememberCoroutineScope()
    val snap by app.repo.list.collectAsState()
    LaunchedEffect(Unit) { if (st.items == null) st.load() }
    val start = rememberStartFocus(mem, ready = st.items != null || st.error != null)

    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val settings by app.prefs.settings.collectAsState()
    val myIds = remember(snap) { snap?.items?.filter { it.status in setOf("watching", "planned", "rewatching", "on_hold") }?.map { it.malId }?.toSet().orEmpty() }
    val fmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val groups = remember(st.items, st.mine, myIds, today) {
        (st.items ?: emptyList())
            .filter { !st.mine || it.anime.id in myIds }
            .groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
            .toSortedMap()
            .filterKeys { !it.isBefore(today) }
    }

    VerticalPage {
    LazyColumn(state = st.list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 28.dp, bottom = 80.dp)) {
        item(key = "head") {
            Column(Modifier.padding(horizontal = PAD)) {
                T("Расписание", Ty.page)
                T("Когда выйдут следующие серии онгоингов — по данным Shikimori.", Ty.body, Yc.Muted)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.focusRestorer().focusGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip("Все онгоинги", !st.mine, { st.mine = false }, modifier = Modifier.focusRequester(start).memo(mem, "all"))
                    if (snap != null) Chip("Только мой список", st.mine, { st.mine = true }, modifier = Modifier.memo(mem, "mine"))
                    CardSizeDropdown(mem)
                }
            }
        }
        val e = st.error
        when {
            st.items == null && e == null -> item(key = "load") { Loading() }
            e != null -> item(key = "err") { ErrorBox(e, { st.load() }) }
            groups.isEmpty() -> item(key = "empty") {
                Empty(if (st.mine) "В вашем списке ничего не выходит" else "Расписание пустое", "Shikimori не вернул ближайших серий.")
            }
            else -> {
                var idx = 1
                groups.forEach { (date, list) ->
                    val myIndex = idx++
                    val title = when (date) {
                        today -> "Сегодня"
                        today.plusDays(1) -> "Завтра"
                        else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, RU).replaceFirstChar { it.titlecase(RU) } +
                            ", " + date.dayOfMonth + " " + date.month.getDisplayName(TextStyle.FULL, RU).let { genitive(it) }
                    }
                    val key = date.toString()
                    item(key = key) {
                        CardRow(
                            title = title,
                            rowKey = key,
                            cards = list.sortedBy { it.at }.map { c ->
                                Card(
                                    malId = c.anime.id, title = c.anime.title, alt = c.anime.alt, poster = c.anime.poster,
                                    badge = fmt.format(Instant.ofEpochMilli(c.at).atZone(zone)),
                                    topBadge = if (c.anime.id in myIds) "В списке" else null,
                                    meta = "${c.nextEpisode} серия",
                                )
                            },
                            mem = mem,
                            state = st.rows.getOrPut(key) { LazyListState() },
                            onClick = { nav.push(Route.Title(it.malId)) },
                            trailing = "${list.size}",
                            posterWidth = rowPosterWidth(settings.coverSize),
                            onRowFocus = { scope.scrollAfterFocus(st.list, myIndex) },
                        )
                    }
                }
            }
        }
    }
    }
}

/** «ноябрь» → «ноября» для дат. */
private fun genitive(m: String): String = when {
    m.endsWith("ь") || m.endsWith("й") -> m.dropLast(1) + "я"
    m == "март" || m == "август" -> m + "а"
    else -> m
}

// =====================================================================
// Уведомления
// =====================================================================

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NotificationsScreen(entry: Entry, nav: Navigator) {
    val app = YoruApp.instance
    val mem = entry.focus
    val notices by app.repo.notices.collectAsState()
    val auth by app.prefs.auth.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    val start = rememberStartFocus(mem)
    val list = remember(entry) { entry.state { LazyListState() } }

    LaunchedEffect(Unit) {
        delay(2500)
        if (app.repo.notices.value.any { !it.read }) app.repo.markNoticesRead()
    }

    VerticalPage {
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = PAD, end = PAD, top = 28.dp, bottom = 80.dp)) {
        item(key = "head") {
            Column {
                T("Уведомления", Ty.page)
                T(
                    "Новые серии и смена статуса у тайтлов из «Смотрю», «Запланировано» и «Отложено». Yoru проверяет список сам, раз в несколько часов.",
                    Ty.body, Yc.Muted,
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.focusRestorer().focusGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (auth != null) {
                        TvButton(
                            if (refreshing) "Проверяем…" else "Проверить сейчас",
                            {
                                if (!refreshing) {
                                    refreshing = true
                                    app.scope.launch {
                                        try {
                                            app.repo.refreshList(force = true)
                                            Toaster.show("Список проверен")
                                        } catch (e: Exception) {
                                            Toaster.show(errorText(e))
                                        } finally {
                                            refreshing = false
                                        }
                                    }
                                }
                            },
                            Modifier.focusRequester(start).memo(mem, "refresh"),
                            icon = Ic.refresh,
                        )
                        if (notices.isNotEmpty()) TvButton("Очистить", { app.repo.clearNotices() }, Modifier.memo(mem, "clear"), icon = Ic.trash)
                    } else {
                        TvButton("Подключить Shikimori", { nav.push(Route.Connect) }, Modifier.focusRequester(start).memo(mem, "connect"), icon = Ic.phone, primary = true)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        if (notices.isEmpty()) {
            item(key = "empty") {
                Empty(
                    "Пока тихо",
                    if (auth == null) "Уведомления собираются из вашего списка на Shikimori." else "Когда у тайтлов из списка выйдут новые серии, они появятся здесь. Первая проверка только запоминает состояние списка.",
                )
            }
        }
        items(notices, key = { it.id }) { n ->
            Focusable(
                onClick = {
                    val ep = n.episode
                    if (ep != null) nav.push(Route.Watch(n.malId, episodeNum = ep.toDouble())) else nav.push(Route.Title(n.malId))
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).memo(mem, "n:${n.id}"),
                shape = RoundedCornerShape(16.dp),
                focusedScale = 1.01f,
            ) { f ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (f) Yc.Night3 else Yc.Night2)
                        .border(1.dp, if (f) Yc.Lantern else Yc.Line, RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(Modifier.size(width = 48.dp, height = 68.dp).clip(RoundedCornerShape(8.dp))) {
                        Poster(n.poster, n.malId, n.title, Modifier.fillMaxSize())
                    }
                    Column(Modifier.weight(1f)) {
                        T(n.title, Ty.cardTitle, maxLines = 1)
                        T(n.text, Ty.body, if (n.read) Yc.Muted else Yc.Lantern, maxLines = 1)
                    }
                    T(relTime(n.at), Ty.small, Yc.Faint, maxLines = 1)
                    if (!n.read) Box(Modifier.size(9.dp).background(Yc.Rose, RoundedCornerShape(50)))
                }
            }
        }
    }
    }
}
