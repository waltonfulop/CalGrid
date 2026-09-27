package com.calgrid.sync

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import com.calgrid.container
import com.calgrid.widget.CalGridWidget
import java.time.Duration

object WidgetUpdater {

    /** Reloads data for every CalGrid widget and re-arms the "next event ends" alarm. */
    suspend fun updateAll(context: Context) {
        val widget = CalGridWidget()
        val ids = GlanceAppWidgetManager(context).getGlanceIds(CalGridWidget::class.java)
        for (id in ids) {
            updateAppWidgetState(context, id) { it[CalGridWidget.VERSION] = System.nanoTime() }
            widget.update(context, id)
        }
        SyncScheduler.scheduleBoundary(context, if (ids.isEmpty()) null else nextEventEnd(context))
    }

    private suspend fun nextEventEnd(context: Context): Long? {
        val now = System.currentTimeMillis()
        return context.container.calendarRepository
            .getInstances(now - Duration.ofDays(1).toMillis(), now + Duration.ofDays(1).toMillis(), null)
            .asSequence()
            .filter { !it.allDay && it.end > now }
            .minOfOrNull { it.end }
    }
}
