package dev.resticky.desktop

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import dev.resticky.AUDIO_KEEP
import dev.resticky.CLUSTER_THRESHOLD
import dev.resticky.Diarizer
import dev.resticky.MIN_DURATION_OFF
import dev.resticky.MIN_DURATION_ON
import dev.resticky.Turn
import dev.resticky.diarization
import java.nio.ByteBuffer
import java.nio.ByteOrder
import dev.resticky.CLOCK_DIAL
import dev.resticky.CLOCK_FACES
import dev.resticky.HOUR_24
import dev.resticky.HOUR_FORMATS
import dev.resticky.HUB_VERSION
import dev.resticky.WEATHER_INTERVAL
import dev.resticky.WEATHER_INTERVALS
import dev.resticky.FirmwareShelf
import dev.resticky.Hub
import dev.resticky.Releases
import dev.resticky.Pipeline
import dev.resticky.SUMMARY_SCHEMA
import dev.resticky.WEATHER_SKETCH
import dev.resticky.WEATHER_STYLES
import dev.resticky.Store
import dev.resticky.Summarizer
import dev.resticky.Summary
import dev.resticky.Transcriber
import dev.resticky.Weather
import dev.resticky.hubJson
import dev.resticky.opusFramesToOgg
import dev.resticky.parseWhisperJson
import dev.resticky.summaryPrompt
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
private const val SEGMENTATION_MODEL = "pyannote-segmentation-3-0.onnx"
private const val EMBEDDING_MODEL = "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"

private val VIRTUAL = listOf("docker", "br-", "veth", "virbr", "lo")

fun log(message: String) = println("${Instant.now()} $message")

private class Flags(args: Array<String>) {
    private val values = mutableMapOf<String, String>()

    init {
        var i = 0
        while (i < args.size) {
            val name = args[i].trimStart('-')
            if ('=' in name) values[name.substringBefore('=')] = name.substringAfter('=')
            else values[name] = args.getOrElse(++i) { "" }
            i++
        }
    }

    fun string(name: String, default: String) = values[name] ?: default
}

private fun run(dir: File?, vararg command: String, input: String? = null, merge: Boolean = true): String {
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

private fun whisper(model: String) = Transcriber { opus ->
    runInterruptible(Dispatchers.IO) {
        val base = opus.toString().removeSuffix(".opus")
        File("$base.ogg").writeBytes(opusFramesToOgg(File(opus.toString()).readBytes()))
        val wav = File("$base.wav")
        val json = File("$base.json")
        try {
            run(null, "ffmpeg", "-y", "-loglevel", "error", "-i", "$base.ogg", "-ar", "16000", "-ac", "1", wav.path)
            run(null, "whisper-cli", "-m", model, "-f", wav.path, "-l", "en", "-np", "-t", "8", "-ojf", "-of", base, merge = false)
            parseWhisperJson(json.readText())
        } finally {
            wav.delete()
            json.delete()
        }
    }
}

private fun pcm(frames: ByteArray): FloatArray {
    val ogg = Files.createTempFile("resticky-diar", ".ogg").toFile()
    try {
        ogg.writeBytes(opusFramesToOgg(frames))
        val p = ProcessBuilder("ffmpeg", "-loglevel", "error", "-i", ogg.path, "-f", "f32le", "-ac", "1", "-ar", "16000", "-")
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

private fun speakers(segmentation: String, embedding: String) = Diarizer { frames ->
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
            hubJson.decodeFromString<Summary>(out.readText().trim())
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

fun main(args: Array<String>) {
    val home = System.getProperty("user.home")
    val flags = Flags(args)
    val port = flags.string("port", "8787").toInt()
    val data = flags.string("data", "$home/.local/share/resticky/data")
    val model = flags.string("model", "$home/.local/share/resticky/models/ggml-base.en.bin")
    val store = Store(IoPath(data))
    val segmentation = flags.string("segmentation", "$home/.local/share/resticky/models/$SEGMENTATION_MODEL")
    val embedding = flags.string("embedding", "$home/.local/share/resticky/models/$EMBEDDING_MODEL")
    val pipeline = Pipeline(store, whisper(model), codex, speakers(segmentation, embedding), log = ::log)
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
    val beep = flags.string("beep", "on")
    require(beep in setOf("on", "off")) { "-beep must be on or off" }
    val firmware = FirmwareShelf(IoPath(flags.string("firmware", "$home/.local/share/resticky/firmware")))
    val hub = Hub(store, pipeline, weather, firmware, ::log)
    hub.clockFace.value = clockFace
    hub.weatherStyle.value = weatherStyle
    hub.hourFormat.value = hourFormat
    hub.beep.value = beep == "on"
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
    if (flags.string("updates", "on") == "on") scope.launch {
        val releases = Releases(IoPath("$home/.local/share/resticky/releases.json"), ::log)
        while (true) {
            releases.check(force = false)
            releases.mirrorFirmware(firmware)
            delay(1.hours)
        }
    }
    scope.launch {
        while (true) {
            delay(1.hours)
            store.deleteOldAudio(Clock.System.now() - AUDIO_KEEP)
        }
    }
    log("INFO resticky hub listening port=$port data=$data model=$model segmentation=$segmentation embedding=$embedding mdns=${announcer != null} city=${city ?: "none"} clock_face=$clockFace weather_style=$weatherStyle hour_format=$hourFormat beep=$beep weather_interval=$weatherInterval")
    embeddedServer(CIO, port = port) {
        hub.module(this)
    }.start(wait = true)
}
