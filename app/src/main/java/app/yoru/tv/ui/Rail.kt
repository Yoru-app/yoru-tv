package app.yoru.tv.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.yoru.tv.YoruApp

val RAIL_W = 60.dp
private val RAIL_OPEN = 212.dp
/** Подсветка пункта в свёрнутом меню — компактная, не прижимается к контенту. */
private val ITEM = 36.dp

private data class RailItem(val route: Route, val title: String, val icon: ImageVector)

private val RAIL_ITEMS by lazy {
    listOf(
        RailItem(Route.Home, "Главная", Ic.home),
        RailItem(Route.Catalog, "Каталог и поиск", Ic.search),
        RailItem(Route.Schedule, "Расписание", Ic.calendar),
        RailItem(Route.Library, "Моё", Ic.bookmark),
        RailItem(Route.Notifications, "Уведомления", Ic.bell),
        RailItem(Route.Settings, "Настройки", Ic.gear),
    )
}

/** Разделы с боковым меню. Меню свёрнуто до иконок и раскрывается, когда в нём фокус. */
@Composable
fun WithRail(nav: Navigator, current: Route, content: @Composable () -> Unit) {
    val settings by YoruApp.instance.prefs.settings.collectAsState()
    val hidden = settings.railHidden
    Box(Modifier.fillMaxSize()) {
        // Контент заходит под прозрачный правый край меню: от иконок до карточек остаётся
        // обычный отступ страницы, а не «ширина меню + отступ».
        Box(Modifier.fillMaxSize().padding(start = if (hidden) 0.dp else RAIL_CONTENT_INSET)) { content() }
        Rail(nav, current, hidden)
    }
}

/** Насколько контент отодвинут от левого края при видимом свёрнутом меню. */
val RAIL_CONTENT_INSET = 34.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Rail(nav: Navigator, current: Route, hidden: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    // скрытое меню стоит за левым краем экрана; по «влево» выезжает вместе с фокусом
    val shift by animateDpAsState(
        if (hidden && !expanded) -RAIL_W else 0.dp,
        railSpec(),
        label = "railShift",
    )
    val width by animateDpAsState(
        if (expanded) RAIL_OPEN else RAIL_W,
        railSpec(),
        label = "railWidth",
    )
    // 0 — свёрнуто, 1 — раскрыто; фон и подсветка пунктов меняются вместе с шириной, а не скачком
    val open = ((width - RAIL_W) / (RAIL_OPEN - RAIL_W)).coerceIn(0f, 1f)
    val frs = remember { RAIL_ITEMS.map { FocusRequester() } }
    val sel = RAIL_ITEMS.indexOfFirst { it.route == current }.coerceAtLeast(0)
    val notices by YoruApp.instance.repo.notices.collectAsState()
    val unread = notices.count { !it.read }

    Box(
        Modifier
            .offset(x = shift)
            .fillMaxHeight()
            .width(width)
            .background(
                // под иконками фон сплошной (карточки, заехавшие под меню, не просвечивают),
                // к правому краю — растворяется; раскрытое меню — сплошное
                // меню целиком непрозрачное: карточки, заехавшие под него при прокрутке ряда, не видны
                Yc.Night,
            )
            .onPreviewKeyEvent { ev ->
                // → из меню — обратно в контент, туда, где был курсор (и меню сворачивается)
                if (expanded && ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight) {
                    nav.restoreContentFocus()
                    true
                } else {
                    false
                }
            },
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .padding(vertical = Safe.v, horizontal = 8.dp)
                .onFocusChanged { expanded = it.hasFocus }
                // меню открывается только кнопкой «влево»; ↑/↓ на странице в него не уводят
                .focusProperties { enter = { dir -> if (dir == FocusDirection.Left) frs[sel] else FocusRequester.Cancel } }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.padding(start = 9.dp, bottom = 14.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Logo(26.dp)
                if (expanded) {
                    Spacer(Modifier.width(11.dp))
                    T("Yoru", Ty.h3, maxLines = 1)
                }
            }
            RAIL_ITEMS.forEachIndexed { i, item ->
                if (i == RAIL_ITEMS.lastIndex) Spacer(Modifier.weight(1f))
                Focusable(
                    onClick = { nav.switchTo(item.route) },
                    // раскрытое меню — пункт растягивается вместе с ним; при сворачивании подсветка
                    // сразу становится маленькой, чтобы не отставать от меню
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .width(if (expanded) ITEM + (RAIL_OPEN - 24.dp - ITEM) * open else ITEM)
                        .height(36.dp)
                        .focusRequester(frs[i]),
                    shape = RoundedCornerShape(11.dp),
                    focusedScale = 1f,
                    border = false,
                ) { f ->
                    val active = i == sel
                    Row(
                        Modifier
                            .fillMaxSize()
                            .background(
                                when {
                                    f -> Yc.Lantern
                                    active -> Yc.Night3
                                    else -> Color.Transparent
                                },
                                RoundedCornerShape(11.dp),
                            )
                            .padding(start = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            Icon(item.icon, if (f) Yc.LanternInk else if (active) Yc.Lantern else Yc.Muted, 18.dp)
                            if (item.route == Route.Notifications && unread > 0) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 4.dp, y = (-3).dp)
                                        .size(9.dp)
                                        .background(Yc.Rose, CircleShape),
                                )
                            }
                        }
                        if (expanded) {
                            Spacer(Modifier.width(14.dp))
                            T(
                                item.title + if (item.route == Route.Notifications && unread > 0) "  $unread" else "",
                                Ty.label,
                                if (f) Yc.LanternInk else if (active) Yc.Text else Yc.Muted,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun railSpec(): androidx.compose.animation.core.AnimationSpec<androidx.compose.ui.unit.Dp> =
    if (liteMode) {
        androidx.compose.animation.core.snap()
    } else {
        androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)
    }

/** Логотип — маскот-фонарь, как в веб-Yoru 2.7 (public/logo.svg). size — высота, ширина по пропорциям. */
@Composable
fun Logo(size: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(app.yoru.tv.R.drawable.yoru_logo),
        contentDescription = "Yoru",
        modifier = Modifier.size(width = size * (662f / 728f), height = size),
    )
}

