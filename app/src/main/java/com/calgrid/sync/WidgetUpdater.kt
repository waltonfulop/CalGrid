package com.calgrid.sync

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import com.calgrid.container
import com.calgrid.widget.CalGridWidget
import java.time.Duration

object WidgetUpdater {

    /** How often a running event's progress bar moves. */
    private val PROGRESS_STEP = Duration.ofMinutes(5).toMillis()

    /** Reloads data for every CalGrid widget and re-arms the next redraw alarm. */
    suspend fun updateAll(context: Context) {
        val widget = CalGridWidget()
        val ids = GlanceAppWidgetManager(context).getGlanceIds(CalGridWidget::class.java)
        for (id in ids) {
            updateAppWidgetState(context, id) { it[CalGridWidget.VERSION] = System.nanoTime() }
            widget.update(context, id)
        }
        SyncScheduler.scheduleBoundary(context, if (ids.isEmpty()) null else nextBoundary(context))
    }

    /**
     * The next moment the agenda looks different: an event starts (now line moves, progress bar appears)
     * or ends (strike-through), or the next progress step of a running event.
     */
    private suspend fun nextBoundary(context: Context): Long? {
        val now = System.currentTimeMillis()
        val timed = context.container.calendarRepository
            .getInstances(now - Duration.ofDays(1).toMillis(), now + Duration.ofDays(1).toMillis(), null)
            .filter { !it.allDay }
        val edges = timed.asSequence().flatMap { sequenceOf(it.begin, it.end) }.filter { it > now }
        val progressTick = if (timed.any { it.begin <= now && now < it.end }) now + PROGRESS_STEP else null
        return (edges + listOfNotNull(progressTick)).minOrNull()
    }
}
