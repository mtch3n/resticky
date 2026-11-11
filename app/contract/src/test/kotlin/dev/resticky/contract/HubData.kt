package dev.resticky.contract

import java.nio.file.Files
import java.nio.file.Path

interface HubData {
    val realEngines: Boolean
    fun write(path: String, text: String)
    fun read(path: String): String
    fun exists(path: String): Boolean
    fun delete(path: String)
}

class LocalData(private val root: Path) : HubData {
    override val realEngines = false
    override fun write(path: String, text: String) {
        root.resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }
    }
    override fun read(path: String): String = Files.readString(root.resolve(path))
    override fun exists(path: String) = Files.exists(root.resolve(path))
    override fun delete(path: String) = root.resolve(path).toFile().deleteRecursively().let { }
}

class AdbData(private val pkg: String) : HubData {
    override val realEngines = true

    private fun sh(command: String, input: String = ""): Pair<Int, String> {
        val p = ProcessBuilder("adb", "shell", "run-as", pkg, "sh", "-c", "'cd files/data && $command'").redirectErrorStream(true).start()
        p.outputStream.use { it.write(input.encodeToByteArray()) }
        val out = p.inputStream.readBytes().decodeToString()
        return p.waitFor() to out
    }

    override fun write(path: String, text: String) {
        val (code, out) = sh("mkdir -p ${path.substringBeforeLast('/')} && cat > $path", text)
        check(code == 0) { out }
    }
    override fun read(path: String): String = sh("cat $path").also { check(it.first == 0) { it.second } }.second
    override fun exists(path: String) = sh("test -e $path").first == 0
    override fun delete(path: String) = sh("rm -rf $path").let { }
}

fun hubData(spec: String): HubData = if (spec.startsWith("adb:")) AdbData(spec.removePrefix("adb:")) else LocalData(Path.of(spec))
