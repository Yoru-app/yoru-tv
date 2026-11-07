package app.yoru.tv.channels

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import app.yoru.tv.R
import app.yoru.tv.YoruApp
import app.yoru.tv.data.Card
import app.yoru.tv.data.CardAction
import app.yoru.tv.data.HomeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ChannelInfo(val id: Long, val source: String, val browsable: Boolean)

/**
 * Каналы на главном экране Android TV: по одному на каждый выбранный источник
 * (те же источники, что у хиро-блока). Карточки — горизонтальные баннеры 16:9.
 * Плюс системный ряд «Продолжить просмотр» (Watch Next).
 */
object TvChannels {
    // Значения констант TvContract — пишем числами, чтобы не зависеть от наследования
    // статических полей Java-интерфейсов в Kotlin.
    private const val TYPE_PREVIEW = "TYPE_PREVIEW"
    private const val ASPECT_16_9 = 0
    private const val ASPECT_2_3 = 4
    private const val PROGRAM_TYPE_TV_SERIES = 1
    private const val PROGRAM_TYPE_TV_EPISODE = 3
    private const val WATCH_NEXT_CONTINUE = 0

    fun supported(ctx: Context): Boolean = try {
        ctx.contentResolver.query(TvContractCompat.Channels.CONTENT_URI, arrayOf("_id"), null, null, null)?.close()
        true
    } catch (_: Exception) {
        false
    }

