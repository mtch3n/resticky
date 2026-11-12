package dev.resticky.desktop

import dev.resticky.CHUNK_MS
import dev.resticky.People
import dev.resticky.Pipeline
import dev.resticky.Session
import dev.resticky.Store
import dev.resticky.Summarizer
import dev.resticky.Summary
import dev.resticky.Vocabulary
import dev.resticky.frameWindows
import dev.resticky.opusFramesToOgg
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.TimeSource
import kotlinx.io.files.Path as IoPath

private val HOME = "${System.getProperty("user.home")}/.local/share/resticky"
private val EVAL_ROOT = "$HOME/eval"
private const val REFERENCE = "reference.json"
private const val DEVICE = "eval"
private const val SESSION = "e"
private const val FRAME_MS = 20L
private const val AGY_BLOCK_MS = 10 * 60 * 1000L
private const val AGY_MODEL = "gemini-3.8-flash-low"
private val OPUS_CHUNK = Regex("""^(\d+)\.opus$""")

private val evalJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private const val REFERENCE_SCHEMA = """{"type":"object","additionalProperties":false,"required":["segments"],"properties":{"segments":{"type":"array","items":{"type":"object","additionalProperties":false,
"required":["start_ms","end_ms","speaker","text"],"properties":{"start_ms":{"type":"integer"},"end_ms":{"type":"integer"},"speaker":{"type":"string"},"text":{"type":"string"}}}}}}"""

private fun frameCount(frames: ByteArray): Int {
    var i = 0
    var n = 0
    while (i + 2 <= frames.size) {
        i += 2 + ((frames[i].toInt() and 0xff) or ((frames[i + 1].toInt() and 0xff) shl 8))
        n++
    }
    return n
}

fun oggPackets(ogg: ByteArray): List<ByteArray> {
    val out = mutableListOf<ByteArray>()
    val packet = ByteArrayOutputStream()
    var i = 0
    while (i + 27 <= ogg.size) {
        val n = ogg[i + 26].toInt() and 0xff
        var at = i + 27 + n
        for (k in 0 until n) {
            val size = ogg[i + 27 + k].toInt() and 0xff
            packet.write(ogg, at, size)
            at += size
            if (size < 255) {
                out += packet.toByteArray()
                packet.reset()
            }
        }
        i = at
    }
    return out.drop(2)
}

private fun encodeAudio(file: File): ByteArray {
    val ogg = Files.createTempFile("resticky-eval", ".ogg").toFile()
    try {
        run(
            null, "ffmpeg", "-loglevel", "error", "-y", "-i", file.path, "-ac", "1", "-ar", "16000", "-c:a", "libopus",
            "-b:a", "16k", "-frame_duration", "$FRAME_MS", "-application", "voip", "-f", "ogg", ogg.path,
        )
        val out = ByteArrayOutputStream()
        oggPackets(ogg.readBytes()).forEach { p ->
            out.write(p.size and 0xff)
            out.write(p.size shr 8)
            out.write(p)
        }
        return out.toByteArray()
    } finally {
        ogg.delete()
    }
}

private fun caseChunks(case: File): List<ByteArray> {
    val opus = case.listFiles().orEmpty().mapNotNull { f -> OPUS_CHUNK.matchEntire(f.name)?.let { it.groupValues[1].toInt() to f } }.toMap()
    if (opus.isNotEmpty()) {
        val silence = ByteArray((CHUNK_MS / FRAME_MS * 2).toInt())
        return (opus.keys.min()..opus.keys.max()).map { opus[it]?.readBytes() ?: silence }
    }
    val audio = case.listFiles().orEmpty().firstOrNull { it.isFile && it.nameWithoutExtension == "audio" }
        ?: error("$case has no <seq>.opus chunks and no audio.* file")
    return frameWindows(sequenceOf(encodeAudio(audio)), CHUNK_MS).map { it.second }.toList()
}

private fun copied(from: String, to: File): IoPath {
    File(from).takeIf { it.isFile }?.copyTo(to)
    return IoPath(to.path)
}

private fun runPipeline(case: File, engines: Engines, flags: Flags): Session = runBlocking {
    val tmp = Files.createTempDirectory("resticky-eval").toFile()
    try {
        val store = Store(IoPath(File(tmp, "data").path))
        val chunks = caseChunks(case)
        chunks.forEachIndexed { seq, frames -> store.addChunk(DEVICE, SESSION, seq, seq == chunks.lastIndex, frameCount(frames) * FRAME_MS, frames) }
        val pipeline = Pipeline(
            store, engines.transcriber(), Summarizer { _, _ -> Summary(DEVICE, "") }, engines.diarizer(),
            speechId = engines::speechId,
            people = People(copied(flags.string("people", "$HOME/people.json"), File(tmp, "people.json"))),
            vocabulary = Vocabulary(copied(flags.string("vocabulary", "$HOME/vocabulary.json"), File(tmp, "vocabulary.json"))),
            log = System.err::println,
        )
        repeat(2) { pipeline.drain() }
        store.all().single()
    } finally {
        tmp.deleteRecursively()
    }
}

private fun hubSegments(s: Session): List<RefSegment> {
    val offsets = s.offsets()
    return s.segments.flatMap { (seq, list) ->
        val at = offsets.getValue(seq)
        list.map { RefSegment(at + it.startMs, at + it.endMs, s.speakerName(it.speaker), it.text) }
    }.sortedBy { it.startMs }
}

