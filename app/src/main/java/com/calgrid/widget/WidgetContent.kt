package com.calgrid.widget

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.calgrid.R
import com.calgrid.model.AgendaEntry
import com.calgrid.model.DaySpan
import com.calgrid.model.MonthCell
import com.calgrid.model.MonthGridBuilder
import com.calgrid.model.TaskItem
import com.calgrid.settings.WidgetLayout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import androidx.glance.color.ColorProvider as DayNightColorProvider

private enum class Mode { MONTH, AGENDA, SIDE_BY_SIDE, STACKED }

private fun resolveMode(layout: WidgetLayout, size: DpSize): Mode = when (layout) {
    WidgetLayout.MONTH -> Mode.MONTH
    WidgetLayout.AGENDA -> Mode.AGENDA
    WidgetLayout.BOTH -> when {
        size.width >= 300.dp && size.width.value >= size.height.value * 1.3f -> Mode.SIDE_BY_SIDE
        size.height >= 300.dp -> Mode.STACKED
        else -> Mode.AGENDA
    }
}

@Composable
fun WidgetContent(state: WidgetUiState) {
    val context = LocalContext.current
    val size = LocalSize.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .background(backgroundColor(context, state.config.backgroundOpacity))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        if (!state.hasPermission) {
            PermissionPrompt()
        } else {
            val mode = resolveMode(state.config.layout, size)
            Header(state, showMonth = mode != Mode.AGENDA)
            when (mode) {
                Mode.MONTH -> MonthGrid(state, selectOnTap = false, GlanceModifier.fillMaxWidth().defaultWeight())
                Mode.AGENDA -> Agenda(state, GlanceModifier.fillMaxWidth().defaultWeight())
                Mode.SIDE_BY_SIDE -> Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    MonthGrid(state, selectOnTap = true, GlanceModifier.defaultWeight().fillMaxHeight())
                    Spacer(GlanceModifier.width(8.dp))
                    Agenda(state, GlanceModifier.defaultWeight().fillMaxHeight())
                }
                Mode.STACKED -> {
                    val gridHeight: Dp = (size.height - 44.dp) * 0.55f
                    MonthGrid(state, selectOnTap = true, GlanceModifier.fillMaxWidth().height(gridHeight))
                    Spacer(GlanceModifier.height(4.dp))
                    Agenda(state, GlanceModifier.fillMaxWidth().defaultWeight())
                }
            }
        }
    }
}

private fun backgroundColor(context: Context, opacityPercent: Int): ColorProvider {
    val alpha = opacityPercent.coerceIn(0, 100) / 100f
    val day = Color(context.getColor(android.R.color.system_neutral1_50)).copy(alpha = alpha)
    val night = Color(context.getColor(android.R.color.system_neutral1_900)).copy(alpha = alpha)
    return DayNightColorProvider(day = day, night = night)
}

@Composable
private fun PermissionPrompt() {
    val context = LocalContext.current
    Box(
        modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity(WidgetIntents.openApp(context))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = context.getString(R.string.widget_permission_needed),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, textAlign = TextAlign.Center),
        )
    }
}

// ---------------------------------------------------------------- header

@Composable
private fun Header(state: WidgetUiState, showMonth: Boolean) {
    val context = LocalContext.current
    val locale = Locale.getDefault()
    val title = if (showMonth) {
        state.month.format(DateTimeFormatter.ofPattern("yyyy. LLLL", locale))
    } else {
        (state.selectedDate ?: state.today).format(DateTimeFormatter.ofPattern("MMM d., EEEE", locale))
    }
    val focusDate = state.selectedDate ?: state.today
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(WidgetIntents.openCalendarAt(focusDate))),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold),
        )
        if (showMonth) {
            HeaderIcon(R.drawable.ic_chevron_left, R.string.action_prev, monthNav(-1))
            HeaderIcon(R.drawable.ic_chevron_right, R.string.action_next, monthNav(1))
        }
        if (state.monthOffset != 0 || state.selectedDate != null) {
            HeaderIcon(R.drawable.ic_today, R.string.widget_today, monthNav(0))
        }
        HeaderIcon(R.drawable.ic_add, R.string.action_add, actionStartActivity(WidgetIntents.newEvent(focusDate, state.today)))
        HeaderIcon(R.drawable.ic_refresh, R.string.action_refresh, actionRunCallback<RefreshAction>())
        HeaderIcon(
            R.drawable.ic_settings, R.string.action_settings,
            actionStartActivity(WidgetIntents.configure(context, state.appWidgetId)),
        )
    }
}

private fun monthNav(delta: Int): Action =
    actionRunCallback<MonthNavAction>(actionParametersOf(WidgetParams.MONTH_DELTA to delta))

@Composable
private fun HeaderIcon(@DrawableRes icon: Int, description: Int, onClick: Action) {
    val context = LocalContext.current
    Box(
        modifier = GlanceModifier.size(32.dp).cornerRadius(16.dp).clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = context.getString(description),
            modifier = GlanceModifier.size(20.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
        )
    }
}

// ---------------------------------------------------------------- month grid

