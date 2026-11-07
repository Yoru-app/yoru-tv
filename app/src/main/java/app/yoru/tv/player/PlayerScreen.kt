package app.yoru.tv.player

import android.view.KeyEvent as AKey
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import coil.compose.AsyncImage
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.yoru.tv.YoruApp
import app.yoru.tv.data.Library
import app.yoru.tv.data.Subtitles
import app.yoru.tv.data.Translation
import app.yoru.tv.data.fmtTime
import app.yoru.tv.ui.Entry
import app.yoru.tv.ui.Focusable
import app.yoru.tv.ui.Ic
import app.yoru.tv.ui.Icon
import app.yoru.tv.ui.Navigator
import app.yoru.tv.ui.Onest
import app.yoru.tv.ui.Route
import app.yoru.tv.ui.liteMode
import app.yoru.tv.ui.Spinner
import app.yoru.tv.ui.T
import app.yoru.tv.ui.TvButton
import app.yoru.tv.ui.Ty
import app.yoru.tv.ui.Yc
import app.yoru.tv.ui.tryFocus
import kotlinx.coroutines.delay

private enum class Panel { TR, QUALITY, SERVER, EPISODES, SETTINGS }

/**
 * Плеер под пульт.
 * Контролы скрыты: OK — пауза/пуск, ←/→ — перемотка (удержание ускоряет),
 * ↑/↓ — показать панель. Кнопки пульта: ⏯, ⏪/⏩ ±30 с, ⏭/⏮ и CH+/CH− — серии, CC — субтитры, 0–9 — переход к доле серии.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(entry: Entry, nav: Navigator, route: Route.Watch) {
    val ctx = LocalContext.current
    val app = YoruApp.instance
    val st = remember(entry) {
        entry.state {
            PlayerState(ctx.applicationContext, route, entry.scope).also { p -> entry.onDispose += { p.release() } }
        }
    }
    val settings = app.prefs.settings.value

    var controls by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var peekUntil by remember { mutableLongStateOf(0L) }
    var focusTarget by remember { mutableStateOf<FocusRequester?>(null) }
    var subsOn by remember { mutableStateOf(settings.subsOn) }
    var subSize by remember { mutableFloatStateOf(settings.subSize) }

    val rootFr = remember { FocusRequester() }
    val playFr = remember { FocusRequester() }
    val seekFr = remember { FocusRequester() }
    val upFr = remember { FocusRequester() }
    val msgFr = remember { FocusRequester() }
    val panelBtn = remember { HashMap<Panel, FocusRequester>() }
    fun btnFr(p: Panel) = panelBtn.getOrPut(p) { FocusRequester() }

    fun show(target: FocusRequester = playFr) {
        controls = true
        focusTarget = target
        lastInput = System.currentTimeMillis()
    }

    fun hide() {
        rootFr.tryFocus()
        controls = false
        panel = null
    }

    fun openPanel(p: Panel) {
        panel = p
        controls = true
    }

    fun closePanel() {
        val p = panel
        panel = null
        if (p != null) focusTarget = btnFr(p)
    }

    LaunchedEffect(focusTarget) {
        val t = focusTarget ?: return@LaunchedEffect
        withFrameNanos { }
        t.tryFocus()
        focusTarget = null
    }
    // телевизор ушёл на главный экран или заснул — ставим на паузу и сохраняем место
    DisposableEffect(Unit) {
        val owner = ctx as? androidx.lifecycle.LifecycleOwner
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) st.onHidden()
        }
        owner?.lifecycle?.addObserver(obs)
        onDispose {
            owner?.lifecycle?.removeObserver(obs)
            // экран плеера ушёл из композиции (открыли подключение поверх) — не играть «вслепую»
            st.onHidden()
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (!playFr.let { runCatching { it.requestFocus() }.isSuccess }) rootFr.tryFocus()
    }

    // скрывать контролы через 5 секунд бездействия во время просмотра
    LaunchedEffect(controls, st.playing, lastInput, panel, st.msg) {
        if (controls && st.playing && panel == null && st.msg == null) {
            delay(5000)
            hide()
        }
    }
    // когда видео пошло — спрятать стартовую панель
    LaunchedEffect(st.playing) {
        if (st.playing && controls && panel == null && System.currentTimeMillis() - lastInput > 1500) hide()
    }
    // сообщение об ошибке: фокус на его кнопку
    LaunchedEffect(st.msg) {
        if (st.msg != null) {
            panel = null
            controls = false
            focusTarget = msgFr
        }
    }
    // «Дальше»: фокус на кнопку и обратный отсчёт
    var upProgress by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(st.upNext) {
        val n = st.upNext ?: return@LaunchedEffect
        panel = null
        controls = false
        focusTarget = upFr
        if (st.upNextAuto) {
            val total = 8000L
            val t0 = System.currentTimeMillis()
            while (true) {
                val left = total - (System.currentTimeMillis() - t0)
                upProgress = (left / total.toFloat()).coerceIn(0f, 1f)
                if (left <= 0) break
                delay(50)
            }
            if (st.upNext == n) st.goNext()
        }
    }

    BackHandler(enabled = panel != null || controls || st.upNext != null) {
        when {
            panel != null -> closePanel()
            st.upNext != null -> {
                st.cancelUpNext()
                hide()
            }
            else -> hide()
        }
    }

    fun seekStep(repeat: Int): Long = when {
        repeat < 3 -> 10_000L
        repeat < 12 -> 30_000L
        else -> 60_000L
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                lastInput = System.currentTimeMillis()
                when (ev.key.nativeKeyCode) {
                    AKey.KEYCODE_MEDIA_PLAY_PAUSE, AKey.KEYCODE_SPACE -> {
                        st.toggle()
                        true
                    }
                    AKey.KEYCODE_MEDIA_PLAY -> {
                        st.exo.play()
                        true
                    }
                    AKey.KEYCODE_MEDIA_PAUSE -> {
                        st.exo.pause()
                        show()
                        true
                    }
                    AKey.KEYCODE_MEDIA_FAST_FORWARD -> {
                        st.seekBy(30_000)
                        st.flash("+30 с")
                        peekUntil = System.currentTimeMillis() + 1500
                        true
                    }
                    AKey.KEYCODE_MEDIA_REWIND -> {
                        st.seekBy(-30_000)
                        st.flash("−30 с")
                        peekUntil = System.currentTimeMillis() + 1500
                        true
                    }
                    AKey.KEYCODE_MEDIA_NEXT, AKey.KEYCODE_CHANNEL_UP -> {
                        if (st.nextEpisode != null) st.goNext() else st.flash("Это последняя серия")
                        true
                    }
                    AKey.KEYCODE_MEDIA_PREVIOUS, AKey.KEYCODE_CHANNEL_DOWN -> {
                        if (st.prevEpisode != null) st.goPrev() else st.flash("Это первая серия")
                        true
                    }
                    AKey.KEYCODE_CAPTIONS -> {
                        subsOn = !subsOn
                        app.prefs.update { it.copy(subsOn = subsOn) }
                        st.flash(if (subsOn) "Субтитры включены" else "Субтитры выключены")
                        true
                    }
                    AKey.KEYCODE_MENU, AKey.KEYCODE_SETTINGS -> {
                        openPanel(Panel.SETTINGS)
                        true
                    }
                    in AKey.KEYCODE_0..AKey.KEYCODE_9 -> {
                        if (panel == null && st.msg == null) {
                            st.seekToFraction((ev.key.nativeKeyCode - AKey.KEYCODE_0) / 10f)
                            peekUntil = System.currentTimeMillis() + 1500
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
            .focusRequester(rootFr)
            .onKeyEvent { ev ->
                // сюда доходят только клавиши, которые не забрали кнопки, — то есть когда контролы скрыты
                if (ev.type != KeyEventType.KeyDown || controls || panel != null || st.msg != null || st.upNext != null) return@onKeyEvent false
                val repeat = ev.nativeKeyEvent.repeatCount
                when (ev.key.nativeKeyCode) {
                    AKey.KEYCODE_DPAD_CENTER, AKey.KEYCODE_ENTER, AKey.KEYCODE_NUMPAD_ENTER -> {
                        st.toggle()
                        show()
                        true
                    }
                    AKey.KEYCODE_DPAD_LEFT -> {
                        val s = seekStep(repeat)
                        st.seekBy(-s)
                        st.flash("−${s / 1000} с")
                        peekUntil = System.currentTimeMillis() + 1500
                        true
                    }
                    AKey.KEYCODE_DPAD_RIGHT -> {
                        val s = seekStep(repeat)
                        st.seekBy(s)
                        st.flash("+${s / 1000} с")
                        peekUntil = System.currentTimeMillis() + 1500
                        true
                    }
                    AKey.KEYCODE_DPAD_UP -> {
                        show(seekFr)
                        true
                    }
                    AKey.KEYCODE_DPAD_DOWN -> {
                        show(playFr)
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        // ---------------- видео ----------------
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    player = st.exo
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    keepScreenOn = true
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    subtitleView?.visibility = android.view.View.GONE
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // ---------------- субтитры ----------------
        if (subsOn && st.cues.isNotEmpty()) {
            Subs(st, subSize, Modifier.align(Alignment.BottomCenter).padding(start = 60.dp, end = 60.dp, bottom = if (controls) 170.dp else 36.dp))
        }

        // ---------------- загрузка ----------------
        if ((st.loading || st.buffering) && st.msg == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Spinner(size = 52.dp)
                st.status?.let {
                    Spacer(Modifier.height(14.dp))
                    T(it, Ty.label, Yc.TextSoft)
                }
            }
        }

        // ---------------- всплывающая подсказка ----------------
        Flash(st, Modifier.align(Alignment.TopCenter).padding(top = 40.dp))

        // ---------------- полоска при перемотке со скрытыми контролами ----------------
        if (!controls && System.currentTimeMillis() < peekUntil + 1 && st.dur > 0) {
            LaunchedEffect(peekUntil) {
                delay((peekUntil - System.currentTimeMillis()).coerceAtLeast(0))
                peekUntil = 0
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 48.dp, vertical = 30.dp)) {
                PeekTimes(st)
                Spacer(Modifier.height(6.dp))
                TrackWithThumb(st) { Track(st, focused = false) }
            }
        }

        // ---------------- контролы ----------------
        if (controls && st.msg == null) {
            Controls(
                st = st,
                panel = panel,
                subsOn = subsOn,
                playFr = playFr,
                seekFr = seekFr,
                btnFr = { btnFr(it) },
                onPanel = { openPanel(it) },
                // пришли со страницы тайтла — возвращаемся на неё, иначе открываем её вместо плеера
                onTitle = {
                    if (nav.stack.getOrNull(nav.stack.lastIndex - 1)?.route == Route.Title(st.malId)) nav.pop() else nav.replace(Route.Title(st.malId))
                },
                onToggleSubs = {
                    subsOn = !subsOn
                    app.prefs.update { s -> s.copy(subsOn = subsOn) }
                    st.flash(if (subsOn) "Субтитры включены" else "Субтитры выключены")
                },
                onActivity = { lastInput = System.currentTimeMillis() },
            )
        }

        // ---------------- боковая панель ----------------
        panel?.let { p ->
            SidePanel(
                st = st,
                panel = p,
                subSize = subSize,
                onSubSize = { v ->
                    subSize = v
                    app.prefs.update { s -> s.copy(subSize = v) }
                },
                onClose = { closePanel() },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        // ---------------- «Дальше» ----------------
        st.upNext?.let { n ->
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = 48.dp)
                    .width(340.dp)
                    .background(Color(0xF51B1630), RoundedCornerShape(18.dp))
                    .border(1.dp, Yc.Line, RoundedCornerShape(18.dp))
                    .padding(20.dp)
                    .focusGroup(),
            ) {
                T("Дальше", Ty.small, Yc.Faint)
                T(n.label, Ty.section, maxLines = 1)
                n.title?.let { T(it, Ty.body, Yc.Muted, maxLines = 1) }
                if (st.upNextAuto) {
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).background(Yc.Line, RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxWidth(upProgress).height(4.dp).background(Yc.Lantern, RoundedCornerShape(2.dp)))
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton("Смотреть", { st.goNext() }, Modifier.focusRequester(upFr), icon = Ic.play, primary = true, small = true)
                    TvButton(if (st.upNextAuto) "Отмена" else "Закрыть", { st.cancelUpNext(); hide() }, small = true)
                }
            }
        }

        // ---------------- ошибки ----------------
        st.msg?.let { m ->
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xF5251F3D), Color(0xFA0A0812)))), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 640.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    T(m.title, Ty.section, align = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    T(m.text, Ty.bodyBig, Yc.Muted, align = TextAlign.Center)
                    m.detail?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(8.dp))
                        T("Ответ API: $it", Ty.small, Yc.Faint, align = TextAlign.Center)
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (m.kind) {
                            MsgKind.AUTH -> {
                                TvButton("Подключить токен", { nav.push(Route.Connect) }, Modifier.focusRequester(msgFr), icon = Ic.phone, primary = true)
                                if (st.translations.size > 1) TvButton("Другой перевод", { st.msg = null; openPanel(Panel.TR) }, icon = Ic.voice)
                                TvButton("Повторить", { st.retry() }, icon = Ic.refresh)
                            }
                            MsgKind.RETRY -> {
                                TvButton("Повторить", { st.retry() }, Modifier.focusRequester(msgFr), icon = Ic.refresh, primary = true)
                                if (st.translations.size > 1) TvButton("Другой перевод", { st.msg = null; openPanel(Panel.TR) }, icon = Ic.voice)
                                if ((st.cands.size) > 1) TvButton("Другой сервер", { st.msg = null; openPanel(Panel.SERVER) }, icon = Ic.server)
                            }
                            MsgKind.NO_TRANSLATION -> {
                                TvButton("Другие серии", { st.msg = null; openPanel(Panel.EPISODES) }, Modifier.focusRequester(msgFr), icon = Ic.list, primary = true)
                                TvButton("Назад", { nav.pop() })
                            }
                            MsgKind.NOT_FOUND -> {
                                TvButton("Назад", { nav.pop() }, Modifier.focusRequester(msgFr), primary = true)
                                TvButton("Страница тайтла", { nav.replace(Route.Title(st.malId)) }, icon = Ic.info)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Flash(st: PlayerState, modifier: Modifier) {
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(st.flash?.first) {
        val f = st.flash ?: return@LaunchedEffect
        text = f.second
        delay(1400)
        text = null
    }
    text?.let {
        Box(modifier.background(Color(0xCC080610), RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 9.dp)) {
            T(it, Ty.label, maxLines = 1)
        }
    }
}

/**
 * Полоса перемотки с миниатюрой кадра над ней. Миниатюра видна, пока жмут ←/→, и ещё полторы секунды.
 */
