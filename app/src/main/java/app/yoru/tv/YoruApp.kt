package app.yoru.tv

import android.app.Application
import app.yoru.tv.channels.SyncWorker
import app.yoru.tv.data.Anime365
import app.yoru.tv.data.Art
import app.yoru.tv.data.Banners
import app.yoru.tv.data.CdnProbe
import app.yoru.tv.data.Http
import app.yoru.tv.data.Library
import app.yoru.tv.data.Prefs
import app.yoru.tv.data.Repo
import app.yoru.tv.data.Shiki
import app.yoru.tv.data.SyncClient
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class YoruApp : Application(), ImageLoaderFactory {
    lateinit var prefs: Prefs
    lateinit var http: Http
    lateinit var shiki: Shiki
    lateinit var a365: Anime365
    lateinit var banners: Banners
    lateinit var art: Art
    lateinit var library: Library
    lateinit var repo: Repo
    lateinit var cdn: CdnProbe
    lateinit var sync: SyncClient

    /** Для дел, которые не должны обрываться при уходе с экрана (сохранение оценки и т. п.). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        http = Http()
        shiki = Shiki(http, prefs)
        a365 = Anime365(http, prefs)
        banners = Banners(this, http, scope)
        art = Art(this, http, prefs)
        library = Library(this)
        repo = Repo(prefs, shiki, a365, banners, library, art)
        art.kindHint = { id -> repo.cachedAnime(id)?.kind }
        cdn = CdnProbe(http)
        sync = SyncClient(http, prefs, shiki, library)
        sync.resolveMal = { sid -> runCatching { a365.series(sid).malId }.getOrNull() }
        scope.launch(Dispatchers.IO) { SyncWorker.schedule(this@YoruApp) }
    }

    private val imageClient by lazy {
        http.client.newBuilder()
            .addInterceptor { chain ->
                val req = chain.request()
                val ua = if (req.url.host.contains("shikimori")) prefs.appName else Http.BROWSER_UA
                chain.proceed(req.newBuilder().header("User-Agent", ua).build())
            }
            .build()
    }

    /** Один на всё приложение: загрузчик картинок пересоздаётся при смене «Для слабых ТВ», а кэш на диске тот же. */
    private val imageDisk by lazy {
        DiskCache.Builder()
            .directory(cacheDir.resolve("images"))
            .maxSizeBytes(256L * 1024 * 1024)
            .build()
    }

    /** Включили или выключили «Для слабых ТВ»: картинки дальше грузятся с новыми настройками. */
    fun applyLite() {
        coil.Coil.setImageLoader(newImageLoader())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun newImageLoader(): ImageLoader {
        val lite = prefs.s.lite
        return ImageLoader.Builder(this)
            .okHttpClient(imageClient)
            .diskCache { imageDisk }
            .respectCacheHeaders(false)
            // декодирование не должно отнимать все ядра у интерфейса
            .decoderDispatcher(Dispatchers.Default.limitedParallelism(if (lite) 1 else 2))
            .crossfade(if (lite) 0 else 120)
            .allowRgb565(lite)
            .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(if (lite) 0.12 else 0.25).build() }
            .build()
    }

    companion object {
        lateinit var instance: YoruApp
            private set
    }
}
