package app.yoru.tv.data

import kotlinx.serialization.Serializable

/** Источники для хиро-блока на главной и для каналов Android TV. */
enum class HomeSource(val title: String, val hint: String, val needsLogin: Boolean = false) {
    CONTINUE("Продолжить просмотр", "Начатые на этом телевизоре серии"),
    NEW_EPISODES("Новые серии из списка", "Вышли серии, которые вы ещё не смотрели", true),
    WATCHING("Смотрю", "Список «Смотрю» на Shikimori", true),
    FEED("Свежие переводы", "Последние озвучки и субтитры на Anime365"),
    ONGOING("Сейчас выходит", "Популярные онгоинги"),
    SEASON("Аниме сезона", "Популярное в текущем сезоне"),
    PLANNED("Запланировано", "Список «Запланировано» на Shikimori", true),
    TOP("Лучшее", "Самые высокие оценки на Shikimori");

    companion object {
        fun of(name: String): HomeSource? = entries.firstOrNull { it.name == name }
    }
}

/** Куда ведёт карточка. */
@Serializable
sealed interface CardAction {
    @Serializable
    data class Title(val malId: Long) : CardAction

    @Serializable
    data class Watch(
        val malId: Long,
        val episodeId: Long? = null,
        val episodeNum: Double? = null,
        val translationId: Long? = null,
    ) : CardAction
}

/** Универсальная карточка для рядов, сеток, хиро и каналов. */
@Serializable
data class Card(
    val malId: Long,
    val title: String,
    val alt: String? = null,
    val poster: String? = null,
    val meta: String? = null,
    val badge: String? = null,
    val topBadge: String? = null,
    val progress: Float? = null,
    val kicker: String? = null,
    val action: CardAction = CardAction.Title(malId),
    /** Что запускает главная кнопка хиро-блока: «Смотреть N серию» и т. п. */
    val play: CardAction.Watch? = null,
    val playLabel: String? = null,
)

data class Genre(val id: Long, val name: String, val russian: String?, val kind: String?) {
    val title: String get() = russian?.takeIf { it.isNotBlank() } ?: name
}

data class Anime(
    val id: Long,
    val name: String,
    val russian: String? = null,
    val english: String? = null,
    val japanese: String? = null,
    val kind: String? = null,
    val score: Double? = null,
    val status: String? = null,
    val episodes: Int = 0,
    val episodesAired: Int = 0,
    val duration: Int? = null,
    val year: Int? = null,
    val airedOn: String? = null,
    val releasedOn: String? = null,
    val season: String? = null,
    val poster: String? = null,
    val posterBig: String? = null,
    val genres: List<Genre> = emptyList(),
    val studios: List<String> = emptyList(),
    val description: String? = null,
    val nextEpisodeAt: String? = null,
    val screenshots: List<String> = emptyList(),
) {
    val title: String get() = russian?.takeIf { it.isNotBlank() } ?: name
    val alt: String? get() = name.takeIf { it != title }
}

data class Related(val relation: String, val anime: Anime)

/** Персонаж тайтла: имя, картинка и роль («Main» — главный). */
data class CharacterRole(val id: Long, val name: String, val poster: String?, val roles: List<String>, val main: Boolean)

/** Сезон — ТВ-сериал или длинная ONA (веб-сериал). Рекапы и спешлы — это special/ova/tv_special. */
fun Anime.isSeason(): Boolean = kind == "tv" || (kind == "ona" && (episodes >= 6 || (episodes == 0 && status != "released")))

/** Что учитывается в номере «Сезон N из M»: как в вебе — только сезоны, сезоны и фильмы или вся франшиза. */
fun chronoCounts(mode: String, a: Anime): Boolean = when (mode) {
    "all" -> true
    "seasons_movies" -> a.isSeason() || a.kind == "movie"
    else -> a.isSeason()
}

val CHRONO_MODES = listOf(
    Triple("seasons", "Только сезоны", "ТВ-сериалы и длинные ONA. Без фильмов, OVA, спешлов и рекапов"),
    Triple("seasons_movies", "Сезоны и фильмы", "Сезоны плюс полнометражные фильмы"),
    Triple("all", "Вся хронология", "Все части франшизы: OVA, спешлы, рекапы, клипы"),
)

data class ChronoPos(val n: Int, val total: Int, val seasons: Boolean)

/**
 * Место тайтла в хронологии по выбранному режиму. Если тайтл в режим не попадает
 * (например, фильм при «только сезоны») или частей меньше двух — считаем по всей хронологии.
 */
