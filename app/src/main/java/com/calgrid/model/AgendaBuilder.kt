package com.calgrid.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** The local dates an event instance covers (inclusive range). */
fun EventInstance.dates(zone: ZoneId): ClosedRange<LocalDate> {
    return if (allDay) {
        val start = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
        val endExclusive = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate()
        start..maxOf(start, endExclusive.minusDays(1))
    } else {
        val start = Instant.ofEpochMilli(begin).atZone(zone).toLocalDate()
        val last = if (end > begin) Instant.ofEpochMilli(end - 1).atZone(zone).toLocalDate() else start
        start..maxOf(start, last)
    }
}

object AgendaBuilder {

    /**
     * Groups events and dated tasks into days starting at [from] for [days] days.
     * Days without entries are skipped, except [from], which is always present.
     * Each day lists undated tasks first, then all-day events, dated tasks, and timed events by start.
     * On [today] timed events that already ended are marked [AgendaEntry.Event.past], running ones
     * carry a [AgendaEntry.Event.progress], and a [AgendaEntry.NowLine] follows the events that already started.
     * Overdue open tasks, and with [includeUndated] tasks without a due date, are listed on [today]
     * (when today is in range), so they carry over from day to day.
     */
    fun build(
        events: List<EventInstance>,
        tasks: List<TaskItem>,
        from: LocalDate,
        days: Int,
        today: LocalDate,
        nowMillis: Long,
        zone: ZoneId,
        includeUndated: Boolean = true,
    ): List<AgendaDay> {
        val until = from.plusDays(days.toLong())
        val byDay = sortedMapOf<LocalDate, MutableList<AgendaEntry>>()

        for (event in events) {
            val range = event.dates(zone)
            var d = maxOf(range.start, from)
            val last = minOf(range.endInclusive, until.minusDays(1))
            while (!d.isAfter(last)) {
                val span = when {
                    event.allDay -> DaySpan.ALL_DAY
                    range.start == range.endInclusive -> DaySpan.SINGLE
                    d == range.start -> DaySpan.STARTS
                    d == range.endInclusive -> DaySpan.ENDS
                    else -> DaySpan.CONTINUES
                }
                val timedToday = d == today && !span.isAllDayLike
                val running = timedToday && event.begin <= nowMillis && nowMillis < event.end
                byDay.getOrPut(d) { mutableListOf() } += AgendaEntry.Event(
                    instance = event,
                    span = span,
                    stableId = stableId(event.eventId, event.begin, d),
                    past = timedToday && event.end <= nowMillis,
                    progress = if (running) {
                        ((nowMillis - event.begin).toFloat() / (event.end - event.begin)).coerceIn(0f, 1f)
                    } else {
                        null
                    },
                )
                d = d.plusDays(1)
            }
        }

        val undatedDay = today.takeIf { includeUndated && !it.isBefore(from) && it.isBefore(until) }
        for (task in tasks) {
            val due = task.due
            val overdue = due != null && !task.completed && due.isBefore(today)
            val day = when {
                due == null -> undatedDay ?: continue
                overdue -> today
                else -> due
            }
            if (day.isBefore(from) || !day.isBefore(until)) continue
            byDay.getOrPut(day) { mutableListOf() } +=
                AgendaEntry.Task(task, overdue, task.stableId)
        }

        byDay.getOrPut(from) { mutableListOf() }

        return byDay.map { (date, entries) ->
            val sorted = entries.sortedWith(entryOrder)
            AgendaDay(date, if (date == today) withNowLine(sorted, nowMillis) else sorted)
        }
    }

    /** Inserts the now marker after the timed events that already started; no marker without timed events. */
    private fun withNowLine(sorted: List<AgendaEntry>, nowMillis: Long): List<AgendaEntry> {
        if (sorted.none { it.isTimed }) return sorted
        val index = sorted.indexOfFirst { it.isTimed && (it as AgendaEntry.Event).instance.begin > nowMillis }
            .takeIf { it >= 0 } ?: sorted.size
        return sorted.toMutableList().apply { add(index, AgendaEntry.NowLine) }
    }

    private val DaySpan.isAllDayLike get() = this == DaySpan.ALL_DAY || this == DaySpan.CONTINUES

    private val AgendaEntry.isTimed get() = this is AgendaEntry.Event && !span.isAllDayLike

    private val entryOrder = compareBy<AgendaEntry>(
        {
            when (it) {
                is AgendaEntry.Task -> if (it.task.due == null) 0 else 2
                is AgendaEntry.Event -> if (it.span.isAllDayLike) 1 else 3
                AgendaEntry.NowLine -> 4
            }
        },
        { (it as? AgendaEntry.Task)?.task?.completed ?: false },
        { (it as? AgendaEntry.Event)?.instance?.begin ?: 0L },
        { (it as? AgendaEntry.Event)?.instance?.title ?: (it as? AgendaEntry.Task)?.task?.title },
    )

    private fun stableId(eventId: Long, begin: Long, day: LocalDate): Long =
        ((eventId * 31 + begin) * 31 + day.toEpochDay()) shl 1
}

object MonthGridBuilder {

    /**
     * 5 weeks x 7 days starting on [firstDayOfWeek]; 6 weeks only when the month's last days
     * would not fit otherwise.
     */
    fun build(
        month: YearMonth,
        firstDayOfWeek: DayOfWeek,
        today: LocalDate,
        selected: LocalDate?,
        events: List<EventInstance>,
        zone: ZoneId,
        maxDots: Int = 3,
    ): List<List<MonthCell>> {
        val start = gridStart(month, firstDayOfWeek)
        val weeks = weekCount(month, firstDayOfWeek)
        val end = start.plusDays(weeks * 7L)
        val colorsByDay = HashMap<LocalDate, LinkedHashSet<Int>>()
        for (event in events) {
            val range = event.dates(zone)
            var d = maxOf(range.start, start)
            val last = minOf(range.endInclusive, end.minusDays(1))
            while (!d.isAfter(last)) {
                colorsByDay.getOrPut(d) { LinkedHashSet() } += event.color
                d = d.plusDays(1)
            }
        }
        return (0 until weeks).map { week ->
            (0 until 7).map { dow ->
                val date = start.plusDays((week * 7 + dow).toLong())
                MonthCell(
                    date = date,
                    inMonth = YearMonth.from(date) == month,
                    isToday = date == today,
                    isSelected = date == selected,
                    dotColors = colorsByDay[date]?.take(maxDots).orEmpty(),
                )
            }
        }
    }

    fun gridStart(month: YearMonth, firstDayOfWeek: DayOfWeek): LocalDate =
        month.atDay(1).with(TemporalAdjusters.previousOrSame(firstDayOfWeek))

    fun weekCount(month: YearMonth, firstDayOfWeek: DayOfWeek): Int {
        val leading = ChronoUnit.DAYS.between(gridStart(month, firstDayOfWeek), month.atDay(1)).toInt()
        return maxOf(5, (leading + month.lengthOfMonth() + 6) / 7)
    }

    /** Weekday order for the header row. */
    fun weekDays(firstDayOfWeek: DayOfWeek): List<DayOfWeek> =
        (0L until 7L).map { firstDayOfWeek.plus(it) }
}
