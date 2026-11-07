package app.yoru.tv.channels

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.yoru.tv.YoruApp
import java.util.concurrent.TimeUnit

/** Раз в несколько часов: обновить список Shikimori, собрать уведомления, перерисовать каналы. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as YoruApp
        if (app.prefs.auth.value != null) {
            try {
                app.repo.refreshList(force = true)
            } catch (_: Exception) {
            }
        }
        try {
            app.sync.push()
            app.sync.pull()
        } catch (_: Exception) {
        }
        try {
            TvChannels.sync(app)
        } catch (_: Exception) {
            return Result.retry()
        }
        return Result.success()
    }

    companion object {
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(3, TimeUnit.HOURS).setConstraints(net).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("yoru-sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** delaySec > 0 — при запуске приложения: не спорить с главной за сеть и процессор, пока она загружается. */
        fun runNow(ctx: Context, delaySec: Long = 0) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(net).setInitialDelay(delaySec, TimeUnit.SECONDS).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("yoru-sync-now", ExistingWorkPolicy.REPLACE, req)
        }
    }
}

/** Лаунчер Android TV присылает INITIALIZE_PROGRAMS сразу после установки. */
class ChannelsInitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SyncWorker.runNow(context)
    }
}