fun chronoPosition(list: List<Anime>, id: Long, mode: String): ChronoPos? {
    if (list.size < 2) return null
    var used = mode
    var pool = list.filter { chronoCounts(mode, it) }
    if (pool.size < 2 || pool.none { it.id == id }) {
        used = "all"
        pool = list
    }
    val i = pool.indexOfFirst { it.id == id }
    return if (i < 0) null else ChronoPos(i + 1, pool.size, used == "seasons")
}

data class CalendarEntry(val anime: Anime, val nextEpisode: Int, val at: Long)

/** Запись списка Shikimori. */
@Serializable
data class RateItem(
    val rateId: Long,
    val malId: Long,
    val status: String,
    val episodes: Int,
    val score: Int,
    val updatedAt: String,
    val title: String,
    val alt: String? = null,
    val kind: String? = null,
    val total: Int = 0,
    val aired: Int = 0,
    val animeStatus: String? = null,
    val poster: String? = null,
) {
    /** Сколько серий уже доступно (у вышедших — все). */
    val available: Int get() = if (animeStatus == "released") maxOf(total, aired) else aired
}

@Serializable
data class ListSnapshot(val userId: Long, val at: Long, val items: List<RateItem>)

val RATE_STATUSES = listOf(
    "watching" to "Смотрю",
    "planned" to "Запланировано",
    "completed" to "Просмотрено",
    "on_hold" to "Отложено",
    "dropped" to "Брошено",
    "rewatching" to "Пересматриваю",
)

fun rateStatusTitle(s: String?): String = RATE_STATUSES.firstOrNull { it.first == s }?.second ?: "Не в списке"

// ---------------- Anime365 ----------------

data class A365Series(
    val id: Long,
    val malId: Long?,
    val title: String,
    val url: String?,
    val poster: String?,
    val episodes: List<A365Episode>,
)

data class A365Episode(
    val id: Long,
    val seriesId: Long,
    val episodeInt: String?,
    val episodeFull: String?,
    val episodeType: String,
    val title: String?,
    val translations: List<Translation> = emptyList(),
) {
    val n: Double get() = episodeInt?.toDoubleOrNull() ?: Double.NaN

    /** Трейлер, промо или тизер: у Anime365 это episodeType «preview», иногда — только подпись серии. */
    val isTrailer: Boolean
        get() = episodeType.lowercase() in TRAILER_TYPES || TRAILER_RE.containsMatchIn(episodeFull ?: title ?: "")

    val short: String
        get() {
            val v = n
            if (!v.isNaN() && v > 0 && episodeType != "movie") return episodeInt!!.removeSuffix(".0")
            return if (episodeType == "movie") "Фильм" else (episodeFull ?: episodeInt ?: "?")
        }

    val label: String
        get() {
            val v = n
            if (episodeType == "movie" && (v.isNaN() || v <= 1)) return "Фильм"
            if (!v.isNaN() && v > 0) return "Серия ${episodeInt!!.removeSuffix(".0")}"
            return episodeFull ?: "Серия"
        }
}

private val TRAILER_TYPES = setOf("preview", "trailer", "pv", "promo", "teaser", "cm")
private val TRAILER_RE = Regex("""трейлер|тизер|промо|анонс|trailer|teaser|promo|\bpv\s*\d*\b|\bcm\s*\d*\b""", RegexOption.IGNORE_CASE)

data class Translation(
    val id: Long,
    val episodeId: Long,
    val kind: String,
    val lang: String?,
    val authors: String?,
    val height: Int?,
    val url: String?,
    val addedAt: String?,
) {
    val name: String get() = authors?.takeIf { it.isNotBlank() } ?: "Без названия"
    val meta: String get() = listOfNotNull(LANGS[lang] ?: lang, height?.let { "${it}p" }).joinToString(", ")

    companion object {
        val KINDS = linkedMapOf("voice" to "Озвучка", "sub" to "Субтитры", "raw" to "Оригинал")
        val LANGS = mapOf("ru" to "рус", "en" to "англ", "ja" to "яп", "uk" to "укр")
    }
}

data class Stream(val height: Int, val urls: List<String>)

data class Embed(val streams: List<Stream>, val subtitlesUrl: String?)

data class FeedItem(val translation: Translation, val seriesId: Long, val malId: Long?, val episodeId: Long, val episodeLabel: String, val seriesTitle: String?, val poster: String?)

// ---------------- локальная библиотека ----------------

