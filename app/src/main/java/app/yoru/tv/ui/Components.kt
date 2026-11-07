package app.yoru.tv.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.yoru.tv.data.ApiError
import app.yoru.tv.data.Card
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

// =====================================================================
// Фокус
// =====================================================================

/**
 * Память фокуса экрана: какой элемент был в фокусе, чтобы после «Назад»
 * вернуть рамку туда же, а не в начало страницы.
 */
class FocusMemory {
    var key: String? = null
    var pending: Boolean = false
    /** Начальный фокус экрана — запасной вариант, если курсор потерялся. */
    var start: FocusRequester? = null
    /** Запомненные элементы, которые сейчас на экране: ключ → их FocusRequester. */
    val frs = HashMap<String, FocusRequester>()

    /** Вернуть курсор на последний запомненный элемент. */
    fun restore(): Boolean {
        val fr = key?.let { frs[it] } ?: return false
        return runCatching { fr.requestFocus() }.isSuccess
    }
}

/** Запоминает фокус элемента и восстанавливает его, когда экран вернулся из стека. */
@Composable
fun Modifier.memo(mem: FocusMemory, key: String): Modifier {
    val fr = remember { FocusRequester() }
    DisposableEffect(mem, key) {
        mem.frs[key] = fr
        onDispose { if (mem.frs[key] === fr) mem.frs.remove(key) }
    }
    if (mem.pending && mem.key == key) {
        LaunchedEffect(Unit) {
            repeat(12) {
                withFrameNanos { }
                runCatching { fr.requestFocus() }
                // pending сбрасывается только фактическим onFocusChanged ниже.
                if (!mem.pending) return@LaunchedEffect
            }
        }
    }
    return this
        .focusRequester(fr)
        .onFocusChanged {
            if (it.isFocused) {
                mem.key = key
                mem.pending = false
            }
        }
}

/**
 * Начальный фокус экрана. Если экран вернулся из стека и есть что восстанавливать —
 * ждём восстановления, а через секунду всё равно ставим фокус сюда.
 */
@Composable
fun rememberStartFocus(mem: FocusMemory, ready: Boolean = true): FocusRequester {
    val fr = remember { FocusRequester() }
    mem.start = fr
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        if (mem.pending && mem.key != null) {
            delay(1000)
            if (!mem.pending) return@LaunchedEffect
            mem.pending = false
        }
        withFrameNanos { }
        runCatching { fr.requestFocus() }
    }
    return fr
}

fun FocusRequester.tryFocus() {
    runCatching { requestFocus() }
}

/**
 * Поставить фокус, когда элемент действительно появится: после прокрутки ленивого списка
 * он компонуется только на следующем кадре, и ранний запрос молча терялся — «пропадал курсор».
 */
suspend fun FocusRequester.focusWhenReady(maxFrames: Int = 12): Boolean {
    repeat(maxFrames) {
        withFrameNanos { }
        if (runCatching { requestFocus() }.isSuccess) return true
    }
    return false
}

/**
 * Базовый фокусируемый элемент: увеличение и рамка-«фонарь» в фокусе.
 * OK/Enter на пульте срабатывает как нажатие (это делает clickable).
 */
@Composable
fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    focusedScale: Float = 1.06f,
    border: Boolean = true,
    enabled: Boolean = true,
    interaction: MutableInteractionSource? = null,
    onFocus: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val src = interaction ?: remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    if (liteMode) {
        // без увеличения и слоя: только рамка — на слабом телевизоре это заметно быстрее
        Box(
            modifier
                .onFocusChanged { if (it.isFocused) onFocus?.invoke() }
                .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
                .then(if (focused && border) Modifier.border(3.dp, Yc.Lantern, shape) else Modifier)
                .clip(shape),
        ) {
            content(focused)
        }
        return
    }
    // пружина вместо линейной анимации: рамка «вспрыгивает» быстро и мягко садится
    val scale by animateFloatAsState(
        if (focused) focusedScale else 1f,
        androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 700f),
        label = "focusScale",
    )
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { if (it.isFocused) onFocus?.invoke() }
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .then(if (focused && border) Modifier.border(3.dp, Yc.Lantern, shape) else Modifier)
            .clip(shape),
    ) {
        content(focused)
    }
}

// =====================================================================
// Кнопки и чипы
// =====================================================================

private val Pill = RoundedCornerShape(50)

