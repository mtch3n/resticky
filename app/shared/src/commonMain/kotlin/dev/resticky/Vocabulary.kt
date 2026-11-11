package dev.resticky

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

const val VOCABULARY_TERMS = 300
const val PROMPT_COST = 400

@Serializable
enum class TermSource {
    @SerialName("user") User,
    @SerialName("correction") Correction,
    @SerialName("calendar") Calendar,
    @SerialName("people") People,
}

@Serializable
data class Term(val text: String, val source: TermSource, val count: Int = 1, @SerialName("last_used") val lastUsed: Time)

@Serializable
data class Rule(val from: String, val to: String)

@Serializable
data class VocabularyData(val terms: List<Term> = emptyList(), val rules: List<Rule> = emptyList())

private fun same(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

private fun isCjk(c: Char) = c in '㐀'..'鿿' || c in '豈'..'﫿'

private fun wordEdge(c: Char) = c.isLetterOrDigit() && !isCjk(c)

fun ruleRegex(from: String): Regex? {
    val parts = from.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val body = parts.joinToString("\\s+") { Regex.escape(it) }
    val head = if (wordEdge(parts.first().first())) "(?<![\\p{L}\\p{N}])" else ""
    val tail = if (wordEdge(parts.last().last())) "(?![\\p{L}\\p{N}])" else ""
    return Regex(head + body + tail, RegexOption.IGNORE_CASE)
}

class Applied<T>(val value: T, val count: Int)

fun applyRules(text: String, rules: List<Rule>): Applied<String> {
    var out = text
    var count = 0
    for (r in rules) {
        val re = ruleRegex(r.from) ?: continue
        out = re.replace(out) {
            count++
            r.to
        }
    }
    return Applied(out, count)
}

fun fixWords(words: List<Word>, rules: List<Rule>): Applied<List<Word>> {
    var out = words
    var count = 0
    for (r in rules) {
        val re = ruleRegex(r.from) ?: continue
        val starts = IntArray(out.size)
        val joined = StringBuilder()
        out.forEachIndexed { i, w ->
            if (i > 0) joined.append(' ')
            starts[i] = joined.length
            joined.append(w.text)
        }
        val matches = re.findAll(joined).toList()
        if (matches.isEmpty()) continue
        val next = mutableListOf<Word>()
        var i = 0
        for (m in matches) {
            val first = starts.indexOfLast { it <= m.range.first }
            val last = starts.indexOfLast { it <= m.range.last }
            if (first < i) continue
            count++
            while (i < first) next += out[i++]
            val a = out[first]
            val b = out[last]
            val head = a.text.substring(0, m.range.first - starts[first])
            val tail = b.text.substring(m.range.last + 1 - starts[last])
            next += Word(a.startMs, b.endMs, head + r.to + tail)
            i = last + 1
        }
        while (i < out.size) next += out[i++]
        out = next
    }
    return Applied(out, count)
}

fun applyRules(segments: List<Segment>, rules: List<Rule>): Applied<List<Segment>> {
    var count = 0
    val out = segments.map { s ->
        if (s.words.isEmpty()) {
            val t = applyRules(s.text, rules)
            count += t.count
            s.copy(text = t.value)
        } else {
            val w = fixWords(s.words, rules)
            count += w.count
            if (w.count == 0) s else s.copy(text = w.value.joinToString(" ") { it.text }, words = w.value)
        }
    }
    return Applied(out, count)
}

fun Session.applyRule(rule: Rule, at: (Long) -> Boolean): Int {
    val offsets = offsets()
    var count = 0
    for ((seq, base) in offsets) {
        val text = chunks.getValue(seq)
        val segs = segments[seq].orEmpty()
        if (segs.isEmpty()) {
            if (text == PENDING || text.isEmpty() || !at(base)) continue
            val t = applyRules(text, listOf(rule))
            count += t.count
            chunks[seq] = t.value
            continue
        }
        var changed = 0
        val next = segs.map { s ->
            if (!at(base + s.startMs)) return@map s
            val a = applyRules(listOf(s), listOf(rule))
            changed += a.count
            a.value.single()
        }
        if (changed == 0) continue
        count += changed
        segments[seq] = next
        chunks[seq] = next.joinToString(" ") { it.text }
    }
    return count
}

fun whisperPrompt(groups: List<List<String>>, max: Int = PROMPT_COST): String {
    val picked = mutableListOf<String>()
    var cost = 0
    for (t in groups.flatten().map { it.trim() }.filter { it.isNotEmpty() }) {
        if (picked.any { same(it, t) }) continue
        val c = t.sumOf { if (it.code < 128) 1 else 3 } + 2
        if (cost + c > max) continue
        picked += t
        cost += c
    }
    return if (picked.isEmpty()) "" else picked.joinToString(", ") + "."
}

class Vocabulary(private val file: Path, private val now: () -> Instant = { Clock.System.now() }) {
    private val lock = Mutex()
    private val _data = MutableStateFlow(
        runCatching { hubJson.decodeFromString<VocabularyData>(SystemFileSystem.source(file).buffered().use { it.readString() }) }.getOrElse { VocabularyData() },
    )
    val data: StateFlow<VocabularyData> get() = _data

    private suspend fun change(f: (VocabularyData) -> VocabularyData) = lock.withLock {
        val next = capped(f(_data.value))
        if (next == _data.value) return@withLock
        _data.value = next
        file.parent?.let { SystemFileSystem.createDirectories(it) }
        val tmp = Path("$file.tmp")
        SystemFileSystem.sink(tmp).buffered().use { it.writeString(hubJson.encodeToString(next)) }
        SystemFileSystem.atomicMove(tmp, file)
    }

    private fun capped(d: VocabularyData): VocabularyData {
        val extra = d.terms.size - VOCABULARY_TERMS
        if (extra <= 0) return d
        val drop = d.terms.filter { it.source != TermSource.User }.sortedBy { it.lastUsed }.take(extra).toSet()
        return d.copy(terms = d.terms.filterNot { it in drop })
    }

    private fun withTerm(d: VocabularyData, text: String, source: TermSource, at: Instant): VocabularyData {
        val t = text.trim()
        if (t.isEmpty()) return d
        val old = d.terms.firstOrNull { same(it.text, t) }
        val keep = if (old != null && old.source < source) old.source else source
        val term = old?.copy(source = keep, count = old.count + 1, lastUsed = at) ?: Term(t, source, 1, at)
        return d.copy(terms = d.terms.filterNot { it === old } + term)
    }

    suspend fun addTerm(text: String) = change { d ->
        val at = now()
        val t = text.trim()
        val old = d.terms.firstOrNull { same(it.text, t) }
        if (t.isEmpty()) d
        else d.copy(terms = d.terms.filterNot { it === old } + (old?.copy(text = t, source = TermSource.User, lastUsed = at) ?: Term(t, TermSource.User, 1, at)))
    }

    suspend fun removeTerm(text: String) = change { d -> d.copy(terms = d.terms.filterNot { it.text == text }) }

    suspend fun removeRule(rule: Rule) = change { d -> d.copy(rules = d.rules - rule) }

    suspend fun correct(from: String, to: String, everywhere: Boolean) = change { d ->
        val f = from.trim()
        val t = to.trim()
        if (f.isEmpty() || t.isEmpty() || f == t) return@change d
        val withRule = if (everywhere) d.copy(rules = d.rules.filterNot { same(it.from, f) } + Rule(f, t)) else d
        withTerm(withRule, t, TermSource.Correction, now())
    }

    suspend fun prompt(names: List<String>, calendar: List<String>): String {
        if (names.isNotEmpty() || calendar.isNotEmpty()) change { d ->
            val at = now()
            var out = d
            names.forEach { out = withTerm(out, it, TermSource.People, at) }
            calendar.forEach { out = withTerm(out, it, TermSource.Calendar, at) }
            out
        }
        val terms = _data.value.terms
        val user = terms.filter { it.source == TermSource.User }.sortedByDescending { it.lastUsed }.map { it.text }
        val corrections = terms.filter { it.source == TermSource.Correction }
            .sortedWith(compareByDescending<Term> { it.count }.thenByDescending { it.lastUsed }).map { it.text }
        return whisperPrompt(listOf(user, names, calendar, corrections))
    }

    fun rules(): List<Rule> = _data.value.rules
}
