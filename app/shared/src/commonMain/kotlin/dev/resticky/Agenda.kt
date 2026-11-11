package dev.resticky

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

const val AGENDA_MAX_EVENTS = 12
const val AGENDA_TITLE_MAX = 48

class CalendarEvent(val title: String, val begin: Instant, val end: Instant, val allDay: Boolean)

@Serializable
data class AgendaEvent(val day: Int, val start: String, val end: String, val title: String)

@Serializable
data class Agenda(val today: String, val show: Boolean, val events: List<AgendaEvent>)

private val hhmm = LocalDateTime.Format { hour(); char(':'); minute() }

private fun cut(title: String): String {
    val cps = title.trim().codePointList()
    return if (cps.size <= AGENDA_TITLE_MAX) title.trim() else cps.take(AGENDA_TITLE_MAX).joinToString("") { it.codePointString() }
}

fun buildAgenda(events: List<CalendarEvent>, show: Boolean, now: Instant, tz: TimeZone): Agenda {
    val today = now.toLocalDateTime(tz).date
    if (!show) return Agenda(today.toString(), false, emptyList())
    val days = listOf(today, today.plus(DatePeriod(days = 1)))
    val out = mutableListOf<AgendaEvent>()
    for (e in events) days.forEachIndexed { d, date ->
        val title = cut(e.title).ifEmpty { "Busy" }
        if (e.allDay) {
            val first = e.begin.toLocalDateTime(TimeZone.UTC).date
            val last = e.end.toLocalDateTime(TimeZone.UTC).date
            if (date >= first && (date < last || first == last && date == first)) out += AgendaEvent(d, "", "", title)
            return@forEachIndexed
        }
        val start = date.atStartOfDayIn(tz)
        val next = date.plus(DatePeriod(days = 1)).atStartOfDayIn(tz)
        val end = if (e.end > e.begin) e.end else e.begin + 1.minutes
        if (end <= now || e.begin >= next || end <= start) return@forEachIndexed
        val from = if (e.begin < start) "00:00" else e.begin.toLocalDateTime(tz).format(hhmm)
        val to = if (e.end >= next) "24:00" else maxOf(e.end, e.begin).toLocalDateTime(tz).format(hhmm)
        out += AgendaEvent(d, from, to, title)
    }
    val sorted = out.sortedWith(compareBy({ it.day }, { it.start }, { it.end }, { it.title })).take(AGENDA_MAX_EVENTS).toMutableList()
    while (sorted.isNotEmpty() && hubJson.encodeToString(Agenda(today.toString(), true, sorted)).encodeToByteArray().size > MAX_DATA_PAYLOAD) {
        sorted.removeAt(sorted.lastIndex)
    }
    return Agenda(today.toString(), true, sorted)
}

fun LocalDate.agendaWindow(tz: TimeZone): Pair<Instant, Instant> = atStartOfDayIn(tz) to plus(DatePeriod(days = 2)).atStartOfDayIn(tz)
