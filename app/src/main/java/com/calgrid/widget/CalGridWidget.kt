package com.calgrid.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.currentState
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.calgrid.container
import com.calgrid.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

class CalGridWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val initialPrefs = getAppWidgetState<Preferences>(context, id)
        // Loaded up front so a fresh session never flashes a loading state.
        val initial = WidgetDataLoader.load(
            context, appWidgetId, initialPrefs[MONTH_OFFSET] ?: 0, initialPrefs.selectedDate(),
        )

        provideContent {
            val prefs = currentState<Preferences>()
            val version = prefs[VERSION] ?: 0L
            val monthOffset = prefs[MONTH_OFFSET] ?: 0
            val selected = prefs.selectedDate()
            val state by produceState(initial, version, monthOffset, selected) {
                value = WidgetDataLoader.load(context, appWidgetId, monthOffset, selected)
            }
            GlanceTheme {
                WidgetContent(state)
            }
        }
    }

    companion object {
        /** Bumped by [com.calgrid.sync.WidgetUpdater] to force a data reload. */
        val VERSION = longPreferencesKey("version")
        val MONTH_OFFSET = intPreferencesKey("month_offset")
        val SELECTED_DATE = stringPreferencesKey("selected_date")

        private fun Preferences.selectedDate(): LocalDate? =
            this[SELECTED_DATE]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }
}

class CalGridWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CalGridWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        SyncScheduler.ensureScheduled(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        SyncScheduler.ensureScheduled(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val store = context.container.widgetConfigStore
        CoroutineScope(Dispatchers.IO).launch {
            appWidgetIds.forEach { store.delete(it) }
        }
    }
}
