package dev.resticky.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import dev.resticky.CLUSTER_THRESHOLD
import dev.resticky.CONTEXT_MS
import dev.resticky.MIN_SPEECH_MS
import dev.resticky.Piece
import dev.resticky.SpeechWindow
import dev.resticky.placeSegments
import dev.resticky.speechMs
import dev.resticky.speechWindows
import dev.resticky.Diarization
import dev.resticky.Diarizer
import dev.resticky.MIN_DURATION_OFF
import dev.resticky.MIN_DURATION_ON
import dev.resticky.Turn
import dev.resticky.diarization
import dev.resticky.summaryPrompt
import dev.resticky.SUMMARY_SCHEMA
import dev.resticky.Summarizer
import dev.resticky.Summary
import dev.resticky.SummaryContext
import dev.resticky.Segment
import dev.resticky.Token
import dev.resticky.wordsFromTokens
import dev.resticky.Transcriber
import dev.resticky.parseSummary
import dev.resticky.NO_SPEECH
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource

private const val WHISPER_THREADS = 4
private const val MAX_OUTPUT_TOKENS = 1024
private const val GEMMA_CONTEXT = 16384
private const val GEMMA_PART_CHARS = 40_000
private const val NANO_PART_CHARS = 60_000
private const val MAX_RUNS = 200
private const val JOB_WAKE_MS = 15 * 60 * 1000L

enum class Model(val label: String, val url: String, val file: String, val megabytes: Int) {
    Auto("Auto (English + Chinese)", "", "", 502),
    WhisperTinyQ8("whisper tiny.en q8_0", whisper("ggml-tiny.en-q8_0.bin"), "ggml-tiny.en-q8_0.bin", 44),
    WhisperBaseQ8("whisper base.en q8_0", whisper("ggml-base.en-q8_0.bin"), "ggml-base.en-q8_0.bin", 82),
    WhisperBase("whisper base.en", whisper("ggml-base.en.bin"), "ggml-base.en.bin", 148),
    WhisperSmallQ8("whisper small.en q8_0", whisper("ggml-small.en-q8_0.bin"), "ggml-small.en-q8_0.bin", 264),
    WhisperMediumQ5("whisper medium.en q5_0", whisper("ggml-medium.en-q5_0.bin"), "ggml-medium.en-q5_0.bin", 539),
    SenseVoice(
        "SenseVoice (English, Mandarin, Cantonese)",
        "$SENSE_VOICE/model.int8.onnx",
        "sense-voice-2025-09-09.int8.onnx",
        237,
    ),
    SenseVoiceTokens("SenseVoice tokens", "$SENSE_VOICE/tokens.txt", "sense-voice-2025-09-09-tokens.txt", 1),
    SpeechDetector("Silero speech detector", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx", "silero_vad.onnx", 1),
    SpeakerSegmentation(
        "pyannote segmentation 3.0",
        "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.onnx",
        "pyannote-segmentation-3-0.onnx",
        6,
    ),
    SpeakerEmbedding(
        "3D-Speaker CAM++",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx",
        "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx",
        29,
    ),
    Denoiser(
        "DPDFNet 2 denoiser",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/dpdfnet2.onnx",
        "dpdfnet2.onnx",
        10,
    ),
    Gemma4E2B(
        "Gemma 4 E2B",
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        "gemma-4-E2B-it.litertlm",
        2588,
    ),
}

private const val SENSE_VOICE = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/main"
private const val SAMPLE_RATE = 16_000
private const val VAD_WINDOW = 512

private fun isCjk(c: Char) = c in '\u3400'..'\u9fff' || c in '\uf900'..'\ufaff'

private fun whisper(file: String) = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$file"

val SPEECH_MODELS get() = listOf(Model.Auto) + Model.entries.filter { it.name.startsWith("Whisper") } + Model.SenseVoice
val DEFAULT_SPEECH = Model.Auto
private val AUTO_ENGLISH = Model.WhisperSmallQ8
private val AUTO_PARTS = listOf(AUTO_ENGLISH, Model.SenseVoice, Model.SenseVoiceTokens)
private val CHINESE = setOf("zh", "yue", "ja", "ko")
val SPEAKER_MODELS = listOf(Model.SpeakerSegmentation, Model.SpeakerEmbedding)
val DIARIZE_WINDOW_MS = 20 * 60 * 1000L
const val LISTEN_FILE = "listen.opus"

enum class SummaryEngine(val label: String) {
    Nano("Gemini Nano"),
    Gemma("Gemma 4 E2B"),
    Laptop("Wait for the laptop"),
}

enum class TranscribeMode(val label: String) {
    Now("Right away"),
    Every15("Every 15 minutes"),
    Charging("Only while charging"),
}

val OPEN_SUMMARY = listOf(SummaryEngine.Nano, SummaryEngine.Gemma)
val CLOSED_SUMMARY = listOf(SummaryEngine.Gemma, SummaryEngine.Laptop)

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val percent: Int) : ModelState
    data object Ready : ModelState
    data class Failed(val reason: Int) : ModelState
}