@Composable
private fun TrackWithThumb(st: PlayerState, track: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(st.seekAt) {
        if (st.seekAt == 0L) return@LaunchedEffect
        visible = true
        delay(1600)
        visible = false
        st.thumbs.clear()
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        track()
        val img = st.thumbs.image
        if (visible && img != null && st.thumbs.imageAt >= 0 && st.dur > 0) {
            val w = 192.dp
            val h = w * img.height / img.width.coerceAtLeast(1)
            val frac = (st.seekTarget.toFloat() / st.dur).coerceIn(0f, 1f)
            val x = (maxWidth * frac - w / 2).coerceIn(0.dp, (maxWidth - w).coerceAtLeast(0.dp))
            // Нулевой размер в разметке: иначе блок растёт на высоту кадра и выталкивает шкалу вверх.
            // Кадр рисуется над верхним краем шкалы, сама шкала остаётся на месте.
            Column(
                Modifier.layout { m, _ ->
                    val p = m.measure(Constraints())
                    layout(0, 0) { p.place(x.roundToPx(), -p.height - 6.dp.roundToPx()) }
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.foundation.Image(
                    bitmap = img,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(w, h)
                        .clip(RoundedCornerShape(8.dp))
                        .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.height(4.dp))
                Box(Modifier.background(Color(0xCC080610), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 2.dp)) {
                    T(fmtTime(st.seekTarget / 1000), Ty.label)
                }
            }
        }
    }
}

/** Полоса прогресса: буфер, просмотренное, «бегунок». */
@Composable
private fun Track(st: PlayerState, focused: Boolean, modifier: Modifier = Modifier) {
    val h = if (focused) 8.dp else 5.dp
    Canvas(modifier.fillMaxWidth().height(18.dp)) {
        val d = st.dur.toFloat().coerceAtLeast(1f)
        val y = size.height / 2
        val th = h.toPx()
        val r = CornerRadius(th / 2, th / 2)
        drawRoundRect(Color(0x38FFFFFF), Offset(0f, y - th / 2), Size(size.width, th), r)
        drawRoundRect(Color(0x52FFFFFF), Offset(0f, y - th / 2), Size(size.width * (st.buffered / d).coerceIn(0f, 1f), th), r)
        val px = size.width * (st.pos / d).coerceIn(0f, 1f)
        drawRoundRect(Yc.Lantern, Offset(0f, y - th / 2), Size(px, th), r)
        if (focused) {
            drawCircle(Yc.Lantern.copy(alpha = 0.3f), radius = 13.dp.toPx(), center = Offset(px, y))
            drawCircle(Yc.Lantern, radius = 8.dp.toPx(), center = Offset(px, y))
        }
    }
}

@Composable
private fun Controls(
    st: PlayerState,
    panel: Panel?,
    subsOn: Boolean,
    playFr: FocusRequester,
    seekFr: FocusRequester,
    btnFr: (Panel) -> FocusRequester,
    onPanel: (Panel) -> Unit,
    onToggleSubs: () -> Unit,
    onActivity: () -> Unit,
    onTitle: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // затемнение кадра, чтобы логотип и кнопки читались на любом фоне
        Box(Modifier.fillMaxSize().background(Color(0x40080610)))
        // вверху по центру: логотип названия (TMDB), если его нет — название текстом
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .then(if (liteMode) Modifier else Modifier.background(Brush.verticalGradient(listOf(Color(0xB3080610), Color.Transparent))))
                .padding(start = 80.dp, end = 80.dp, top = 26.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            var logoFailed by remember(st.logo) { mutableStateOf(false) }
            val logo = st.logo
            if (logo != null && !logoFailed) {
                AsyncImage(
                    model = logo,
                    contentDescription = st.title,
                    contentScale = ContentScale.Fit,
                    onError = { logoFailed = true },
                    modifier = Modifier.widthIn(max = 340.dp).heightIn(max = 86.dp),
                )
            } else {
                T(st.title, Ty.page.copy(fontSize = 25.sp, lineHeight = 30.sp, shadow = Shadow(Color.Black, Offset(0f, 3f), 12f)), align = TextAlign.Center, maxLines = 2)
            }
            Spacer(Modifier.height(8.dp))
            val ep = st.episode
            val tr = st.current
            T(
                listOfNotNull(ep?.label, ep?.title, tr?.let { "${Translation.KINDS[it.kind]?.lowercase()} ${it.name}" }).joinToString(" · "),
                if (liteMode) Ty.body else Ty.body.copy(shadow = Shadow(Color.Black, Offset(0f, 2f), 8f)), Yc.TextSoft, maxLines = 1, align = TextAlign.Center,
            )
        }
        // низ: перемотка и кнопки
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .then(if (liteMode) Modifier.background(Color(0xD9080610)) else Modifier.background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x8C080610), Color(0xE6080610)))))
                .padding(start = 48.dp, end = 48.dp, top = 60.dp, bottom = 26.dp),
        ) {
            ControlTimes(st)
            Spacer(Modifier.height(4.dp))
            TrackWithThumb(st) { SeekBar(st, seekFr, onActivity) }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().focusGroup(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RoundBtn(if (st.playing) Ic.pause else Ic.play, { st.toggle() }, Modifier.focusRequester(playFr), big = true)
                RoundBtn(Ic.prev, { st.goPrev() }, enabled = st.prevEpisode != null)
                RoundBtn(Ic.next, { st.goNext() }, enabled = st.nextEpisode != null)
                PillBtn(Ic.info, "О тайтле", onTitle)
                Spacer(Modifier.weight(1f))
                PillBtn(Ic.voice, st.current?.name ?: "Перевод", { onPanel(Panel.TR) }, Modifier.focusRequester(btnFr(Panel.TR)), active = panel == Panel.TR)
                PillBtn(Ic.quality, st.stream?.let { "${it.height}p" } ?: "Качество", { onPanel(Panel.QUALITY) }, Modifier.focusRequester(btnFr(Panel.QUALITY)), active = panel == Panel.QUALITY, enabled = st.streams.size > 1)
                // сервер — только значок, адреса видны в панели
                RoundBtn(Ic.server, { onPanel(Panel.SERVER) }, Modifier.focusRequester(btnFr(Panel.SERVER)), enabled = st.cands.isNotEmpty(), selected = panel == Panel.SERVER)
                RoundBtn(Ic.cc, onToggleSubs, enabled = st.cues.isNotEmpty(), selected = subsOn && st.cues.isNotEmpty())
                RoundBtn(Ic.list, { onPanel(Panel.EPISODES) }, Modifier.focusRequester(btnFr(Panel.EPISODES)))
                RoundBtn(Ic.sliders, { onPanel(Panel.SETTINGS) }, Modifier.focusRequester(btnFr(Panel.SETTINGS)))
            }
        }
    }
}

