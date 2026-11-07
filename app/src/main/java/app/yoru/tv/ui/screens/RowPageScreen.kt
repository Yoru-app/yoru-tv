package app.yoru.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import app.yoru.tv.YoruApp
import app.yoru.tv.data.Card
import app.yoru.tv.data.HomeSource
import app.yoru.tv.ui.Empty
import app.yoru.tv.ui.Entry
import app.yoru.tv.ui.ErrorBox
import app.yoru.tv.ui.Loading
import app.yoru.tv.ui.Navigator
import app.yoru.tv.ui.PosterCard
import app.yoru.tv.ui.Route
import app.yoru.tv.ui.T
import app.yoru.tv.ui.Ty
import app.yoru.tv.ui.memo
import app.yoru.tv.ui.rememberStartFocus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private class RowPageState(private val key: String, private val scope: CoroutineScope) {
    private val app = YoruApp.instance
    var cards by mutableStateOf<List<Card>?>(null)
    var error by mutableStateOf<Throwable?>(null)
    val grid = LazyGridState()

    fun load() {
        scope.launch {
            error = null
            try {
                val src = HomeSource.of(key)
                cards = (if (src != null) app.repo.source(src, 60) else todayCards(app.repo.today())).distinctBy { it.malId }
            } catch (e: Exception) {
                error = e
            }
        }
    }
}

/** Все тайтлы одного ряда главной. «Назад» возвращает на главную, на ту же плитку. */
@Composable
fun RowPageScreen(entry: Entry, nav: Navigator, route: Route.RowPage) {
    val st = remember(entry) { entry.state { RowPageState(route.key, entry.scope).also { it.load() } } }
    val settings by YoruApp.instance.prefs.settings.collectAsState()
    val mem = entry.focus
    val list = st.cards
    val start = rememberStartFocus(mem, ready = !list.isNullOrEmpty())

    LazyVerticalGrid(
        columns = GridCells.Fixed(gridColumns(settings.coverSize)),
        state = st.grid,
        contentPadding = PaddingValues(start = PAD + 20.dp, end = PAD + 20.dp, top = 28.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "head") { T(route.title, Ty.page, maxLines = 1) }
        val e = st.error
        when {
            e != null -> item(span = { GridItemSpan(maxLineSpan) }, key = "err") { ErrorBox(e, { st.load() }) }
            list == null -> item(span = { GridItemSpan(maxLineSpan) }, key = "load") { Loading() }
            list.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                Box(contentAlignment = Alignment.Center) { Empty("Здесь пока пусто", null) }
            }
            else -> items(list, key = { it.malId }) { c ->
                PosterCard(
                    c,
                    onClick = { nav.open(c.action) },
                    width = null,
                    focusModifier = (if (c == list.first()) Modifier.focusRequester(start) else Modifier).memo(mem, "c:${c.malId}"),
                )
            }
        }
    }
}
