package dev.resticky

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class AgendaTest {
    private val tz = TimeZone.of("Europe/Berlin")
    private fun at(day: Int, h: Int, m: Int = 0): Instant = LocalDateTime(2026, 11, day, h, m).toInstant(tz)
    private fun utcDay(day: Int): Instant = LocalDateTime(2026, 11, day, 0, 0).toInstant(TimeZone.UTC)
    private val now = at(10, 9, 30)

    @Test
    fun eventsAreMergedSortedAndCut() {
        val events = listOf(
            CalendarEvent("Lunch", at(10, 12), at(10, 13), false),
            CalendarEvent("Standup", at(10, 10), at(10, 10, 15), false),
            CalendarEvent("Early call", at(10, 7), at(10, 8), false),
            CalendarEvent("Holiday", utcDay(11), utcDay(12), true),
            CalendarEvent("Next week", at(17, 9), at(17, 10), false),
            CalendarEvent("Dentist", at(11, 8), at(11, 9), false),
            CalendarEvent("x".repeat(60), at(10, 18), at(10, 19), false),
        )
        val a = buildAgenda(events, true, now, tz)
        assertEquals("2026-11-10", a.today)
        assertEquals(
            listOf(
                AgendaEvent(0, "10:00", "10:15", "Standup"),
                AgendaEvent(0, "12:00", "13:00", "Lunch"),
                AgendaEvent(0, "18:00", "19:00", "x".repeat(48)),
                AgendaEvent(1, "", "", "Holiday"),
                AgendaEvent(1, "08:00", "09:00", "Dentist"),
            ),
            a.events,
        )
    }

    @Test
    fun eventOverMidnightShowsOnBothDays() {
        val a = buildAgenda(listOf(CalendarEvent("Night shift", at(10, 22), at(11, 6), false)), true, now, tz)
        assertEquals(listOf(AgendaEvent(0, "22:00", "24:00", "Night shift"), AgendaEvent(1, "00:00", "06:00", "Night shift")), a.events)
    }

    @Test
    fun switchedOffSendsNoEvents() {
        val a = buildAgenda(listOf(CalendarEvent("Lunch", at(10, 12), at(10, 13), false)), false, now, tz)
        assertEquals(Agenda("2026-11-10", false, emptyList()), a)
    }

    @Test
    fun atMostTwelveEventsAndTwoKilobytes() {
        val many = (0 until 20).map { CalendarEvent("會議".repeat(30), at(10, 10, it), at(10, 11), false) }
        val a = buildAgenda(many, true, now, tz)
        assertTrue(a.events.size <= AGENDA_MAX_EVENTS)
        assertTrue(hubJson.encodeToString(a).encodeToByteArray().size <= MAX_DATA_PAYLOAD)
        assertEquals(48, a.events[0].title.length)
        val short = buildAgenda(many.map { CalendarEvent("Meeting", it.begin, it.end, false) }, true, now, tz)
        assertEquals(AGENDA_MAX_EVENTS, short.events.size)
    }
}
