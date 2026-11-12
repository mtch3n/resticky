package dev.resticky

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
        assertEquals(listOf(Todo("Send the billing report on Friday (Sam)", "can you send the billing report on Friday")), parseSummary(raw, transcript).todos)
        assertEquals("can you send the billing report on Friday", quoteSpan("Can you send the billing report, on Friday?", transcript))
        assertNull(quoteSpan("ok", transcript))
    }

    @Test
    fun courtesyLabelsAndShortQuotesAreNotTodos() {
        val transcript = "Thank you. Speaker D said we should figure out what to say to the coach. 谢谢 好的 ok bye"
        val raw = """{"title":"Coach","summary":"S.","todos":[
            {"task":"Thank you","quote":"Thank you"},
            {"task":"thank","quote":"Thank you"},
            {"task":"谢谢","quote":"谢谢 好的"},
            {"task":"Speaker D: Figure out what to say.","quote":"figure out what to say to the coach"},
            {"task":"Speaker A: Figure out what to say.","quote":"figure out what to say to the coach"},
            {"task":"Say bye","quote":"ok bye"}]}"""
        assertEquals(listOf("Figure out what to say."), parseSummary(raw, transcript).todos.map { it.task })
    }

    @Test
    fun summaryCutOffInSpeakersKeepsTitleSummaryAndTodos() {
        val transcript = "Sam, can you send the billing report on Friday?"
        val raw = """{"title":"Billing","summary":"Sam sends it.","todos":[{"task":"Send the billing report","quote":"send the billing report on Friday"}],
            "speakers":[{"speaker":"Speaker A","name":"Sam","evidence":"Sam, can"},{"speaker":"Speaker B","name":"""
        val s = parseSummary(raw, transcript)
        assertEquals(Summary("Billing", "Sam sends it.", listOf(Todo("Send the billing report", "send the billing report on Friday"))), s)
    }

    @Test
    fun quoteWordsMustComeInOrder() {
        val transcript = "the report is late. Friday we send nothing. Can you please send the full billing report on Friday morning?"
        assertNull(quoteSpan("send the report on Friday we", "we send the report on Friday"))
        assertNull(quoteSpan("Friday send the report", transcript))
        assertEquals("send the full billing report on Friday", quoteSpan("send the billing report on Friday", transcript))
        assertEquals("Can you please send the full billing report on", quoteSpan("can you please send the full billing report on Fryday", transcript))
        assertNull(quoteSpan("send the full billing report on Friday mornin", transcript))
        assertNull(quoteSpan("send report", "send a very long and winding report"))
    }

    @Test
    fun chineseQuotesMatchByCharacter() {
        assertEquals("聽日交報告", quoteSpan("聽日交報告", "我哋聽日交報告 ok"))
        assertNull(quoteSpan("報告交聽日", "我哋聽日交報告 ok"))
    }

    @Test
    fun todoWithANegationMismatchIsDropped() {
        val transcript = "Please do not send the invoice to Alex yet. We won't call the bank. Sam, book the room for Monday."
        val raw = """{"title":"Plans","summary":"S.","todos":[
            {"task":"Send the invoice to Alex","quote":"do not send the invoice to Alex"},
            {"task":"Don't call the bank","quote":"We won't call the bank"},
            {"task":"Book the room for Monday","quote":"book the room for Monday"},
            {"task":"Do not book the room","quote":"book the room for Monday"}]}"""
        assertEquals(listOf("Don't call the bank", "Book the room for Monday"), parseSummary(raw, transcript).todos.map { it.task })
        assertTrue(negated("We can’t do it") && negated("do not") && !negated("notes and knots"))
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
