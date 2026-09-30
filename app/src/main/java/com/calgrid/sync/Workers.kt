package com.calgrid.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import com.calgrid.auth.NotAuthorizedException
import com.calgrid.container
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
            val body = runCatching { e.response.bodyAsText() }.getOrDefault("")
            Log.w(TAG, "Tasks sync failed: HTTP ${e.response.status}: $body", e)
            container.appPrefs.recordSync(describeHttpError(e.response.status.value, body))
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

/** Turns a Google API error response into a message the user can act on. */
internal fun describeHttpError(status: Int, body: String): String {
    val error = runCatching { Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()
    val message = error?.get("message")?.jsonPrimitive?.contentOrNull
    return when {
        "SERVICE_DISABLED" in body || "accessNotConfigured" in body ->
            "A Google Tasks API nincs engedélyezve a Cloud projektben. " +
                "APIs & Services → Library → Google Tasks API → Enable, majd pár perc múlva Szinkron."
        "ACCESS_TOKEN_SCOPE_INSUFFICIENT" in body || "insufficientPermissions" in body ->
            "A Google-engedélyezésnél nem lett megadva a Tasks-hozzáférés. " +
                "Nyomd meg a Leválasztás, majd a Csatlakozás gombot, és pipáld be a feladatokhoz való hozzáférést."
        message != null -> "Szinkronhiba (HTTP $status): $message"
        else -> "Szinkronhiba (HTTP $status)"
    }
}
