package com.calgrid.widget

import android.appwidget.AppWidgetManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.calgrid.container
import com.calgrid.model.EventInstance
import com.calgrid.sync.SyncScheduler
import com.calgrid.sync.WidgetUpdater
import com.calgrid.ui.AddChooserActivity
import com.calgrid.ui.MainActivity
import com.calgrid.ui.config.WidgetConfigActivity
import java.time.LocalDate
import java.time.ZoneId

object WidgetParams {
    val TASK_ID = ActionParameters.Key<String>("task_id")
    val COMPLETED = ActionParameters.Key<Boolean>("completed")
    val MONTH_DELTA = ActionParameters.Key<Int>("month_delta")
    val DATE = ActionParameters.Key<String>("date")
}

/** Widget refresh button: ask calendar accounts and Google Tasks to sync, then redraw. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        context.container.calendarRepository.requestSync()
        SyncScheduler.requestTasksSync(context)
        WidgetUpdater.updateAll(context)
    }
}

class ToggleTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[WidgetParams.TASK_ID] ?: return
        val completed = parameters[WidgetParams.COMPLETED] ?: return
        context.container.tasksRepository.setCompleted(id, completed)
    }
}

/** Moves the month grid by [WidgetParams.MONTH_DELTA]; delta 0 jumps back to today. */
class MonthNavAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val delta = parameters[WidgetParams.MONTH_DELTA] ?: 0
        updateAppWidgetState(context, glanceId) { prefs ->
            if (delta == 0) {
                prefs.remove(CalGridWidget.MONTH_OFFSET)
                prefs.remove(CalGridWidget.SELECTED_DATE)
            } else {
                prefs[CalGridWidget.MONTH_OFFSET] = (prefs[CalGridWidget.MONTH_OFFSET] ?: 0) + delta
            }
        }
        CalGridWidget().update(context, glanceId)
    }
}

/** Starts the agenda at the tapped day; tapping the selected day again clears the selection. */
class SelectDayAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val date = parameters[WidgetParams.DATE] ?: return
        updateAppWidgetState(context, glanceId) { prefs ->
            if (prefs[CalGridWidget.SELECTED_DATE] == date || LocalDate.parse(date) == LocalDate.now()) {
                prefs.remove(CalGridWidget.SELECTED_DATE)
            } else {
                prefs[CalGridWidget.SELECTED_DATE] = date
            }
        }
        CalGridWidget().update(context, glanceId)
    }
}

object WidgetIntents {

    fun openEvent(event: EventInstance): Intent =
        Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openCalendarAt(date: LocalDate): Intent {
        val millis = date.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").also {
            ContentUris.appendId(it, millis)
        }.build()
        return Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun newEvent(date: LocalDate, today: LocalDate): Intent {
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (date != today) {
            val begin = date.atTime(9, 0).atZone(ZoneId.systemDefault())
            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin.toInstant().toEpochMilli())
            intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin.plusHours(1).toInstant().toEpochMilli())
        }
        return intent
    }

    fun addChooser(context: Context, date: LocalDate, today: LocalDate): Intent =
        Intent(context, AddChooserActivity::class.java)
            .setData(Uri.parse("calgrid://add/$date"))
            .putExtra(AddChooserActivity.EXTRA_DATE, (if (date == today) null else date)?.toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    fun openTask(context: Context, taskId: String): Intent =
        Intent(context, MainActivity::class.java)
            .setData(Uri.parse("calgrid://task/$taskId"))
            .putExtra(MainActivity.EXTRA_TASK_ID, taskId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** Launcher entry of the Google Tasks app; start it with [newTaskInApp] as the fallback. */
    fun googleTasks(): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(GOOGLE_TASKS_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private const val GOOGLE_TASKS_PACKAGE = "com.google.android.apps.tasks"

    fun newTaskInApp(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .setData(Uri.parse("calgrid://task/new"))
            .putExtra(MainActivity.EXTRA_NEW_TASK, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun openApp(context: Context): Intent =
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun configure(context: Context, appWidgetId: Int): Intent =
        Intent(context, WidgetConfigActivity::class.java)
            .setData(Uri.parse("calgrid://widget/$appWidgetId"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
