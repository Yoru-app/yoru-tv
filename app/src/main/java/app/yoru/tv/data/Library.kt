package app.yoru.tv.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** То, что хранится только на этом телевизоре: история, прогресс серий, избранное, выбор перевода. */
class Library(context: Context) {
    private val dir = File(context.filesDir, "library").apply { mkdirs() }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val histSer = ListSerializer(HistoryItem.serializer())
    private val favSer = ListSerializer(FavItem.serializer())
    private val progSer = MapSerializer(Long.serializer(), Progress.serializer())
    private val trSer = MapSerializer(String.serializer(), TrPref.serializer())
    private val rankSer = MapSerializer(String.serializer(), TrRank.serializer())

    private val loaded = CompletableDeferred<Unit>()
    private val _history = MutableStateFlow<List<HistoryItem>>(emptyList())
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()

    private val _favs = MutableStateFlow<List<FavItem>>(emptyList())
    val favs: StateFlow<List<FavItem>> = _favs.asStateFlow()

    private val progress = ConcurrentHashMap<Long, Progress>()
    private val _progressVersion = MutableStateFlow(0)
    val progressVersion: StateFlow<Int> = _progressVersion.asStateFlow()

    private val trPrefs = ConcurrentHashMap<String, TrPref>()
    private val _rank = MutableStateFlow<Map<String, TrRank>>(emptyMap())

    init {
        // Файлы библиотеки могут быть крупными. Первый кадр приложения не должен ждать
        // чтения и JSON-декодирования; загруженное состояние аккуратно объединяется с
        // изменениями, которые могли прийти от UI/синхронизации во время старта.
        io.launch {
            try {
                val diskHistory = load("history.json", histSer).orEmpty()
                val diskFavs = load("favs.json", favSer).orEmpty()
                val diskProgress = load("progress.json", progSer).orEmpty()
                val diskTr = load("trpref.json", trSer).orEmpty()
                val diskRank = load("trrank.json", rankSer).orEmpty()

                _history.value = (diskHistory + _history.value)
                    .groupBy { it.malId }
                    .mapNotNull { (_, values) -> values.maxByOrNull { it.at } }
                    .sortedByDescending { it.at }
                    .take(40)
                _favs.value = (_favs.value + diskFavs).distinctBy { it.malId }.take(500)
                diskProgress.forEach { (id, value) ->
                    progress.merge(id, value) { current, stored -> if (current.at >= stored.at) current else stored }
                }
                diskTr.forEach { (key, value) -> trPrefs.putIfAbsent(key, value) }
                _rank.value = (diskRank.keys + _rank.value.keys).associateWith { key ->
                    listOfNotNull(diskRank[key], _rank.value[key]).maxBy { it.at }
                }
                _progressVersion.value++
            } finally {
                loaded.complete(Unit)
            }
        }
    }

    /** Уведомления синхронизации о локальных изменениях (веб ↔ ТВ). */
    var onLocalProgress: ((Long, Progress) -> Unit)? = null
    var onLocalHistory: ((HistoryItem) -> Unit)? = null

    // ---------------- история ----------------

    fun pushHistory(item: HistoryItem) {
        if (item.del) return
        _history.value = (listOf(item) + _history.value.filter { it.malId != item.malId }).take(40)
        save("history.json", histSer, _history.value)
        onLocalHistory?.invoke(item)
    }

    /** Запись истории с другого устройства: берём, только если она свежее нашей. */
    fun mergeRemoteHistory(item: HistoryItem) {
        val cur = _history.value.firstOrNull { it.malId == item.malId }
        if (cur != null && cur.at >= item.at) return
        if (item.del) {
            // на другом устройстве убрали из «Продолжить просмотр»
            if (cur != null) {
                _history.value = _history.value.filter { it.malId != item.malId }
                save("history.json", histSer, _history.value)
            }
            return
        }
        // перевод, которым смотрели на другом устройстве, становится выбранным для тайтла
        item.trKind?.let { k -> setTrPref(item.malId, TrPref(k, item.trLang, item.trAuthor), global = false) }
        val merged = cur?.let { c -> item.copy(title = item.title.ifBlank { c.title }, poster = item.poster ?: c.poster) } ?: item
        _history.value = (_history.value.filter { it.malId != item.malId } + merged).sortedByDescending { it.at }.take(40)
        save("history.json", histSer, _history.value)
    }

    /** Убрать тайтл из «Продолжить просмотр» — здесь и (через синхронизацию) на других устройствах. */
    fun removeHistory(malId: Long) {
        val cur = _history.value.firstOrNull { it.malId == malId } ?: return
        _history.value = _history.value.filter { it.malId != malId }
        save("history.json", histSer, _history.value)
        onLocalHistory?.invoke(cur.copy(del = true, at = maxOf(System.currentTimeMillis(), cur.at + 1)))
    }

    fun clearHistory() {
        _history.value = emptyList()
        progress.clear()
        _progressVersion.value++
        save("history.json", histSer, emptyList())
        save("progress.json", progSer, emptyMap())
    }

    fun lastFor(malId: Long): HistoryItem? = _history.value.firstOrNull { it.malId == malId }

    // ---------------- прогресс ----------------

    private var progressJob: Job? = null

    fun saveProgress(episodeId: Long, t: Long, d: Long, tr: Long? = null) {
        if (d <= 0) return
        progress[episodeId] = Progress(t, d, System.currentTimeMillis(), tr ?: progress[episodeId]?.tr)
        if (progress.size > 3000) {
            progress.entries.sortedBy { it.value.at }.take(500).forEach { progress.remove(it.key) }
        }
        _progressVersion.value++
        scheduleProgressWrite()
        progress[episodeId]?.let { onLocalProgress?.invoke(episodeId, it) }
    }

