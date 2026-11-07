package app.yoru.tv.ui

import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.rotate
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.animateScrollBy

/** Модальное окно в стиле Yoru. «Назад» на пульте закрывает его. */
@Composable
fun TvDialog(
    onDismiss: () -> Unit,
    title: String,
    subtitle: String? = null,
    width: Dp = 560.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Yc.Scrim)
                .onPreviewKeyEvent { ev ->
                    // «Назад» закрывает окно, а не уводит фокус из группы
                    if (ev.key == Key.Back || ev.key == Key.Escape) {
                        if (ev.type == KeyEventType.KeyUp) onDismiss()
                        true
                    } else {
                        false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = width)
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .background(Yc.Night2, RoundedCornerShape(22.dp))
                    .border(1.dp, Yc.Line, RoundedCornerShape(22.dp))
                    .padding(26.dp),
            ) {
                T(title, Ty.section)
                if (subtitle != null) T(subtitle, Ty.body, Yc.Muted, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(18.dp))
                content()
            }
        }
    }
}

/** Пункт списка в диалоге: галочка у выбранного. */
@Composable
fun DialogItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    icon: ImageVector? = null,
) {
    Focusable(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), focusedScale = 1.02f, border = false) { f ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (f) Yc.Text else if (selected) Yc.Night3 else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.width(20.dp)) {
                if (selected) Icon(Ic.check, if (f) Yc.Night else Yc.Lantern, 18.dp)
                else if (icon != null) Icon(icon, if (f) Yc.Night else Yc.Muted, 18.dp)
            }
            T(text, Ty.label, if (f) Yc.Night else if (selected) Yc.Text else Yc.TextSoft, maxLines = 1, modifier = Modifier.weight(1f))
            if (hint != null) T(hint, Ty.small, if (f) Yc.Night.copy(alpha = 0.7f) else Yc.Faint, maxLines = 1)
        }
    }
}

/** Длинный текст: пульт вверх/вниз прокручивает. */
@Composable
fun TextDialog(title: String, text: String, onDismiss: () -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val fr = remember { FocusRequester() }
    TvDialog(onDismiss, title, width = 720.dp) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .focusRequester(fr)
                .onKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (ev.key) {
                        Key.DirectionDown -> {
                            scope.launch { scroll.animateScrollBy(220f) }
                            true
                        }
                        Key.DirectionUp -> {
                            scope.launch { scroll.animateScrollBy(-220f) }
                            true
                        }
                        else -> false
                    }
                }
                .focusable()
                .verticalScroll(scroll),
        ) {
            T(text, Ty.bodyBig, Yc.TextSoft)
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            kotlinx.coroutines.delay(60)
            fr.tryFocus()
        }
    }
}

/**
 * Раскрывающийся список под пульт: кнопка «Сортировка: Популярные ▾», по OK — выпадающее меню.
 * multi = true — можно отметить несколько пунктов, меню закрывается «Назад» (тогда сработает onClose).
 */
@Composable
fun <V> TvDropdown(
    label: String,
    summary: String,
    options: List<Pair<V, String>>,
    selected: Set<V>,
    onPick: (V) -> Unit,
    modifier: Modifier = Modifier,
    multi: Boolean = false,
    onClose: () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    val btnFr = remember { FocusRequester() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val shape = RoundedCornerShape(50)

    Box(modifier) {
        Focusable(
            onClick = { open = true },
            modifier = Modifier.focusRequester(btnFr).height(40.dp),
            shape = shape,
            focusedScale = 1.05f,
            border = false,
        ) { f ->
            Row(
                Modifier
                    .background(if (f) Yc.Lantern else Yc.Night2, shape)
                    .border(1.dp, if (f) Yc.Lantern else Yc.Line, shape)
                    .padding(start = 16.dp, end = 12.dp)
                    .height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T("$label: ", Ty.small, if (f) Yc.LanternInk.copy(alpha = 0.7f) else Yc.Faint, maxLines = 1)
                T(summary, Ty.label, if (f) Yc.LanternInk else Yc.Text, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Icon(Ic.chevron, if (f) Yc.LanternInk else Yc.Muted, 16.dp, Modifier.rotate(90f))
            }
        }
        if (open) {
            val close = {
                open = false
                onClose()
            }
            androidx.compose.ui.window.Popup(
                alignment = Alignment.TopStart,
                offset = androidx.compose.ui.unit.IntOffset(0, with(density) { 46.dp.roundToPx() }),
                onDismissRequest = { close() },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
            ) {
                val first = remember { FocusRequester() }
                val startIndex = options.indexOfFirst { it.first in selected }.coerceAtLeast(0)
                val listState = androidx.compose.foundation.lazy.rememberLazyListState(maxOf(0, startIndex - 2))
                androidx.compose.foundation.lazy.LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .width(280.dp)
                        .heightIn(max = 360.dp)
                        .background(Yc.Night2, RoundedCornerShape(16.dp))
                        .border(1.dp, Yc.Line, RoundedCornerShape(16.dp))
                        .onPreviewKeyEvent { ev ->
                            if (ev.key == Key.Back || ev.key == Key.Escape) {
                                if (ev.type == KeyEventType.KeyUp) close()
                                true
                            } else {
                                false
                            }
                        }
                        .padding(6.dp),
                ) {
                    items(options.size) { i ->
                        val (v, text) = options[i]
                        DialogItem(
                            text, v in selected,
                            {
                                onPick(v)
                                if (!multi) close()
                            },
                            modifier = if (i == startIndex) Modifier.focusRequester(first) else Modifier,
                        )
                    }
                }
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    kotlinx.coroutines.delay(40)
                    first.tryFocus()
                }
            }
        }
    }
    // после закрытия — фокус обратно на кнопку списка
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (!open && wasOpen) {
            withFrameNanos { }
            btnFr.tryFocus()
        }
        wasOpen = open
    }
}
