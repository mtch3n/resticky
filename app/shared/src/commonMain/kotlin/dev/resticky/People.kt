package dev.resticky

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random

const val PERSON_MATCH = 0.62
const val PERSON_MARGIN = 0.05
const val PERSON_SUGGEST = 0.5
const val ME_NAME = "You"
const val OWNER_SHARE = 0.4
const val OWNER_MIN_SESSIONS = 5
const val RECURRING_MIN_SESSIONS = 3
const val UNNAMED_PREFIX = "Person "
private val UNNAMED = Regex("^${UNNAMED_PREFIX}(\\d+)$")

@Serializable
class Person(
    val id: String,
    val name: String,
    @Serializable(FloatsBase64::class) val embedding: FloatArray,
    @SerialName("talk_ms") val talkMs: Long,
    @SerialName("is_me") val isMe: Boolean = false,
    val auto: Boolean = false,
) {
    fun copy(name: String = this.name, embedding: FloatArray = this.embedding, talkMs: Long = this.talkMs, isMe: Boolean = this.isMe, auto: Boolean = this.auto) =
        Person(id, name, embedding, talkMs, isMe, auto)

    val number: Int? get() = if (auto) UNNAMED.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull() else null

    val unnamed: Boolean get() = number != null
}

fun placeholderName(name: String): Boolean = name.trim().startsWith("Speaker ") || UNNAMED.matches(name.trim())

fun spokenNames(people: List<Person>): List<String> = people.filter { !it.isMe && !it.unnamed }.map { it.name }

class PersonMatch(val speaker: Int, val person: String, val sure: Boolean)

fun matchPeople(voices: Map<Int, Voiceprint>, people: List<Person>): List<PersonMatch> {
    val scores = voices.flatMap { (speaker, v) ->
        people.filter { it.talkMs > 0 && it.embedding.size == v.embedding.size }.map { Triple(speaker, it.id, cosine(v.embedding.unit(), it.embedding)) }
    }
    val bySpeaker = scores.groupBy { it.first }
    val usedSpeakers = mutableSetOf<Int>()
    val usedPeople = mutableSetOf<String>()
    val out = mutableListOf<PersonMatch>()
    for ((speaker, id, c) in scores.filter { it.third >= PERSON_SUGGEST }.sortedByDescending { it.third }) {
        if (speaker in usedSpeakers || id in usedPeople) continue
        usedSpeakers += speaker
        usedPeople += id
        val second = bySpeaker.getValue(speaker).filter { it.second != id }.maxOfOrNull { it.third } ?: -1.0
        out += PersonMatch(speaker, id, c >= PERSON_MATCH && c - second >= PERSON_MARGIN)
    }
    return out.sortedBy { it.speaker }
}

fun Session.labelVoices(found: List<Voice>, people: List<Person>) {
    voices.clear()
    this.people.clear()
    suggested.clear()
    found.forEach { voices["${it.speaker}"] = Voiceprint(it.embedding, it.weightMs) }
    val names = people.associateBy { it.id }
    for (m in matchPeople(voices.mapKeys { it.key.toInt() }, people)) {
        if (m.sure) {
            this.people["${m.speaker}"] = m.person
            renameSpeaker(m.speaker, names.getValue(m.person).name)
        } else suggested["${m.speaker}"] = m.person
    }
}

private fun blend(e: FloatArray, w: Long, add: FloatArray, addW: Long): FloatArray =
    FloatArray(e.size) { e[it] * w + add[it] * addW }.unit()

private fun Person.plus(v: Voiceprint): Person =
    if (talkMs <= 0 || embedding.size != v.embedding.size) copy(embedding = v.embedding.unit(), talkMs = v.talkMs)
    else copy(embedding = blend(embedding, talkMs, v.embedding.unit(), v.talkMs), talkMs = talkMs + v.talkMs)

private fun Person.minus(v: Voiceprint): Person =
    if (talkMs - v.talkMs <= 0 || embedding.size != v.embedding.size) copy(embedding = FloatArray(0), talkMs = 0)
    else copy(embedding = blend(embedding, talkMs, v.embedding.unit(), -v.talkMs), talkMs = talkMs - v.talkMs)

