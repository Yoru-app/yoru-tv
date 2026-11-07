package app.yoru.tv.ui.screens

import app.yoru.tv.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.yoru.tv.YoruApp
import app.yoru.tv.channels.SyncWorker
import app.yoru.tv.data.ApiError
import app.yoru.tv.pairing.PairResult
import app.yoru.tv.pairing.PairingServer
import app.yoru.tv.ui.Entry
import app.yoru.tv.ui.Ic
import app.yoru.tv.ui.Logo
import app.yoru.tv.ui.Navigator
import app.yoru.tv.ui.QrImage
import app.yoru.tv.ui.Route
import app.yoru.tv.ui.T
import app.yoru.tv.ui.Toaster
import app.yoru.tv.ui.TvButton
import app.yoru.tv.ui.TvTextInput
import app.yoru.tv.ui.Ty
import app.yoru.tv.ui.Yc
import app.yoru.tv.ui.errorText
import app.yoru.tv.ui.memo
import app.yoru.tv.ui.rememberStartFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Применить то, что пришло с телефона (или введено пультом).
 * Сообщения с «✓» и «✗» страница на телефоне красит в зелёный и красный.
 */
suspend fun applyConnect(form: Map<String, String>): PairResult = withContext(Dispatchers.Main) {
    val app = YoruApp.instance
    val msgs = ArrayList<String>()
    var ok = true
    fun v(k: String) = form[k]?.trim()?.takeIf { it.isNotEmpty() }

    val cid = v("clientId")
    val secret = v("clientSecret")
    val appName = v("appName")
    val site = v("site")
    if (cid != null || secret != null || appName != null || site != null) {
        app.prefs.update { s ->
            s.copy(
                shikiClientId = cid ?: s.shikiClientId,
                shikiClientSecret = secret ?: s.shikiClientSecret,
                shikiAppName = appName ?: s.shikiAppName,
                a365Site = site?.trimEnd('/') ?: s.a365Site,
            )
        }
        msgs += "Настройки приложения сохранены."
    }

    v("syncUrl")?.let { url ->
        val clean = url.trimEnd('/').let { if (it.startsWith("http")) it else "https://$it" }
        app.prefs.update { it.copy(syncServer = clean) }
        app.sync.resetSince()
    }

    v("tmdb")?.let { key ->
        app.prefs.update { it.copy(tmdbToken = key) }
        app.art.clearTmdbCache()
        try {
            msgs += "✓ TMDB: " + app.art.checkTmdb()
        } catch (e: Exception) {
            ok = false
            msgs += "✗ TMDB: " + errorText(e) + " Если api.themoviedb.org у вас заблокирован, оставьте баннеры AniList."
        }
    }

    v("token")?.let { token ->
        app.prefs.update { it.copy(a365Token = token) }
        try {
            msgs += "✓ Anime365: " + app.a365.checkToken(token)
        } catch (e: Exception) {
            ok = false
            msgs += "✗ Anime365: " + errorText(e) + " Токен всё равно сохранён."
        }
    }

    v("code")?.let { code ->
        try {
            val auth = app.shiki.exchangeCode(code)
            msgs += "✓ Shikimori: вошли как ${auth.nickname}."
            try {
                app.repo.refreshList(force = true)
            } catch (_: Exception) {
            }
            SyncWorker.runNow(app)
        } catch (e: Exception) {
            ok = false
            val hint = if (e is ApiError && e.code in 400..401) " Код одноразовый и живёт недолго: получите новый." else ""
            msgs += "✗ Shikimori: " + errorText(e) + hint
        }
    }
    // сервер Yoru проверяем, когда уже есть вход в Shikimori (он пускает по нему)
    if ((v("syncUrl") != null || v("code") != null) && app.prefs.auth.value != null && app.sync.server.isNotBlank()) {
        try {
            msgs += "✓ " + app.sync.check()
        } catch (e: Exception) {
            msgs += "✗ Сервер Yoru: " + errorText(e)
        }
    }

    if (msgs.isEmpty()) {
        ok = false
        msgs += "✗ Нечего отправлять: заполните код Shikimori, токен Anime365 или ключ TMDB."
    } else if (ok) {
        msgs += "Готово. Можно вернуться к телевизору."
    }
    msgs.forEach { Toaster.show(it.removePrefix("✓ ").removePrefix("✗ ")) }
    PairResult(ok, msgs)
}