/**
 * Субтитры, время и полоса читают позицию сами. Позиция меняется 5 раз в секунду: если читать её в общем экране,
 * он весь перерисовывается с той же частотой. Здесь перерисовка только при смене реплики или секунды.
 */
@Composable
private fun Subs(st: PlayerState, subSize: Float, modifier: Modifier) {
    val active by remember(st) { androidx.compose.runtime.derivedStateOf { Subtitles.active(st.cues, st.pos) } }
    if (active.isEmpty()) return
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        active.forEach { c ->
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .background(Color(0xA8080610), RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 3.dp),
            ) {
                T(
                    c.text,
                    Ty.body.copy(
                        fontFamily = Onest,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = (24f * subSize).sp,
                        lineHeight = (31f * subSize).sp,
                        shadow = if (liteMode) null else Shadow(Color.Black, Offset(0f, 2f), 4f),
                    ),
                    Color.White,
                    align = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun PeekTimes(st: PlayerState) {
    val sec by remember(st) { androidx.compose.runtime.derivedStateOf { st.pos / 1000 } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        T(fmtTime(sec), Ty.label)
        T(fmtTime(st.dur / 1000), Ty.label, Yc.TextSoft)
    }
}

@Composable
private fun ControlTimes(st: PlayerState) {
    val sec by remember(st) { androidx.compose.runtime.derivedStateOf { st.pos / 1000 } }
    val durSec = st.dur / 1000
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        T(fmtTime(sec), Ty.label)
        T(if (durSec > 0) "−" + fmtTime(durSec - sec) + " · " + fmtTime(durSec) else "", Ty.label, Yc.TextSoft)
    }
}

@Composable
private fun SeekBar(st: PlayerState, fr: FocusRequester, onActivity: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .focusRequester(fr)
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                val rep = ev.nativeKeyEvent.repeatCount
                val step = when {
                    rep < 3 -> 10_000L
                    rep < 12 -> 30_000L
                    else -> 60_000L
                }
                when (ev.key.nativeKeyCode) {
                    AKey.KEYCODE_DPAD_LEFT -> {
                        st.seekBy(-step)
                        onActivity()
                        true
                    }
                    AKey.KEYCODE_DPAD_RIGHT -> {
                        st.seekBy(step)
                        onActivity()
                        true
                    }
                    AKey.KEYCODE_DPAD_CENTER, AKey.KEYCODE_ENTER -> {
                        st.toggle()
                        true
                    }
                    else -> false
                }
            }
            .focusable(interactionSource = src),
        contentAlignment = Alignment.Center,
    ) {
        Track(st, focused)
    }
}

