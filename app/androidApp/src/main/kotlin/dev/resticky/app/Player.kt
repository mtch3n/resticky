package dev.resticky.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import dev.resticky.timeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import dev.resticky.nextPlay

private const val SKIP_MS = 15_000L
private val Track = Color(0xFFDDDDDD)

enum class Audio { Loading, Ready, Missing }

private const val RATE = 16_000
private const val FRAME_MS = 20
private const val FRAME_SAMPLES = RATE * FRAME_MS / 1000
private const val FADE_SAMPLES = RATE / 200

class SessionAudio(val frames: ByteArray, val enhanced: ByteArray?)

class Engine(audio: SessionAudio, denoiserModel: () -> String?) {
    private val frames = audio.frames
    private val enhanced = audio.enhanced
    private val starts: IntArray
    private val lengths: IntArray
    val durationMs: Long
    private val decoder = Whisper.opusOpen()
    private val chain: VoiceChain
    @Volatile private var effectNext = false
    private val enhancedDecoder = if (audio.enhanced != null) Whisper.opusOpen() else 0L
    private val enhancedStarts: IntArray
    private val enhancedLengths: IntArray
    private val track: AudioTrack
    private val lock = Object()
    private val played = ArrayDeque<Pair<Long, Long>>()
    private var written = 0L
    private var headBase = 0L
    private var index = 0
    private var fade = true
    @Volatile var playing = false
        private set
    @Volatile private var seekTo: Long? = null
    @Volatile private var closed = false
    @Volatile var skips: List<LongRange> = emptyList()
    private val thread = Thread(::run, "resticky-player")

    init {
        val s = mutableListOf<Int>()
        val l = mutableListOf<Int>()
        var i = 0
        while (i + 2 <= frames.size) {
            val n = (frames[i].toInt() and 0xff) or ((frames[i + 1].toInt() and 0xff) shl 8)
            if (i + 2 + n > frames.size) break
            s += i + 2
            l += n
            i += 2 + n
        }
        starts = s.toIntArray()
        lengths = l.toIntArray()
        val es = mutableListOf<Int>()
        val el = mutableListOf<Int>()
        val e = audio.enhanced ?: ByteArray(0)
        i = 0
        while (i + 2 <= e.size) {
            val n = (e[i].toInt() and 0xff) or ((e[i + 1].toInt() and 0xff) shl 8)
            if (i + 2 + n > e.size) break
            es += i + 2
            el += n
            i += 2 + n
        }
        enhancedStarts = es.toIntArray()
        enhancedLengths = el.toIntArray()
        durationMs = starts.size.toLong() * FRAME_MS
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, RATE / 5 * 4))
            .build()
        check(decoder != 0L) { "opus decoder failed" }
        chain = VoiceChain(track.audioSessionId, denoiserModel)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack) {
                chain.effectOn = effectNext
            }

            override fun onPeriodicNotification(t: AudioTrack) = Unit
        }, android.os.Handler(android.os.Looper.getMainLooper()))
        thread.start()
    }

    var crisp: Crisp
        get() = chain.target
        set(value) {
            chain.target = value
        }

    val audioSession: Int get() = track.audioSessionId

    fun play() {
        if (index >= starts.size && seekTo == null) seekTo = 0
        playing = true
    }

    fun pause() {
        playing = false
    }

    fun seek(ms: Long) {
        seekTo = ms.coerceIn(0, durationMs)
    }

    fun positionMs(): Long = synchronized(lock) {
        val head = (track.playbackHeadPosition.toLong() and 0xffffffffL) - headBase
        val next = played.firstOrNull { it.first > head } ?: return@synchronized played.lastOrNull()?.second?.plus(FRAME_MS) ?: index.toLong() * FRAME_MS
        next.second + (FRAME_SAMPLES - (next.first - head)).coerceIn(0, FRAME_SAMPLES.toLong()) * 1000 / RATE
    }

    fun close() {
        closed = true
        thread.join(500)
        chain.release()
        track.release()
        Whisper.opusClose(decoder)
        if (enhancedDecoder != 0L) Whisper.opusClose(enhancedDecoder)
    }

    private fun jump(frame: Int) {
        index = frame.coerceIn(0, starts.size)
        Whisper.opusReset(decoder)
        if (enhancedDecoder != 0L) Whisper.opusReset(enhancedDecoder)
        chain.reset()
        fade = true
    }

    private fun run() {
        val out = FloatArray(FRAME_SAMPLES * 6)
        val better = FloatArray(FRAME_SAMPLES * 6)
        while (!closed) {
            seekTo?.let { ms ->
                seekTo = null
                track.pause()
                track.flush()
                synchronized(lock) {
                    played.clear()
                    written = 0
                    headBase = track.playbackHeadPosition.toLong() and 0xffffffffL
                    jump((ms / FRAME_MS).toInt())
                }
            }
            if (!playing || index >= starts.size) {
                if (index >= starts.size) playing = false
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                Thread.sleep(10)
                continue
            }
            nextPlay(index.toLong() * FRAME_MS, skips)?.let { jump(((it + FRAME_MS) / FRAME_MS).toInt()) }
            if (index >= starts.size) continue
            val n = Whisper.opusFrame(decoder, frames, starts[index], lengths[index], out)
            if (n <= 0) {
                index++
                continue
            }
            val e = if (enhancedDecoder != 0L && index < enhancedStarts.size && Whisper.opusFrame(enhancedDecoder, enhanced!!, enhancedStarts[index], enhancedLengths[index], better) >= n) better else null
            val base = synchronized(lock) { headBase + written }
            chain.process(out, n, e) { k, on ->
                effectNext = on
                track.notificationMarkerPosition = (base + k).toInt()
            }
            if (fade) {
                for (k in 0 until minOf(FADE_SAMPLES, n)) out[k] *= k.toFloat() / FADE_SAMPLES
                fade = false
            }
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
            synchronized(lock) {
                written += n
                played.addLast(written to index.toLong() * FRAME_MS)
                if (played.size > 400) played.removeFirst()
            }
            track.write(out, 0, n, AudioTrack.WRITE_BLOCKING)
            index++
        }
    }
}

