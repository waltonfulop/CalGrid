package com.calgrid.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

class AgendaBuilderTest {

    private val zone = ZoneId.of("Europe/Budapest")
    private val today = LocalDate.of(2026, 9, 27)

    private fun millis(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()
    private fun utcMidnight(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun timed(id: Long, start: LocalDateTime, end: LocalDateTime) =
        EventInstance(id, 1, "e$id", millis(start), millis(end), allDay = false, color = 0, location = null)

    private fun allDay(id: Long, first: LocalDate, lastInclusive: LocalDate) =
        EventInstance(id, 1, "a$id", utcMidnight(first), utcMidnight(lastInclusive.plusDays(1)), allDay = true, color = 0, location = null)

    private fun build(events: List<EventInstance>, tasks: List<TaskItem> = emptyList(), from: LocalDate = today, now: LocalDateTime = today.atTime(8, 0)) =
        AgendaBuilder.build(events, tasks, from, days = 7, today = today, nowMillis = millis(now), zone = zone)

    @Test
    fun allDayEventStaysOnItsDateInAnyZone() {
        val event = allDay(1, today.plusDays(1), today.plusDays(1))
        for (z in listOf("America/Los_Angeles", "Europe/Budapest", "Pacific/Auckland")) {
            assertEquals(today.plusDays(1)..today.plusDays(1), event.dates(ZoneId.of(z)))
        }
    }

    @Test
    fun multiDayTimedEventIsSplitIntoSpans() {
        val event = timed(1, today.atTime(22, 0), today.plusDays(2).atTime(10, 0))
        val days = build(listOf(event))
        val spans = days.flatMap { d -> d.entries.filterIsInstance<AgendaEntry.Event>().map { d.date to it.span } }
        assertEquals(
            listOf(today to DaySpan.STARTS, today.plusDays(1) to DaySpan.CONTINUES, today.plusDays(2) to DaySpan.ENDS),
            spans,
        )
    }

    @Test
    fun eventEndingAtMidnightDoesNotSpillIntoNextDay() {
        val event = timed(1, today.atTime(20, 0), today.plusDays(1).atStartOfDay())
        assertEquals(today..today, event.dates(zone))
    }

    @Test
    fun todaysEndedEventsStayAndAreMarkedPast() {
        val past = timed(1, today.atTime(7, 0), today.atTime(7, 30))
        val tomorrow = timed(2, today.plusDays(1).atTime(7, 0), today.plusDays(1).atTime(7, 30))
        val days = build(listOf(past, tomorrow))
        val todayEvent = days.single { it.date == today }.entries.filterIsInstance<AgendaEntry.Event>().single()
        assertTrue(todayEvent.past)
        val tomorrowEvent = days.single { it.date == today.plusDays(1) }.entries.single() as AgendaEntry.Event
        assertFalse(tomorrowEvent.past)
    }

    @Test
    fun allDayEventsAreNeverPastAndHaveNoProgress() {
        val entries = build(listOf(allDay(1, today, today)), now = today.atTime(23, 0)).single().entries
        val event = entries.single() as AgendaEntry.Event
        assertFalse(event.past)
        assertNull(event.progress)
    }

    @Test
    fun runningEventHasProgress() {
        val running = timed(1, today.atTime(7, 0), today.atTime(9, 0))
        val event = build(listOf(running)).single().entries.filterIsInstance<AgendaEntry.Event>().single()
        assertEquals(0.5f, event.progress!!, 0.001f)
        assertFalse(event.past)
    }

    @Test
    fun nowLineFollowsStartedEventsAndAllDayComesFirst() {
        val ended = timed(1, today.atTime(6, 0), today.atTime(7, 0))
        val running = timed(2, today.atTime(7, 30), today.atTime(9, 0))
        val later = timed(3, today.atTime(10, 0), today.atTime(11, 0))
        val wholeDay = allDay(4, today, today)
        val task = TaskItem("t", "l", "task", today, completed = false)
        val entries = build(listOf(later, running, ended, wholeDay), listOf(task)).single().entries
        val labels = entries.map {
            when (it) {
                is AgendaEntry.Event -> it.instance.title
                is AgendaEntry.Task -> it.task.title
                AgendaEntry.NowLine -> "now"
            }
        }
        assertEquals(listOf("a4", "task", "e1", "e2", "now", "e3"), labels)
    }

    @Test
    fun noNowLineWithoutTimedEventsOrOnOtherDays() {
        assertTrue(build(listOf(allDay(1, today, today))).single().entries.none { it is AgendaEntry.NowLine })
        val tomorrow = timed(2, today.plusDays(1).atTime(9, 0), today.plusDays(1).atTime(10, 0))
        val days = build(listOf(tomorrow))
        assertTrue(days.flatMap { it.entries }.none { it is AgendaEntry.NowLine })
    }

    @Test
    fun startDayIsAlwaysPresentAndEmptyDaysAreSkipped() {
        val later = timed(1, today.plusDays(3).atTime(9, 0), today.plusDays(3).atTime(10, 0))
        assertEquals(listOf(today, today.plusDays(3)), build(listOf(later)).map { it.date })
    }

    @Test
    fun overdueTasksAreListedOnTodayAndFirst() {
        val overdue = TaskItem("t1", "l", "old", today.minusDays(2), completed = false)
        val doneOld = TaskItem("t2", "l", "done", today.minusDays(2), completed = true)
        val event = timed(1, today.atTime(9, 0), today.atTime(10, 0))
        val todayEntries = build(listOf(event), listOf(overdue, doneOld)).single { it.date == today }.entries
        assertEquals(1, todayEntries.count { it is AgendaEntry.Task })
        assertTrue((todayEntries.first() as AgendaEntry.Task).overdue)
    }

    @Test
    fun allDayEntriesSortBeforeTimedOnes() {
        val timedEvent = timed(1, today.atTime(9, 0), today.atTime(10, 0))
        val allDayEvent = allDay(2, today, today)
        val entries = build(listOf(timedEvent, allDayEvent)).single().entries
        assertEquals(listOf(2L, 1L), entries.filterIsInstance<AgendaEntry.Event>().map { it.instance.eventId })
    }

    @Test
    fun monthGridHasFiveRowsUnlessTheMonthNeedsSix() {
        fun rows(y: Int, m: Int, first: DayOfWeek) =
            MonthGridBuilder.build(YearMonth.of(y, m), first, today, null, emptyList(), zone).size
        assertEquals(5, rows(2026, 9, DayOfWeek.MONDAY))
        assertEquals(5, rows(2027, 2, DayOfWeek.MONDAY)) // exactly 4 weeks
        assertEquals(6, rows(2026, 8, DayOfWeek.MONDAY)) // Aug 1 is Saturday, Aug 31 Monday
        val aug = MonthGridBuilder.build(YearMonth.of(2026, 8), DayOfWeek.MONDAY, today, null, emptyList(), zone)
        assertEquals(LocalDate.of(2026, 8, 31), aug.last().first().date)
    }

    @Test
    fun monthGridStartsOnConfiguredWeekday() {
        val grid = MonthGridBuilder.build(YearMonth.of(2026, 9), DayOfWeek.MONDAY, today, null, emptyList(), zone)
        assertEquals(5, grid.size)
        assertTrue(grid.all { it.size == 7 })
        assertEquals(LocalDate.of(2026, 8, 31), grid[0][0].date)

        val sundayGrid = MonthGridBuilder.build(YearMonth.of(2026, 9), DayOfWeek.SUNDAY, today, null, emptyList(), zone)
        assertEquals(LocalDate.of(2026, 8, 30), sundayGrid[0][0].date)
    }

    @Test
    fun monthGridHandlesLeapFebruary() {
        val grid = MonthGridBuilder.build(YearMonth.of(2028, 2), DayOfWeek.MONDAY, today, null, emptyList(), zone)
        val inMonth = grid.flatten().filter { it.inMonth }
        assertEquals(29, inMonth.size)
        assertEquals(LocalDate.of(2028, 2, 29), inMonth.last().date)
    }

    @Test
    fun monthGridDotsCoverMultiDayEventsAndMarkToday() {
        val event = allDay(1, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 12)).copy(color = 42)
        val cells = MonthGridBuilder.build(YearMonth.of(2026, 9), DayOfWeek.MONDAY, today, null, listOf(event), zone).flatten()
        val dotted = cells.filter { it.dotColors.isNotEmpty() }.map { it.date.dayOfMonth }
        assertEquals(listOf(10, 11, 12), dotted)
        assertEquals(today, cells.single { it.isToday }.date)
    }
}