@Composable
private fun RoundBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    Focusable(onClick, modifier.size(if (big) 44.dp else 36.dp), shape = CircleShape, focusedScale = 1.12f, border = false, enabled = enabled) { f ->
        Box(
            Modifier
                .fillMaxSize()
                .background(if (f) Yc.Lantern else Color(0x1FFFFFFF), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                when {
                    !enabled -> Color(0x52FFFFFF)
                    f -> Yc.LanternInk
                    selected -> Yc.Lantern
                    else -> Color.White
                },
                if (big) 21.dp else 17.dp,
            )
        }
    }
}

@Composable
private fun PillBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    Focusable(onClick, modifier.height(34.dp), shape = RoundedCornerShape(50), focusedScale = 1.06f, border = false, enabled = enabled) { f ->
        Row(
            Modifier
                .fillMaxHeight()
                .background(if (f) Yc.Lantern else if (active) Color(0x40FFFFFF) else Color(0x1FFFFFFF), RoundedCornerShape(50))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val c = if (!enabled) Color(0x73FFFFFF) else if (f) Yc.LanternInk else Color.White
            Icon(icon, c, 15.dp)
            T(text, Ty.label.copy(fontSize = 12.sp, lineHeight = 15.sp), c, maxLines = 1, modifier = Modifier.widthIn(max = 150.dp))
        }
    }
}

