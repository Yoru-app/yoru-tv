package app.yoru.tv.data

import android.content.Context
import app.yoru.tv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val a365Site: String = "",
    val a365Token: String = "",
    val shikiClientId: String = "",
    val shikiClientSecret: String = "",
    val shikiAppName: String = "",
    val heroSources: List<String> = listOf(HomeSource.NEW_EPISODES.name, HomeSource.WATCHING.name, HomeSource.ONGOING.name),
    val channelSources: List<String> = listOf(HomeSource.NEW_EPISODES.name, HomeSource.WATCHING.name),
    val watchNext: Boolean = true,
    val autoNext: Boolean = true,
    val autoMark: Boolean = true,
    val fastestCdn: Boolean = true,
    val subsOn: Boolean = true,
    val subSize: Float = 1f,
    val rate: Float = 1f,
    val quality: Int = 0,
    val onboardingDone: Boolean = false,
    /** Баннеры: "anilist" (AniList → Kitsu → кадр Shikimori) или "tmdb" (как в AniScroll). */
    val artSource: String = "anilist",
    /** Обложки: "shikimori" или "anilist". */
    val coverSource: String = "shikimori",
    val tmdbToken: String = "",
    /** Адрес сервера Yoru для общего прогресса с веб-версией, например https://yoru.example.com */
    val syncServer: String = "https://yoru.dohuyanevezuch.com",
    /** Боковое меню спрятано, пока не нажать ← у левого края. */
    val railHidden: Boolean = false,
    /** Ряды главной, которые выключены (имена HomeSource и «TODAY»). */
    val homeHidden: List<String> = emptyList(),
    /** Большой блок наверху главной. */
    val heroOn: Boolean = true,
    /** Логотип названия с TMDB вместо большого заголовка в большом блоке. */
    val heroLogo: Boolean = false,
    /** Серия, открытая со страницы тайтла, сразу запускается (иначе ждёт на паузе). */
    val autoplay: Boolean = true,
    /** Миниатюры кадров над шкалой при перемотке. */
    val scrubPreview: Boolean = true,
    /** Что считать в номере «Сезон N из M»: seasons | seasons_movies | all. */
    val chronoMode: String = "seasons",
    /** Тип перевода, если любимых у серии нет: auto | voice | sub. Общий с вебом. */
    val trKind: String = "auto",
    val trKindAt: Long = 0,
    /** Сортировка списка в «Моё»: updated | title | score | progress. */
    val libSort: String = "updated",
    /** Оставлено для чтения старых настроек. Интерфейс теперь всегда использует боковое меню. */
    val menuTop: Boolean = false,
    /** Главная: classic — обычные ряды, modern — каждый раздел как полноэкранная хиро-секция. */
    val homeVisual: String = "classic",
    /** Баннер большого блока: "stretch" — на всю ширину, "blur" — ×1,35 с размытыми краями. */
    val heroFit: String = "stretch",
    /** Где картинка при размытых краях: center | left | right. */
    val heroAlign: String = "center",
    /** «Для слабых ТВ»: простой вид без анимаций и размытия, функции те же. */
    val lite: Boolean = false,
    /** Ряды главной: "scroll" — листаются вбок, "page" — по ширине экрана и плитка «Все». */
    val rowsMode: String = "scroll",
    /** Размер обложек в «Каталоге», «Моём» и «Расписании»: s | m | l. */
    val coverSize: String = "m",
    /** Шапка страницы тайтла: как большой блок, но свои значения по умолчанию (как в вебе 2.8). */
    val titleFit: String = "blur",
    val titleAlign: String = "right",
    val titleLogo: Boolean = true,
)

@Serializable
data class ShikiAuth(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val userId: Long = 0,
    val nickname: String = "",
    val avatar: String? = null,
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("yoru", Context.MODE_PRIVATE)

    /**
     * В SharedPreferences остаются только настройки и вход: он целиком читается при запуске и целиком
     * переписывается при каждой записи. Список Shikimori (сотни килобайт) и остальное большое — в файлах.
     */
    private val kvDir = java.io.File(context.filesDir, "kv").apply { mkdirs() }
    private val pendingWrites = java.util.concurrent.ConcurrentHashMap<String, String>()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _settings = MutableStateFlow(read<Settings>("settings") ?: Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val s: Settings get() = _settings.value

    private val _auth = MutableStateFlow<ShikiAuth?>(read<ShikiAuth>("shikiAuth"))
    val auth: StateFlow<ShikiAuth?> = _auth.asStateFlow()

    fun update(f: (Settings) -> Settings) {
        val n = f(_settings.value)
        _settings.value = n
        write("settings", n)
    }

    fun setAuth(a: ShikiAuth?) {
        _auth.value = a
        if (a == null) sp.edit().remove("shikiAuth").apply() else write("shikiAuth", a)
    }

    // ---- значения с запасными из local.properties ----
    val site: String
        get() = (s.a365Site.ifBlank { BuildConfig.A365_SITE }.ifBlank { "https://smotret-anime.org" }).trimEnd('/')
    val token: String get() = s.a365Token.trim()
    val clientId: String get() = s.shikiClientId.ifBlank { BuildConfig.SHIKI_CLIENT_ID }.trim()
    val clientSecret: String get() = s.shikiClientSecret.ifBlank { BuildConfig.SHIKI_CLIENT_SECRET }.trim()
    val appName: String get() = s.shikiAppName.ifBlank { BuildConfig.SHIKI_APP_NAME }.ifBlank { "Yoru TV" }.trim()
    val tmdbToken: String get() = s.tmdbToken.ifBlank { BuildConfig.TMDB_TOKEN }.trim()

    // ---- произвольные значения в JSON ----
    inline fun <reified T> read(key: String): T? = try {
        rawGet(key)?.let { JsonCfg.decodeFromString(kotlinx.serialization.serializer<T>(), it) }
    } catch (_: Exception) {
        null
    }

    inline fun <reified T> write(key: String, value: T) {
        rawPut(key, JsonCfg.encodeToString(kotlinx.serialization.serializer<T>(), value))
    }

    /** То же, но и сериализация — вне основного потока: для больших значений. */
    inline fun <reified T> writeAsync(key: String, value: T) {
        io.launch { write(key, value) }
    }

    private fun kvFile(key: String) = java.io.File(kvDir, key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    fun rawGet(key: String): String? {
        if (key in SMALL_KEYS) return sp.getString(key, null)
        pendingWrites[key]?.let { return it.ifEmpty { null } }
        val f = kvFile(key)
        if (f.exists()) return runCatching { f.readText() }.getOrNull()
        // значение из старой версии приложения: переносим в файл и освобождаем настройки
        val old = sp.getString(key, null) ?: return null
        rawPut(key, old)
        sp.edit().remove(key).apply()
        return old
    }

    fun rawPut(key: String, value: String?) {
        if (key in SMALL_KEYS) {
            sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
            return
        }
        val v = value ?: ""
        pendingWrites[key] = v
        io.launch {
            val f = kvFile(key)
            runCatching {
                if (value == null) {
                    f.delete()
                } else {
                    val tmp = java.io.File(f.path + ".tmp")
                    tmp.writeText(value)
                    tmp.renameTo(f)
                }
            }
            pendingWrites.remove(key, v)
        }
    }

    private companion object {
        val SMALL_KEYS = setOf("settings", "shikiAuth")
    }

    fun long(key: String, def: Long = 0): Long = sp.getLong(key, def)
    fun putLong(key: String, v: Long) = sp.edit().putLong(key, v).apply()
}
