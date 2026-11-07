package app.yoru.tv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Общий прогресс серий с веб-Yoru через сервер Yoru (`/api/sync`, модуль sync-addon/server/yoru-sync.cjs).
 * Ключ — id эпизода Anime365, одинаковый у веба и ТВ. Телевизор входит токеном Shikimori.
 * При расхождении побеждает запись с более поздним временем (`at`).
 */
class SyncClient(
    private val http: Http,
    private val prefs: Prefs,
    private val shiki: Shiki,
    private val library: Library,
) {
    /** Нужен, чтобы по id серии Anime365 узнать MAL id для истории из веба. */
    var resolveMal: suspend (seriesId: Long) -> Long? = { null }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingProgress = ConcurrentHashMap<Long, Progress>()
    private val pendingHistory = ConcurrentHashMap<Long, HistoryItem>()
    /** Любимые переводы и тип по умолчанию поменялись здесь — отправить вместе с прогрессом. */
    private val pendingPrefs = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pullMutex = Mutex()
    private val pushMutex = Mutex()
    private var pushJob: Job? = null

    private val _status = MutableStateFlow<String?>(null)
    /** Строка для настроек: когда синхронизировались или какая ошибка. */
    val status: StateFlow<String?> = _status.asStateFlow()

    val server: String get() = normalizeServer(prefs.s.syncServer)
    val enabled: Boolean get() = server.isNotBlank() && prefs.auth.value != null

    init {
        library.onLocalProgress = { eid, p -> noteProgress(eid, p) }
        library.onLocalHistory = { h -> noteHistory(h) }
        library.onLocalPrefs = { notePrefs() }
    }

    // ------------------------------------------------------------------ отправка

    fun noteProgress(episodeId: Long, p: Progress) {
        if (!enabled) return
        pendingProgress[episodeId] = p
        schedulePush(8000)
    }

    fun noteHistory(h: HistoryItem) {
        if (!enabled) return
        pendingHistory[h.malId] = h
        schedulePush(8000)
    }

    fun notePrefs() {
        if (!enabled) return
        pendingPrefs.set(true)
        schedulePush(2000)
    }

    /** Пауза, выход из плеера — отправить сразу. */
    fun flushSoon() = schedulePush(300)

    private fun schedulePush(delayMs: Long) {
        // во время просмотра прогресс сохраняется каждые 5 с — не откладываем отправку бесконечно
        if (delayMs >= 1000 && pushJob?.isActive == true) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(delayMs)
            push()
        }
    }

    suspend fun push(): Boolean = pushMutex.withLock {
        if (!enabled || (pendingProgress.isEmpty() && pendingHistory.isEmpty() && !pendingPrefs.get())) return@withLock true
        val p = HashMap(pendingProgress)
        val h = HashMap(pendingHistory)
        val sendPrefs = pendingPrefs.getAndSet(false)
        val body = buildJsonObject {
            if (sendPrefs) {
                putJsonObject("prefs") {
                    put("trKind", prefs.s.trKind)
                    put("trKindAt", prefs.s.trKindAt)
                    putJsonObject("trRank") {
                        library.rankRaw.value.forEach { (k, r) ->
                            putJsonObject(k) {
                                if (r.deleted) {
                                    put("deleted", true)
                                } else {
                                    put("author", r.author)
                                    put("kind", r.kind)
                                    put("count", r.count)
                                    put("last", r.last)
                                    put("pinned", r.pinned)
                                }
                                put("at", r.at)
                            }
                        }
                    }
                }
            }
            putJsonObject("progress") {
                p.forEach { (eid, v) ->
                    putJsonObject(eid.toString()) {
                        put("t", v.t)
                        put("d", v.d)
                        put("at", v.at)
                        v.tr?.let { put("tr", it) }
                    }
                }
            }
            putJsonArray("history") {
                h.values.forEach { it ->
                    addJsonObject {
                        put("eid", it.episodeId)
                        put("sid", it.seriesId)
                        put("mal", it.malId)
                        put("n", it.episodeNum)
                        put("ep", it.epLabel)
                        put("title", it.title)
                        it.poster?.let { url -> put("poster", url) }
                        put("at", it.at)
                        it.trKind?.let { v -> put("trKind", v) }
                        it.trLang?.let { v -> put("trLang", v) }
                        it.trAuthor?.let { v -> put("trAuthor", v) }
                        if (it.del) put("del", true)
                    }
                }
            }
        }.toString()
        val ok = request("POST", body) != null
        if (ok) {
            // убираем отправленное, если за время запроса не появилось новее
            p.forEach { (k, v) -> pendingProgress.remove(k, v) }
            h.forEach { (k, v) -> pendingHistory.remove(k, v) }
        } else {
            if (sendPrefs) pendingPrefs.set(true)
            schedulePush(60_000)
        }
        ok
    }

    // ------------------------------------------------------------------ получение

    /** Подтянуть изменения с сервера (только новое с прошлого раза). */
    suspend fun pull(): Boolean = pullMutex.withLock {
        if (!enabled) return@withLock false
        val since = prefs.long(PULL_KEY + server.hashCode())
        val o = request("GET", null, "?since=$since") ?: return@withLock false
        var newest = 0L
        o.o("progress")?.forEach { (k, v) ->
            val eid = k.toLongOrNull() ?: return@forEach
            val e = v.obj ?: return@forEach
            val d = e.long("d") ?: return@forEach
            val at = e.long("at") ?: return@forEach
            library.mergeRemoteProgress(eid, Progress(e.long("t") ?: 0, d, at, e.long("tr")?.takeIf { it > 0 }))
            newest = maxOf(newest, at)
        }
        for (e in o.a("history").objects()) {
            val eid = e.long("eid") ?: continue
            val sid = e.long("sid") ?: 0L
            val mal = e.long("mal") ?: (if (sid > 0) runCatching { resolveMal(sid) }.getOrNull() else null) ?: continue
            val label = e.str("ep") ?: "Серия"
            val num = e.double("n") ?: Regex("\\d+(?:\\.\\d+)?").find(label)?.value?.toDoubleOrNull() ?: 0.0
            library.mergeRemoteHistory(
                HistoryItem(
                    malId = mal, seriesId = sid, episodeId = eid, episodeNum = num, epLabel = label,
                    title = e.str("title") ?: "", poster = e.str("poster"), at = e.long("at") ?: continue,
                    trKind = e.str("trKind"), trLang = e.str("trLang"), trAuthor = e.str("trAuthor"),
                    del = e.bool("del") == true,
                ),
            )
        }
        o.o("prefs")?.let { mergePrefs(it) }
        o.long("now")?.let { prefs.putLong(PULL_KEY + server.hashCode(), it) }
        _status.value = "Синхронизировано: " + java.text.SimpleDateFormat("HH:mm", java.util.Locale("ru")).format(java.util.Date())
        true
    }

    /** Любимые переводы с сервера: по каждому автору побеждает более свежая запись. */
    private fun mergePrefs(o: JsonObject) {
        var localNewer = false
        val remote = o.o("trRank")
        library.updateRank(local = false) { m, _ ->
            remote?.forEach { (k, v) ->
                val e = v.obj ?: return@forEach
                val at = e.long("at") ?: e.long("last") ?: 0L
                val cur = m[k]
                if (cur != null && cur.at >= at) return@forEach
                m[k] = if (e.bool("deleted") == true) {
                    TrRank(deleted = true, at = at)
                } else {
                    TrRank(
                        author = e.str("author") ?: "", kind = e.str("kind") ?: "voice",
                        count = e.double("count") ?: 0.0, last = e.long("last") ?: 0L,
                        pinned = e.bool("pinned") == true, at = at,
                    )
                }
            }
            m.forEach { (k, r) ->
                val rAt = remote?.get(k)?.obj?.let { it.long("at") ?: it.long("last") } ?: -1L
                if (r.at > rAt) localNewer = true
            }
        }
        val kindAt = o.long("trKindAt") ?: 0L
        val kind = o.str("trKind")
        if (kind != null && kindAt > prefs.s.trKindAt) {
            prefs.update { it.copy(trKind = kind, trKindAt = kindAt) }
        } else if (prefs.s.trKindAt > kindAt) {
            localNewer = true
        }
        if (localNewer) notePrefs()
    }

    /** Для плеера: не ждать сервер дольше пары секунд. */
    suspend fun pullQuick(timeoutMs: Long = 2500): Boolean = try {
        withTimeoutOrNull(timeoutMs) { pull() } ?: false
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /** Полная перезагрузка (после смены сервера). */
    fun resetSince() = prefs.putLong(PULL_KEY + server.hashCode(), 0)

    private suspend fun request(method: String, body: String?, query: String = ""): JsonObject? {
        val token = shiki.freshToken() ?: run {
            _status.value = "Нужен вход в Shikimori"
            return null
        }
        val r = try {
            http.send(
                "$server/api/sync$query", method, body,
                mapOf("Authorization" to "Bearer $token", "User-Agent" to "Yoru TV"),
            )
        } catch (e: ApiError) {
            _status.value = "Сервер Yoru недоступен: " + netReason(e.cause ?: e)
            android.util.Log.w("YoruSync", "sync request failed", e.cause ?: e)
            return null
        } catch (e: IllegalArgumentException) {
            _status.value = "Неверный адрес сервера: $server"
            return null
        }
        return when (r.code) {
            // если модуль не подключён, сервер может отдать страницу входа (HTML) — это не JSON
            in 200..299 -> try {
                parseJson(r.body).obj ?: buildJsonObject { }
            } catch (_: Exception) {
                _status.value = "Сервер ответил не JSON — модуль синхронизации не подключён"
                null
            }
            401, 403 -> null.also {
                // ответ модуля yoru-sync — JSON с полем module и понятной причиной
                val j = runCatching { parseJson(r.body).obj }.getOrNull()
                val why = j?.str("error")
                _status.value = when {
                    j?.str("module") == "yoru-sync" && why != null -> "Сервер: $why"
                    r.code == 403 -> "Вашего ника нет в списке доступа сервера"
                    else -> "Сервер ответил 401 не из модуля синхронизации: /api/sync закрыт проверкой входа сайта. " +
                        "Подключите модуль раньше этой проверки (см. sync-addon/README.md)"
                }
            }
            404 -> null.also { _status.value = "На сервере нет /api/sync — подключите модуль синхронизации" }
            else -> null.also { _status.value = "Сервер ответил ${r.code}" }
        }
    }

    /** Проверка для страницы подключения. */
    suspend fun check(): String {
        if (prefs.auth.value == null) throw ApiError(401, "Сначала подключите Shikimori: сервер узнаёт вас по нему.")
        resetSince()
        if (!pull()) throw ApiError(-1, status.value ?: "Не удалось связаться с сервером")
        return "Сервер Yoru: прогресс синхронизирован."
    }

    /** Понятная причина сетевой ошибки — чтобы было видно, DNS это, сертификат или таймаут. */
    private fun netReason(t: Throwable): String = when (t) {
        is java.net.UnknownHostException ->
            "адрес не найден (DNS). Откройте этот адрес в браузере на самом устройстве — если и там не открывается, дело в DNS устройства."
        is javax.net.ssl.SSLHandshakeException, is java.security.cert.CertificateException -> certReason(t)
        is javax.net.ssl.SSLException -> "ошибка HTTPS: ${t.message ?: t.javaClass.simpleName}"
        is java.net.SocketTimeoutException -> "сервер не ответил вовремя."
        is java.net.ConnectException -> "соединение отклонено (${t.message ?: "порт закрыт"})."
        is java.net.NoRouteToHostException -> "нет маршрута до сервера."
        else -> t.message ?: t.javaClass.simpleName
    }

    /** Разбираем, что именно не так с сертификатом: часы устройства или настройка сервера. */
    private fun certReason(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }.take(8).toList()
        val text = chain.joinToString(" | ") { it.message.orEmpty() }
        return when {
            chain.any { it is java.security.cert.CertificateExpiredException || it is java.security.cert.CertificateNotYetValidException } ||
                text.contains("expired", true) || text.contains("not yet valid", true) ->
                "сертификат «просрочен» или «ещё не действует» — неверные дата и время на устройстве."
            text.contains("Trust anchor", true) ->
                "устройство не доверяет сертификату сервера: сервер отдаёт неполную цепочку (нет промежуточного сертификата) " +
                    "или сертификат самоподписанный. Браузер на ПК такое прощает, Android — нет. Чинится на сервере."
            text.contains("Hostname", true) && text.contains("not verified", true) ->
                "сертификат выписан на другой домен."
            else -> "ошибка HTTPS: ${chain.lastOrNull()?.message ?: t.javaClass.simpleName}"
        }
    }

    companion object {
        private const val PULL_KEY = "syncSince_"

        /** «yoru.site.com/» → «https://yoru.site.com»; лишний «/api/sync» в конце тоже убираем. */
        fun normalizeServer(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (s.isBlank()) return ""
            if (s.endsWith("/api/sync")) s = s.removeSuffix("/api/sync").trimEnd('/')
            if (!s.contains("://")) s = "https://$s"
            return s
        }
    }
}
