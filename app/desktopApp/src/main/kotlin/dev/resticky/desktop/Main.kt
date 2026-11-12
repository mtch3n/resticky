package dev.resticky.desktop

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dev.resticky.AUDIO_KEEP
import dev.resticky.CONTEXT_MS
import dev.resticky.MIN_SPEECH_MS
import dev.resticky.Piece
import dev.resticky.placeSegments
import dev.resticky.speechMs
import dev.resticky.speechWindows
import dev.resticky.CLUSTER_THRESHOLD
import dev.resticky.Diarizer
import dev.resticky.People
import dev.resticky.MIN_DURATION_OFF
import dev.resticky.MIN_DURATION_ON
import dev.resticky.Turn
import dev.resticky.diarization
import java.nio.ByteBuffer
import java.nio.ByteOrder
import dev.resticky.CLOCK_DIAL
import dev.resticky.CLOCK_FACES
import dev.resticky.HOME_APPS
import dev.resticky.NIGHT_OFF
import dev.resticky.validNight
import dev.resticky.HOME_SCREENS
import dev.resticky.SOUNDS
import dev.resticky.SOUND_BEEP
import dev.resticky.BUZZ_HZ
import dev.resticky.BUZZ_RANGE
import dev.resticky.HOUR_24
import dev.resticky.HOUR_FORMATS
import dev.resticky.HUB_VERSION
import dev.resticky.WEATHER_INTERVAL
import dev.resticky.WEATHER_INTERVALS
import dev.resticky.FirmwareShelf
import dev.resticky.Hub
import dev.resticky.Pipeline
import dev.resticky.SUMMARY_SCHEMA
import dev.resticky.WEATHER_SKETCH
import dev.resticky.WEATHER_STYLES
import dev.resticky.Store
import dev.resticky.TOKEN_PATTERN
import dev.resticky.Summarizer
import dev.resticky.Summary
import dev.resticky.Transcriber
import dev.resticky.Vocabulary
import dev.resticky.Weather
import dev.resticky.hubJson
import dev.resticky.opusFramesToOgg
import dev.resticky.parseWhisperJson
import dev.resticky.summaryPrompt
import dev.resticky.parseSummary
import dev.resticky.PIPELINE_VERSION
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.nio.file.Files
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import javax.jmdns.impl.JmDNSImpl
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.io.files.Path as IoPath

private const val SHERPA_THREADS = 4
private const val RATE = 16_000
private const val VAD_WINDOW = 512
private const val VAD_MODEL = "silero_vad.onnx"
private const val VAD_OFF = "off"
private const val SEGMENTATION_MODEL = "pyannote-segmentation-3-0.onnx"
private const val EMBEDDING_MODEL = "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"

private val SWITCHES = setOf("insecure")

private val VIRTUAL = listOf("docker", "br-", "veth", "virbr", "lo")

fun log(message: String) = println("${Instant.now()} $message")

internal class Flags(args: List<String>, switches: Set<String> = SWITCHES) {
    private val values = mutableMapOf<String, String>()
    val positional = mutableListOf<String>()

    init {
        var i = 0
        while (i < args.size) {
            val name = args[i].trimStart('-')
            if (!args[i].startsWith("-")) positional += args[i]
            else if ('=' in name) values[name.substringBefore('=')] = name.substringAfter('=')
            else if (name in switches) values[name] = "true"
            else values[name] = args.getOrElse(++i) { "" }
            i++
        }
    }

    fun string(name: String, default: String) = values[name] ?: default

    fun on(name: String) = values[name] == "true"
}

internal class Engines(flags: Flags) {
    private val models = "${System.getProperty("user.home")}/.local/share/resticky/models"
    val model = flags.string("model", "$models/ggml-small.en-q8_0.bin")
    val vad = flags.string("vad", "$models/$VAD_MODEL")
    val segmentation = flags.string("segmentation", "$models/$SEGMENTATION_MODEL")
    val embedding = flags.string("embedding", "$models/$EMBEDDING_MODEL")

    fun transcriber() = whisper(model, SpeechDetector(vad))

    fun diarizer() = speakers(segmentation, embedding)

    fun speechId() = "${File(model).name}/$PIPELINE_VERSION"
}