fun scoreCase(reference: Reference, hub: List<RefSegment>, seconds: Long): Score {
    val ref = reference.segments.sortedBy { it.startMs }
    return Score(
        textErrors(ref.joinToString(" ") { it.text }, hub.joinToString(" ") { it.text }),
        speakerErrors(ref, hub.map { HubSpan(it.startMs, it.endMs, it.speaker) }),
        seconds,
    )
}

private fun cases(roots: List<File>): List<File> = roots.flatMap { r ->
    if (File(r, REFERENCE).isFile) listOf(r) else r.listFiles().orEmpty().filter { File(it, REFERENCE).isFile }.sortedBy { it.name }
}

private fun scoreAll(flags: Flags, engines: Engines) {
    val found = cases(flags.positional.ifEmpty { listOf(EVAL_ROOT) }.map(::File))
    require(found.isNotEmpty()) { "no case with $REFERENCE in ${flags.positional.ifEmpty { listOf(EVAL_ROOT) }}" }
    val old = flags.string("compare", "").ifEmpty { null }?.let { evalJson.decodeFromString<Run>(File(it).readText()) }
    val scores = linkedMapOf<String, Score>()
    for (case in found) {
        val reference = evalJson.decodeFromString<Reference>(File(case, REFERENCE).readText())
        val start = TimeSource.Monotonic.markNow()
        val session = runPipeline(case, engines, flags)
        val score = scoreCase(reference, hubSegments(session), start.elapsedNow().inWholeSeconds)
        scores[case.name] = score
        println(table(case.name, score, old?.cases?.get(case.name)))
        println()
    }
    val total = scores.values.fold(Score()) { a, b -> a + b }
    println(table("total", total, old?.total))
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val out = File(flags.string("out", "$EVAL_ROOT/runs/$stamp.json"))
    out.absoluteFile.parentFile.mkdirs()
    out.writeText(evalJson.encodeToString(Run(scores, total)))
    println("results: $out")
}

private fun agyPrompt(minutes: Long, draft: List<RefSegment>) =
    "Listen to the audio file block.ogg in the current folder. It is $minutes minutes of a conversation recorded by a desk microphone. " +
        "It can mix English, Mandarin and Cantonese. Write a verbatim transcript as segments: start_ms and end_ms (milliseconds from the start of the file), speaker and text. " +
        "Write what each person says in the language they speak: English in Latin letters, Chinese characters for Mandarin and Cantonese, " +
        "colloquial Cantonese characters (嘅, 喺, 咗) for Cantonese. Do not translate, do not summarize and do not correct grammar. " +
        "Do not add segments for noise or music. Start a new segment when the speaker changes. " +
        "Below is a machine draft of the same audio, one line for each segment: start_ms end_ms speaker | text. " +
        "Use its speaker labels for the same voices. Use a new label (for example \"Speaker N\") for a voice that the draft does not have.\n\nDraft:\n" +
        draft.joinToString("\n") { "${it.startMs} ${it.endMs} ${it.speaker} | ${it.text}" }

private fun agyDraft(case: File, draft: List<RefSegment>): List<RefSegment> {
    return frameWindows(caseChunks(case).asSequence(), AGY_BLOCK_MS).toList().flatMap { (offset, block) ->
        val dir = Files.createTempDirectory("resticky-agy").toFile()
        try {
            File(dir, "block.ogg").writeBytes(opusFramesToOgg(block))
            val ms = frameCount(block) * FRAME_MS
            val local = draft.filter { it.startMs >= offset && it.startMs < offset + ms }.map { it.copy(startMs = it.startMs - offset, endMs = it.endMs - offset) }
            System.err.println("agy block offset_ms=$offset length_ms=$ms")
            val out = run(
                dir, "agy", "-p", agyPrompt((ms + 59_999) / 60_000, local), "--model", AGY_MODEL, "--json-schema", REFERENCE_SCHEMA,
                "--output-format", "json", "--print-timeout", "170s", merge = false,
            )
            val structured = evalJson.decodeFromString<JsonObject>(out.substring(out.indexOf('{')))["structured_output"] ?: error("agy gave no structured_output")
            val reference = if (structured is JsonPrimitive) evalJson.decodeFromString<Reference>(structured.jsonPrimitive.content)
            else evalJson.decodeFromJsonElement(Reference.serializer(), structured)
            reference.segments.map { it.copy(startMs = it.startMs + offset, endMs = it.endMs + offset) }
        } finally {
            dir.deleteRecursively()
        }
    }
}

private fun draftCase(case: File, engines: Engines, flags: Flags) {
    val target = File(case, REFERENCE)
    require(!target.exists()) { "$target exists; delete it to make a new draft" }
    val hub = hubSegments(runPipeline(case, engines, flags))
    val segments = if (flags.on("agy")) agyDraft(case, hub) else hub
    target.writeText(evalJson.encodeToString(Reference(segments)))
    println("draft: $target segments=${segments.size}")
}

fun evalMain(args: List<String>) {
    val draft = args.firstOrNull() == "draft"
    val flags = Flags(if (draft) args.drop(1) else args, setOf("agy"))
    val engines = Engines(flags)
    if (!draft) return scoreAll(flags, engines)
    require(flags.positional.isNotEmpty()) { "usage: eval draft [-agy] <case dir>..." }
    flags.positional.forEach { draftCase(File(it), engines, flags) }
}