    /** Прогресс с другого устройства: берём, только если он свежее нашего. */
    fun mergeRemoteProgress(episodeId: Long, p: Progress): Boolean {
        val cur = progress[episodeId]
        if (cur != null && cur.at >= p.at) return false
        progress[episodeId] = if (p.tr == null && cur?.tr != null) p.copy(tr = cur.tr) else p
        _progressVersion.value++
        scheduleProgressWrite()
        return true
    }

    private fun scheduleProgressWrite() {
        progressJob?.cancel()
        progressJob = io.launch {
            delay(2500)
            loaded.await()
            write("progress.json", progSer, HashMap(progress))
        }
    }

    fun progressOf(episodeId: Long): Progress? = progress[episodeId]

    fun pct(episodeId: Long): Int {
        val p = progress[episodeId] ?: return 0
        if (p.d <= 0) return 0
        return minOf(100, (p.t * 100 / p.d).toInt())
    }

    fun seen(episodeId: Long): Boolean = pct(episodeId) >= SEEN_PCT

    // ---------------- избранное ----------------

    fun isFav(malId: Long): Boolean = _favs.value.any { it.malId == malId }

    fun toggleFav(item: FavItem): Boolean {
        val on = !isFav(item.malId)
        _favs.value = if (on) (listOf(item) + _favs.value).take(500) else _favs.value.filter { it.malId != item.malId }
        save("favs.json", favSer, _favs.value)
        return on
    }

    // ---------------- выбранный перевод ----------------

    fun trPref(malId: Long): TrPref? = trPrefs[malId.toString()]

    /** Последний выбранный перевод любого тайтла — запасной вариант, если любимых нет. */
    fun lastTrPref(): TrPref? = trPrefs["_last"]

    fun setTrPref(malId: Long, tr: Translation) = setTrPref(malId, TrPref(tr.kind, tr.lang, tr.authors))

    fun setTrPref(malId: Long, p: TrPref, global: Boolean = true) {
        trPrefs[malId.toString()] = p
        if (global) trPrefs["_last"] = p
        save("trpref.json", trSer, HashMap(trPrefs))
    }

    // ---------------- любимые переводы ----------------

    /** Все записи, вместе с удалёнными — для синхронизации. */
    val rankRaw: StateFlow<Map<String, TrRank>> = _rank.asStateFlow()

    /** Изменились любимые переводы или тип по умолчанию — отправить на сервер. */
    var onLocalPrefs: (() -> Unit)? = null

    fun trRank(): Map<String, TrRank> = _rank.value.filterValues { !it.deleted }

    fun updateRank(local: Boolean = true, f: (MutableMap<String, TrRank>, Long) -> Unit) {
        val m = HashMap(_rank.value)
        f(m, System.currentTimeMillis())
        if (m == _rank.value) return
        _rank.value = m
        save("trrank.json", rankSer, m)
        if (local) onLocalPrefs?.invoke()
    }

    /** Досмотренная серия засчитывается переводу: так Yoru узнаёт любимые озвучки. */
    fun learnTr(tr: Translation, weight: Double = 1.0) {
        val key = normAuthor(tr.authors)
        if (key.isBlank()) return
        updateRank { m, now ->
            val old = m[key]?.takeUnless { it.deleted }
            m[key] = TrRank(
                author = tr.authors ?: "", kind = tr.kind,
                count = Math.round(((old?.count ?: 0.0) + weight) * 10) / 10.0,
                last = now, pinned = old?.pinned == true, at = now,
            )
        }
    }

    fun pinTr(key: String) = updateRank { m, now ->
        val r = m[key]?.takeUnless { it.deleted } ?: return@updateRank
        val pin = !r.pinned
        if (pin) m.entries.forEach { e -> if (e.value.pinned && !e.value.deleted) e.setValue(e.value.copy(pinned = false, at = now)) }
        m[key] = r.copy(pinned = pin, at = now)
    }

    fun forgetTr(key: String) = updateRank { m, now -> m[key] = TrRank(deleted = true, at = now) }

    /** Лучший перевод серии: закреплённый, затем самые досматриваемые, затем тип по вкусу и русский язык. */
    fun pickTr(trs: List<Translation>, kindPref: String): Translation? {
        if (trs.isEmpty()) return null
        val rank = trRank()
        val now = System.currentTimeMillis()
        var best: Translation? = null
        var bestScore = 0.0
        for (t in trs) {
            val r = rank[normAuthor(t.authors)] ?: continue
            val score = (if (r.pinned) 1000.0 else 0.0) + r.count * 10 + (if (kindPref == t.kind) 5 else 0) +
                (if (now - r.last < 14 * 86_400_000L) 3 else 0)
            if (score > bestScore) {
                best = t
                bestScore = score
            }
        }
        return best
    }

    // ---------------- файлы ----------------

    private fun <T> load(name: String, ser: KSerializer<T>): T? = try {
        val f = File(dir, name)
        if (f.exists()) JsonCfg.decodeFromString(ser, f.readText()) else null
    } catch (_: Exception) {
        null
    }

    private fun <T> save(name: String, ser: KSerializer<T>, value: T) {
        io.launch {
            loaded.await()
            write(name, ser, value)
        }
    }

    @Synchronized
    private fun <T> write(name: String, ser: KSerializer<T>, value: T) {
        try {
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(JsonCfg.encodeToString(ser, value))
            tmp.renameTo(File(dir, name))
        } catch (_: Exception) {
        }
    }

    companion object {
        const val SEEN_PCT = 88
    }
}