internal fun run(dir: File?, vararg command: String, input: String? = null, merge: Boolean = true): String {
    val pb = ProcessBuilder(*command).directory(dir)
    if (merge) pb.redirectErrorStream(true) else pb.redirectError(ProcessBuilder.Redirect.DISCARD)
    val p = pb.start()
    input?.let { p.outputStream.bufferedWriter().use { w -> w.write(it) } } ?: p.outputStream.close()
    val out = p.inputStream.bufferedReader().readText()
    if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) {
        error("${command[0]} failed: ${out.lines().takeLast(5).joinToString("\n")}")
    }
    return out
}

internal class SpeechDetector(private val model: String) {
    private val vad by lazy {
        check(File(model).isFile) { "speech detector missing: $model" }
        Vad(
            VadModelConfig.builder()
                .setSileroVadModelConfig(
                    SileroVadModelConfig.builder().setModel(model).setThreshold(0.5f).setMinSilenceDuration(0.4f)
                        .setMinSpeechDuration(0.25f).setWindowSize(VAD_WINDOW).setMaxSpeechDuration(20f).build(),
                )
                .setSampleRate(RATE).setNumThreads(1).build(),
        )
    }

    @Synchronized
    fun regions(pcm: FloatArray): List<Piece> {
        if (model == VAD_OFF) return listOf(Piece(0, pcm.size))
        vad.reset()
        val parts = mutableListOf<Piece>()
        fun drain() {
            while (!vad.empty()) {
                val s = vad.front()
                parts += Piece(s.start.toInt(), s.start.toInt() + s.samples.size)
                vad.pop()
            }
        }
        var i = 0
        while (i < pcm.size) {
            vad.acceptWaveform(pcm.copyOfRange(i, minOf(i + VAD_WINDOW, pcm.size)))
            i += VAD_WINDOW
            drain()
        }
        vad.flush()
        drain()
        return parts
    }
}

private fun writeWav(file: File, pcm: FloatArray) {
    val b = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    b.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        .putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size * 2)
    pcm.forEach { b.putShort((it.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()) }
    file.writeBytes(b.array())
}

private fun previousTail(opus: File): FloatArray {
    val seq = opus.nameWithoutExtension.toIntOrNull() ?: return FloatArray(0)
    val prev = File(opus.parentFile, "${seq - 1}.opus")
    if (!prev.isFile) return FloatArray(0)
    val samples = pcm(prev.readBytes())
    return samples.copyOfRange(maxOf(0, samples.size - (CONTEXT_MS * RATE / 1000).toInt()), samples.size)
}

internal fun whisper(model: String, detector: SpeechDetector) = Transcriber { opus, prompt ->
    runInterruptible(Dispatchers.IO) {
        val file = File(opus.toString())
        val frames = file.readBytes()
        File(file.path.removeSuffix(".opus") + ".ogg").writeBytes(opusFramesToOgg(frames))
        val context = previousTail(file)
        val all = context + pcm(frames)
        val regions = detector.regions(all)
        if (speechMs(regions, context.size, RATE) < MIN_SPEECH_MS) return@runInterruptible emptyList()
        val windows = speechWindows(regions, all.size, context.size, RATE)
        val dir = Files.createTempDirectory("resticky-asr").toFile()
        try {
            val wavs = windows.mapIndexed { i, w -> File(dir, "$i.wav").also { writeWav(it, w.audio(all)) } }
            val hint = if (prompt.isEmpty()) emptyArray() else arrayOf("--prompt", prompt)
            run(null, "whisper-cli", "-m", model, *wavs.flatMap { listOf("-f", it.path) }.toTypedArray(), "-l", "en", "-np", "-t", "8", "-ojf", *hint, merge = false)
            windows.flatMapIndexed { i, w -> placeSegments(parseWhisperJson(File(dir, "$i.wav.json").readText()), w, context.size, RATE) }
        } finally {
            dir.deleteRecursively()
        }
    }
}

private fun pcm(frames: ByteArray): FloatArray {
    val ogg = Files.createTempFile("resticky-pcm", ".ogg").toFile()
    try {
        ogg.writeBytes(opusFramesToOgg(frames))
        val p = ProcessBuilder("ffmpeg", "-loglevel", "error", "-i", ogg.path, "-f", "f32le", "-ac", "1", "-ar", "$RATE", "-")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        p.outputStream.close()
        val bytes = p.inputStream.readBytes()
        check(p.waitFor() == 0) { "ffmpeg failed" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buf.remaining()).also { buf.get(it) }
    } finally {
        ogg.delete()
    }
}

