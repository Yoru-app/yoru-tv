package app.yoru.tv.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.yoru.tv.BuildConfig
import app.yoru.tv.YoruApp
import app.yoru.tv.channels.ChannelInfo
import app.yoru.tv.channels.TvChannels
import app.yoru.tv.data.CHRONO_MODES
import app.yoru.tv.data.HomeSource
import app.yoru.tv.data.TrRank
import app.yoru.tv.data.plural
import app.yoru.tv.ui.Chip
import app.yoru.tv.ui.DialogItem
import app.yoru.tv.ui.Entry
import app.yoru.tv.ui.FocusMemory
import app.yoru.tv.ui.Focusable
import app.yoru.tv.ui.Ic
import app.yoru.tv.ui.Icon
import app.yoru.tv.ui.Navigator
import app.yoru.tv.ui.Poster
import app.yoru.tv.ui.Route
import app.yoru.tv.ui.T
import app.yoru.tv.ui.Toaster
import app.yoru.tv.ui.TvDialog
import app.yoru.tv.ui.Ty
import app.yoru.tv.ui.Yc
import app.yoru.tv.ui.errorText
import app.yoru.tv.ui.focusWhenReady
import app.yoru.tv.ui.memo
import app.yoru.tv.ui.rememberStartFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Разделы настроек — те же, что в веб-Yoru (#settings/<раздел>), плюс каналы Android TV. */
private data class SetSection(val key: String, val title: String, val hint: String, val icon: ImageVector)

private val SECTIONS by lazy {
    listOf(
        SetSection("account", "Аккаунт", "Shikimori, Anime365, сервер", Ic.user),
        SetSection("home", "Главный экран", "Меню, большой блок, ряды", Ic.home),
        SetSection("anime", "Аниме", "Тайтл, баннеры, обложки", Ic.catalog),
        SetSection("player", "Плеер", "Видео, озвучка, субтитры", Ic.play),
        SetSection("channels", "Android TV", "Каналы на главном экране", Ic.tv),
        SetSection("data", "Данные", "Продолжить, история, кэш", Ic.list),
    )
}

private class SettingsState {
    var sec by mutableStateOf("account")
    val lists = HashMap<String, LazyListState>()
    fun list(k: String) = lists.getOrPut(k) { LazyListState() }
}

private const val KIND_NONE = ""

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(entry: Entry, nav: Navigator) {
    val app = YoruApp.instance
    val mem = entry.focus
    val st = remember(entry) { entry.state { SettingsState() } }
    val history by app.library.history.collectAsState()
    val start = rememberStartFocus(mem)

    var editContinue by remember { mutableStateOf(false) }
    var rankEdit by remember { mutableStateOf<String?>(null) }

    val dialogScope = rememberCoroutineScope()
    if (editContinue) {
        TvDialog(
            onDismiss = { editContinue = false },
            title = "Продолжить просмотр",
            subtitle = "OK — убрать тайтл. Пропадёт на всех ваших устройствах вместе с местом, где вы остановились.",
            width = 620.dp,
        ) {
            val frs = remember { HashMap<Int, FocusRequester>() }
            fun frAt(i: Int) = frs.getOrPut(i) { FocusRequester() }
            LaunchedEffect(Unit) { frAt(0).focusWhenReady() }
            if (history.isEmpty()) {
                T("Список пуст", Ty.body, Yc.Muted)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(history, key = { _, h -> h.malId }) { i, h ->
                        ContinueItem(
                            h = h,
                            modifier = Modifier.focusRequester(frAt(i)),
                            onRemove = {
                                val left = history.size - 1
                                app.library.removeHistory(h.malId)
                                Toaster.show("«${h.title}» убран из «Продолжить просмотр»")
                                syncChannels()
                                if (left <= 0) {
                                    editContinue = false
                                } else {
                                    // курсор — на соседний тайтл, а не в пустоту
                                    dialogScope.launch { frAt(i.coerceAtMost(left - 1)).focusWhenReady() }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    rankEdit?.let { key ->
        val r = app.library.trRank()[key]
        if (r == null) {
            rankEdit = null
        } else {
            TvDialog(onDismiss = { rankEdit = null }, title = r.author, subtitle = rankHint(r), width = 520.dp) {
                val fr = remember { FocusRequester() }
                LaunchedEffect(Unit) { fr.focusWhenReady() }
                DialogItem(
                    if (r.pinned) "Открепить" else "Закрепить первым",
                    false,
                    {
                        app.library.pinTr(key)
                        Toaster.show(if (r.pinned) "Перевод откреплён" else "«${r.author}» будет выбираться первым")
                        rankEdit = null
                    },
                    modifier = Modifier.focusRequester(fr),
                    icon = Ic.star,
                )
                DialogItem(
                    "Забыть",
                    false,
                    {
                        app.library.forgetTr(key)
                        Toaster.show("«${r.author}» убран из любимых")
                        rankEdit = null
                    },
                    hint = "Счётчик досмотренных серий обнулится на всех устройствах",
                    icon = Ic.trash,
                )
            }
        }
    }

    VerticalPage {
        Row(Modifier.fillMaxSize().padding(start = PAD, top = 28.dp)) {
            // ---------- разделы ----------
            Column(Modifier.width(250.dp).fillMaxHeight()) {
                T("Настройки", Ty.page)
                T("Хранятся на этом телевизоре.", Ty.small, Yc.Faint, modifier = Modifier.padding(top = 2.dp, bottom = 18.dp))
                Column(Modifier.focusRestorer().focusGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SECTIONS.forEach { sec ->
                        SectionTab(
                            sec, sec.key == st.sec,
                            onPick = { st.sec = sec.key },
                            modifier = Modifier
                                .then(if (sec.key == st.sec) Modifier.focusRequester(start) else Modifier)
                                .memo(mem, "sec:${sec.key}"),
                        )
                    }
                }
            }
            Spacer(Modifier.width(28.dp))
            // ---------- содержимое раздела ----------
            LazyColumn(
                state = st.list(st.sec),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(end = PAD, top = 4.dp, bottom = 90.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (st.sec) {
                    "account" -> item(key = "account") { AccountSection(nav, mem) }
                    "home" -> item(key = "home") { HomeSection(mem) }
                    "anime" -> item(key = "anime") { AnimeSection(nav, mem) }
                    "player" -> item(key = "player") { PlayerSection(mem, onRank = { rankEdit = it }) }
                    "channels" -> item(key = "channels") { ChannelsSection(mem) }
                    else -> item(key = "data") { DataSection(mem, onContinue = { editContinue = true }) }
                }
            }
        }
    }
}

private fun syncChannels() {
    val app = YoruApp.instance
    app.scope.launch {
        try {
            TvChannels.sync(app)
        } catch (e: Exception) {
            Toaster.show(errorText(e))
        }
    }
}

// ============================================================ разделы

@Composable
private fun AccountSection(nav: Navigator, mem: FocusMemory) {
    val app = YoruApp.instance
    val s by app.prefs.settings.collectAsState()
    val auth by app.prefs.auth.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Group("Shikimori", "Список, оценки, отметки серий и уведомления — ваши.", first = true)
        Line(
            auth?.let { "Вошли как ${it.nickname}" } ?: "Shikimori не подключён",
            if (auth != null) "Уведомления Yoru собирает сам по вашему списку: новые серии, начало показа, окончание тайтла." else "Нужен для «Смотрю», уведомлений и отметок серий.",
            onClick = { nav.push(Route.Connect) },
            modifier = Modifier.memo(mem, "shiki"),
            value = if (auth != null) "Сменить" else "Подключить",
            good = auth != null,
        )

        Group("Anime365", "Видео и переводы берутся с вашего аккаунта Anime365: без токена сайт не отдаёт ссылки на видео, а смотреть могут только аккаунты с подпиской.")
        Line(
            if (s.a365Token.isNotBlank()) "Токен сохранён" else "Токен не задан — видео не загрузится",
            "Сайт: ${app.prefs.site}. Токен удобнее вставить с телефона на странице подключения.",
            onClick = { nav.push(Route.Connect) },
            modifier = Modifier.memo(mem, "a365"),
            value = if (s.a365Token.isNotBlank()) "Изменить" else "Добавить",
            good = s.a365Token.isNotBlank(),
        )

        Group("Сервер Yoru", "Общие с сайтом места в сериях, «Продолжить просмотр», озвучки и любимые переводы.")
        val status by app.sync.status.collectAsState()
        Line(
            "Синхронизация",
            when {
                app.sync.server.isBlank() -> "Не задан. Адрес вводится на странице подключения с телефона."
                auth == null -> "${app.sync.server} — нужен вход в Shikimori."
                else -> "${app.sync.server}. ${status ?: "Нажмите, чтобы синхронизировать сейчас."}"
            },
            onClick = {
                if (app.sync.server.isBlank() || auth == null) {
                    nav.push(Route.Connect)
                } else {
                    app.scope.launch {
                        try {
                            Toaster.show(app.sync.check())
                        } catch (e: Exception) {
                            Toaster.show(errorText(e))
                        }
                    }
                }
            },
            modifier = Modifier.memo(mem, "sync"),
            value = if (app.sync.enabled) "Сверить" else "Настроить",
            good = status?.startsWith("Синхронизировано") == true,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HomeSection(mem: FocusMemory) {
    val app = YoruApp.instance
    val s by app.prefs.settings.collectAsState()
    val auth by app.prefs.auth.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Group("Быстродействие", first = true)
        val lowRam = remember {
            (app.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice
        }
        Toggle(
            "Для слабых ТВ",
            "Без большого блока на главной, фона на странице тайтла, анимаций и размытия. Превью при перемотке выключается, его можно вернуть." +
                if (lowRam) " Рекомендуем для этого устройства." else "",
            s.lite, Modifier.memo(mem, "lite"),
        ) {
            // экран соберётся заново — курсор вернётся на эту же строку
            mem.pending = true
            // превью требует второго видеодекодера: при включении режима выключаем, но решает пользователь
            app.prefs.update { it.copy(lite = !it.lite, scrubPreview = if (it.lite) it.scrubPreview else false) }
            app.applyLite()
        }

        Group("Меню", "Разделы Yoru находятся в боковом меню и открываются кнопкой ←.")
        Toggle(
            "Скрывать боковое меню",
            "Меню прячется за краем экрана и выезжает, когда нажимаете ← у левого края. Места для карточек больше.",
            s.railHidden, Modifier.memo(mem, "rail"),
        ) { app.prefs.update { it.copy(railHidden = !it.railHidden, menuTop = false) } }

        Group("Вид главной", "Обычные ряды или полноэкранные разделы.")
        Radio("Классический", "Большой блок и компактные горизонтальные ряды", s.homeVisual != "modern", Modifier.memo(mem, "homeVisual:classic")) {
            app.prefs.update { it.copy(homeVisual = "classic", menuTop = false) }
        }
        Radio("Modern", "Каждый раздел занимает экран: фон выбранного тайтла и ряд карточек снизу", s.homeVisual == "modern", Modifier.memo(mem, "homeVisual:modern")) {
            app.prefs.update { it.copy(homeVisual = "modern", menuTop = false) }
        }

        Group("Большой блок", if (s.lite) "В режиме «Для слабых ТВ» он скрыт, настройки ниже применятся, когда режим будет выключен." else null)
        Toggle("Показывать большой блок наверху", "Баннер с тайтлом и полоской других тайтлов под ним.", s.heroOn, Modifier.memo(mem, "heroOn")) {
            app.prefs.update { it.copy(heroOn = !it.heroOn) }
            Toaster.show(if (!s.heroOn) "Большой блок включён" else "Большой блок скрыт")
        }
        Hint(if (s.heroOn) "Что в нём показывать — по одному тайтлу из каждой подборки по очереди:" else "Подборки для большого блока — включите его, чтобы они появились на главной:")
        SourcePicker(s.heroSources, auth != null, mem, "hero") { name ->
            val cur = s.heroSources
            val next = if (name in cur) cur - name else cur + name
            if (next.isEmpty()) Toaster.show("Нужна хотя бы одна подборка") else app.prefs.update { it.copy(heroSources = next) }
        }
        Hint("Баннер на широком экране:")
        Radio("Растягивать", "Картинка на весь блок. Маленькие баннеры видны с пикселями", s.heroFit != "blur", Modifier.memo(mem, "fit:stretch")) {
            app.prefs.update { it.copy(heroFit = "stretch") }
        }
        Radio(
            "Размывать края",
            "Картинка заполняет блок по высоте. Если она не достаёт до краёв по ширине, по бокам — её размытая копия",
            s.heroFit == "blur", Modifier.memo(mem, "fit:blur"),
        ) { app.prefs.update { it.copy(heroFit = "blur") } }
        if (s.heroFit == "blur") {
            ChoiceLine("Где картинка", listOf("left" to "У левого края", "center" to "По центру", "right" to "У правого края"), s.heroAlign, mem, "align") { v ->
                app.prefs.update { it.copy(heroAlign = v) }
            }
        }
        Toggle(
            "Логотип вместо названия",
            if (app.art.tmdbEnabled) "Картинка с названием с TMDB, как в плеере. Само название — маленькой строкой под ней. Нет логотипа — обычное название." else "Нужен ключ TMDB — «Настройки → Аниме».",
            s.heroLogo && app.art.tmdbEnabled, Modifier.memo(mem, "heroLogo"),
        ) {
            if (!app.art.tmdbEnabled) Toaster.show("Сначала добавьте ключ TMDB в разделе «Аниме»") else app.prefs.update { it.copy(heroLogo = !it.heroLogo) }
        }

        Group("Ряды", "Как показывать ряды на главной.")
        Radio("Прокрутка вбок", "Ряд листается в сторону", s.rowsMode != "page", Modifier.memo(mem, "rows:scroll")) {
            app.prefs.update { it.copy(rowsMode = "scroll") }
        }
        Radio("По ширине экрана", "Сколько помещается, а в конце ряда плитка «Все» открывает все его тайтлы", s.rowsMode == "page", Modifier.memo(mem, "rows:page")) {
            app.prefs.update { it.copy(rowsMode = "page") }
        }
        Hint("Какие ряды показывать. Выключенный ряд просто не появляется.")
        Column(Modifier.focusRestorer().focusGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val opts = HomeSource.entries.map { it.name to it.title } + ("TODAY" to "Сегодня выходит")
            opts.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (k, title) ->
                        val on = k !in s.homeHidden
                        Chip(title, on, {
                            app.prefs.update { st -> st.copy(homeHidden = if (on) st.homeHidden + k else st.homeHidden - k) }
                        }, modifier = Modifier.memo(mem, "rows:$k"))
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimeSection(nav: Navigator, mem: FocusMemory) {
    val app = YoruApp.instance
    val s by app.prefs.settings.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Group("Шапка страницы тайтла", first = true)
        Hint("Баннер на широком экране:")
        Radio("Растягивать", "Картинка на всю шапку. Маленькие баннеры видны с пикселями", s.titleFit != "blur", Modifier.memo(mem, "tfit:stretch")) {
            app.prefs.update { it.copy(titleFit = "stretch") }
        }
        Radio("Размывать края", "Картинка заполняет шапку по высоте. Если она не достаёт до краёв по ширине, по бокам — её размытая копия", s.titleFit == "blur", Modifier.memo(mem, "tfit:blur")) {
            app.prefs.update { it.copy(titleFit = "blur") }
        }
        if (s.titleFit == "blur") {
            ChoiceLine("Где картинка", listOf("left" to "У левого края", "center" to "По центру", "right" to "У правого края"), s.titleAlign, mem, "talign") { v ->
                app.prefs.update { it.copy(titleAlign = v) }
            }
        }
        Toggle(
            "Логотип вместо названия",
            if (app.art.tmdbEnabled) "Картинка с названием с TMDB, само название — строкой под ней. Нет логотипа — обычное название." else "Нужен ключ TMDB — он задаётся ниже.",
            s.titleLogo && app.art.tmdbEnabled, Modifier.memo(mem, "tlogo"),
        ) {
            if (!app.art.tmdbEnabled) Toaster.show("Сначала добавьте ключ TMDB") else app.prefs.update { it.copy(titleLogo = !it.titleLogo) }
        }

        Group("Размер обложек", "В «Каталоге», «Моём» и «Расписании». Переключатель есть и на самих экранах.")
        ChoiceLine("Обложки", listOf("s" to "Мелкие", "m" to "Средние", "l" to "Крупные"), s.coverSize, mem, "csize") { v ->
            app.prefs.update { it.copy(coverSize = v) }
        }

        Group("Номер в хронологии", "Как считать номер на постере («Сезон 2 из 4»). Ряд «Хронология» показывает всё, но нумерует только части, которые учитываются.", first = true)
        CHRONO_MODES.forEach { (k, t, d) ->
            Radio(t, d, s.chronoMode == k, Modifier.memo(mem, "chrono:$k")) {
                app.prefs.update { it.copy(chronoMode = k) }
                Toaster.show("Номер в хронологии обновлён")
            }
        }
        Hint("Спин-оффы-сериалы Shikimori помечает так же, как обычные сезоны, поэтому отсеять их по типу нельзя.")

        Group(
            "Баннеры и обложки",
            "TMDB — как на AniScroll: чистые широкие кадры и логотипы названий. Нужен бесплатный ключ themoviedb.org. Чего нет в выбранном источнике, берём из AniList/Kitsu.",
        )
        ChoiceLine("Баннеры", listOf("anilist" to "AniList → Kitsu", "tmdb" to "TMDB"), s.artSource, mem, "art") { v ->
            if (v == "tmdb" && !app.art.tmdbEnabled) Toaster.show("Сначала добавьте ключ TMDB — строкой ниже")
            app.prefs.update { it.copy(artSource = v) }
            syncChannels()
        }
        ChoiceLine("Обложки", listOf("shikimori" to "Shikimori", "anilist" to "AniList"), s.coverSource, mem, "cover") { v ->
            app.prefs.update { it.copy(coverSource = v) }
        }
        Line(
            "Ключ TMDB",
            if (app.prefs.tmdbToken.isNotBlank()) "Задан. Логотипы названий — в плеере и (по желанию) в большом блоке." else "Не задан. Удобнее вставить с телефона на странице подключения.",
            onClick = { nav.push(Route.Connect) },
            modifier = Modifier.memo(mem, "tmdb"),
            value = if (app.prefs.tmdbToken.isNotBlank()) "Изменить" else "Добавить",
            good = app.prefs.tmdbToken.isNotBlank(),
        )
    }
}

@Composable
private fun PlayerSection(mem: FocusMemory, onRank: (String) -> Unit) {
    val app = YoruApp.instance
    val s by app.prefs.settings.collectAsState()
    val rankRaw by app.library.rankRaw.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Group("Воспроизведение", first = true)
        Toggle("Запускать видео сразу", "Выключите, чтобы серия со страницы тайтла открывалась на паузе и ждала OK.", s.autoplay, Modifier.memo(mem, "autoplay")) {
            app.prefs.update { it.copy(autoplay = !it.autoplay) }
        }
        Toggle("Следующая серия автоматически", "Через 8 секунд после конца серии.", s.autoNext, Modifier.memo(mem, "auto")) {
            app.prefs.update { it.copy(autoNext = !it.autoNext) }
        }
        Toggle(
            "Превью при перемотке",
            "Кадр над шкалой, куда попадёте: один на каждые 15 секунд. Подгружается, только пока вы перематываете. Переключается и в настройках плеера.",
            s.scrubPreview, Modifier.memo(mem, "scrub"),
        ) {
            app.prefs.update { it.copy(scrubPreview = !it.scrubPreview) }
        }
        Toggle("Отмечать серии на Shikimori", "Когда досмотрено почти до конца, в списке растёт счётчик серий.", s.autoMark, Modifier.memo(mem, "mark")) {
            app.prefs.update { it.copy(autoMark = !it.autoMark) }
        }
        Toggle("Выбирать самый быстрый сервер", "Перед запуском замеряем скорость каждого адреса CDN из ответа Anime365.", s.fastestCdn, Modifier.memo(mem, "cdn")) {
            app.prefs.update { it.copy(fastestCdn = !it.fastestCdn) }
        }
        // 2K и 4K в списке нет: телевизор такие файлы Anime365 не воспроизводит
        val quality = s.quality.takeIf { it in listOf(1080, 720, 480, 360) } ?: 0
        ChoiceLine("Качество по умолчанию", listOf(0 to "Авто", 1080 to "1080p", 720 to "720p", 480 to "480p", 360 to "360p"), quality, mem, "q") { v ->
            app.prefs.update { it.copy(quality = v) }
        }
        ChoiceLine("Размер субтитров", listOf(0.8f to "Мелкий", 1f to "Обычный", 1.25f to "Крупный", 1.5f to "Огромный"), s.subSize, mem, "ss") { v ->
            app.prefs.update { it.copy(subSize = v) }
        }

        Group(
            "Озвучка и субтитры",
            "Yoru запоминает, с какими переводами вы досматриваете серии, и сам выбирает самый любимый из доступных. Перевод, выбранный вручную для тайтла, важнее. Всё это общее с сайтом Yoru.",
        )
        ChoiceLine("Если любимых у серии нет", listOf("auto" to "Без разницы", "voice" to "Озвучка", "sub" to "Субтитры"), s.trKind, mem, "trKind") { v ->
            app.prefs.update { it.copy(trKind = v, trKindAt = System.currentTimeMillis()) }
            app.sync.notePrefs()
        }
        val rank = rankRaw.filterValues { !it.deleted }.entries
            .sortedWith(compareByDescending<Map.Entry<String, TrRank>> { it.value.pinned }.thenByDescending { it.value.count })
        if (rank.isEmpty()) {
            Hint("Ваши переводы: пока пусто. Досмотрите пару серий — и здесь появятся любимые озвучки.")
        } else {
            Hint("Ваши переводы — OK, чтобы закрепить первым или забыть:")
            rank.forEachIndexed { i, (k, r) ->
                Line(
                    "${i + 1}. ${r.author}",
                    rankHint(r),
                    onClick = { onRank(k) },
                    modifier = Modifier.memo(mem, "rank:$k"),
                    value = if (r.pinned) "Закреплён" else null,
                    good = r.pinned,
                )
            }
        }
    }
}

@Composable
private fun ChannelsSection(mem: FocusMemory) {
    val app = YoruApp.instance
    val ctx = LocalContext.current
    val s by app.prefs.settings.collectAsState()
    val auth by app.prefs.auth.collectAsState()
    var channels by remember { mutableStateOf<List<ChannelInfo>>(emptyList()) }
    var supported by remember { mutableStateOf(true) }
    var tick by remember { mutableIntStateOf(0) }
    val browsable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }

    LaunchedEffect(tick, s.channelSources) {
        val (sup, ch) = withContext(Dispatchers.IO) { TvChannels.supported(ctx) to TvChannels.channels(ctx) }
        supported = sup
        channels = ch
    }
    fun resync() {
        app.scope.launch {
            try {
                TvChannels.sync(app)
                Toaster.show("Каналы обновлены")
            } catch (e: Exception) {
                Toaster.show(errorText(e))
            }
            delay(300)
            tick++
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Group(
            "Каналы на главном экране",
            "Каждая подборка — отдельный ряд баннеров на главном экране телевизора. Обновляются раз в 3 часа.",
            first = true,
        )
        if (!supported) {
            T(
                "Этот лаунчер не принимает каналы приложений (так бывает на Google TV и некоторых приставках). Системный ряд «Продолжить просмотр» может работать.",
                Ty.body, Yc.Rose,
            )
        }
        SourcePicker(s.channelSources, auth != null, mem, "ch") { name ->
            val cur = s.channelSources
            app.prefs.update { it.copy(channelSources = if (name in cur) cur - name else cur + name) }
            resync()
        }
        s.channelSources.mapNotNull { HomeSource.of(it) }.forEach { src ->
            val ch = channels.firstOrNull { it.source == src.name } ?: return@forEach
            Line(
                "Канал «${src.title}»",
                if (ch.browsable) "Показывается на главном экране." else "Создан, но скрыт. Нажмите, чтобы добавить на главный экран.",
                onClick = {
                    if (!ch.browsable) {
                        try {
                            browsable.launch(TvChannels.browsableIntent(ch.id))
                        } catch (_: Exception) {
                            Toaster.show("Лаунчер не умеет добавлять каналы — включите его в настройках главного экрана")
                        }
                    } else {
                        Toaster.show("Скрыть канал можно в настройках главного экрана телевизора")
                    }
                },
                modifier = Modifier.memo(mem, "chs:${src.name}"),
                value = if (ch.browsable) "Виден" else "Показать",
                good = ch.browsable,
            )
        }
        Toggle("Ряд «Продолжить просмотр»", "Начатые серии в системном ряду Android TV / Google TV.", s.watchNext, Modifier.memo(mem, "wn")) {
            app.prefs.update { st -> st.copy(watchNext = !st.watchNext) }
            resync()
        }
        Line("Обновить каналы сейчас", "Перерисовать ряды с текущими данными.", onClick = { resync() }, modifier = Modifier.memo(mem, "chNow"), value = "Обновить")
    }
}

@Composable
private fun DataSection(mem: FocusMemory, onContinue: () -> Unit) {
    val app = YoruApp.instance
    val history by app.library.history.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    var cacheCleared by remember { mutableStateOf(false) }
    val n = history.size
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Group(
            "Продолжить просмотр",
            "Тайтлы, которые вы начали смотреть. Открытые случайно можно убрать — они пропадут из «Продолжить просмотр» на всех ваших устройствах. Список Shikimori не меняется.",
            first = true,
        )
        Line(
            "Выбрать, что убрать",
            if (n == 0) "Пусто" else "В списке $n ${plural(n, "тайтл", "тайтла", "тайтлов")}",
            onClick = { if (n > 0) onContinue() else Toaster.show("Список пуст") },
            modifier = Modifier.memo(mem, "cont"),
            value = if (n == 0) null else "$n",
        )
        Line(
            "Вся история и позиции просмотра",
            if (n == 0) "История пуста" else "Очищается на всех устройствах, подключённых к серверу Yoru.",
            onClick = {
                if (n == 0) {
                    Toaster.show("История пуста")
                } else if (!confirmClear) {
                    confirmClear = true
                } else {
                    // каждое удаление уходит на сервер — тайтлы пропадут и с сайта
                    history.forEach { app.library.removeHistory(it.malId) }
                    app.library.clearHistory()
                    confirmClear = false
                    Toaster.show("История очищена")
                    syncChannels()
                }
            },
            modifier = Modifier.memo(mem, "hist"),
            value = if (confirmClear) "Точно очистить?" else "Очистить всё",
        )

        Group("Этот телевизор")
        Line(
            "Кэш сопоставлений и картинок",
            "Если тайтл не находится на Anime365, хотя он там есть, или баннер устарел.",
            onClick = {
                app.repo.clearCaches()
                cacheCleared = true
                Toaster.show("Кэш очищен")
            },
            modifier = Modifier.memo(mem, "cache"),
            value = if (cacheCleared) "Очищено" else "Очистить",
            good = cacheCleared,
        )
        Hint("Вход Shikimori, токен Anime365 и настройки хранятся на этом телевизоре. Места в сериях, озвучка и любимые переводы — ещё и на сервере Yoru, если он подключён.")
        Box(Modifier.padding(top = 18.dp)) {
            T(
                "Yoru TV ${BuildConfig.VERSION_NAME} · неофициальный клиент. Данные: Shikimori, видео: Anime365, баннеры: AniList и Kitsu. " +
                    "This product uses the TMDB API but is not endorsed or certified by TMDB.",
                Ty.small, Yc.Faint,
            )
        }
    }
}

private val KIND_RU = mapOf("voice" to "озвучка", "sub" to "субтитры", "raw" to "оригинал")

private fun rankHint(r: TrRank): String {
    val c = maxOf(1, r.count.toInt())
    return "${KIND_RU[r.kind] ?: KIND_NONE}, $c ${plural(c, "серия", "серии", "серий")}"
}

// ============================================================ элементы

/** Пункт списка разделов: значок, название и подпись. Раздел открывается, как только на нём фокус. */
@Composable
private fun SectionTab(sec: SetSection, active: Boolean, onPick: () -> Unit, modifier: Modifier = Modifier) {
    Focusable(
        onClick = onPick,
        onFocus = onPick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        focusedScale = 1.02f,
        border = false,
    ) { f ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    when {
                        f -> Yc.Lantern
                        active -> Yc.Night3
                        else -> Color.Transparent
                    },
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(sec.icon, if (f) Yc.LanternInk else if (active) Yc.Lantern else Yc.Muted, 20.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                T(sec.title, Ty.label, if (f) Yc.LanternInk else Yc.Text, maxLines = 1)
                T(sec.hint, Ty.small, if (f) Yc.LanternInk.copy(alpha = 0.75f) else Yc.Faint, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Group(title: String, hint: String? = null, first: Boolean = false) {
    Column(Modifier.padding(top = if (first) 6.dp else 22.dp, bottom = 4.dp)) {
        T(title, Ty.section)
        if (hint != null) T(hint, Ty.body, Yc.Muted, modifier = Modifier.widthIn(max = 760.dp))
    }
}

@Composable
private fun Hint(text: String) {
    T(text, Ty.small, Yc.Faint, modifier = Modifier.widthIn(max = 760.dp).padding(top = 2.dp, bottom = 2.dp))
}

@Composable
private fun Line(
    title: String,
    hint: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    good: Boolean = false,
) {
    Focusable(onClick = onClick, modifier = modifier.widthIn(max = 820.dp).fillMaxWidth(), shape = RoundedCornerShape(14.dp), focusedScale = 1.01f, border = false) { f ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (f) Yc.Text else Yc.Night2, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                T(title, Ty.label, if (f) Yc.Night else Yc.Text, maxLines = 1)
                if (hint != null) T(hint, Ty.small, if (f) Yc.Night.copy(alpha = 0.7f) else Yc.Faint, maxLines = 2)
            }
            if (value != null) {
                Spacer(Modifier.width(16.dp))
                T(value, Ty.label, if (f) Yc.Night else if (good) Yc.Ok else Yc.Lantern, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Toggle(title: String, hint: String?, on: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    Focusable(onClick = onToggle, modifier = modifier.widthIn(max = 820.dp).fillMaxWidth(), shape = RoundedCornerShape(14.dp), focusedScale = 1.01f, border = false) { f ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (f) Yc.Text else Yc.Night2, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                T(title, Ty.label, if (f) Yc.Night else Yc.Text, maxLines = 1)
                if (hint != null) T(hint, Ty.small, if (f) Yc.Night.copy(alpha = 0.7f) else Yc.Faint, maxLines = 2)
            }
            Spacer(Modifier.width(16.dp))
            // переключатель как в вебе
            Box(
                Modifier
                    .size(width = 44.dp, height = 26.dp)
                    .background(if (on) Yc.Lantern else Yc.Line, RoundedCornerShape(50)),
                contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(
                    Modifier
                        .padding(3.dp)
                        .size(20.dp)
                        .background(if (on) Yc.LanternInk else Yc.Text, RoundedCornerShape(50)),
                )
            }
        }
    }
}

/** Вариант из нескольких, с пояснением — как «choice» в вебе. */
@Composable
private fun Radio(title: String, hint: String?, on: Boolean, modifier: Modifier = Modifier, onPick: () -> Unit) {
    Focusable(onClick = onPick, modifier = modifier.widthIn(max = 820.dp).fillMaxWidth(), shape = RoundedCornerShape(14.dp), focusedScale = 1.01f, border = false) { f ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (f) Yc.Text else Yc.Night2, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .background(if (on) Yc.Lantern else if (f) Yc.Night.copy(alpha = 0.25f) else Yc.Line, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) {
                if (on) Box(Modifier.size(8.dp).background(Yc.LanternInk, RoundedCornerShape(50)))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                T(title, Ty.label, if (f) Yc.Night else Yc.Text, maxLines = 1)
                if (hint != null) T(hint, Ty.small, if (f) Yc.Night.copy(alpha = 0.7f) else Yc.Faint, maxLines = 2)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun <V> ChoiceLine(title: String, options: List<Pair<V, String>>, value: V, mem: FocusMemory, key: String, onPick: (V) -> Unit) {
    Row(
        Modifier.widthIn(max = 820.dp).fillMaxWidth().background(Yc.Night2, RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(title, Ty.label, modifier = Modifier.weight(1f), maxLines = 1)
        Row(Modifier.focusRestorer().focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, l) -> Chip(l, v == value, { onPick(v) }, modifier = Modifier.memo(mem, "$key:$l")) }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SourcePicker(selected: List<String>, loggedIn: Boolean, mem: FocusMemory, key: String, onToggle: (String) -> Unit) {
    Column(Modifier.focusRestorer().focusGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HomeSource.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { src ->
                    val on = src.name in selected
                    Chip(
                        src.title + if (src.needsLogin && !loggedIn) " (нужен Shikimori)" else "",
                        on, { onToggle(src.name) },
                        modifier = Modifier.memo(mem, "$key:${src.name}"),
                    )
                }
            }
        }
    }
}

/** Строка списка «Продолжить просмотр»: постер, название и серия, корзина справа. */
@Composable
private fun ContinueItem(h: app.yoru.tv.data.HistoryItem, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val pct = YoruApp.instance.library.pct(h.episodeId)
    Focusable(onClick = onRemove, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), focusedScale = 1.02f, border = false) { f ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (f) Yc.Text else Color.Transparent, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(width = 34.dp, height = 50.dp).clip(RoundedCornerShape(6.dp))) {
                Poster(h.poster, h.malId, h.title, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f)) {
                T(h.title.ifBlank { "Без названия" }, Ty.label, if (f) Yc.Night else Yc.Text, maxLines = 1)
                val sub = listOfNotNull(
                    h.epLabel,
                    pct.takeIf { it > 0 }?.let { "просмотрено $it%" },
                    app.yoru.tv.data.relTime(h.at).takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                T(sub, Ty.small, if (f) Yc.Night.copy(alpha = 0.7f) else Yc.Faint, maxLines = 1)
            }
            Icon(Ic.trash, if (f) Yc.Night else Yc.Muted, 18.dp)
        }
    }
}