class Playback {
    var audio by mutableStateOf(Audio.Loading)
    var playing by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0)
    var durationMs by mutableLongStateOf(0)
    var engine by mutableStateOf<Engine?>(null)

    fun toggle() {
        val e = engine ?: return
        if (e.playing) e.pause() else e.play()
        playing = e.playing
    }

    fun seek(ms: Long) {
        val e = engine ?: return
        val to = ms.coerceIn(0, durationMs)
        e.seek(to)
        positionMs = to
    }

    fun skip(ms: Long) = seek(positionMs + ms)
}

@Composable
fun rememberPlayback(device: String, id: String, skips: List<LongRange>, crisp: Crisp, denoiserModel: () -> String?, audio: suspend () -> SessionAudio?): Playback {
    val playback = remember(device, id) { Playback() }
    LaunchedEffect(playback) {
        val engine = runCatching { withContext(Dispatchers.IO) { audio()?.let { Engine(it, denoiserModel) } } }.getOrNull()
        if (engine == null) {
            playback.audio = Audio.Missing
            return@LaunchedEffect
        }
        playback.engine = engine
        playback.durationMs = engine.durationMs
        playback.audio = Audio.Ready
        while (true) {
            playback.positionMs = engine.positionMs()
            playback.playing = engine.playing
            delay(50)
        }
    }
    LaunchedEffect(playback, skips, crisp) {
        snapshotFlow { playback.engine }.collect {
            it?.skips = skips
            it?.crisp = crisp
        }
    }
    DisposableEffect(playback) {
        onDispose {
            playback.engine?.close()
            playback.engine = null
        }
    }
    return playback
}

@Composable
private fun SeekBar(fraction: Float, marks: List<Float>, photos: List<Float>, onSeek: (Float) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val shown = (drag ?: fraction).coerceIn(0f, 1f)
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(30.dp)
            .pointerInput(Unit) { detectTapGestures { seek((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { drag?.let(seek); drag = null },
                    onDragCancel = { drag = null },
                ) { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f) }
            },
    ) {
        val w = maxWidth
        marks.forEach { Box(Modifier.offset(x = w * it - 7.dp)) { Glyph(R.drawable.ic_star_filled, 14.dp) } }
        photos.forEach { Box(Modifier.offset(x = w * it - 7.dp)) { Glyph(R.drawable.ic_camera, 14.dp) } }
        Box(Modifier.offset(y = 19.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(Track))
        Box(Modifier.offset(y = 19.dp).width(w * shown).height(4.dp).clip(CircleShape).background(Ink.black))
        Box(Modifier.offset(x = w * shown - 7.dp, y = 14.dp).size(14.dp).clip(CircleShape).background(Ink.black))
    }
}

data class PlayerPrefs(val cleanUp: Boolean = false, val skipSilence: Boolean = false, val crisp: Crisp = Crisp.Off)

@Composable
private fun Option(text: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(if (on) Ink.black else Ink.card).clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (on) Glyph(R.drawable.ic_check, 14.dp, Ink.white)
        Text(text, style = Type.button.copy(fontSize = 13.sp, color = if (on) Ink.white else Ink.black))
    }
}

@Composable
fun PlayerBar(playback: Playback, marks: List<Long>, photos: List<Long>, prefs: PlayerPrefs, setPrefs: (PlayerPrefs) -> Unit, clock: (Long) -> Long) {
    val total = playback.durationMs.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().background(Ink.white)) {
        HorizontalDivider(thickness = 1.dp, color = Ink.navLine)
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SeekBar(playback.positionMs.toFloat() / total, marks.map { it.toFloat() / total }, photos.map { it.toFloat() / total }) { playback.seek((it * total).toLong()) }
            Row {
                Text(timeLabel(clock(playback.positionMs)), style = Type.pill.copy(color = Ink.muted))
                Spacer(Modifier.weight(1f))
                Text(timeLabel(clock(playback.durationMs)), style = Type.pill.copy(color = Ink.muted))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(34.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(CircleShape).clickable { playback.skip(-SKIP_MS) }.padding(6.dp)) { Glyph(R.drawable.ic_rewind_backward_15, 30.dp) }
                Box(Modifier.size(60.dp).clip(CircleShape).background(Ink.black).clickable { playback.toggle() }, contentAlignment = Alignment.Center) {
                    Glyph(if (playback.playing) R.drawable.ic_player_pause_filled else R.drawable.ic_player_play_filled, 26.dp, Ink.white)
                }
                Box(Modifier.clip(CircleShape).clickable { playback.skip(SKIP_MS) }.padding(6.dp)) { Glyph(R.drawable.ic_rewind_forward_15, 30.dp) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                Option("Clean up", prefs.cleanUp) { setPrefs(prefs.copy(cleanUp = !prefs.cleanUp)) }
                Option("Skip silence", prefs.skipSilence) { setPrefs(prefs.copy(skipSilence = !prefs.skipSilence)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                Text("Crisp voice", style = Type.small.copy(fontWeight = FontWeight.Bold))
                Segmented(Crisp.entries.map { it to it.label }, prefs.crisp) { setPrefs(prefs.copy(crisp = it)) }
            }
        }
    }
}
