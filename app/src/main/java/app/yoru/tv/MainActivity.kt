package app.yoru.tv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import app.yoru.tv.channels.SyncWorker
import app.yoru.tv.player.PlayerScreen
import app.yoru.tv.ui.Navigator
import app.yoru.tv.ui.Route
import app.yoru.tv.ui.ToastHost
import app.yoru.tv.ui.WithRail
import app.yoru.tv.ui.Yc
import app.yoru.tv.ui.screens.CatalogScreen
import app.yoru.tv.ui.screens.ConnectScreen
import app.yoru.tv.ui.screens.HomeScreen
import app.yoru.tv.ui.screens.LibraryScreen
import app.yoru.tv.ui.screens.NotificationsScreen
import app.yoru.tv.ui.screens.ScheduleScreen
import app.yoru.tv.ui.screens.SettingsScreen
import app.yoru.tv.ui.screens.TitleScreen

class MainActivity : ComponentActivity() {
    private val nav = Navigator(Route.Home)
    private var lastRepeat = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as YoruApp
        if (savedInstanceState == null) {
            val p = app.prefs
            val opened = handle(intent)
            if (!opened && !p.s.onboardingDone && p.auth.value == null && p.token.isBlank()) nav.push(Route.Connect)
            SyncWorker.runNow(this, delaySec = 45)
        }
        setContent { Root(nav) }
    }

    /**
     * Страховка от «пропавшего курсора»: если элемент в фокусе исчез (ряд пересоздался,
     * список прокрутился), Compose остаётся без фокуса и пульт перестаёт реагировать совсем.
     * Тогда первое нажатие стрелки/OK не теряем, а возвращаем фокус на экран.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // все экраны — Compose (плеер тоже держит фокус на Compose-элементе), так что
        // «нет фокуса в Compose» = курсора нет нигде
        // Пока кнопку держат, пульт шлёт повторы быстрее, чем слабый телевизор успевает рисовать; очередь нажатий
        // копится, и курсор «едет» ещё долго после отпускания. Лишние повторы отбрасываем (в плеере они нужны для перемотки).
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && event.keyCode in NAV_KEYS && nav.top.route !is Route.Watch) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastRepeat < (if (app.yoru.tv.ui.liteMode) 140L else 70L)) return true
            lastRepeat = now
        }
        if (event.keyCode in NAV_KEYS && !FocusGuard.composeHasFocus) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) recoverFocus()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun recoverFocus() {
        val top = nav.top
        if (top.recover?.invoke() == true) return
        val start = top.focus.start
        if (start != null && runCatching { start.requestFocus() }.isSuccess) return
        FocusGuard.fallback?.invoke()
    }

    override fun onDestroy() {
        // Navigator принадлежит конкретному экземпляру Activity. Даже при системном
        // пересоздании старые Entry больше не используются, поэтому их player/scope
        // должны быть освобождены, а не ждать окончательного закрытия приложения.
        nav.stack.toList().forEach { it.dispose() }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** yoru://title/123, yoru://watch/123?ep=..&n=..&tr=.., yoru://home — ссылки из каналов главного экрана. */
    private fun handle(intent: Intent?): Boolean {
        val u: Uri = intent?.data ?: return false
        if (u.scheme != "yoru") return false
        val id = u.pathSegments.firstOrNull()?.toLongOrNull()
        return when (u.host) {
            "title" -> {
                if (id == null) return false
                nav.switchTo(Route.Home)
                nav.push(Route.Title(id))
                true
            }
            "watch" -> {
                if (id == null) return false
                nav.switchTo(Route.Home)
                nav.push(
                    Route.Watch(
                        malId = id,
                        episodeId = u.getQueryParameter("ep")?.toLongOrNull(),
                        episodeNum = u.getQueryParameter("n")?.toDoubleOrNull(),
                        translationId = u.getQueryParameter("tr")?.toLongOrNull(),
                    ),
                )
                true
            }
            "home" -> {
                nav.switchTo(Route.Home)
                true
            }
            else -> false
        }
    }
}

/** Есть ли сейчас фокус хоть у чего-то в Compose (см. MainActivity.dispatchKeyEvent). */
object FocusGuard {
    @Volatile var composeHasFocus: Boolean = true
    var fallback: (() -> Unit)? = null
}

private val NAV_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
)

@Composable
private fun Root(nav: Navigator) {
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val fm = LocalFocusManager.current
    SideEffect {
        FocusGuard.fallback = {
            if (!fm.moveFocus(FocusDirection.Down)) fm.moveFocus(FocusDirection.Right)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Yc.Night)
            .onFocusChanged { FocusGuard.composeHasFocus = it.hasFocus }
            // Compose на ТВ превращает «Назад» в «выйти из группы фокуса» и съедает нажатие,
            // поэтому экраны и панели его не получали. Перехватываем раньше и отдаём BackHandler-ам.
            .onPreviewKeyEvent { ev ->
                if ((ev.key == Key.Back || ev.key == Key.Escape) && back != null) {
                    if (ev.type == KeyEventType.KeyUp) back.onBackPressed()
                    true
                } else {
                    false
                }
            },
    ) {
        BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
        val top = nav.stack.last()
        // «Для слабых ТВ» меняет сами элементы, поэтому экран при переключении собирается заново; его данные живут в стеке
        val lite = app.yoru.tv.YoruApp.instance.prefs.settings.collectAsState().value.lite
        key(top.id, lite) {
            when (val r = top.route) {
                Route.Home -> WithRail(nav, r) { HomeScreen(top, nav) }
                Route.Catalog -> WithRail(nav, r) { app.yoru.tv.ui.screens.GridPage { CatalogScreen(top, nav) } }
                Route.Library -> WithRail(nav, r) { app.yoru.tv.ui.screens.GridPage { LibraryScreen(top, nav) } }
                Route.Schedule -> WithRail(nav, r) { ScheduleScreen(top, nav) }
                Route.Notifications -> WithRail(nav, r) { NotificationsScreen(top, nav) }
                Route.Settings -> WithRail(nav, r) { SettingsScreen(top, nav) }
                Route.Connect -> ConnectScreen(top, nav)
                is Route.RowPage -> app.yoru.tv.ui.screens.GridPage { app.yoru.tv.ui.screens.RowPageScreen(top, nav, r) }
                is Route.Title -> TitleScreen(top, nav, r.malId)
                is Route.Watch -> PlayerScreen(top, nav, r)
            }
        }
        ToastHost()
    }
}
