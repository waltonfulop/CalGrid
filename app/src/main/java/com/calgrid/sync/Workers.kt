package com.calgrid.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import com.calgrid.auth.NotAuthorizedException
import com.calgrid.container
import io.ktor.client.plugins.ResponseException

/** Fires on calendar provider changes; refreshes widgets and re-arms the trigger. */
class CalendarObserverWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            WidgetUpdater.updateAll(applicationContext)
        } finally {
            // Queued behind this run, so it starts waiting for the next change once we finish.
            SyncScheduler.watchCalendarProvider(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        return Result.success()
    }
}

/** Pushes local task edits and pulls Google Tasks; runs every 15 minutes and on demand. */
class TasksSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = applicationContext.container
        if (!container.appPrefs.tasksAccountNow().enabled) return Result.success()

        val result = try {
            container.tasksRepository.sync()
            container.appPrefs.recordSync(null)
            Result.success()
        } catch (e: NotAuthorizedException) {
            container.appPrefs.recordSync("A Google Tasks hozzáférést újra engedélyezni kell az appban.")
            Result.success()
        } catch (e: ResponseException) {
            Log.w(TAG, "Tasks sync failed: HTTP ${e.response.status}", e)
            container.appPrefs.recordSync("Szinkronhiba (HTTP ${e.response.status.value})")
            if (e.response.status.value >= 500 || e.response.status.value == 429) Result.retry() else Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Tasks sync failed", e)
            container.appPrefs.recordSync("Nem sikerült szinkronizálni: ${e.javaClass.simpleName}")
            Result.retry()
        }
        WidgetUpdater.updateAll(applicationContext)
        return result
    }

    private companion object {
        const val TAG = "TasksSyncWorker"
    }
}
