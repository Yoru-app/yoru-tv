package app.yoru.tv.ui.screens

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlin.math.abs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import app.yoru.tv.ui.Focusable
import app.yoru.tv.ui.liteMode
import app.yoru.tv.ui.TvDropdown
import app.yoru.tv.YoruApp
import coil.compose.AsyncImage
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.yoru.tv.data.Card
import app.yoru.tv.data.CardAction
import app.yoru.tv.ui.FocusMemory
import app.yoru.tv.ui.Navigator
import app.yoru.tv.ui.PosterCard
import app.yoru.tv.ui.Route
import app.yoru.tv.ui.T
import app.yoru.tv.ui.Ty
import app.yoru.tv.ui.WideCard
import app.yoru.tv.ui.Yc
import app.yoru.tv.ui.memo
import app.yoru.tv.ui.focusWhenReady
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

/**
 * Прокрутить список так, чтобы ряд с фокусом встал наверх.
 * Ждём чуть-чуть: сначала система сама «подтягивает» элемент в фокусе,
 * и если начать раньше, её прокрутка отменит нашу и верх страницы останется обрезанным.
 */
fun CoroutineScope.scrollAfterFocus(list: LazyListState, index: Int) {
    launch {
        delay(80)
        list.smoothScrollTo(index)
    }
}

/**
 * На телевизорах Compose по умолчанию прокручивает списки так, чтобы элемент в фокусе
 * стоял на 30 % высоты экрана. Для вертикальных страниц это ломает вид (хиро уезжает вверх),
 * поэтому там прокручиваем минимально, а ряды выравниваем сами.
 */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("DEPRECATION")
val MinimalBringIntoView: BringIntoViewSpec = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> get() = SoftSpring
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0 && trailing <= containerSize -> 0f
            offset < 0 && trailing > containerSize -> 0f
            abs(offset) < abs(trailing - containerSize) -> offset
            else -> trailing - containerSize
        }
    }
}

/** Мягкая пружина без отскока: ряд «доезжает», а не дёргается, и при удержании кнопки плавно ускоряется. */
val SoftSpring: AnimationSpec<Float>
    get() = if (liteMode) androidx.compose.animation.core.snap() else spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)

/**
 * Горизонтальные ряды как на Google TV: карточка в фокусе всегда стоит на одном месте
 * у левого края (предыдущая чуть выглядывает), ряд плавно едет под ней на одну карточку.
 */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("DEPRECATION")
val RowBringIntoView: BringIntoViewSpec = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> get() = SoftSpring
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val target = containerSize * 0.09f
        // в начале ряда прокручивать некуда — список сам ограничит отрицательный сдвиг
        return offset - target
    }
}

/** Плавно поставить элемент списка вверх экрана: без рывка «прыгнуть и доехать». */
suspend fun LazyListState.smoothScrollTo(index: Int) {
    if (liteMode) {
        scrollToItem(index)
        return
    }
    val info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (info != null) {
        animateScrollBy(
            (info.offset - layoutInfo.viewportStartOffset).toFloat(),
            tween(durationMillis = 420, easing = FastOutSlowInEasing),
        )
    } else {
        animateScrollToItem(index)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VerticalPage(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalBringIntoView) { content() }
}

/** Страница, которая сама ведёт вертикальную прокрутку: система элемент в фокусе не подтягивает. */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("DEPRECATION")
val NoBringIntoView: BringIntoViewSpec = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> get() = SoftSpring
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StaticPage(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides NoBringIntoView) { content() }
}

/**
 * Сетки «Каталога», «Моего» и страницы ряда. Стандартная прокрутка на ТВ ставит элемент в фокусе в заданную точку
 * экрана и учитывает его увеличение при фокусе, поэтому при движении вбок страница подрагивала по вертикали.
 * Здесь прокрутка нужна, только если обложка вышла за рабочую полосу экрана (снизу остаётся место под название).
 */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("DEPRECATION")