@Composable
fun ConnectScreen(entry: Entry, nav: Navigator) {
    val app = YoruApp.instance
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val mem = entry.focus
    val settings by app.prefs.settings.collectAsState()
    val auth by app.prefs.auth.collectAsState()
    var code by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var tmdb by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var lastMessages by remember { mutableStateOf<List<String>>(emptyList()) }

    val server = remember {
        PairingServer(
            ctx.applicationContext,
            page = {
                mapOf(
                    "CLIENT_ID" to app.prefs.clientId,
                    "SECRET_HINT" to if (app.prefs.clientSecret.isNotBlank()) "задан — оставьте пустым" else "client_secret",
                    "APP_NAME" to app.prefs.appName,
                    "SITE" to app.prefs.site,
                    "SHIKI_USER" to (app.prefs.auth.value?.nickname ?: ""),
                    "TOKEN_SET" to if (app.prefs.token.isNotBlank()) "1" else "",
                    "TMDB_SET" to if (app.prefs.tmdbToken.isNotBlank()) "1" else "",
                    "SYNC_URL" to app.prefs.s.syncServer,
                )
            },
            onSubmit = { form ->
                val r = applyConnect(form)
                withContext(Dispatchers.Main) { lastMessages = r.messages }
                r
            },
        )
    }
    var urls by remember { mutableStateOf<List<String>>(emptyList()) }
    DisposableEffect(Unit) {
        if (server.start()) urls = server.urls()
        onDispose { server.stop() }
    }
    val pairQr = remember(urls) { urls.firstOrNull()?.let { PairingServer.qr(it, 480).asImageBitmap() } }
    val authUrl = remember(settings.shikiClientId) { if (app.prefs.clientId.isNotBlank()) app.shiki.authorizeUrl() else null }
    val authQr = remember(authUrl) { authUrl?.let { PairingServer.qr(it, 480).asImageBitmap() } }
    val start = rememberStartFocus(mem)
    val hasApp = app.prefs.clientId.isNotBlank() && app.prefs.clientSecret.isNotBlank()

    fun submit(form: Map<String, String>) {
        if (busy) return
        busy = true
        app.scope.launch {
            try {
                lastMessages = applyConnect(form).messages
                code = ""
                token = ""
                tmdb = ""
            } finally {
                busy = false
            }
        }
    }

    fun leave() {
        app.prefs.update { it.copy(onboardingDone = true) }
        if (!nav.pop()) nav.switchTo(Route.Home)
    }

    Row(
        Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        // ---------- с телефона ----------
        Column(Modifier.width(330.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(36.dp)
                Spacer(Modifier.width(14.dp))
                T("Подключение", Ty.page)
            }
            Spacer(Modifier.height(18.dp))
            if (pairQr != null) {
                QrImage(pairQr, Modifier.size(210.dp))
                Spacer(Modifier.height(14.dp))
                T("Наведите камеру телефона", Ty.h3)
                T(
                    "Откроется страница, где можно вставить код Shikimori и токен Anime365 — без ввода пультом. Телефон должен быть в той же Wi‑Fi сети.",
                    Ty.body, Yc.Muted,
                )
                Spacer(Modifier.height(8.dp))
                urls.take(2).forEach { T(it.substringBefore("/?"), Ty.label, Yc.Lantern, maxLines = 1) }
            } else {
                T("Не удалось открыть страницу подключения в локальной сети. Введите данные справа.", Ty.body, Yc.Muted)
            }
            Spacer(Modifier.weight(1f))
            TvButton(
                if (auth != null && settings.a365Token.isNotBlank()) "Готово" else "Пропустить",
                { leave() },
                (if (!hasApp) Modifier.focusRequester(start) else Modifier).memo(mem, "done"),
                primary = auth != null && settings.a365Token.isNotBlank(),
                icon = Ic.check,
            )
        }

        // ---------- пультом ----------
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Block("Shikimori", auth?.let { "Вошли как ${it.nickname}" } ?: "Не подключён", auth != null) {
                if (!hasApp) {
                    T(
                        "Сначала нужен client_id и client_secret приложения Shikimori. Проще всего ввести их на странице с телефона (раздел «Приложение Shikimori») или прописать в local.properties при сборке.",
                        Ty.body, Yc.Rose,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (authQr != null) QrImage(authQr, Modifier.size(120.dp))
                        T("Или отсканируйте этот код: откроется вход Shikimori. Скопировать код на телефоне и ввести его здесь пультом тоже можно.", Ty.body, Yc.Muted, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TvTextInput(
                            code, { code = it }, "Код авторизации",
                            modifier = Modifier.weight(1f).memo(mem, "code"),
                            imeAction = ImeAction.Done,
                            focusRequester = start,
                        )
                        TvButton("Войти", { submit(mapOf("code" to code)) }, Modifier.memo(mem, "codeGo"), enabled = code.isNotBlank() && !busy)
                    }
                }
                if (auth != null) {
                    Spacer(Modifier.height(10.dp))
                    TvButton("Выйти из Shikimori", {
                        app.prefs.setAuth(null)
                        app.repo.clearList()
                        // если приложение Shikimori прописано в local.properties — забываем введённое с телефона,
                        // чтобы следующий вход шёл через него
                        if (BuildConfig.SHIKI_CLIENT_ID.isNotBlank()) {
                            app.prefs.update { it.copy(shikiClientId = "", shikiClientSecret = "", shikiAppName = "") }
                        }
                        Toaster.show("Вы вышли из Shikimori")
                    }, Modifier.memo(mem, "logout"), small = true)
                }
            }
            Spacer(Modifier.height(16.dp))
            Block("Anime365", if (settings.a365Token.isNotBlank()) "Токен задан" else "Токена нет — видео не загрузится", settings.a365Token.isNotBlank()) {
                T("Нужен токен API и аккаунт с подпиской. Это тот же токен, что в настройках веб-Yoru.", Ty.body, Yc.Muted)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvTextInput(
                        token, { token = it }, "access_token",
                        modifier = Modifier.weight(1f).memo(mem, "token"),
                        secret = true,
                    )
                    TvButton("Сохранить", { submit(mapOf("token" to token)) }, Modifier.memo(mem, "tokenGo"), enabled = token.isNotBlank() && !busy)
                }
                if (settings.a365Token.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    TvButton("Проверить текущий токен", {
                        busy = true
                        app.scope.launch {
                            try {
                                val m = app.a365.checkToken(app.prefs.token)
                                lastMessages = listOf("✓ $m")
                            } catch (e: Exception) {
                                lastMessages = listOf("✗ " + errorText(e))
                            } finally {
                                busy = false
                            }
                        }
                    }, Modifier.memo(mem, "check"), small = true, icon = Ic.refresh)
                }
            }
            Spacer(Modifier.height(16.dp))
            Block("TMDB (необязательно)", if (settings.tmdbToken.isNotBlank()) "Ключ задан" else "Ключа нет", settings.tmdbToken.isNotBlank()) {
                T("Для баннеров и логотипов названий как на AniScroll. Ключ: themoviedb.org → Настройки → API.", Ty.body, Yc.Muted)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvTextInput(
                        tmdb, { tmdb = it }, "API Read Access Token или API Key",
                        modifier = Modifier.weight(1f).memo(mem, "tmdb"),
                        secret = true,
                    )
                    TvButton("Сохранить", { submit(mapOf("tmdb" to tmdb)) }, Modifier.memo(mem, "tmdbGo"), enabled = tmdb.isNotBlank() && !busy)
                }
            }
            if (lastMessages.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                lastMessages.forEach {
                    T(it, Ty.body, if (it.startsWith("✗")) Yc.Rose else if (it.startsWith("✓")) Yc.Ok else Yc.TextSoft)
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Block(title: String, state: String, good: Boolean, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Yc.Night2, RoundedCornerShape(18.dp))
            .border(1.dp, Yc.Line, RoundedCornerShape(18.dp))
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(title, Ty.h3)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(8.dp).background(if (good) Yc.Ok else Yc.Faint, RoundedCornerShape(50)))
            Spacer(Modifier.width(8.dp))
            T(state, Ty.label, if (good) Yc.Ok else Yc.Muted, maxLines = 1)
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}
