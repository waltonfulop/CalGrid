package com.calgrid.model

import java.time.LocalDate

/** One occurrence of a calendar event. For all-day events [begin]/[end] are UTC midnights. */
data class EventInstance(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int,
    val location: String?,
)

/** A task as the widget and the agenda builder see it. */
data class TaskItem(
    val id: String,
    val listId: String,
    val title: String,
    val due: LocalDate?,
    val completed: Boolean,
) {
    /** Id for widget list items; odd, so it never collides with event entry ids. */
    val stableId: Long get() = (id.hashCode().toLong() shl 1) or 1L
}

sealed interface AgendaEntry {
    val stableId: Long

    data class Event(
        val instance: EventInstance,
        /** Which part of a multi-day event falls on this day. */
        val span: DaySpan,
        override val stableId: Long,
        /** Already over; only set for today's timed entries. */
        val past: Boolean = false,
        /** Elapsed fraction (0..1) while the event is running today; never set for all-day entries. */
        val progress: Float? = null,
    ) : AgendaEntry

    data class Task(
        val task: TaskItem,
        val overdue: Boolean,
        override val stableId: Long,
    ) : AgendaEntry

    /** Marks the current time among today's timed events. */
    data object NowLine : AgendaEntry {
        override val stableId: Long = -2L
    }
}

enum class DaySpan { ALL_DAY, SINGLE, STARTS, CONTINUES, ENDS }

data class AgendaDay(val date: LocalDate, val entries: List<AgendaEntry>)

data class MonthCell(
    val date: LocalDate,
    val inMonth: Boolean,
    val isToday: Boolean,
    val isSelected: Boolean,
    val dotColors: List<Int>,
)