val GridBringIntoView: BringIntoViewSpec = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> get() = SoftSpring
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val top = containerSize * 0.05f
        val bottom = containerSize * 0.85f
        // запас больше, чем даёт увеличение карточки в фокусе
        val slack = containerSize * 0.03f
        val trailing = offset + size
        return when {
            offset < top - slack || size > bottom - top && offset > top + slack -> offset - top
            trailing > bottom + slack && size <= bottom - top -> trailing - bottom
            else -> 0f
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridPage(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides GridBringIntoView) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HorizontalRow(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides RowBringIntoView) { content() }
}

/**
 * Фокус ряда: помнит последнюю карточку и ставит фокус на конкретную карточку.
 * Просить фокус у группы (ряда целиком) нельзя: в Compose 1.7 фокус тогда получает сама
 * невидимая группа — рамки нигде нет, и «курсор пропадает».
 */
class RowFocus {
    var last: Int = -1
    /** Какая карточка ряда сейчас реально в фокусе (-1 — никакая). */
    var focused: Int = -1
    private val frs = HashMap<Int, FocusRequester>()
    fun fr(i: Int): FocusRequester = frs.getOrPut(i) { FocusRequester() }

    fun onFocus(i: Int, isFocused: Boolean) {
        if (isFocused) {
            last = i
            focused = i
        } else if (focused == i) {
            focused = -1
        }
    }

    /**
     * Поставить фокус в ряд и убедиться, что он действительно встал.
     * Раньше запрос мог «пройти» без ошибки, но фокус не появлялся — пульт переставал реагировать.
     */
    suspend fun enter(state: LazyListState): Boolean {
        // ряд мог только что появиться после прокрутки страницы — дождёмся, пока он разложится
        var waited = 0
        while (state.layoutInfo.totalItemsCount == 0 && waited++ < 12) androidx.compose.runtime.withFrameNanos { }
        val count = state.layoutInfo.totalItemsCount
        if (count == 0) return false
        val target = (if (last >= 0) last else state.firstVisibleItemIndex).coerceIn(0, count - 1)
        if (state.layoutInfo.visibleItemsInfo.none { it.index == target }) interruptible { state.scrollToItem(target) }
        if (focusAt(target)) return true
        // запасной путь: любая карточка, которая сейчас на экране
        for (info in state.layoutInfo.visibleItemsInfo.toList()) {
            if (info.index != target && focusAt(info.index, frames = 4)) return true
        }
        return false
    }

    private suspend fun focusAt(i: Int, frames: Int = 10): Boolean {
        repeat(frames) {
            androidx.compose.runtime.withFrameNanos { }
            if (focused == i) return true
            if (runCatching { fr(i).requestFocus() }.isSuccess) {
                // состояние фокуса применяется к следующему кадру
                androidx.compose.runtime.withFrameNanos { }
                if (focused == i) return true
            }
        }
        return focused == i
    }
}

/**
 * Прокрутка списка, которую может прервать другая прокрутка (например, системная подтяжка
 * элемента в фокусе). Прерывание — не повод бросать дело: раньше из-за него фокус так и не ставился.
 */
suspend fun interruptible(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
    }
}

/** ↑/↓ внутри ряда: перейти к соседнему ряду явно, а не полагаться на поиск фокуса. */
fun Modifier.verticalNav(onUp: (() -> Unit)?, onDown: (() -> Unit)?): Modifier = onPreviewKeyEvent { ev ->
    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (ev.key) {
        Key.DirectionUp -> onUp?.let { it(); true } ?: false
        Key.DirectionDown -> onDown?.let { it(); true } ?: false
        else -> false
    }
}

/** Отступ контента от бокового меню. */
val PAD = 36.dp

fun Navigator.open(a: CardAction) {
    when (a) {
        is CardAction.Title -> push(Route.Title(a.malId))
        is CardAction.Watch -> push(Route.Watch(a.malId, a.episodeId, a.episodeNum, a.translationId))
    }
}

@Composable
fun SectionHead(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(start = PAD, end = PAD, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        T(title, Ty.section, maxLines = 1)
        if (trailing != null) T(trailing, Ty.label, Yc.Muted, maxLines = 1)
    }
}

