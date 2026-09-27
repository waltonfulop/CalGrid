package com.calgrid.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
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
     * When the agenda starts today, timed events that already ended are dropped.
     * Overdue open tasks are listed on [today].
     */
    fun build(
        events: List<EventInstance>,
        tasks: List<TaskItem>,
        from: LocalDate,
        days: Int,
        today: LocalDate,
        nowMillis: Long,
        zone: ZoneId,
    ): List<AgendaDay> {
        val until = from.plusDays(days.toLong())
        val byDay = sortedMapOf<LocalDate, MutableList<AgendaEntry>>()
        val hideEndedBefore = if (from == today) nowMillis else Long.MIN_VALUE

        for (event in events) {
            if (!event.allDay && event.end <= hideEndedBefore && event.end > event.begin) continue
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
                byDay.getOrPut(d) { mutableListOf() } +=
                    AgendaEntry.Event(event, span, stableId(event.eventId, event.begin, d))
                d = d.plusDays(1)
            }
        }

        for (task in tasks) {
            val due = task.due ?: continue
            val overdue = !task.completed && due.isBefore(today)
            val day = if (overdue) today else due
            if (day.isBefore(from) || !day.isBefore(until)) continue
            byDay.getOrPut(day) { mutableListOf() } +=
                AgendaEntry.Task(task, overdue, task.stableId)
        }

        byDay.getOrPut(from) { mutableListOf() }

        return byDay.map { (date, entries) -> AgendaDay(date, entries.sortedWith(entryOrder)) }
    }

    private val entryOrder = compareBy<AgendaEntry>(
        {
            when (it) {
                is AgendaEntry.Task -> 0
                is AgendaEntry.Event -> if (it.span == DaySpan.ALL_DAY || it.span == DaySpan.CONTINUES) 1 else 2
            }
        },
        { (it as? AgendaEntry.Event)?.instance?.begin ?: 0L },
        { (it as? AgendaEntry.Event)?.instance?.title ?: (it as AgendaEntry.Task).task.title },
    )

    private fun stableId(eventId: Long, begin: Long, day: LocalDate): Long =
        ((eventId * 31 + begin) * 31 + day.toEpochDay()) shl 1

    /** Tasks without a due date, open ones first. */
    fun undated(tasks: List<TaskItem>): List<TaskItem> =
        tasks.filter { it.due == null }.sortedBy { it.completed }
}

object MonthGridBuilder {

    /** Always 6 weeks x 7 days, starting on [firstDayOfWeek]. */
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
        val end = start.plusDays(42)
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
        return (0 until 6).map { week ->
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

    /** Weekday order for the header row. */
    fun weekDays(firstDayOfWeek: DayOfWeek): List<DayOfWeek> =
        (0L until 7L).map { firstDayOfWeek.plus(it) }
}
