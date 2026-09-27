package com.calgrid.widget

import android.content.Context
import android.text.format.DateFormat
import com.calgrid.container
import com.calgrid.model.AgendaBuilder
import com.calgrid.model.AgendaDay
import com.calgrid.model.MonthCell
import com.calgrid.model.MonthGridBuilder
import com.calgrid.model.TaskItem
import com.calgrid.settings.WidgetConfig
import com.calgrid.settings.WidgetLayout
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Everything one widget instance renders, loaded in one go. */
data class WidgetUiState(
    val appWidgetId: Int,
    val config: WidgetConfig,
    val hasPermission: Boolean,
    val today: LocalDate,
    val month: YearMonth,
    val monthOffset: Int,
    val selectedDate: LocalDate?,
    val monthWeeks: List<List<MonthCell>>,
    val agenda: List<AgendaDay>,
    val undatedTasks: List<TaskItem>,
    val tasksEnabled: Boolean,
    val is24Hour: Boolean,
)

object WidgetDataLoader {

    suspend fun load(
        context: Context,
        appWidgetId: Int,
        monthOffset: Int,
        selectedDate: LocalDate?,
    ): WidgetUiState {
        val container = context.container
        val config = container.widgetConfigStore.get(appWidgetId)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val now = System.currentTimeMillis()
        val month = YearMonth.from(today).plusMonths(monthOffset.toLong())

        val agendaFrom = selectedDate ?: today
        val agendaUntil = agendaFrom.plusDays(config.agendaDays.toLong())
        val gridStart = MonthGridBuilder.gridStart(month, config.firstDayOfWeek)
        val gridEnd = gridStart.plusDays(42)

        val needsMonth = config.layout != WidgetLayout.AGENDA
        val rangeStart = if (needsMonth) minOf(gridStart, agendaFrom) else agendaFrom
        val rangeEnd = if (needsMonth) maxOf(gridEnd, agendaUntil) else agendaUntil
        // One extra day on both sides: all-day events are stored in UTC.
        val events = container.calendarRepository.getInstances(
            startMillis = rangeStart.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            endMillis = rangeEnd.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            calendarIds = config.calendarIds,
        )

        val tasksEnabled = config.showTasks && container.appPrefs.tasksAccountNow().enabled
        val tasks = if (tasksEnabled) {
            container.tasksRepository.widgetTasks(config.taskListIds, config.showCompletedTasks)
        } else {
            emptyList()
        }

        return WidgetUiState(
            appWidgetId = appWidgetId,
            config = config,
            hasPermission = container.calendarRepository.hasPermission(),
            today = today,
            month = month,
            monthOffset = monthOffset,
            selectedDate = selectedDate,
            monthWeeks = if (needsMonth) {
                MonthGridBuilder.build(month, config.firstDayOfWeek, today, selectedDate, events, zone)
            } else {
                emptyList()
            },
            agenda = AgendaBuilder.build(events, tasks, agendaFrom, config.agendaDays, today, now, zone),
            undatedTasks = if (config.showUndatedTasks) AgendaBuilder.undated(tasks) else emptyList(),
            tasksEnabled = tasksEnabled,
            is24Hour = DateFormat.is24HourFormat(context),
        )
    }
}
