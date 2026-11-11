package dev.resticky.app

import android.media.audiofx.DynamicsProcessing
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserDpdfNetModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

enum class Crisp(val label: String) {
    Off("Off"),
    Clear("Clear"),
    Denoise("Clear + denoise"),
}

private const val VOICE_RATE = 16_000
private const val FADE = VOICE_RATE / 40
private const val GAP = VOICE_RATE / 50
private const val CROSS = VOICE_RATE / 20
private const val TARGET_DB = -18f
private const val GATE_DB = -50f
private const val LEVEL_TAU_S = 3f
private const val MIN_GAIN_DB = -6f
private const val MAX_GAIN_DB = 20f

private fun clearConfig(): DynamicsProcessing.Config {
    val eq = listOf(90f to -24f, 250f to 0f, 400f to -2f, 2000f to 0f, 5000f to 3.5f, 8000f to 1f)
    val config = DynamicsProcessing.Config.Builder(
        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 1, true, eq.size, true, 3, false, 0, true,
    ).setPreferredFrameDuration(10f).build()
    val pre = DynamicsProcessing.Eq(true, true, eq.size)
    eq.forEachIndexed { i, (hz, db) -> pre.setBand(i, DynamicsProcessing.EqBand(true, hz, db)) }
    config.setPreEqAllChannelsTo(pre)
    val mbc = DynamicsProcessing.Mbc(true, true, 3)
    listOf(300f, 3000f, 8000f).forEachIndexed { i, hz ->
        mbc.setBand(i, DynamicsProcessing.MbcBand(true, hz, 10f, 150f, 2f, -30f, 6f, -90f, 1f, 0f, 2f))
    }
    config.setMbcAllChannelsTo(mbc)
    config.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -2f, 0f))
    return config
}

class VoiceChain(session: Int, private val denoiserModel: () -> String?) {
    @Volatile var target = Crisp.Off
    var current = Crisp.Off
        private set
    private val effect = runCatching { DynamicsProcessing(0, session, clearConfig()).apply { enabled = false } }.getOrNull()
    private var denoiser: OnlineSpeechDenoiser? = null
    private var lastLook = 0L
    private var pending = FloatArray(0)
    private var progress = 0
    private var levelDb = TARGET_DB
    private var gain = 1f
    var effectOn: Boolean
        get() = effect?.enabled == true
        set(on) {
            effect?.enabled = on
        }

    fun reset() {
        denoiser?.reset()
        pending = FloatArray(0)
    }

    private fun denoised(dry: FloatArray, n: Int, enhanced: FloatArray?): FloatArray? {
        if (enhanced != null) return enhanced
        val now = System.nanoTime()
        val d = denoiser ?: (if (now - lastLook > 2_000_000_000L) { lastLook = now; denoiserModel() } else null)?.let { path ->
            runCatching {
                OnlineSpeechDenoiser(null, OnlineSpeechDenoiserConfig(OfflineSpeechDenoiserModelConfig(dpdfnet = OfflineSpeechDenoiserDpdfNetModelConfig(path), numThreads = 1)))
            }.getOrNull()?.also { denoiser = it }
        } ?: return null
        pending += d.run(dry.copyOf(n), VOICE_RATE).samples
        val out = FloatArray(n)
        val have = minOf(n, pending.size)
        pending.copyInto(out, n - have, 0, have)
        pending = pending.copyOfRange(have, pending.size)
        return out
    }

    private fun level(x: FloatArray, n: Int) {
        val rms = sqrt((0 until n).sumOf { (x[it] * x[it]).toDouble() } / n).toFloat()
        val db = 20 * log10(rms.coerceAtLeast(1e-9f))
        if (db > GATE_DB) levelDb += (db - levelDb) * (n.toFloat() / VOICE_RATE / LEVEL_TAU_S)
    }

    fun process(dry: FloatArray, n: Int, enhanced: FloatArray?, toggleAt: (Int, Boolean) -> Unit) {
        val want = target
        val wet = if (current == Crisp.Denoise || want == Crisp.Denoise) denoised(dry, n, enhanced) else null
        level(wet ?: dry, n)
        val goal = 10f.pow((TARGET_DB - levelDb).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) / 20)
        fun sample(mode: Crisp, k: Int, g: Float) = when (mode) {
            Crisp.Off -> dry[k]
            Crisp.Clear -> dry[k] * g
            Crisp.Denoise -> (wet?.get(k) ?: dry[k]) * g
        }
        val effectChange = (current == Crisp.Off) != (want == Crisp.Off)
        for (k in 0 until n) {
            gain += (goal - gain) / VOICE_RATE * 4
            if (want == current) {
                progress = 0
                dry[k] = sample(current, k, gain)
                continue
            }
            if (effectChange) {
                val p = progress
                dry[k] = when {
                    p < FADE -> sample(current, k, gain) * (1 - p.toFloat() / FADE)
                    p < FADE + GAP -> 0f
                    else -> sample(want, k, gain) * ((p - FADE - GAP).toFloat() / FADE)
                }
                if (p == FADE + GAP / 2) toggleAt(k, want != Crisp.Off)
                if (++progress >= 2 * FADE + GAP) {
                    current = want
                    progress = 0
                }
            } else {
                val t = progress.toFloat() / CROSS
                dry[k] = sample(current, k, gain) * (1 - t) + sample(want, k, gain) * t
                if (++progress >= CROSS) {
                    current = want
                    progress = 0
                }
            }
        }
    }

    fun release() {
        effect?.release()
        denoiser?.release()
    }
}