/** Ряд карточек с заголовком. Возврат в ряд пультом ставит фокус на ту же карточку. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CardRow(
    title: String,
    rowKey: String,
    cards: List<Card>,
    mem: FocusMemory,
    state: LazyListState,
    onClick: (Card) -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    images: Map<Long, String?> = emptyMap(),
    trailing: String? = null,
    posterWidth: Dp = 136.dp,
    wideWidth: Dp = 252.dp,
    onRowFocus: () -> Unit = {},
    onCardFocus: (Int, Card) -> Unit = { _, _ -> },
    showTitle: Boolean = true,
    showCardLabels: Boolean = true,
    continueStyle: Boolean = false,
    rowFocus: RowFocus? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    /** Ещё одна точка фокуса на первой карточке (например, стартовый фокус экрана). */
    firstFocus: FocusRequester? = null,
    /** Плитка «Все» в конце ряда: нажатие открывает страницу со всеми тайтлами ряда. */
    onAll: (() -> Unit)? = null,
) {
    // ключ — сам тайтл: когда ряд обновляется, карточка под курсором остаётся на месте и не теряет фокус
    val shown = remember(cards) { cards.distinctBy { it.malId } }
    Column(
        modifier
            .padding(top = if (showTitle) 22.dp else 6.dp)
            .verticalNav(onUp, onDown)
            .onFocusChanged { if (it.hasFocus) onRowFocus() }
            .focusGroup(),
    ) {
        if (showTitle) SectionHead(title, trailing = trailing)
        HorizontalRow {
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = PAD, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(if (wide) 20.dp else 18.dp),
            modifier = Modifier.focusRestorer().focusGroup(),
        ) {
            itemsIndexed(shown, key = { _, c -> c.malId }, contentType = { _, _ -> if (wide) 1 else 0 }) { i, c ->
                val fm = Modifier.memo(mem, "$rowKey:${c.malId}").then(
                    if (rowFocus != null) {
                        Modifier
                            .focusRequester(rowFocus.fr(i))
                            .onFocusChanged { rowFocus.onFocus(i, it.isFocused) }
                    } else {
                        Modifier
                    },
                ).onFocusChanged { if (it.isFocused) onCardFocus(i, c) }
                    .then(if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                if (wide) {
                    WideCard(
                        c,
                        images[c.malId],
                        onClick = { onClick(c) },
                        width = wideWidth,
                        focusModifier = fm,
                        showLabels = showCardLabels,
                        continueStyle = continueStyle,
                    )
                } else {
                    PosterCard(c, onClick = { onClick(c) }, width = posterWidth, focusModifier = fm, showLabels = showCardLabels)
                }
            }
            if (onAll != null) {
                item(key = "all", contentType = 2) {
                    val i = shown.size
                    AllTile(
                        total = trailing,
                        onClick = onAll,
                        modifier = if (wide) Modifier.size(width = 120.dp, height = 142.dp) else Modifier.size(width = posterWidth, height = posterWidth * 1.5f),
                        focusModifier = Modifier.memo(mem, "$rowKey:all").then(
                            if (rowFocus != null) Modifier.focusRequester(rowFocus.fr(i)).onFocusChanged { rowFocus.onFocus(i, it.isFocused) } else Modifier,
                        ),
                    )
                }
            }
        }
        }
    }
}

/** Плитка «Все» в конце ряда главной. */
@Composable
private fun AllTile(total: String?, onClick: () -> Unit, modifier: Modifier, focusModifier: Modifier) {
    Focusable(onClick = onClick, modifier = modifier.then(focusModifier), shape = RoundedCornerShape(12.dp)) { f ->
        Column(
            Modifier.fillMaxSize().background(if (f) Yc.Lantern else Yc.Night2),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            T("Все  →", Ty.h3, if (f) Yc.LanternInk else Yc.Text, maxLines = 1)
            if (total != null) T(total, Ty.small, if (f) Yc.LanternInk else Yc.Muted, maxLines = 1)
        }
    }
}