@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    small: Boolean = false,
    selected: Boolean = false,
    onFocus: (() -> Unit)? = null,
) {
    Focusable(
        onClick = onClick,
        modifier = modifier.alpha(if (enabled) 1f else 0.4f),
        shape = Pill,
        focusedScale = 1.05f,
        border = false,
        enabled = enabled,
        onFocus = onFocus,
    ) { f ->
        val bg = when {
            f && primary -> Yc.LanternHi
            f -> Yc.Text
            primary -> Yc.Lantern
            selected -> Yc.Night3
            else -> Color(0x17ECE7F7)
        }
        val fg = when {
            f -> if (primary) Yc.LanternInk else Yc.Night
            primary -> Yc.LanternInk
            selected -> Yc.Lantern
            else -> Yc.Text
        }
        Row(
            Modifier
                .background(bg, Pill)
                .then(if (!f && !primary) Modifier.border(1.dp, if (selected) Yc.Lantern.copy(alpha = 0.6f) else Color(0x29ECE7F7), Pill) else Modifier)
                .height(if (small) 36.dp else 44.dp)
                .padding(horizontal = if (small) 14.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) Icon(icon, fg, if (small) 16.dp else 18.dp)
            T(text, if (small) Ty.label else Ty.button, fg, maxLines = 1)
        }
    }
}

@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: String? = null,
    onFocus: (() -> Unit)? = null,
) {
    Focusable(onClick = onClick, modifier = modifier, shape = Pill, focusedScale = 1.06f, border = false, onFocus = onFocus) { f ->
        val bg = when {
            f -> Yc.Lantern
            selected -> Yc.Text
            else -> Color.Transparent
        }
        val fg = when {
            f -> Yc.LanternInk
            selected -> Yc.Night
            else -> Yc.Muted
        }
        Row(
            Modifier
                .background(bg, Pill)
                .then(if (!f && !selected) Modifier.border(1.dp, Yc.Line, Pill) else Modifier)
                .height(34.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            T(text, Ty.label, fg, maxLines = 1)
            if (count != null) T(count, Ty.small, if (f || selected) fg.copy(alpha = 0.7f) else Yc.Faint, maxLines = 1)
        }
    }
}

// =====================================================================
// Карточки
// =====================================================================

@Composable
fun Placeholder(seed: Long, title: String, modifier: Modifier = Modifier, letter: Boolean = true) {
    if (liteMode) {
        Box(modifier.background(Yc.Night3))
        return
    }
    val h = ((seed * 47) % 360).toFloat().let { if (it < 0) it + 360 else it }
    Box(
        modifier.background(
            Brush.linearGradient(
                listOf(Color.hsl(h, 0.55f, 0.38f), Color.hsl((h + 60f) % 360f, 0.45f, 0.18f)),
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (letter) T(title.trimStart { !it.isLetterOrDigit() }.take(1).uppercase().ifEmpty { "?" }, Ty.page, Color.White.copy(alpha = 0.85f))
    }
}

/**
 * Картинка тайтла с плашкой-буквой, пока грузится.
 * cover = true — это обложка: если в настройках выбраны обложки AniList, подменяем на них.
 */
@Composable
fun Poster(url: String?, seed: Long, title: String, modifier: Modifier = Modifier, cover: Boolean = true) {
    val art = app.yoru.tv.YoruApp.instance.art
    val useAniList = cover && seed > 0 && art.coversEnabled
    if (useAniList) LaunchedEffect(seed) { art.requestCover(seed) }
    val src = (if (useAniList) art.covers[seed] else null) ?: url
    // когда картинка загрузилась, букву больше не измеряем и не рисуем: на странице сотни таких карточек
    var loaded by remember(src) { mutableStateOf(false) }
    Box(modifier) {
        Placeholder(seed, title, Modifier.fillMaxSize(), letter = !loaded)
        if (src != null) {
            AsyncImage(
                model = src, contentDescription = null, contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, dark: Boolean = false) {
    Box(
        modifier
            .background(if (dark) Color(0xD1141020) else Yc.Lantern, Pill)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        T(text, Ty.badge, if (dark) Yc.Text else Yc.LanternInk, maxLines = 1)
    }
}

@Composable
fun ProgressLine(value: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(Color(0xB3000000))) {
        Box(
            Modifier
                .fillMaxWidth(value.coerceIn(0f, 1f))
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(Yc.Lantern),
        )
    }
}

/** Прогресс внутри изображения: рамка фокуса больше не перекрывает полосу снизу. */
@Composable
private fun BoxScope.CardProgress(value: Float) {
    val progress = value.coerceIn(0f, 1f)
    Column(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .padding(8.dp)
            .padding(horizontal = 7.dp, vertical = 5.dp),
    ) {
        ProgressLine(progress, height = 4.dp)
        T("${(progress * 100).roundToInt()}%", Ty.small, Yc.Lantern, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Вертикальный постер 2:3 с названием — основная карточка рядов и сеток. */
@Composable
fun PosterCard(
    card: Card,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = 136.dp,
    focusModifier: Modifier = Modifier,
    onFocus: (() -> Unit)? = null,
    showLabels: Boolean = true,
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Column(if (width != null) modifier.width(width) else modifier.fillMaxWidth()) {
        Focusable(
            onClick = onClick,
            modifier = focusModifier.fillMaxWidth().aspectRatio(2f / 3f),
            interaction = src,
            onFocus = onFocus,
        ) {
            Poster(card.poster, card.malId, card.title, Modifier.fillMaxSize())
            card.topBadge?.let {
                Badge(
                    it,
                    Modifier.align(Alignment.TopStart).padding(start = 7.dp, top = if (card.progress != null) 50.dp else 7.dp),
                    dark = true,
                )
            }
            card.badge?.let { Badge(it, Modifier.align(Alignment.BottomStart).padding(7.dp)) }
            card.progress?.let { CardProgress(it) }
        }
        if (showLabels) {
            Spacer(Modifier.height(10.dp))
            T(card.title, Ty.cardTitle, if (focused) Yc.Lantern else Yc.Text, maxLines = 2)
            card.meta?.takeIf { it.isNotBlank() }?.let { T(it, Ty.small, Yc.Muted, maxLines = 1, modifier = Modifier.padding(top = 2.dp)) }
        }
    }
}

/** Горизонтальная карточка 16:9 с баннером — для «Продолжить» и новых серий. */
@Composable
fun WideCard(
    card: Card,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 252.dp,
    focusModifier: Modifier = Modifier,
    onFocus: (() -> Unit)? = null,
    showLabels: Boolean = true,
    continueStyle: Boolean = false,
) {
    Focusable(
        onClick = onClick,
        modifier = modifier.then(focusModifier).width(width).aspectRatio(16f / 9f),
        onFocus = onFocus,
        focusedScale = 1.05f,
    ) {
        Poster(image ?: card.poster, card.malId, card.title, Modifier.fillMaxSize(), cover = image == null)
        if (continueStyle) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xE6141020)),
                ),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 16.dp)) {
                if (showLabels) T(card.title, Ty.cardTitle, maxLines = 1)
                card.meta?.let { T(it, Ty.body, Yc.Text, maxLines = 1, modifier = if (showLabels) Modifier.padding(top = 2.dp) else Modifier) }
            }
            card.progress?.let {
                ProgressLine(
                    it,
                    Modifier.align(Alignment.BottomCenter).padding(start = 8.dp, end = 8.dp, bottom = 7.dp),
                    height = 4.dp,
                )
            }
        } else if (showLabels) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color(0xF0141020)),
                ),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                T(card.title, Ty.cardTitle, maxLines = 1)
                card.meta?.let { T(it, Ty.small, Yc.TextSoft, maxLines = 1) }
            }
        }
        card.badge?.let {
            Badge(
                it,
                Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = if (card.progress != null && !continueStyle) 50.dp else 8.dp),
            )
        }
        if (!continueStyle) card.progress?.let { CardProgress(it) }
    }
}

