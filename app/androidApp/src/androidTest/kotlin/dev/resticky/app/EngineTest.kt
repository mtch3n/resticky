package dev.resticky.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun frames(): ByteArray {
        val sessions = File(context.filesDir, "data").listFiles()!!.flatMap { it.listFiles()?.toList().orEmpty() }
        val dir = sessions.first { File(it, "0.opus").exists() && File(it, "1.opus").exists() }
        return File(dir, "0.opus").readBytes() + File(dir, "1.opus").readBytes()
    }

    private fun waitFor(engine: Engine, ms: Long, check: (Long) -> Boolean): Long {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val p = engine.positionMs()
            if (check(p)) return p
            Thread.sleep(20)
        }
        return engine.positionMs()
    }

    @Test
    fun playsSeeksSkipsAndSwitchesVoice() {
        val model = File(context.getExternalFilesDir("models"), "dpdfnet2.onnx").path
        val engine = Engine(SessionAudio(frames(), null)) { model }
        try {
            assertEquals(60_000L, engine.durationMs)
            engine.play()
            assertTrue(waitFor(engine, 3000) { it > 1000 } > 1000)
            engine.crisp = Crisp.Clear
            Thread.sleep(500)
            engine.crisp = Crisp.Denoise
            Thread.sleep(500)
            engine.seek(30_000)
            val after = waitFor(engine, 2000) { it in 30_200..31_500 }
            assertTrue("after seek $after", after in 30_200..31_500)
            engine.skips = listOf(32_000L..40_000L)
            val skipped = waitFor(engine, 4000) { it >= 40_000 }
            assertTrue("after skip $skipped", skipped >= 40_000)
            engine.crisp = Crisp.Off
            Thread.sleep(300)
            assertTrue(engine.playing)
        } finally {
            engine.close()
        }
    }
}
