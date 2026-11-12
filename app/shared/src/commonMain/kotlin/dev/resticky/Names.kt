package dev.resticky

class SpeakerSlot(val label: String, val session: Session, val speaker: Int, val open: Boolean)

fun Conversation.slots(people: List<Person>): List<SpeakerSlot> {
    val byId = people.associateBy { it.id }
    return parts.flatMap { p -> p.lines().map { p.session to it.speaker } }.filter { it.second >= 0 }.distinctBy { it.first.id to it.second }.map { (s, k) ->
        val person = s.people["$k"]?.let(byId::get)
        val label = s.speakerName(k)
        val open = "$k" !in s.userNamed && person?.isMe != true && (label == speakerLetter(k) || person?.unnamed == true)
        SpeakerSlot(label, s, k, open)
    }
}

private fun invited(name: String, attendee: String): Boolean {
    val n = tokens(name)
    val a = tokens(attendee)
    return n.isNotEmpty() && (n == a || a.containsAll(n))
}

private fun labelsAround(transcript: String, span: String): Set<String> {
    val at = transcript.indexOf(span)
    if (at < 0) return emptySet()
    val first = transcript.lastIndexOf('\n', at) + 1
    val lines = transcript.substring(first).split('\n')
    val covered = transcript.substring(first, at + span.length).count { it == '\n' } + 1
    return lines.take(covered + 1).mapNotNull { l -> l.substringBefore(':', "").trim().takeIf { it.isNotEmpty() }?.lowercase() }.toSet()
}

fun acceptNames(proposals: List<SpeakerName>, transcript: String, attendees: List<String>, slots: List<SpeakerSlot>): List<Pair<SpeakerSlot, String>> {
    val words = tokens(transcript).toSet()
    val ok = proposals.mapNotNull { p ->
        val name = p.name.trim()
        val label = p.speaker.trim().removeSuffix(":").trim()
        val run = tokens(name)
        val span = quoteSpan(p.evidence, transcript) ?: return@mapNotNull null
        when {
            run.isEmpty() || label.isEmpty() || placeholderName(name) || same(name, ME_NAME) -> null
            run.first() !in tokens(span) -> null
            label.lowercase() !in labelsAround(transcript, span) -> null
            attendees.none { invited(name, it) } && !words.containsAll(run) -> null
            else -> label to name
        }
    }.distinctBy { it.first.lowercase() to it.second.lowercase() }
    val names = ok.groupingBy { it.second.lowercase() }.eachCount()
    val labels = ok.groupingBy { it.first.lowercase() }.eachCount()
    val taken = slots.map { it.label.lowercase() }.toSet()
    return ok.filter { (label, name) -> names[name.lowercase()] == 1 && labels[label.lowercase()] == 1 && name.lowercase() !in taken }
        .mapNotNull { (label, name) -> slots.filter { same(it.label, label) }.singleOrNull()?.takeIf { it.open }?.let { it to name } }
}