    fun channels(ctx: Context): List<ChannelInfo> {
        val out = ArrayList<ChannelInfo>()
        try {
            ctx.contentResolver.query(
                TvContractCompat.Channels.CONTENT_URI,
                arrayOf("_id", "internal_provider_id", "browsable"), null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    out += ChannelInfo(c.getLong(0), c.getString(1) ?: "", c.getInt(2) == 1)
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Интент системного диалога «Добавить канал на главный экран?». */
    fun browsableIntent(channelId: Long): Intent =
        Intent(TvContractCompat.ACTION_REQUEST_CHANNEL_BROWSABLE).putExtra(TvContractCompat.EXTRA_CHANNEL_ID, channelId)

    suspend fun sync(app: YoruApp) = withContext(Dispatchers.IO) {
        val ctx: Context = app
        if (!supported(ctx)) return@withContext
        val wanted = app.prefs.s.channelSources.mapNotNull { HomeSource.of(it) }
        val loggedIn = app.prefs.auth.value != null
        var existing = channels(ctx)

        // убрать каналы, которые больше не выбраны
        existing.filter { ch -> wanted.none { it.name == ch.source } }.forEach { ch ->
            try {
                ctx.contentResolver.delete(TvContractCompat.buildChannelUri(ch.id), null, null)
            } catch (_: Exception) {
            }
        }
        existing = channels(ctx)

        for (src in wanted) {
            val have = existing.firstOrNull { it.source == src.name }
            // без входа в Shikimori не создаём пустые каналы, а существующие очищаем
            if (src.needsLogin && !loggedIn) {
                if (have != null) replacePrograms(ctx, have.id, emptyList(), emptyMap())
                continue
            }
            val cards = try {
                app.repo.source(src, 16)
            } catch (_: Exception) {
                continue
            }
            if (cards.isEmpty() && have == null) continue
            val id = existing.firstOrNull { it.source == src.name }?.id ?: createChannel(ctx, src, isFirst = existing.isEmpty()).also {
                existing = channels(ctx)
            } ?: continue
            val art = try {
                app.repo.backdrops(cards.map { it.malId })
            } catch (_: Exception) {
                emptyMap()
            }
            replacePrograms(ctx, id, cards, art)
        }

        if (app.prefs.s.watchNext) syncWatchNext(app) else clearWatchNext(ctx)
    }

    private fun createChannel(ctx: Context, src: HomeSource, isFirst: Boolean): Long? = try {
        val b = Channel.Builder()
        b.setType(TYPE_PREVIEW)
        b.setDisplayName("Yoru · ${src.title}")
        b.setDescription(src.hint)
        b.setAppLinkIntentUri(Uri.parse("yoru://home"))
        b.setInternalProviderId(src.name)
        val uri = ctx.contentResolver.insert(TvContractCompat.Channels.CONTENT_URI, b.build().toContentValues())
        val id = uri?.let { ContentUris.parseId(it) }
        if (id != null) {
            BitmapFactory.decodeResource(ctx.resources, R.drawable.channel_logo)?.let {
                ChannelLogoUtils.storeChannelLogo(ctx, id, it)
            }
            // Первый канал приложения можно показать без вопроса пользователю
            if (isFirst) TvContractCompat.requestChannelBrowsable(ctx, id)
        }
        id
    } catch (_: Exception) {
        null
    }

    private fun replacePrograms(ctx: Context, channelId: Long, cards: List<Card>, art: Map<Long, String?>) {
        val cr = ctx.contentResolver
        try {
            val old = ArrayList<Long>()
            cr.query(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), arrayOf("_id"), null, null, null)?.use { c ->
                while (c.moveToNext()) old += c.getLong(0)
            }
            old.forEach { cr.delete(TvContractCompat.buildPreviewProgramUri(it), null, null) }
        } catch (_: Exception) {
        }
        cards.forEachIndexed { i, card ->
            try {
                val b = PreviewProgram.Builder()
                b.setChannelId(channelId)
                b.setType(PROGRAM_TYPE_TV_SERIES)
                b.setTitle(card.title)
                b.setDescription(listOfNotNull(card.kicker, card.meta).joinToString(" · "))
                val banner = art[card.malId]
                val image = banner ?: card.poster
                if (image != null) {
                    b.setPosterArtUri(Uri.parse(image))
                    b.setPosterArtAspectRatio(if (banner != null) ASPECT_16_9 else ASPECT_2_3)
                }
                b.setIntentUri(Uri.parse(deepLink(card.action)))
                b.setInternalProviderId("${card.malId}")
                b.setWeight(1000 - i)
                cr.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, b.build().toContentValues())
            } catch (_: Exception) {
            }
        }
    }

    /** Только системный ряд «Продолжить просмотр» — после выхода из плеера. */
    suspend fun updateWatchNext(app: YoruApp) = withContext(Dispatchers.IO) {
        if (app.prefs.s.watchNext && supported(app)) syncWatchNext(app)
    }

    private suspend fun syncWatchNext(app: YoruApp) {
        val ctx: Context = app
        clearWatchNext(ctx)
        val hist = app.library.history.value.take(6)
        val art = try {
            app.repo.backdrops(hist.map { it.malId })
        } catch (_: Exception) {
            emptyMap()
        }
        for (h in hist) {
            val p = app.library.progressOf(h.episodeId) ?: continue
            if (p.d <= 0 || p.t * 100 / p.d >= 88) continue
            try {
                val b = WatchNextProgram.Builder()
                b.setWatchNextType(WATCH_NEXT_CONTINUE)
                b.setType(PROGRAM_TYPE_TV_EPISODE)
                b.setTitle(h.title)
                b.setEpisodeTitle(h.epLabel)
                val banner = art[h.malId]
                val image = banner ?: h.poster
                if (image != null) {
                    b.setPosterArtUri(Uri.parse(image))
                    b.setPosterArtAspectRatio(if (banner != null) ASPECT_16_9 else ASPECT_2_3)
                }
                b.setLastEngagementTimeUtcMillis(h.at)
                b.setLastPlaybackPositionMillis((p.t * 1000).toInt())
                b.setDurationMillis((p.d * 1000).toInt())
                b.setIntentUri(Uri.parse(deepLink(CardAction.Watch(h.malId, episodeId = h.episodeId))))
                b.setInternalProviderId("hist-${h.malId}")
                ctx.contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, b.build().toContentValues())
            } catch (_: Exception) {
            }
        }
    }

    private fun clearWatchNext(ctx: Context) {
        val cr = ctx.contentResolver
        try {
            val ids = ArrayList<Long>()
            cr.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, arrayOf("_id"), null, null, null)?.use { c ->
                while (c.moveToNext()) ids += c.getLong(0)
            }
            ids.forEach { cr.delete(TvContractCompat.buildWatchNextProgramUri(it), null, null) }
        } catch (_: Exception) {
        }
    }

    fun deepLink(a: CardAction): String = when (a) {
        is CardAction.Title -> "yoru://title/${a.malId}"
        is CardAction.Watch -> buildString {
            append("yoru://watch/${a.malId}")
            val q = listOfNotNull(
                a.episodeId?.let { "ep=$it" },
                a.episodeNum?.let { "n=$it" },
                a.translationId?.let { "tr=$it" },
            )
            if (q.isNotEmpty()) append("?" + q.joinToString("&"))
        }
    }
}