// =====================================================================
// Боковая панель: перевод, качество, сервер, серии, настройки
// =====================================================================

private data class PanelRow(
    val key: String,
    val text: String,
    val hint: String? = null,
    val selected: Boolean = false,
    val header: Boolean = false,
    val progress: Float? = null,
    val onClick: () -> Unit = {},
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SidePanel(
    st: PlayerState,
    panel: Panel,
    subSize: Float,
    onSubSize: (Float) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val app = YoruApp.instance
    val lib = app.library
    val s = app.prefs.settings.value
    var autoNext by remember { mutableStateOf(s.autoNext) }
    var autoMark by remember { mutableStateOf(s.autoMark) }
    var fastest by remember { mutableStateOf(s.fastestCdn) }
    var previews by remember { mutableStateOf(s.scrubPreview) }

    val title: String
    val rows: List<PanelRow>
    when (panel) {
        Panel.TR -> {
            title = "Перевод"
            rows = buildList {
                Translation.KINDS.forEach { (k, label) ->
                    val g = st.translations.filter { it.kind == k }
                    if (g.isNotEmpty()) {
                        add(PanelRow("h:$k", "$label · ${g.size}", header = true))
                        g.forEach { t ->
                            add(PanelRow("t:${t.id}", t.name, t.meta, t.id == st.current?.id) { st.selectTranslation(t); onClose() })
                        }
                    }
                }
                if (isEmpty()) add(PanelRow("none", "У этой серии нет переводов", header = true))
            }
        }
        Panel.QUALITY -> {
            title = "Качество"
            rows = st.streams.map { q ->
                PanelRow(
                    "q:${q.height}", "${q.height}p",
                    when {
                        q.height >= 1080 -> "Full HD"
                        q.height >= 720 -> "HD"
                        else -> null
                    },
                    q == st.stream,
                ) { st.pickQuality(q.height); onClose() }
            }
        }
        Panel.SERVER -> {
            title = "Сервер видео"
            rows = buildList {
                add(PanelRow("h", "Канал CDN задаётся в профиле на сайте Anime365, здесь — адреса из ответа API.", header = true))
                st.cands.forEachIndexed { i, c ->
                    val hint = when {
                        st.isMp4Fallback(c) -> "запасной путь"
                        c.mbps != null -> String.format(java.util.Locale.US, "%.1f Мбит/с", c.mbps)
                        st.measuring -> "замер…"
                        else -> null
                    }
                    add(PanelRow("s:$i", if (st.isMp4Fallback(c)) "MP4 с сайта" else c.host, hint, i == st.candIdx) { st.pickServer(i); onClose() })
                }
                if (st.stream?.urls?.size ?: 0 > 1) {
                    add(PanelRow("measure", if (st.measuring) "Замеряем…" else "Замерить скорость заново", null, false) { st.remeasure() })
                }
            }
        }
        Panel.EPISODES -> {
            title = "Серии"
            val pv = lib.progressVersion.value
            rows = st.episodesOfType.map { e ->
                val p = if (pv >= 0) lib.pct(e.id) else 0
                PanelRow(
                    "e:${e.id}", e.label + (e.title?.let { " · $it" } ?: ""),
                    when {
                        p >= Library.SEEN_PCT -> "просмотрено"
                        p > 0 -> "$p%"
                        else -> null
                    },
                    e.id == st.episode?.id,
                    progress = p.takeIf { it in 1 until Library.SEEN_PCT }?.let { it / 100f },
                ) { if (e.id != st.episode?.id) st.openEpisode(e); onClose() }
            }
        }
        Panel.SETTINGS -> {
            title = "Настройки плеера"
            rows = buildList {
                add(PanelRow("h1", "Скорость", header = true))
                listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { r ->
                    add(PanelRow("r:$r", "${r}×", null, st.speed == r) { st.setRate(r) })
                }
                add(PanelRow("h2", "Размер субтитров", header = true))
                listOf(0.8f to "Мелкий", 1f to "Обычный", 1.25f to "Крупный", 1.5f to "Огромный").forEach { (v, l) ->
                    add(PanelRow("ss:$v", l, null, subSize == v) { onSubSize(v) })
                }
                add(PanelRow("h3", "Прочее", header = true))
                add(PanelRow("thumbs", "Превью при перемотке", if (previews) "вкл" else "выкл", previews) {
                    previews = !previews
                    app.prefs.update { it.copy(scrubPreview = previews) }
                    if (!previews) st.thumbs.clear()
                })
                add(PanelRow("auto", "Следующая серия автоматически", if (autoNext) "вкл" else "выкл", autoNext) {
                    autoNext = !autoNext
                    app.prefs.update { it.copy(autoNext = autoNext) }
                })
                add(PanelRow("mark", "Отмечать на Shikimori", if (autoMark) "вкл" else "выкл", autoMark) {
                    autoMark = !autoMark
                    app.prefs.update { it.copy(autoMark = autoMark) }
                })
                add(PanelRow("fast", "Самый быстрый сервер", if (fastest) "вкл" else "выкл", fastest) {
                    fastest = !fastest
                    app.prefs.update { it.copy(fastestCdn = fastest) }
                })
                add(PanelRow("keys", "Пульт: ←/→ перемотка (удерживайте — быстрее), ↑ полоса перемотки, OK пауза, CH+/CH− серии, 0–9 переход к доле серии", header = true))
            }
        }
    }

    val list = rememberLazyListState()
    val selFr = remember(panel) { FocusRequester() }
    val firstFr = remember(panel) { FocusRequester() }
    val selIndex = rows.indexOfFirst { it.selected && !it.header }
    val firstIndex = rows.indexOfFirst { !it.header }
    LaunchedEffect(panel) {
        if (selIndex > 2) list.scrollToItem(selIndex - 2)
        withFrameNanos { }
        if (selIndex >= 0) selFr.tryFocus() else firstFr.tryFocus()
    }

    Column(
        modifier
            .fillMaxHeight()
            .width(400.dp)
            .background(Color(0xF71B1630))
            .border(1.dp, Yc.Line)
            .padding(top = 30.dp, bottom = 20.dp)
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup(),
    ) {
        T(title, Ty.section, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(12.dp))
        LazyColumn(state = list, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(rows, key = { _, r -> r.key }) { i, r ->
                if (r.header) {
                    T(r.text, Ty.small, Yc.Faint, modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 4.dp))
                } else {
                    val fm = when (i) {
                        selIndex -> Modifier.focusRequester(selFr)
                        firstIndex -> Modifier.focusRequester(firstFr)
                        else -> Modifier
                    }
                    Focusable(r.onClick, fm.fillMaxWidth(), shape = RoundedCornerShape(12.dp), focusedScale = 1.02f, border = false) { f ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(if (f) Yc.Text else if (r.selected) Yc.Night3 else Color.Transparent, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.width(18.dp)) {
                                    if (r.selected) Icon(Ic.check, if (f) Yc.Night else Yc.Lantern, 16.dp)
                                }
                                T(r.text, Ty.label, if (f) Yc.Night else Yc.Text, maxLines = 1, modifier = Modifier.weight(1f))
                                r.hint?.let { T(it, Ty.small, if (f) Yc.Night.copy(alpha = 0.7f) else Yc.Faint, maxLines = 1) }
                            }
                            r.progress?.let { p ->
                                Spacer(Modifier.height(6.dp))
                                Box(Modifier.padding(start = 28.dp).fillMaxWidth().height(3.dp).background(Yc.Line)) {
                                    Box(Modifier.fillMaxWidth(p).height(3.dp).background(Yc.Lantern))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