data class Run(
    val key: String,
    val job: String,
    val engine: String,
    val started: Instant,
    val seconds: Double? = null,
    val error: String? = null,
    val words: Int? = null,
    val summary: Summary? = null,
    val speakers: Int? = null,
)

class Engines(private val context: Context, private val onScreen: () -> Boolean) {
    private val prefs = context.getSharedPreferences("engines", Context.MODE_PRIVATE)
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val dir = context.getExternalFilesDir("models")!!
    private var whisper: Pair<Model, Long>? = null
    private val jobLock = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "resticky:job")

    val runs = MutableStateFlow<List<Run>>(emptyList())
    val speech = MutableStateFlow(Model.valueOf(prefs.getString("speech", DEFAULT_SPEECH.name)!!))
    val summaryOpen = MutableStateFlow(SummaryEngine.valueOf(prefs.getString("summaryOpen", SummaryEngine.Nano.name)!!))
    val summaryClosed = MutableStateFlow(SummaryEngine.valueOf(prefs.getString("summaryClosed", SummaryEngine.Gemma.name)!!))

    val transcribe = MutableStateFlow(TranscribeMode.valueOf(prefs.getString("transcribe", TranscribeMode.Now.name)!!))

    fun selectTranscribe(m: TranscribeMode) {
        prefs.edit().putString("transcribe", m.name).apply()
        transcribe.value = m
    }

    fun selectSpeech(m: Model) {
        prefs.edit().putString("speech", m.name).apply()
        speech.value = m
    }

    fun selectSummary(open: Boolean, e: SummaryEngine) {
        prefs.edit().putString(if (open) "summaryOpen" else "summaryClosed", e.name).apply()
        (if (open) summaryOpen else summaryClosed).value = e
    }

    fun speed(engine: String): Double? = prefs.getFloat("speed:$engine", -1f).takeIf { it >= 0 }?.toDouble()

    fun file(m: Model) = File(dir, m.file)

    fun state(m: Model): ModelState {
        if (m == Model.Auto) {
            val parts = AUTO_PARTS.map(::state)
            return parts.filterIsInstance<ModelState.Failed>().firstOrNull()
                ?: parts.filterIsInstance<ModelState.Downloading>().minByOrNull { it.percent }
                ?: if (parts.all { it == ModelState.Ready }) ModelState.Ready else ModelState.Missing
        }
        val id = prefs.getLong("download:${m.name}", -1)
        if (id >= 0) downloads.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (c.moveToFirst()) {
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                return when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> ModelState.Ready
                    DownloadManager.STATUS_FAILED -> ModelState.Failed(c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
                    else -> ModelState.Downloading(if (total > 0) (done * 100 / total).toInt() else 0)
                }
            }
        }
        return if (file(m).exists()) ModelState.Ready else ModelState.Missing
    }

    fun download(m: Model) {
        if (m == Model.Auto) return AUTO_PARTS.filter { state(it) == ModelState.Missing || state(it) is ModelState.Failed }.forEach(::download)
        file(m).delete()
        val request = DownloadManager.Request(Uri.parse(m.url))
            .setTitle("resticky: ${m.label}")
            .setDestinationInExternalFilesDir(context, "models", m.file)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(false)
        prefs.edit().putLong("download:${m.name}", downloads.enqueue(request)).apply()
    }

    private suspend fun <T> track(key: String, job: String, engine: String, block: suspend () -> T): T {
        val run = Run(key, job, engine, Clock.System.now())
        runs.update { (listOf(run) + it).take(MAX_RUNS) }
        val mark = TimeSource.Monotonic.markNow()
        jobLock.acquire(JOB_WAKE_MS)
        val result = runCatching { block() }
        if (jobLock.isHeld) jobLock.release()
        val seconds = mark.elapsedNow().inWholeMilliseconds / 1000.0
        val error = result.exceptionOrNull()?.let { it.message ?: "failed" }
        log("INFO run job=$job engine=\"$engine\" key=$key seconds=$seconds error=$error")
        val value = result.getOrNull()
        val done = run.copy(
            seconds = seconds,
            error = error,
            words = (value as? List<*>)?.sumOf { (it as? Segment)?.text?.split(' ')?.count { w -> w.isNotBlank() } ?: 0 },
            summary = value as? Summary,
            speakers = (value as? Diarization)?.turns?.map { it.speaker }?.distinct()?.size,
        )
        runs.update { list -> list.map { if (it === run) done else it } }
        if (error == null) prefs.edit().putFloat("speed:$engine", seconds.toFloat()).apply()
        return result.getOrThrow()
    }

    fun modelPath(m: Model): String? = when (state(m)) {
        ModelState.Ready -> file(m).path
        ModelState.Missing -> {
            download(m)
            null
        }
        else -> null
    }

    private fun requireReady(m: Model): File = when (val s = state(m)) {
        ModelState.Ready -> file(m)
        is ModelState.Downloading -> error("${m.label} is downloading (${s.percent}%)")
        else -> {
            download(m)
            error("${m.label} download started")
        }
    }

    val transcriber = Transcriber { opus ->
        val m = speech.value
        val opusFile = File(opus.toString())
        track("${opusFile.parentFile!!.name}/${opusFile.nameWithoutExtension}", "speech", m.label) {
            withContext(Dispatchers.Default) {
                val chunk = Whisper.decodeOpus(opusFile.readBytes()) ?: error("opus decoder failed")
                val context = previousTail(opusFile)
                val pcm = context + chunk
                val regions = speechRegions(pcm)
                if (speechMs(regions, context.size, SAMPLE_RATE) < MIN_SPEECH_MS) return@withContext emptyList()
                val windows = speechWindows(regions, pcm.size, context.size, SAMPLE_RATE)
                val routes = mutableListOf<String>()
                val segments = windows.flatMap { w ->
                    val (route, segs) = transcribeWindow(m, w.audio(pcm), w)
                    routes += route
                    placeSegments(segs, w, context.size, SAMPLE_RATE)
                }
                log("INFO speech key=${opusFile.parentFile!!.name}/${opusFile.nameWithoutExtension} windows=${windows.size} routes=${routes.joinToString(",")} audio_ms=${windows.sumOf { it.size } * 1000L / SAMPLE_RATE}")
                segments
            }
        }
    }

    private fun previousTail(opus: File): FloatArray {
        val seq = opus.nameWithoutExtension.toIntOrNull() ?: return FloatArray(0)
        val prev = File(opus.parentFile, "${seq - 1}.opus")
        if (!prev.isFile) return FloatArray(0)
        val pcm = Whisper.decodeOpus(prev.readBytes()) ?: return FloatArray(0)
        return pcm.copyOfRange(maxOf(0, pcm.size - (CONTEXT_MS * SAMPLE_RATE / 1000).toInt()), pcm.size)
    }

    private fun transcribeWindow(m: Model, audio: FloatArray, w: SpeechWindow): Pair<String, List<Segment>> =
        when (m) {
            Model.SenseVoice -> senseVoice(audio, w).let { "sensevoice" to it.second }
            Model.Auto -> {
                val sense = if (AUTO_PARTS.drop(1).all { modelPath(it) != null }) senseVoice(audio, w) else null
                if (sense != null && (sense.first in CHINESE || sense.second.any { s -> s.text.any(::isCjk) })) "sensevoice:${sense.first}" to sense.second
                else "whisper:${sense?.first ?: "-"}" to whisper(AUTO_ENGLISH, audio)
            }
            else -> "whisper" to whisper(m, audio)
        }

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null

    private fun speechRegions(pcm: FloatArray): List<Piece> {
        val v = vad ?: Vad(
            null,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = requireReady(Model.SpeechDetector).path,
                    threshold = 0.5f,
                    minSilenceDuration = 0.4f,
                    minSpeechDuration = 0.25f,
                    windowSize = VAD_WINDOW,
                    maxSpeechDuration = 20f,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
            ),
        ).also { vad = it }
        v.reset()
        val parts = mutableListOf<Piece>()
        fun drain() {
            while (!v.empty()) {
                val s = v.front()
                parts += Piece(s.start.toInt(), s.start.toInt() + s.samples.size)
                v.pop()
            }
        }
        var i = 0
        while (i < pcm.size) {
            v.acceptWaveform(pcm.copyOfRange(i, minOf(i + VAD_WINDOW, pcm.size)))
            i += VAD_WINDOW
            drain()
        }
        v.flush()
        drain()
        return parts
    }

    private fun whisper(m: Model, audio: FloatArray): List<Segment> {
        val ctx = whisperContext(m)
        check(Whisper.transcribe(ctx, audio, WHISPER_THREADS)) { "whisper failed" }
        return List(Whisper.segments(ctx)) { i ->
            val times = Whisper.tokenTimes(ctx, i)
            val tokens = (0 until Whisper.tokens(ctx, i)).filter { times[it * 3 + 2] == 0L }.map { j ->
                Token(Whisper.tokenText(ctx, i, j).decodeToString(), times[j * 3], times[j * 3 + 1])
            }
            Segment(Whisper.segmentStartMs(ctx, i), Whisper.segmentEndMs(ctx, i), Whisper.segmentText(ctx, i).decodeToString(), words = wordsFromTokens(tokens))
        }
    }

    private fun senseVoice(audio: FloatArray, w: SpeechWindow): Pair<String, List<Segment>> {
        val rec = recognizer ?: OfflineRecognizer(
            null,
            OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(model = requireReady(Model.SenseVoice).path, language = "auto", useInverseTextNormalization = true),
                    tokens = requireReady(Model.SenseVoiceTokens).path,
                    numThreads = WHISPER_THREADS,
                ),
            ),
        ).also { recognizer = it }
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(audio, SAMPLE_RATE)
            rec.decode(stream)
            val r = rec.getResult(stream)
            val endMs = audio.size * 1000L / SAMPLE_RATE
            val tokens = r.tokens.mapIndexed { k, t ->
                val from = (r.timestamps.getOrElse(k) { 0f } * 1000).toLong()
                val to = if (k + 1 < r.tokens.size) (r.timestamps.getOrElse(k + 1) { 0f } * 1000).toLong() else endMs
                Token(if (t.startsWith("\u2581")) " " + t.drop(1) else if (t.firstOrNull()?.let(::isCjk) == true) " $t" else t, from, to)
            }
            val words = wordsFromTokens(tokens)
            var from = 0L
            val segments = w.ends(SAMPLE_RATE).mapNotNull { end ->
                val part = words.filter { it.startMs in from until end }
                val start = from
                from = end
                part.takeIf { it.isNotEmpty() }?.let { Segment(start, end, it.joinToString(" ") { x -> x.text }, words = it) }
            }
            return r.lang.removePrefix("<|").removeSuffix("|>") to segments
        } finally {
            stream.release()
        }
    }

    private fun whisperContext(m: Model): Long {
        whisper?.let { (loaded, ctx) -> if (loaded == m) return ctx else Whisper.free(ctx) }
        whisper = null
        val ctx = Whisper.load(requireReady(m).path)
        check(ctx != 0L) { "whisper could not load ${m.label}" }
        whisper = m to ctx
        return ctx
    }

    val diarizer = Diarizer { frames ->
        val segmentation = requireReady(Model.SpeakerSegmentation).path
        val embedding = requireReady(Model.SpeakerEmbedding).path
        track("speakers", "speakers", "pyannote + CAM++") {
            withContext(Dispatchers.Default) {
                val samples = Whisper.decodeOpus(frames) ?: error("opus decoder failed")
                val embed = SpeakerEmbeddingExtractorConfig(model = embedding, numThreads = WHISPER_THREADS)
                val config = OfflineSpeakerDiarizationConfig(
                    segmentation = OfflineSpeakerSegmentationModelConfig(OfflineSpeakerSegmentationPyannoteModelConfig(segmentation), numThreads = WHISPER_THREADS),
                    embedding = embed,
                    clustering = FastClusteringConfig(numClusters = -1, threshold = CLUSTER_THRESHOLD),
                    minDurationOn = MIN_DURATION_ON,
                    minDurationOff = MIN_DURATION_OFF,
                )
                val sd = OfflineSpeakerDiarization(null, config)
                val extractor = SpeakerEmbeddingExtractor(null, embed)
                try {
                    val rate = sd.sampleRate()
                    val turns = sd.process(samples).map { Turn((it.start * 1000).toLong(), (it.end * 1000).toLong(), it.speaker) }
                    diarization(samples, rate, turns) { part ->
                        val stream = extractor.createStream()
                        try {
                            stream.acceptWaveform(part, rate)
                            stream.inputFinished()
                            extractor.compute(stream)
                        } finally {
                            stream.release()
                        }
                    }
                } finally {
                    extractor.release()
                    sd.release()
                }
            }
        }
    }

    suspend fun nanoStatus(): Int = Generation.getClient().run { try { checkStatus() } finally { close() } }

    suspend fun downloadNano() = Generation.getClient().run { try { download().collect { } } finally { close() } }

    private val nano = Summarizer { text, sc ->
        val model = Generation.getClient()
        try {
            check(model.checkStatus() == FeatureStatus.AVAILABLE) { "Gemini Nano is not available" }
            val request = generateContentRequest(TextPart(summaryPrompt(sc) + text)) {
                temperature = 0.2f
                topK = 10
                maxOutputTokens = MAX_OUTPUT_TOKENS
            }
            parseSummary(model.generateContent(request).candidates.first().text, text)
        } finally {
            model.close()
        }
    }.inParts(NANO_PART_CHARS)

    private val gemma = Summarizer { text, sc ->
        val path = requireReady(Model.Gemma4E2B)
        withContext(Dispatchers.Default) {
            Engine(EngineConfig(modelPath = path.path, backend = Backend.CPU(), maxNumTokens = GEMMA_CONTEXT, cacheDir = context.cacheDir.path)).use { engine ->
                engine.initialize()
                val config = ConversationConfig(
                    samplerConfig = SamplerConfig(topK = 10, topP = 0.95, temperature = 0.2),
                    maxOutputToken = MAX_OUTPUT_TOKENS,
                    enableResponseFormat = true,
                )
                engine.createConversation(config).use { c ->
                    val reply = c.sendMessage(summaryPrompt(sc) + text, responseFormat = ResponseFormat.json(SUMMARY_SCHEMA))
                    parseSummary(reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }, text)
                }
            }
        }
    }.inParts(GEMMA_PART_CHARS)

    private suspend fun summarizeWith(e: SummaryEngine, text: String, sc: SummaryContext): Summary = when (e) {
        SummaryEngine.Nano -> track("summary", "summary", e.label) { nano.summarize(text, sc).copy(engine = e.label) }
        SummaryEngine.Gemma -> track("summary", "summary", e.label) { gemma.summarize(text, sc).copy(engine = e.label) }
        SummaryEngine.Laptop -> error("waiting for the laptop hub")
    }

    val summarizer = Summarizer { text, sc ->
        val open = onScreen()
        val e = if (open) summaryOpen.value else summaryClosed.value
        if (open && e == SummaryEngine.Nano) runCatching { summarizeWith(e, text, sc) }.getOrElse { if (it is CancellationException) throw it else summarizeWith(SummaryEngine.Gemma, text, sc) }
        else summarizeWith(e, text, sc)
    }
}

private fun Summarizer.inParts(maxChars: Int) = Summarizer { text, sc ->
    if (text.length <= maxChars) return@Summarizer summarize(text, sc)
    val parts = mutableListOf(StringBuilder())
    for (word in text.split(' ')) {
        if (parts.last().length + word.length + 1 > maxChars) parts += StringBuilder()
        parts.last().append(word).append(' ')
    }
    val sums = parts.map { summarize(it.toString(), sc) }.filter { it.title != NO_SPEECH }
    if (sums.isEmpty()) return@Summarizer Summary(NO_SPEECH, "")
    val merged = summarize(sums.joinToString("\n") { it.summary }, sc)
    Summary(merged.title.takeIf { it != NO_SPEECH } ?: sums.first().title, merged.summary, sums.flatMap { it.todos }.distinct())
}
