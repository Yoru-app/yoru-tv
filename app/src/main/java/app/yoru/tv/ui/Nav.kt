package app.yoru.tv.ui

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

sealed interface Route {
    data object Home : Route
    data object Catalog : Route
    data object Library : Route
    data object Schedule : Route
    data object Notifications : Route
    data object Settings : Route
    data object Connect : Route
    /** Все тайтлы одного ряда главной (key — имя HomeSource или «TODAY»). */
    data class RowPage(val key: String, val title: String) : Route
    data class Title(val malId: Long) : Route
    data class Watch(
        val malId: Long,
        val episodeId: Long? = null,
        val episodeNum: Double? = null,
        val translationId: Long? = null,
    ) : Route
}

/** Запись стека: живёт, пока экран в стеке, и хранит его состояние и память фокуса. */
class Entry(val route: Route) {
    val id: Long = next++
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val focus = FocusMemory()
    val bag = HashMap<String, Any>()
    val onDispose = ArrayList<() -> Unit>()

    /** Вернуть курсор, если он потерялся. true — экран справился сам. */
    var recover: (() -> Boolean)? = null

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> state(make: () -> T): T = bag.getOrPut("state") { make() } as T

    fun dispose() {
        onDispose.forEach { runCatching { it() } }
        onDispose.clear()
        scope.cancel()
    }

    companion object {
        private var next = 1L
    }
}

class Navigator(start: Route) {
    val stack = mutableStateListOf(Entry(start))
    val top: Entry get() = stack.last()

    fun push(r: Route) {
        stack.add(Entry(r))
    }

    /** Заменить верхний экран (например, следующая серия в плеере). */
    fun replace(r: Route) {
        val old = stack.removeAt(stack.lastIndex)
        old.dispose()
        stack.add(Entry(r))
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        val old = stack.removeAt(stack.lastIndex)
        old.dispose()
        top.focus.pending = top.focus.key != null
        return true
    }

    /** Разделы бокового меню: Главная всегда внизу стека. */
    fun switchTo(r: Route) {
        if (stack.size == 1 && top.route == r) return
        if (stack.size == 2 && top.route == r) return
        while (stack.size > 1) stack.removeAt(stack.lastIndex).dispose()
        if (r == Route.Home) {
            top.focus.pending = false
        } else {
            stack.add(Entry(r))
        }
    }

    /** Из бокового меню обратно в контент экрана — на тот элемент, где был курсор. */
    fun restoreContentFocus() {
        val e = top
        if (e.focus.restore()) return
        if (e.recover?.invoke() == true) return
        e.focus.start?.let { runCatching { it.requestFocus() } }
    }

    val isTopLevel: Boolean
        get() = top.route in listOf(Route.Home, Route.Catalog, Route.Library, Route.Schedule, Route.Notifications, Route.Settings)
}