/** Сколько колонок в сетках «Каталога» и «Моего» при выбранном размере обложек. */
fun gridColumns(size: String): Int = when (size) {
    "s" -> 8
    "l" -> 4
    else -> 6
}

/** Ширина обложки в рядах «Расписания». */
fun rowPosterWidth(size: String): Dp = when (size) {
    "s" -> 104.dp
    "l" -> 180.dp
    else -> 136.dp
}

private val CARD_SIZES = listOf("s" to "Мелкие", "m" to "Средние", "l" to "Крупные")

/** Выбор размера обложек — как переключатель в вебе, общий для «Каталога», «Моего» и «Расписания». */
@Composable
fun CardSizeDropdown(mem: FocusMemory) {
    val prefs = YoruApp.instance.prefs
    val size = prefs.settings.collectAsState().value.coverSize
    TvDropdown(
        "Обложки", CARD_SIZES.firstOrNull { it.first == size }?.second ?: "Средние", CARD_SIZES, setOf(size),
        onPick = { v -> prefs.update { it.copy(coverSize = v) } },
        modifier = Modifier.memo(mem, "dd:size"),
    )
}

/** Во сколько раз можно увеличить баннер ради ширины, не испортив резкость (как в вебе). */
private const val MAX_UP = 1.35f

/**
 * Широкая картинка большого блока и шапки тайтла. fit = "stretch" — на весь блок. fit = "blur" — картинка всегда
 * заполняет блок по высоте от верхнего края, а ради ширины увеличивается не больше чем в 1,35 раза. Если до краёв
 * блока она так не достаёт, то встаёт по центру, у левого или у правого края, а по бокам — её размытая копия.
 * Боковые края и самый низ картинки растворяются; сверху размытия нет.
 */
@Composable
fun BannerArt(url: String, fit: String, align: String) {
    if (fit != "blur") {
        AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        return
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var natural by remember(url) { mutableStateOf<androidx.compose.ui.geometry.Size?>(null) }
    // Полноразмерные 4K-файлы не нужны для интерфейса и занимают десятки мегабайт.
    // В lite режиме дополнительно уменьшаем декодирование до 720p.
    val request = remember(url, liteMode) {
        val (rw, rh) = if (liteMode) 1280 to 720 else 1920 to 1080
        coil.request.ImageRequest.Builder(ctx)
            .data(url)
            .size(rw, rh)
            .precision(coil.size.Precision.INEXACT)
            .build()
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val n = natural
        // масштаб, при котором картинка закрывает всю высоту блока, но не меньше допустимого увеличения
        val scale = if (n != null && n.width > 0 && n.height > 0) maxOf(MAX_UP, h / n.height) else 0f
        // размер неизвестен или картинка и так достаёт до боковых краёв — размывать нечего
        if (n == null || scale == 0f || n.width * scale >= w) {
            AsyncImage(
                model = request, contentDescription = null, contentScale = ContentScale.Crop,
                onSuccess = { natural = it.painter.intrinsicSize },
                modifier = Modifier.fillMaxSize(),
            )
            return@BoxWithConstraints
        }
        val cw = n.width * scale
        val x = when (align) {
            "left" -> 0f
            "right" -> w - cw
            else -> (w - cw) / 2
        }
        AsyncImage(request, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(36.dp))
        Box(Modifier.fillMaxSize().background(Color(0x59141020)))
        val d = androidx.compose.ui.platform.LocalDensity.current
        val fadeL = align != "left"
        val fadeR = align != "right"
        AsyncImage(
            request, null, contentScale = ContentScale.Crop,
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(x.toInt(), 0) }
                .size(with(d) { cw.toDp() }, with(d) { h.toDp() })
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.horizontalGradient(
                            0f to (if (fadeL) Color.Transparent else Color.Black),
                            (if (fadeL) 0.16f else 0f) to Color.Black,
                            (if (fadeR) 0.84f else 1f) to Color.Black,
                            1f to (if (fadeR) Color.Transparent else Color.Black),
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                    // только внизу: небольшой переход в размытую копию
                    drawRect(
                        Brush.verticalGradient(0.88f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                },
        )
    }
}
