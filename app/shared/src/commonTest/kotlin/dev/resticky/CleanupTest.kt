package dev.resticky

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CleanupTest {
    private fun words(text: String) = text.split(' ').mapIndexed { i, t -> Word(i * 100L, i * 100L + 90, t) }

    @Test
    fun soundLabelsAreRemoved() {
        assertEquals("Yes. All right.", cleanTranscript("[ Silence ] >> Yes. [ Silence ] >> All right. [ Inaudible Remark ]"))
        assertEquals("", cleanTranscript("(keyboard clicking) [typing] [typing] . . . . . ♪"))
        assertEquals("Can you go back?", cleanTranscript(">> [inaudible] >> Can you go back? (door closes)"))
    }

    @Test
    fun loopsKeepTwoCopies() {
        val text = "I'm going to take a look. I'm going to take a look. I'm going to take a look. I'm going to take a look. Okay."
        assertEquals("I'm going to take a look. I'm going to take a look. Okay.", cleanTranscript(text))
        assertEquals("As is? As is? This is it.", cleanTranscript("As is? As is? As is? As is? This is it."))
        assertEquals("very very good", cleanTranscript("very very very very good"))
    }

    @Test
    fun segmentWordsKeepTheirTimes() {
        val s = Segment(0, 2000, ">> [inaudible] >> Yeah. Sure.", words = words(">> [inaudible] >> Yeah. Sure."))
        val out = cleanSegments(listOf(s)).single()
        assertEquals("Yeah. Sure.", out.text)
        assertEquals(listOf(300L, 400L), out.words.map { it.startMs })
        assertEquals(emptyList(), cleanSegments(listOf(Segment(0, 1000, "[ Silence ]", words = words("[ Silence ]")))))
    }

    @Test
    fun speechUnitsCountWordsAndChineseCharacters() {
        assertEquals(3, speechUnits("Ship it Friday"))
        assertEquals(5, speechUnits("我哋聽日 ship"))
        assertEquals(0, speechUnits(". . ."))
    }

    @Test
    fun todosNeedAQuoteFromTheTranscript() {
        val transcript = "Okay so Sam, can you send the billing report on Friday? Sure, I will send it."
        val raw = """{"title":"Billing report","summary":"Sam sends the report.","todos":[
            {"task":"Send the billing report on Friday (Sam)","quote":"can you send the billing report on Friday"},
            {"task":"Review the perverts issues","quote":"resolve the perverts issues"},
            {"task":"Call Alex","quote":""}]}"""
        assertEquals(listOf("Send the billing report on Friday (Sam)"), parseSummary(raw, transcript).todos)
        assertTrue(quoteFound("Can you send the billing report, on Friday?", transcript))
        assertFalse(quoteFound("ok", transcript))
    }

    @Test
    fun noSpeechTitleDropsEverything() {
        val raw = """{"title":"No speech","summary":"Crowd cheering.","todos":[{"task":"Blow the whistle","quote":"whistle"}]}"""
        assertEquals(Summary(NO_SPEECH, ""), parseSummary(raw, "whistle whistle"))
    }

    @Test
    fun promptAsksForQuotesAndMixedLanguages() {
        val p = summaryPrompt(SummaryContext())
        assertTrue("quote" in p && "Cantonese" in p && NO_SPEECH in p)
    }
}