// =====================================================================
// Состояния
// =====================================================================

@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 34.dp) {
    if (liteMode) {
        // вращение перерисовывает экран каждый кадр — на слабом телевизоре достаточно надписи
        Box(modifier, contentAlignment = Alignment.Center) { T("Загрузка…", Ty.label, Yc.Muted, maxLines = 1) }
        return
    }
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "angle")
    Canvas(modifier.size(size)) {
        val w = this.size.minDimension * 0.1f
        drawArc(Yc.Line, 0f, 360f, false, style = Stroke(w))
        drawArc(Yc.Lantern, a, 90f, false, style = Stroke(w, cap = StrokeCap.Round))
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) { Spinner() }
}

@Composable
fun Empty(
    title: String,
    text: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Box(modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 620.dp)
                .border(1.dp, Yc.Line, RoundedCornerShape(22.dp))
                .padding(horizontal = 30.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            T(title, Ty.h3, align = androidx.compose.ui.text.style.TextAlign.Center)
            if (text != null) {
                Spacer(Modifier.height(8.dp))
                T(text, Ty.body, Yc.Muted, align = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { actions() }
        }
    }
}

fun errorText(e: Throwable): String = when {
    e is ApiError && e.code == 0 -> "Нет связи с интернетом или сервер не отвечает."
    else -> e.message ?: "Неизвестная ошибка"
}

@Composable
fun ErrorBox(e: Throwable, onRetry: (() -> Unit)?, modifier: Modifier = Modifier, focus: FocusRequester? = null) {
    Empty("Не удалось загрузить", errorText(e), modifier) {
        if (onRetry != null) {
            TvButton("Повторить", onRetry, Modifier.then(if (focus != null) Modifier.focusRequester(focus) else Modifier), icon = Ic.refresh)
        }
    }
}

// =====================================================================
// Всплывающие сообщения
// =====================================================================

object Toaster {
    val msg = MutableStateFlow<Pair<Long, String>?>(null)
    fun show(text: String) {
        msg.value = System.nanoTime() to text
    }
}

@Composable
fun BoxScope.ToastHost() {
    val m by Toaster.msg.collectAsState()
    var visible by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(m?.first) {
        val cur = m ?: return@LaunchedEffect
        visible = cur.second
        delay(2800)
        visible = null
    }
    visible?.let {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp)
                .background(Yc.Text, Pill)
                .padding(horizontal = 22.dp, vertical = 11.dp),
        ) {
            T(it, Ty.label, Yc.Night, maxLines = 2)
        }
    }
}

