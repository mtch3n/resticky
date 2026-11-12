package dev.resticky

import kotlinx.datetime.TimeZone
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString

class Exporter(private val dir: Path, private val zone: TimeZone = TimeZone.currentSystemDefault()) {
    private val names = mutableMapOf<String, String>()
    private val texts = mutableMapOf<String, Int>()

    fun export(all: List<Conversation>): Int {
        var written = 0
        for (c in all) {
            if (!c.ready() || c.recap().title == NO_SPEECH) continue
            val name = c.fileName(zone)
            val text = c.markdown(zone)
            if (names[c.key] == name && texts[c.key] == text.hashCode()) continue
            SystemFileSystem.createDirectories(dir)
            names[c.key]?.takeIf { it != name }?.let { SystemFileSystem.delete(Path(dir, it), mustExist = false) }
            val tmp = Path(dir, ".$name.tmp")
            SystemFileSystem.sink(tmp).buffered().use { it.writeString(text) }
            SystemFileSystem.atomicMove(tmp, Path(dir, name))
            names[c.key] = name
            texts[c.key] = text.hashCode()
            written++
        }
        return written
    }
}