internal fun speakers(segmentation: String, embedding: String) = Diarizer { frames ->
    runInterruptible(Dispatchers.IO) {
        check(File(segmentation).isFile && File(embedding).isFile) { "speaker models missing: $segmentation $embedding" }
        val samples = pcm(frames)
        val embed = SpeakerEmbeddingExtractorConfig.builder().setModel(embedding).setNumThreads(SHERPA_THREADS).build()
        val config = OfflineSpeakerDiarizationConfig.builder()
            .setSegmentation(
                OfflineSpeakerSegmentationModelConfig.builder()
                    .setPyannote(OfflineSpeakerSegmentationPyannoteModelConfig.builder().setModel(segmentation).build())
                    .setNumThreads(SHERPA_THREADS).build(),
            )
            .setEmbedding(embed)
            .setClustering(FastClusteringConfig.builder().setNumClusters(-1).setThreshold(CLUSTER_THRESHOLD).build())
            .setMinDurationOn(MIN_DURATION_ON)
            .setMinDurationOff(MIN_DURATION_OFF)
            .build()
        val sd = OfflineSpeakerDiarization(config)
        val extractor = SpeakerEmbeddingExtractor(embed)
        try {
            val turns = sd.process(samples).map { Turn((it.start * 1000).toLong(), (it.end * 1000).toLong(), it.speaker) }
            diarization(samples, sd.sampleRate, turns) { part ->
                val stream = extractor.createStream()
                try {
                    stream.acceptWaveform(part, sd.sampleRate)
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

private val codex = Summarizer { transcript, context ->
    runInterruptible(Dispatchers.IO) {
        val dir = Files.createTempDirectory("resticky-sum").toFile()
        try {
            val schema = File(dir, "schema.json").apply { writeText(SUMMARY_SCHEMA) }
            val out = File(dir, "out.json")
            run(
                dir, "codex", "exec", "-m", "gpt-6-luna", "-c", "model_reasoning_effort=low", "-s", "read-only",
                "--skip-git-repo-check", "--ephemeral", "--output-schema", schema.path, "-o", out.path, "-",
                input = summaryPrompt(context) + transcript,
            )
            parseSummary(out.readText(), transcript)
        } finally {
            dir.deleteRecursively()
        }
    }
}

private fun lanAddresses(): List<InetAddress> = NetworkInterface.networkInterfaces().toList()
    .filter { i -> i.isUp && i.supportsMulticast() && VIRTUAL.none { i.name.startsWith(it) } }
    .flatMap { it.inetAddresses.toList() }
    .filterIsInstance<Inet4Address>()

private val MDNS_CHECK = 30.seconds

private class Announcer(private val port: Int) {
    private val host = InetAddress.getLocalHost().hostName
    private val live = mutableMapOf<InetAddress, JmDNS>()

    @Synchronized
    fun refresh() {
        val wanted = lanAddresses().toSet()
        live.entries.removeIf { (addr, mdns) ->
            val dead = addr !in wanted || (mdns as JmDNSImpl).let { it.isCanceled || it.isClosed }
            if (dead) {
                runCatching { mdns.close() }
                log("INFO mdns stopped address=${addr.hostAddress}")
            }
            dead
        }
        (wanted - live.keys).forEach { addr ->
            runCatching {
                live[addr] = JmDNS.create(addr, host).apply {
                    registerService(ServiceInfo.create("_resticky._tcp.local.", "resticky-hub $host", port, 0, 0, mapOf("version" to HUB_VERSION, "prio" to "5")))
                }
                log("INFO mdns announced address=${addr.hostAddress}")
            }.onFailure { log("WARN mdns announce failed address=${addr.hostAddress} error=${it.message}") }
        }
    }

    @Synchronized
    fun close() = live.values.forEach { runCatching { it.close() } }
}

private fun hubToken(flags: Flags): String? {
    val file = flags.string("token-file", "")
    val token = (if (file.isEmpty()) flags.string("token", "") else File(file).readText()).trim().lowercase().ifEmpty { null }
    val insecure = flags.on("insecure")
    require(token != null || insecure) { "need -token <hex> or -token-file <path> (the hub password from the phone); -insecure runs with no password, only for local tests" }
    require(token == null || TOKEN_PATTERN.matches(token)) { "the hub password must be 64 hex characters (the hub password from the phone)" }
    if (token == null) log("WARN no hub password (-insecure): every device on the network can use this hub")
    return token
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "eval") return evalMain(args.drop(1))
    val home = System.getProperty("user.home")
    val flags = Flags(args.toList())
    val token = hubToken(flags)
    val port = flags.string("port", "8787").toInt()
    val data = flags.string("data", "$home/.local/share/resticky/data")
    val engines = Engines(flags)
    val model = engines.model
    val store = Store(IoPath(data))
    val segmentation = engines.segmentation
    val embedding = engines.embedding
    val vad = engines.vad
    val people = People(IoPath(File(data).resolveSibling("people.json").path))
    val pipeline = Pipeline(store, engines.transcriber(), codex, engines.diarizer(), speechId = engines::speechId, people = people, vocabulary = Vocabulary(IoPath(File(File(data).absoluteFile.parentFile, "vocabulary.json").path)), log = ::log)
    val city = flags.string("city", "").ifBlank { null }
    val weatherInterval = flags.string("weather-interval", "$WEATHER_INTERVAL").toInt()
    require(weatherInterval in WEATHER_INTERVALS) { "-weather-interval must be one of ${WEATHER_INTERVALS.joinToString("|")}" }
    val weather = Weather(IoPath(flags.string("weather", "$home/.local/share/resticky/weather.json")), null, ::log, city, weatherInterval)
    val clockFace = flags.string("clock-face", CLOCK_DIAL)
    require(clockFace in CLOCK_FACES) { "-clock-face must be one of ${CLOCK_FACES.joinToString("|")}" }
    val weatherStyle = flags.string("weather-style", WEATHER_SKETCH)
    require(weatherStyle in WEATHER_STYLES) { "-weather-style must be one of ${WEATHER_STYLES.joinToString("|")}" }
    val hourFormat = flags.string("hour-format", HOUR_24)
    require(hourFormat in HOUR_FORMATS) { "-hour-format must be one of ${HOUR_FORMATS.joinToString("|")}" }
    val sound = flags.string("sound", SOUND_BEEP)
    require(sound in SOUNDS) { "-sound must be one of ${SOUNDS.joinToString("|")}" }
    val night = flags.string("night", NIGHT_OFF)
    require(validNight(night)) { "-night must be off or HH:MM-HH:MM (minutes 00 or 30)" }
    val homeScreen = flags.string("home", HOME_APPS)
    require(homeScreen in HOME_SCREENS) { "-home must be one of ${HOME_SCREENS.joinToString("|")}" }
    val firmware = FirmwareShelf(IoPath(flags.string("firmware", "$home/.local/share/resticky/firmware")))
    val hub = Hub(store, pipeline, weather, firmware, ::log, token = token)
    hub.clockFace.value = clockFace
    hub.weatherStyle.value = weatherStyle
    hub.hourFormat.value = hourFormat
    hub.soundSetting.load(sound)
    hub.buzzSetting.load(flags.string("buzz-hz", "$BUZZ_HZ").toInt().also { require(it in BUZZ_RANGE) { "-buzz-hz must be in $BUZZ_RANGE" } })
    hub.nightSetting.load(night)
    hub.homeSetting.load(homeScreen)
    val announcer = if (flags.string("mdns", "true") == "true") Announcer(port) else null
    Runtime.getRuntime().addShutdownHook(Thread { announcer?.close() })
    val scope = CoroutineScope(Dispatchers.Default)
    if (announcer != null) scope.launch(Dispatchers.IO) {
        while (true) {
            runCatching { announcer.refresh() }.onFailure { log("WARN mdns check failed error=${it.message}") }
            delay(MDNS_CHECK)
        }
    }
    scope.launch { pipeline.run() }
    scope.launch { weather.run() }

    scope.launch {
        while (true) {
            delay(1.hours)
            store.deleteOldAudio(Clock.System.now() - AUDIO_KEEP)
        }
    }
    log("INFO resticky hub listening port=$port data=$data model=$model vad=$vad segmentation=$segmentation embedding=$embedding mdns=${announcer != null} city=${city ?: "none"} clock_face=$clockFace weather_style=$weatherStyle hour_format=$hourFormat sound=$sound home=$homeScreen night=$night weather_interval=$weatherInterval")
    embeddedServer(CIO, port = port) {
        hub.module(this)
    }.start(wait = true)
}