private fun sameName(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

fun newPersonId(): String = Random.nextLong().toULong().toString(16)

fun learnVoice(people: List<Person>, name: String, voice: Voiceprint?, from: String?, auto: Boolean = false, newId: () -> String = ::newPersonId): Pair<List<Person>, String?> {
    val wanted = name.trim()
    val old = people.find { it.id == from }
    if (old != null && sameName(old.name, wanted)) return people to old.id
    var out = people
    if (old != null && voice != null) {
        val left = old.minus(voice)
        out = if (left.talkMs <= 0 && !old.isMe) out.filter { it.id != old.id } else out.map { if (it.id == old.id) left else it }
    }
    if (wanted.isEmpty() || wanted.startsWith("Speaker ")) return out to null
    val target = out.find { sameName(it.name, wanted) }
    return when {
        target != null -> out.map { p ->
            if (p.id != target.id) p else (if (voice == null) p else p.plus(voice)).let { if (auto) it else it.copy(auto = false) }
        } to target.id
        voice == null -> out to null
        else -> newId().let { id -> out + Person(id, wanted, voice.embedding.unit(), voice.talkMs, auto = auto) to id }
    }
}

class People(private val path: Path) {
    private val mutex = Mutex()
    private val state = MutableStateFlow(load())
    val list: StateFlow<List<Person>> = state

    fun all(): List<Person> = state.value

    private fun load(): List<Person> = runCatching {
        hubJson.decodeFromString<List<Person>>(SystemFileSystem.source(path).buffered().use { it.readString() })
    }.getOrDefault(emptyList())

    private fun save(people: List<Person>) {
        path.parent?.let { SystemFileSystem.createDirectories(it) }
        val tmp = Path("$path.tmp")
        SystemFileSystem.sink(tmp).buffered().use { it.write(hubJson.encodeToString(people).encodeToByteArray()) }
        SystemFileSystem.atomicMove(tmp, path)
    }

    private suspend fun <T> change(f: (List<Person>) -> Pair<List<Person>, T>): T = mutex.withLock {
        val (next, result) = f(state.value)
        if (next != state.value) {
            save(next)
            state.value = next
        }
        result
    }

    suspend fun learn(name: String, voice: Voiceprint?, from: String?, auto: Boolean = false): String? = change { learnVoice(it, name, voice, from, auto) }

    suspend fun discover(sessions: List<Session>) = change { autoPeople(it, sessions) to Unit }

    suspend fun setMe(voice: Voiceprint): String = change { list ->
        val me = list.find { it.isMe }
        if (me == null) newPersonId().let { id -> list + Person(id, ME_NAME, voice.embedding.unit(), voice.talkMs, isMe = true) to id }
        else list.map { if (it.isMe) it.copy(embedding = voice.embedding.unit(), talkMs = voice.talkMs) else it } to me.id
    }

    suspend fun rename(id: String, name: String, auto: Boolean = false): String? = change { list ->
        val wanted = name.trim()
        val p = list.find { it.id == id }
        val other = list.find { it.id != id && sameName(it.name, wanted) }
        when {
            p == null || wanted.isEmpty() -> list to null
            other == null -> list.map { if (it.id == id) it.copy(name = wanted, auto = auto && it.auto) else it } to id
            else -> list.filter { it.id != id }.map { o ->
                if (o.id != other.id) o else o.plus(Voiceprint(p.embedding, p.talkMs)).copy(isMe = o.isMe || p.isMe, auto = auto && o.auto)
            } to other.id
        }
    }

    suspend fun delete(id: String) = change { list -> list.filter { it.id != id } to Unit }
}

suspend fun nameSpeaker(store: Store, people: People, device: String, id: String, speaker: Int, name: String, auto: Boolean = false) {
    val key = "$speaker"
    val (voice, from) = store.update(device, id) { s ->
        if (!auto) s.userNamed += key
        s.voices[key] to s.people[key]
    } ?: return
    if (people.all().find { it.id == from }?.unnamed == true && !placeholderName(name) && name.isNotBlank()) return renamePerson(store, people, from!!, name, auto)
    val person = people.learn(name, voice, from, auto)
    store.update(device, id) {
        it.renameSpeaker(speaker, name)
        if (person == null) it.people.remove(key) else it.people[key] = person
        it.suggested.remove(key)
    }
}

suspend fun renamePerson(store: Store, people: People, id: String, name: String, auto: Boolean = false) {
    val into = people.rename(id, name, auto) ?: return
    val now = people.all().first { it.id == into }.name
    for (s in store.all()) {
        val linked = s.people.filterValues { it == id || it == into }.keys
        if (linked.isEmpty()) continue
        store.update(s.device, s.id) { m ->
            linked.forEach { k ->
                m.people[k] = into
                m.renameSpeaker(k.toInt(), now)
            }
        }
    }
}

class VoiceCluster(var embedding: FloatArray, var talkMs: Long, val sessions: MutableSet<String>)

fun clusterVoices(sessions: List<Session>): List<VoiceCluster> {
    val voices = sessions.flatMap { s -> s.voices.values.map { "${s.device}/${s.id}" to it } }
        .filter { it.second.talkMs > 0 && it.second.embedding.isNotEmpty() }
        .sortedWith(compareByDescending<Pair<String, Voiceprint>> { it.second.talkMs }.thenBy { it.first })
    val out = mutableListOf<VoiceCluster>()
    for ((key, v) in voices) {
        val e = v.embedding.unit()
        val best = out.filter { key !in it.sessions && it.embedding.size == e.size }
            .map { it to cosine(it.embedding, e) }.filter { it.second >= PERSON_MATCH }.maxByOrNull { it.second }?.first
        if (best == null) out += VoiceCluster(e, v.talkMs, mutableSetOf(key))
        else {
            best.embedding = blend(best.embedding, best.talkMs, e, v.talkMs)
            best.talkMs += v.talkMs
            best.sessions += key
        }
    }
    return out.sortedWith(compareByDescending<VoiceCluster> { it.sessions.size }.thenByDescending { it.talkMs })
}

fun autoPeople(people: List<Person>, sessions: List<Session>, newId: () -> String = ::newPersonId): List<Person> {
    val clusters = clusterVoices(sessions)
    val heard = sessions.count { it.voices.isNotEmpty() }
    var out = people
    fun known(c: VoiceCluster) = out.filter { it.talkMs > 0 && it.embedding.size == c.embedding.size }
        .map { it to cosine(it.embedding, c.embedding) }.filter { it.second >= PERSON_MATCH }.maxByOrNull { it.second }?.first
    val owner = clusters.firstOrNull()
    if (owner != null && out.none { it.isMe } && owner.sessions.size >= OWNER_MIN_SESSIONS && owner.sessions.size >= heard * OWNER_SHARE) {
        val same = known(owner)
        out = if (same != null) out.map { if (it.id == same.id) it.copy(name = if (it.unnamed) ME_NAME else it.name, isMe = true) else it }
        else out + Person(newId(), ME_NAME, owner.embedding, owner.talkMs, isMe = true, auto = true)
    }
    var next = (out.mapNotNull { it.number }.maxOrNull() ?: 0) + 1
    for (c in clusters) {
        if (c.sessions.size < RECURRING_MIN_SESSIONS || known(c) != null) continue
        out = out + Person(newId(), "$UNNAMED_PREFIX${next++}", c.embedding, c.talkMs, auto = true)
    }
    return out
}

fun Session.linkPeople(people: List<Person>): Boolean {
    val taken = this.people.values.toSet()
    val free = voices.filterKeys { it !in this.people && it !in userNamed }.mapKeys { it.key.toInt() }
    val names = people.associateBy { it.id }
    var changed = false
    for ((k, id) in this.people) {
        val now = names[id]?.name ?: continue
        if (k in userNamed || speakerName(k.toInt()) == now) continue
        renameSpeaker(k.toInt(), now)
        changed = true
    }
    for (m in matchPeople(free, people.filter { it.id !in taken })) {
        if (!m.sure || !placeholderName(speakerName(m.speaker))) continue
        this.people["${m.speaker}"] = m.person
        suggested.remove("${m.speaker}")
        renameSpeaker(m.speaker, names.getValue(m.person).name)
        changed = true
    }
    return changed
}
