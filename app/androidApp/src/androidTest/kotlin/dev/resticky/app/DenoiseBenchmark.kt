package dev.resticky.app

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserDpdfNetModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DenoiseBenchmark {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val block = 320

    private fun audio(): FloatArray {
        val sessions = File(context.filesDir, "data").listFiles()!!.flatMap { it.listFiles()?.toList().orEmpty() }
        val dir = sessions.first { File(it, "0.opus").exists() && File(it, "1.opus").exists() }
        val pcm = Whisper.decodeOpus(File(dir, "0.opus").readBytes() + File(dir, "1.opus").readBytes())!!
        return pcm.copyOf(minOf(pcm.size, 60 * 16_000))
    }

    private fun measure(name: String, pcm: FloatArray, run: (FloatArray) -> Unit) {
        val parts = (0 until pcm.size / block).map { pcm.copyOfRange(it * block, (it + 1) * block) }
        repeat(50) { run(parts[it].copyOf()) }
        val cpu0 = Debug.threadCpuTimeNanos()
        val wall0 = System.nanoTime()
        parts.forEach(run)
        val cpu = (Debug.threadCpuTimeNanos() - cpu0) / 1e9
        val wall = (System.nanoTime() - wall0) / 1e9
        val seconds = parts.size * block / 16_000.0
        Log.i("bench", "denoise=$name audio_s=$seconds wall_s=${"%.2f".format(wall)} rtf=${"%.3f".format(wall / seconds)} cpu_core_pct=${"%.1f".format(cpu / seconds * 100)}")
    }

    private fun model(file: String) = File(context.getExternalFilesDir("models"), file).path

    @Test
    fun dpdfnet() {
        val d = OnlineSpeechDenoiser(null, OnlineSpeechDenoiserConfig(OfflineSpeechDenoiserModelConfig(dpdfnet = OfflineSpeechDenoiserDpdfNetModelConfig(model("dpdfnet2.onnx")), numThreads = 1)))
        try {
            measure("dpdfnet2", audio()) { d.run(it, 16_000) }
        } finally {
            d.release()
        }
    }
}