@Composable
private fun MonthGrid(state: WidgetUiState, selectOnTap: Boolean, modifier: GlanceModifier) {
    val locale = Locale.getDefault()
    Column(modifier = modifier) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            MonthGridBuilder.weekDays(state.config.firstDayOfWeek).forEach { dow ->
                Text(
                    text = dow.getDisplayName(JavaTextStyle.SHORT_STANDALONE, locale).take(2),
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
        state.monthWeeks.forEach { week ->
            Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                week.forEach { cell ->
                    DayCell(cell, selectOnTap, GlanceModifier.defaultWeight().fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun DayCell(cell: MonthCell, selectOnTap: Boolean, modifier: GlanceModifier) {
    val onClick: Action = if (selectOnTap) {
        actionRunCallback<SelectDayAction>(actionParametersOf(WidgetParams.DATE to cell.date.toString()))
    } else {
        actionStartActivity(WidgetIntents.openCalendarAt(cell.date))
    }
    var cellModifier = modifier.clickable(onClick)
    if (cell.isSelected) {
        cellModifier = cellModifier.cornerRadius(8.dp).background(GlanceTheme.colors.secondaryContainer)
    }
    Column(
        modifier = cellModifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var numberModifier = GlanceModifier.size(22.dp)
        if (cell.isToday) numberModifier = numberModifier.cornerRadius(11.dp).background(GlanceTheme.colors.primary)
        Box(modifier = numberModifier, contentAlignment = Alignment.Center) {
            Text(
                text = cell.date.dayOfMonth.toString(),
                style = TextStyle(
                    color = when {
                        cell.isToday -> GlanceTheme.colors.onPrimary
                        cell.inMonth -> GlanceTheme.colors.onSurface
                        else -> GlanceTheme.colors.outline
                    },
                    fontSize = 12.sp,
                    fontWeight = if (cell.isToday) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                ),
            )
        }
        Row(modifier = GlanceModifier.height(5.dp), verticalAlignment = Alignment.CenterVertically) {
            cell.dotColors.forEachIndexed { index, color ->
                if (index > 0) Spacer(GlanceModifier.width(2.dp))
                Box(
                    modifier = GlanceModifier.size(4.dp).cornerRadius(2.dp).background(ColorProvider(Color(color))),
                ) {}
            }
        }
    }
}

// ---------------------------------------------------------------- agenda

@Composable
private fun Agenda(state: WidgetUiState, modifier: GlanceModifier) {
    val context = LocalContext.current
    LazyColumn(modifier = modifier) {
        state.agenda.forEach { day ->
            item(itemId = -(day.date.toEpochDay() * 2 + 10)) {
                DayHeader(day.date, state.today)
            }
            if (day.entries.isEmpty()) {
                item(itemId = -(day.date.toEpochDay() * 2 + 11)) {
                    Text(
                        text = context.getString(R.string.widget_no_events),
                        modifier = GlanceModifier.padding(vertical = 2.dp),
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                    )
                }
            }
            items(day.entries, itemId = { it.stableId }) { entry ->
                when (entry) {
                    is AgendaEntry.Event -> EventRow(entry, state.is24Hour)
                    is AgendaEntry.Task -> TaskRow(entry.task, entry.overdue)
                }
            }
        }
        if (state.tasksEnabled && state.undatedTasks.isNotEmpty()) {
            item(itemId = -1L) { TasksHeader() }
            items(state.undatedTasks, itemId = { it.stableId }) { task -> TaskRow(task, overdue = false) }
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate, today: LocalDate) {
    val context = LocalContext.current
    val formatted = date.format(DateTimeFormatter.ofPattern("MMM d., EEEE", Locale.getDefault()))
    val text = when (date) {
        today -> "${context.getString(R.string.widget_today)} · $formatted"
        today.plusDays(1) -> "${context.getString(R.string.widget_tomorrow)} · $formatted"
        else -> formatted
    }
    Text(
        text = text,
        maxLines = 1,
        modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)
            .clickable(actionStartActivity(WidgetIntents.openCalendarAt(date))),
        style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold),
    )
}

@Composable
private fun TasksHeader() {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = context.getString(R.string.widget_tasks),
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold),
        )
        HeaderIcon(R.drawable.ic_add, R.string.action_add, actionStartActivity(WidgetIntents.newTask(context)))
    }
}

@Composable
private fun EventRow(entry: AgendaEntry.Event, is24Hour: Boolean) {
    val context = LocalContext.current
    val event = entry.instance
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp)
            .clickable(actionStartActivity(WidgetIntents.openEvent(event))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier.width(4.dp).height(30.dp).cornerRadius(2.dp)
                .background(ColorProvider(Color(event.color))),
        ) {}
        Spacer(GlanceModifier.width(8.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = event.title.ifBlank { "–" },
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            )
            Text(
                text = eventSubtitle(context, entry, is24Hour),
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
            )
        }
    }
}

private fun eventSubtitle(context: Context, entry: AgendaEntry.Event, is24Hour: Boolean): String {
    val fmt = DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", Locale.getDefault())
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(entry.instance.begin).atZone(zone).format(fmt)
    val end = Instant.ofEpochMilli(entry.instance.end).atZone(zone).format(fmt)
    val time = when (entry.span) {
        DaySpan.ALL_DAY, DaySpan.CONTINUES -> context.getString(R.string.widget_all_day)
        DaySpan.SINGLE -> "$start – $end"
        DaySpan.STARTS -> "$start –"
        DaySpan.ENDS -> "– $end"
    }
    val location = entry.instance.location
    return if (location.isNullOrBlank()) time else "$time · $location"
}

@Composable
private fun TaskRow(task: TaskItem, overdue: Boolean) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckBox(
            checked = task.completed,
            onCheckedChange = actionRunCallback<ToggleTaskAction>(
                actionParametersOf(WidgetParams.TASK_ID to task.id, WidgetParams.COMPLETED to !task.completed)
            ),
        )
        Text(
            text = task.title.ifBlank { "–" },
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight().padding(vertical = 4.dp)
                .clickable(actionStartActivity(WidgetIntents.openTask(context, task.id))),
            style = TextStyle(
                color = if (task.completed) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface,
                fontSize = 13.sp,
                textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None,
            ),
        )
        if (overdue) {
            Text(
                text = context.getString(R.string.widget_overdue),
                style = TextStyle(color = GlanceTheme.colors.error, fontSize = 11.sp),
            )
        }
    }
}