@Serializable
data class HistoryItem(
    val malId: Long,
    val seriesId: Long,
    val episodeId: Long,
    val episodeNum: Double,
    val epLabel: String,
    val title: String,
    val poster: String? = null,
    val at: Long,
    /** Перевод, которым смотрели тайтл последним (поля Anime365: typeKind, typeLang, authorsSummary). */
    val trKind: String? = null,
    val trLang: String? = null,
    val trAuthor: String? = null,
    /** Убрано из «Продолжить просмотр» — метка для других устройств. */
    val del: Boolean = false,
)

@Serializable
/** tr — id перевода Anime365, которым смотрели эту серию. */
data class Progress(val t: Long, val d: Long, val at: Long, val tr: Long? = null)

@Serializable
data class TrPref(val kind: String, val lang: String? = null, val author: String? = null)

/**
 * Любимый перевод: сколько серий с ним досмотрено, закреплён ли первым. Общий с вебом (prefs.trRank).
 * Удалённый автор остаётся «надгробием» (deleted), чтобы удаление дошло до других устройств.
 */
@Serializable
data class TrRank(
    val author: String = "",
    val kind: String = "voice",
    val count: Double = 0.0,
    val last: Long = 0,
    val pinned: Boolean = false,
    val at: Long = 0,
    val deleted: Boolean = false,
)

/** «AniLibria.TV» и «AniLibria» — одна команда. */
fun normAuthor(s: String?): String = (s ?: "").lowercase()
    .replace(Regex("""\.(tv|ru|com|org|club|online)\b"""), "")
    .replace(Regex("[^a-zа-яё0-9]+"), "")

@Serializable
data class FavItem(val malId: Long, val title: String, val poster: String? = null, val meta: String? = null)

@Serializable
data class Notice(
    val id: String,
    val malId: Long,
    val title: String,
    val text: String,
    val poster: String? = null,
    val at: Long,
    val read: Boolean = false,
    val episode: Int? = null,
)

// ---------------- подписи ----------------

val KIND_TITLES = mapOf(
    "tv" to "ТВ", "movie" to "Фильм", "ova" to "OVA", "ona" to "ONA", "special" to "Спешл",
    "tv_special" to "ТВ-спешл", "music" to "Клип", "pv" to "Проморолик", "cm" to "Реклама",
)
val STATUS_TITLES = mapOf("anons" to "Анонс", "ongoing" to "Выходит", "released" to "Вышло")
val EP_TYPES = linkedMapOf(
    "tv" to "ТВ", "movie" to "Фильм", "ova" to "OVA", "ona" to "ONA",
    "special" to "Спешлы", "music" to "Клипы",
)

fun plural(n: Int, one: String, few: String, many: String): String {
    val a = kotlin.math.abs(n) % 100
    val b = a % 10
    return when {
        a in 11..19 -> many
        b in 2..4 -> few
        b == 1 -> one
        else -> many
    }
}

fun epWord(n: Int) = "$n ${plural(n, "серия", "серии", "серий")}"

fun Anime.metaLine(): String = listOfNotNull(
    KIND_TITLES[kind],
    year?.toString(),
    when {
        status == "ongoing" && episodes > 0 -> "$episodesAired из $episodes"
        status == "ongoing" && episodesAired > 0 -> "$episodesAired ${plural(episodesAired, "серия", "серии", "серий")}"
        episodes > 1 -> epWord(episodes)
        else -> null
    },
).joinToString(" · ")

fun Anime.toCard(): Card = Card(
    malId = id,
    title = title,
    alt = alt,
    poster = poster,
    meta = metaLine(),
    topBadge = score?.takeIf { it > 0 }?.let { String.format(java.util.Locale.US, "%.1f", it) },
)

fun seasonTitle(season: String?, year: Int?): String {
    val m = Regex("^(winter|spring|summer|fall)_(\\d{4})").find(season ?: "")
    if (m != null) {
        val name = mapOf("winter" to "Зима", "spring" to "Весна", "summer" to "Лето", "fall" to "Осень")[m.groupValues[1]]
        return "$name ${m.groupValues[2]}"
    }
    return year?.toString() ?: ""
}

fun fmtTime(sec: Long): String {
    val s = maxOf(0L, sec)
    val h = s / 3600
    val m = s % 3600 / 60
    val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
}

fun relTime(ms: Long): String {
    if (ms <= 0) return ""
    val m = ((System.currentTimeMillis() - ms) / 60000).toInt()
    return when {
        m < 1 -> "только что"
        m < 60 -> "$m мин назад"
        m < 60 * 24 -> "${m / 60} ч назад"
        m < 60 * 48 -> "вчера"
        else -> {
            val d = m / 60 / 24
            "$d ${plural(d, "день", "дня", "дней")} назад"
        }
    }
}
