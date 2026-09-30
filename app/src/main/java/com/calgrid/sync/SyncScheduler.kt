package com.calgrid.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** Everything that keeps the widget current without the user touching it. */
object SyncScheduler {

    private const val CALENDAR_OBSERVER = "calendar_observer"
    private const val TASKS_PERIODIC = "tasks_periodic"
    private const val TASKS_NOW = "tasks_now"
    private const val TASKS_SOON = "tasks_soon"

    private const val REQ_MIDNIGHT = 1
    private const val REQ_BOUNDARY = 2

    /** Idempotent: safe to call from app start, widget callbacks and system broadcasts. */
    fun ensureScheduled(context: Context) {
        watchCalendarProvider(context, ExistingWorkPolicy.KEEP)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            TASKS_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TasksSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraint())
                .build(),
        )
        scheduleMidnight(context)
    }

    /**
     * One-shot work that fires when anything under the calendar provider changes
     * (a sync brought new events, another app edited one, …). The worker re-arms itself.
     */
    fun watchCalendarProvider(context: Context, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(CalendarContract.CONTENT_URI, true)
            .setTriggerContentUpdateDelay(2.seconds.toJavaDuration())
            .setTriggerContentMaxDelay(10.seconds.toJavaDuration())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            CALENDAR_OBSERVER,
            policy,
            OneTimeWorkRequestBuilder<CalendarObserverWorker>().setConstraints(constraints).build(),
        )
    }

    fun requestTasksSync(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            TASKS_NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<TasksSyncWorker>()
                .setConstraints(networkConstraint())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
    }

    /**
     * Follow-up syncs after handing off to the Google Tasks app, so a task added there shows up
     * in the widget within a few minutes instead of at the next periodic run.
     */
    fun requestTasksSyncSoon(context: Context) {
        val manager = WorkManager.getInstance(context)
        listOf(1L, 3L, 10L).forEach { minutes ->
            manager.enqueueUniqueWork(
                "$TASKS_SOON-$minutes",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TasksSyncWorker>()
                    .setConstraints(networkConstraint())
                    .setInitialDelay(minutes, TimeUnit.MINUTES)
                    .build(),
            )
        }
    }

    private fun networkConstraint() =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun scheduleMidnight(context: Context) {
        val zone = ZoneId.systemDefault()
        // A few seconds past midnight so "today" has definitely rolled over.
        val at = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + 5_000
        setAlarm(context, REQ_MIDNIGHT, AlarmReceiver.ACTION_MIDNIGHT, at)
    }

    /** Re-renders at the next event start / end or progress step (see [WidgetUpdater]). */
    fun scheduleBoundary(context: Context, atMillis: Long?) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pending = alarmIntent(context, REQ_BOUNDARY, AlarmReceiver.ACTION_BOUNDARY)
        if (atMillis == null) alarmManager.cancel(pending)
        else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC, atMillis + 1_000, pending)
    }

    private fun setAlarm(context: Context, requestCode: Int, action: String, atMillis: Long) {
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, alarmIntent(context, requestCode, action))
    }

    private fun alarmIntent(context: Context, requestCode: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
