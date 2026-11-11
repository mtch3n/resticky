package dev.resticky.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import dev.resticky.Agenda
import dev.resticky.CalendarEvent
import dev.resticky.agendaWindow
import dev.resticky.buildAgenda
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

data class CalendarInfo(val id: Long, val account: String, val name: String)

data class CalendarChoice(val show: Boolean = true, val selected: Set<Long> = emptySet())

class Calendars(private val context: Context) {
    private val prefs = context.getSharedPreferences("calendars", Context.MODE_PRIVATE)

    val choice = MutableStateFlow(
        CalendarChoice(prefs.getBoolean("show", true), prefs.getStringSet("selected", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()),
    )

    val allowed: Boolean get() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun set(c: CalendarChoice) {
        prefs.edit().putBoolean("show", c.show).putStringSet("selected", c.selected.map { "$it" }.toSet()).apply()
        choice.value = c
    }

    fun list(): List<CalendarInfo> {
        if (!allowed) return emptyList()
        val cols = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
        val out = mutableListOf<CalendarInfo>()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, cols, "${CalendarContract.Calendars.VISIBLE} = 1", null, null)?.use { c ->
            while (c.moveToNext()) out += CalendarInfo(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty())
        }
        return out.sortedWith(compareBy({ it.account }, { it.name }))
    }

    fun agenda(): Agenda {
        val now = Clock.System.now()
        val tz = TimeZone.currentSystemDefault()
        val c = choice.value
        val ids = c.selected
        if (!c.show || !allowed || ids.isEmpty()) return buildAgenda(emptyList(), c.show, now, tz)
        val (from, to) = now.toLocalDateTime(tz).date.agendaWindow(tz)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.toEpochMilliseconds() - 86_400_000L)
            ContentUris.appendId(it, to.toEpochMilliseconds() + 86_400_000L)
        }.build()
        val cols = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY)
        val where = "${CalendarContract.Instances.CALENDAR_ID} IN (${ids.joinToString(",")})"
        val events = mutableListOf<CalendarEvent>()
        context.contentResolver.query(uri, cols, where, null, null)?.use { cur ->
            while (cur.moveToNext()) events += CalendarEvent(
                cur.getString(0).orEmpty(),
                Instant.fromEpochMilliseconds(cur.getLong(1)),
                Instant.fromEpochMilliseconds(cur.getLong(2)),
                cur.getInt(3) == 1,
            )
        }
        return buildAgenda(events, true, now, tz)
    }
}