// =====================================================================
// Ввод текста с пульта
// =====================================================================

/**
 * На ТВ клавиатура выскакивает, как только поле получает фокус, и мешает ходить пультом.
 * Поэтому в покое это обычная кнопка, а клавиатура открывается только по OK.
 */
@Composable
fun TvTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    secret: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onSubmit: () -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    var editing by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    val view = androidx.compose.ui.platform.LocalView.current
    val boxFr = focusRequester ?: remember { FocusRequester() }
    val fieldFr = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(14.dp)

    if (!editing) {
        Focusable(
            onClick = {
                hadFocus = false
                editing = true
            },
            modifier = modifier.focusRequester(boxFr).height(50.dp),
            shape = shape,
            focusedScale = 1.02f,
        ) { f ->
            Row(
                Modifier.fillMaxSize().background(if (f) Yc.Night3 else Yc.Night2).border(1.dp, Yc.Line, shape).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (icon != null) Icon(icon, if (f) Yc.Lantern else Yc.Faint, 20.dp)
                val shown = when {
                    value.isEmpty() -> placeholder
                    secret -> "•".repeat(minOf(value.length, 24))
                    else -> value
                }
                T(shown, Ty.bodyBig, if (value.isEmpty()) Yc.Faint else Yc.Text, maxLines = 1)
            }
        }
    } else {
        val finish = {
            editing = false
            kb?.hide()
        }
        // «Назад» при открытом поле закрывает только поле, а не экран
        androidx.activity.compose.BackHandler(enabled = true) { finish() }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = Ty.bodyBig.copy(color = Yc.Text),
            cursorBrush = SolidColor(Yc.Lantern),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onDone = { finish(); onSubmit() },
                onSearch = { finish(); onSubmit() },
                onGo = { finish(); onSubmit() },
                onSend = { finish(); onSubmit() },
            ),
            modifier = modifier
                .height(50.dp)
                .focusRequester(fieldFr)
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyUp && (ev.key == Key.Back || ev.key == Key.Escape)) {
                        finish()
                        true
                    } else {
                        false
                    }
                }
                // первый вызов приходит сразу, ещё без фокуса — закрываем поле, только если фокус уже был и ушёл
                .onFocusChanged {
                    if (it.isFocused) hadFocus = true
                    else if (hadFocus && editing) editing = false
                },
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxSize().background(Yc.Night3, shape).border(2.dp, Yc.Lantern, shape).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (icon != null) Icon(icon, Yc.Lantern, 20.dp)
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) T(placeholder, Ty.bodyBig, Yc.Faint, maxLines = 1)
                        inner()
                    }
                }
            },
        )
        LaunchedEffect(Unit) {
            withFrameNanos { }
            fieldFr.tryFocus()
            // клавиатура: Compose может не успеть подключиться к полю — просим несколько раз
            // и дублируем системным вызовом, иначе на части телевизоров она не появляется
            repeat(3) { n ->
                delay(if (n == 0) 60 else 200)
                kb?.show()
                val imm = view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(view, 0)
            }
        }
    }
    // после закрытия клавиатуры возвращаем фокус на «кнопку» поля
    var wasEditing by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (!editing && wasEditing) {
            withFrameNanos { }
            boxFr.tryFocus()
        }
        wasEditing = editing
    }
}

@Composable
fun QrImage(bitmap: ImageBitmap, modifier: Modifier = Modifier) {
    Box(modifier.background(Color.White, RoundedCornerShape(14.dp)).padding(10.dp)) {
        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}
