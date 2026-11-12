package dev.resticky

const val CANDIDATES_MAX = 60
const val AUTO_TERMS_MAX = 20
const val AUTO_TERMS_WINDOW = 100
const val AUTO_TERM_CONVERSATIONS = 2
const val RULE_CONVERSATIONS = 2

val COMMON_WORDS = setOf(
    "a", "about", "after", "again", "all", "also", "am", "an", "and", "any", "are", "as", "at", "be", "because", "been", "before", "but", "by",
    "can", "could", "did", "do", "does", "done", "for", "from", "get", "go", "good", "had", "has", "have", "he", "her", "here", "him", "his",
    "how", "i", "if", "in", "into", "is", "it", "its", "just", "know", "let", "like", "me", "more", "my", "no", "not", "now", "of", "off", "ok",
    "okay", "on", "one", "only", "or", "our", "out", "over", "please", "right", "said", "say", "see", "she", "so", "some", "sure", "than",
    "thank", "thanks", "that", "the", "their", "them", "then", "there", "these", "they", "think", "this", "those", "to", "too", "two", "up",
    "us", "very", "was", "we", "well", "were", "what", "when", "where", "which", "who", "why", "with", "would", "yeah", "yes", "you", "your",
    "will", "may", "mark", "grace", "bill", "art", "hope", "joy", "faith", "rose", "june", "april", "august", "march", "summer", "autumn",
    "dawn", "eve", "sky", "rich", "frank", "earnest", "pat", "sue", "rob", "drew", "jack", "jay", "ray", "max", "gene", "chase", "hunter",
    "page", "lane", "dale", "glen", "victor", "major", "king", "price", "young", "white", "black", "brown", "green", "gray", "grey", "long",
    "new", "next", "last", "first", "today", "tomorrow", "yesterday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday",
    "sunday", "january", "february", "september", "october", "november", "december", "speaker", "person", "hello", "hi", "hey", "sorry",
)

private val SPLIT = Regex("\\s+")

private fun bare(w: String) = w.trim { !it.isLetterOrDigit() }

private fun containsRun(words: List<String>, run: List<String>): Boolean =
    run.isNotEmpty() && (0..words.size - run.size).any { i -> run.indices.all { words[i + it] == run[it] } }

fun skeleton(word: String): String {
    var w = word.lowercase().filter { it in 'a'..'z' }
    if (w.isEmpty()) return ""
    for ((a, b) in listOf("sch" to "sk", "ph" to "f", "ck" to "k", "gh" to "", "sh" to "s", "ch" to "k", "th" to "t", "wr" to "r", "qu" to "k")) w = w.replace(a, b)
    if (w.startsWith("kn")) w = w.substring(1)
    val out = StringBuilder()
    w.forEachIndexed { i, c ->
        val next = w.getOrNull(i + 1)
        val sound = when (c) {
            'c' -> if (next == 'e' || next == 'i' || next == 'y') 's' else 'k'
            'g' -> if (next == 'e' || next == 'i' || next == 'y') 'j' else 'g'
            'q' -> 'k'
            'z' -> 's'
            'v' -> 'f'
            'x' -> 'k'
            'a', 'e', 'i', 'o', 'u', 'y' -> if (i == 0) 'a' else null
            'h', 'w' -> if (i == 0) c else null
            else -> c
        }
        if (sound != null && out.lastOrNull() != sound) out.append(sound)
    }
    return out.toString()
}

internal fun editDistance(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1)
        cur[0] = i
        for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        prev = cur
    }
    return prev[b.length]
}

fun soundsLike(from: String, to: String): Boolean {
    val a = skeleton(from)
    val b = skeleton(to)
    if (a.isEmpty() || b.isEmpty() || a.first() != b.first()) return false
    val longest = maxOf(a.length, b.length)
    val allowed = when {
        longest <= 2 -> 0
        longest <= 5 -> 1
        else -> 2
    }
    return editDistance(a, b) <= allowed
}

fun acceptCorrections(proposals: List<Correction>, transcript: String, candidates: List<String>, refused: Set<Pair<String, String>> = emptySet()): List<Rule> {
    val words = tokens(transcript)
    return proposals.mapNotNull { p ->
        val from = p.from.trim()
        val to = candidates.firstOrNull { same(it, p.to) }?.trim() ?: return@mapNotNull null
        val run = tokens(from)
        when {
            run.isEmpty() || same(from, to) -> null
            candidates.any { same(it, from) } -> null
            run.any { it in COMMON_WORDS } -> null
            !containsRun(words, run) -> null
            !soundsLike(from, to) -> null
            from.lowercase() to to.lowercase() in refused -> null
            else -> Rule(from, to)
        }
    }.distinctBy { it.from.lowercase() }
}

fun Session.refusedFixes(): Set<Pair<String, String>> = fixes.filter { it.undone }.map { it.from.lowercase() to it.to.lowercase() }.toSet()

fun Session.autoFix(rule: Rule, at: (Long) -> Boolean): Int {
    val changed = applyRule(rule, at)
    changed.forEach { fixes += Fix(rule.from, rule.to, it) }
    return changed.size
}

fun Session.undoFix(fix: Fix): Boolean {
    val i = fixes.indexOfFirst { it == fix }
    if (i < 0 || fix.undone) return false
    applyRule(Rule(fix.to, fix.from)) { it == fix.atMs }
    fixes[i] = fix.copy(undone = true)
    return true
}

fun Recap.fixed(rule: Rule): Recap = copy(
    title = applyRules(title, listOf(rule)).value,
    summary = applyRules(summary, listOf(rule)).value,
    todos = todos.map { applyRules(it, listOf(rule)).value },
)

fun Conversation.fixes(): List<Fix> = parts.flatMap { p -> p.session.fixes.filter { !it.undone && p.covers(it.atMs) } }

fun repeatedFixes(conversations: List<Conversation>, min: Int = RULE_CONVERSATIONS): List<Rule> =
    conversations.flatMap { c -> c.fixes().map { it.from.lowercase() to it.to }.distinct() }
        .groupingBy { it }.eachCount().filterValues { it >= min }.keys.map { Rule(it.first, it.second) }

fun properNouns(text: String): Set<String> {
    val out = mutableSetOf<String>()
    for (line in text.lines()) {
        var initial = true
        for (raw in line.split(SPLIT)) {
            if (raw.isEmpty()) continue
            val w = bare(raw)
            val first = initial
            initial = raw.last() in ".?!:"
            if (first || w.length < 3 || !w.first().isUpperCase()) continue
            if (w.any { !it.isLetterOrDigit() && it != '-' } || w.lowercase() in COMMON_WORDS) continue
            out += w
        }
    }
    return out
}

private fun lowerWords(text: String): Set<String> =
    text.split(SPLIT).map(::bare).filter { w -> w.isNotEmpty() && w.first().isLowerCase() }.toSet()

fun recurringTerms(current: String, others: List<String>, min: Int = AUTO_TERM_CONVERSATIONS): List<String> {
    val lower = (others + current).flatMap(::lowerWords).toSet()
    val seen = others.map(::properNouns)
    return properNouns(current).filter { t -> t.lowercase() !in lower && 1 + seen.count { t in it } >= min }.sorted().take(AUTO_TERMS_MAX)
}
