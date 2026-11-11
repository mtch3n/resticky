package dev.resticky

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PhotosTest {
    private val t0 = Instant.fromEpochSeconds(1_794_000_000)

    private fun session(id: String, start: Instant, minutes: Int) = Session(
        "d", id, start, start + minutes.minutes, minutes * 60_000L,
        chunks = (0 until minutes * 2).associate { "$it" to "x" }.toMutableMap(),
    )

    @Test
    fun liveSessionWinsThenTheNearestWithinTenMinutes() {
        val a = session("a", t0, 30)
        val b = session("b", t0 + 60.minutes, 10)
        assertEquals("a", linkPhoto(listOf(a, b), t0 + 12.minutes)?.id)
        assertEquals("a", linkPhoto(listOf(a, b), t0 + 32.minutes)?.id)
        assertEquals("a", linkPhoto(listOf(a, b), t0 + 39.minutes)?.id)
        assertEquals("b", linkPhoto(listOf(a, b), t0 + 52.minutes)?.id)
        assertNull(linkPhoto(listOf(a, b), t0 + 45.minutes))
        assertNull(linkPhoto(listOf(a, b), t0 - 11.minutes))
    }

    @Test
    fun offsetIsTheTimeSinceTheStartInsideTheSession() {
        val a = session("a", t0, 30)
        assertEquals(12 * 60_000L + 5000, photoOffset(a, t0 + 12.minutes + 5.seconds))
        assertEquals(0L, photoOffset(a, t0 - 3.minutes))
        assertEquals(30 * 60_000L, photoOffset(a, t0 + 40.minutes))
    }

    @Test
    fun photoTextGoesToTheSummary() {
        val a = session("a", t0, 30)
        a.photos += Photo(90_000, "photos/1.jpg", "Restore test env\nDNS Friday", 1)
        a.photos += Photo(100_000, "photos/2.jpg", "", 2)
        assertEquals(listOf("01:30 Restore test env DNS Friday"), a.photoTexts())
        val p = summaryPrompt(SummaryContext(photos = a.photoTexts()))
        assertEquals(true, "- 01:30 Restore test env DNS Friday" in p)
        assertEquals(1, transcriptItems(listOf(a.apply { photos.removeLast() })).single().photos)
    }
}
