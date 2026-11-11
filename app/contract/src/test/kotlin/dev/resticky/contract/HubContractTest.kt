package dev.resticky.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.imageio.ImageIO
import kotlin.random.Random
import org.junit.jupiter.api.Assumptions.assumeFalse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class HubContractTest {
    private val base = System.getProperty("hub.url").ifEmpty { fail("set -PhubUrl") }
    private val data = hubData(System.getProperty("hub.data").ifEmpty { fail("set -PhubData") })
    private val client = HttpClient.newHttpClient()
    private val device = "%012x".format(Random.nextLong(1L shl 47))

    private class Reply(val status: Int, val body: ByteArray) {
        val json: JsonElement get() = Json.parseToJsonElement(body.decodeToString())
    }

    private fun send(method: String, path: String, body: ByteArray = ByteArray(0), headers: Map<String, String> = emptyMap()): Reply {
        val req = HttpRequest.newBuilder(URI.create(base + path)).method(method, BodyPublishers.ofByteArray(body))
        headers.forEach { (k, v) -> req.header(k, v) }
        val res = client.send(req.build(), BodyHandlers.ofByteArray())
        return Reply(res.statusCode(), res.body())
    }

    @AfterTest
    fun removeDevice() = data.delete(device)

    private fun get(path: String) = send("GET", path)

    private fun chunk(session: String, seq: Int, final: Boolean = false, frames: Int = 50) = send(
        "POST", "/api/chunks", opusFrames(frames),
        mapOf(
            "X-Device" to device, "X-Session" to session, "X-Seq" to "$seq", "X-Final" to if (final) "1" else "0",
            "X-Start-Ms" to "0", "X-Duration-Ms" to "${frames * 20}",
        ),
    )

    private fun opusFrames(n: Int): ByteArray {
        val packet = byteArrayOf(0x08, 0x0b, 0xe4.toByte(), 0xc1.toByte())
        val out = java.io.ByteArrayOutputStream()
        repeat(n) {
            out.write(packet.size and 0xff); out.write(packet.size shr 8); out.write(packet)
        }
        return out.toByteArray()
    }

    private fun seed(session: String, started: ZonedDateTime, meta: String) {
        val time = started.toOffsetDateTime().toString()
        data.write(
            "$device/$session/meta.json",
            """{"device":"$device","id":"$session","started":"$time","last_chunk":"$time","duration_ms":30000,"final":true,$meta}""",
        )
    }

    private fun meta(session: String): JsonObject =
        Json.parseToJsonElement(data.read("$device/$session/meta.json")).jsonObject

    private fun todos(): Pair<List<String>, List<String>> {
        val r = get("/api/todos?device=$device")
        assertEquals(200, r.status)
        val o = r.json.jsonObject
        fun texts(k: String) = o.getValue(k).jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content }
        return texts("open") to texts("done")
    }

    @Test
    fun hello() {
        val r = get("/api/hello")
        assertEquals(200, r.status)
        assertEquals("resticky-hub", r.json.jsonObject.getValue("service").jsonPrimitive.content)
        assertTrue(r.json.jsonObject.getValue("version").jsonPrimitive.content.isNotEmpty())
    }

    @Test
    fun checkinAndDevices() {
        val body = """{"device":"$device","firmware":"9.9.9","battery_pct":77,"voltage_mv":4000,"current_ma":-30,"recording":true}"""
        val r = send("POST", "/api/checkin", body.encodeToByteArray(), mapOf("Content-Type" to "application/json"))
        assertEquals(200, r.status)
        assertTrue(r.json.jsonObject.getValue("hub_version").jsonPrimitive.content.isNotEmpty())
        assertEquals(ZonedDateTime.now().offset.totalSeconds / 60, r.json.jsonObject.getValue("utc_offset_min").jsonPrimitive.int)
        assertTrue(r.json.jsonObject.getValue("clock_face").jsonPrimitive.content in setOf("dial", "digits", "words", "note", "flip"))
        assertTrue(r.json.jsonObject.getValue("weather_style").jsonPrimitive.content in setOf("sketch", "big", "journal", "week"))
        assertTrue(r.json.jsonObject.getValue("hour_format").jsonPrimitive.content in setOf("24", "12"))
        assertTrue(r.json.jsonObject.getValue("sound").jsonPrimitive.content in setOf("off", "beep", "buzz"))
        assertTrue(Regex("^off$|^([01]\\d|2[0-3]):(00|30)-([01]\\d|2[0-3]):(00|30)$").matches(r.json.jsonObject.getValue("night").jsonPrimitive.content))
        assertTrue(r.json.jsonObject.getValue("home").jsonPrimitive.content in setOf("apps", "clock", "weather", "recorder", "todo"))
        assertTrue(!r.json.jsonObject.containsKey("install"))
        assertEquals(400, send("POST", "/api/checkin", "nope".encodeToByteArray()).status)
        assertEquals(400, send("POST", "/api/checkin", """{"device":"XYZ"}""".encodeToByteArray()).status)
        val d = get("/api/devices").json.jsonObject.getValue(device).jsonObject
        assertEquals("9.9.9", d.getValue("firmware").jsonPrimitive.content)
        assertEquals(77, d.getValue("battery_pct").jsonPrimitive.int)
        assertTrue(d.getValue("recording").jsonPrimitive.boolean)
        assertTrue(d.containsKey("seen"))
    }

    @Test
    fun checkinMarksAreStoredOnce() {
        val s = "a1b2c3d4"
        fun checkin(marks: String) = send(
            "POST", "/api/checkin", """{"device":"$device","battery_pct":50,"marks":[$marks]}""".encodeToByteArray(),
            mapOf("Content-Type" to "application/json"),
        )
        val first = """{"session":"$s","offset_ms":42000,"at":1794000000000},{"session":"$s","offset_ms":5000,"at":1794000001000}"""
        assertEquals(200, checkin(first).status)
        assertEquals(200, checkin(first).status)
        assertEquals(200, chunk(s, 0).status)
        assertEquals(200, checkin("""{"session":"$s","offset_ms":5000,"at":1794000001000},{"session":"XYZ","offset_ms":1},{"session":"$s","offset_ms":-3}""").status)
        assertEquals(listOf(5000L, 42000L), meta(s).getValue("marks").jsonArray.map { it.jsonPrimitive.long })
        val item = get("/api/transcripts?device=$device").json.jsonArray.single().jsonObject
        assertEquals(2, item.getValue("marks").jsonPrimitive.int)
        assertTrue(!get("/api/devices").json.jsonObject.getValue(device).jsonObject.containsKey("marks"))
    }

    @Test
    fun firmwareShape() {
        val r = get("/api/firmware")
        assertTrue(r.status == 200 || r.status == 404)
        if (r.status == 200) {
            val version = r.json.jsonObject.getValue("version").jsonPrimitive.content
            assertTrue(Regex("""\d+\.\d+\.\d+""").matches(version))
            assertEquals(r.json.jsonObject.getValue("size").jsonPrimitive.long, get("/api/firmware/bin?version=$version").body.size.toLong())
        }
        assertEquals(404, get("/api/firmware/bin?version=../meta").status)
        assertEquals(404, get("/api/firmware/bin?version=0.0.0").status)
    }

    @Test
    fun healthHistory() {
        val empty = get("/api/health?device=$device")
        assertEquals(200, empty.status)
        assertEquals(JsonArray(emptyList()), empty.json)
        fun checkin(battery: Int) = send(
            "POST", "/api/checkin",
            """{"device":"$device","battery_pct":$battery,"current_ma":-30,"wifi_rssi":-60,"health":{"uptime_s":61,"heap_free":95000,"heap_min":85000,"heap_largest":60000,"psram_free":6000000,"psram_min":5900000,"stack_main":2100,"stack_net":1800,"stack_ble":1500,"cpu_filter_pm":120,"cpu_opus_pm":340,"cpu_vad_pm":210,"opus_max_us":9100,"overflows":0,"temp_c":41.5}}""".encodeToByteArray(),
            mapOf("Content-Type" to "application/json"),
        ).also { assertEquals(200, it.status) }
        checkin(71); checkin(72); checkin(73)
        val samples = get("/api/health?device=$device&hours=1").json.jsonArray
        assertTrue(samples.size in 1..2)
        val first = samples.first().jsonObject
        assertEquals(71, first.getValue("battery_pct").jsonPrimitive.int)
        assertEquals(-60, first.getValue("wifi_rssi").jsonPrimitive.int)
        assertTrue(first.getValue("t").jsonPrimitive.long > 0)
        val health = first.getValue("health").jsonObject
        assertEquals(85000, health.getValue("heap_min").jsonPrimitive.int)
        assertEquals(0, health.getValue("overflows").jsonPrimitive.int)
        assertEquals(41.5, health.getValue("temp_c").jsonPrimitive.double)
        assertEquals(samples, get("/api/health?device=$device&hours=168").json)
        listOf("/api/health", "/api/health?device=XYZ", "/api/health?device=$device&hours=0", "/api/health?device=$device&hours=169", "/api/health?device=$device&hours=x")
            .forEach { assertEquals(400, get(it).status, it) }
    }

    @Test
    fun chunkHeadersAreChecked() {
        assertEquals(400, send("POST", "/api/chunks", opusFrames(1)).status)
        assertEquals(400, send("POST", "/api/chunks", opusFrames(1), mapOf("X-Device" to device, "X-Session" to "ZZ", "X-Seq" to "0", "X-Duration-Ms" to "20")).status)
        assertEquals(400, send("POST", "/api/chunks", opusFrames(1), mapOf("X-Device" to device, "X-Session" to "ab", "X-Seq" to "-1", "X-Duration-Ms" to "20")).status)
    }

    @Test
    fun repeatedChunkIsStoredOnce() {
        val s = "c0ffee01"
        assertEquals(200, chunk(s, 0).status)
        assertEquals(200, chunk(s, 0).status)
        assertEquals(200, chunk(s, 1).status)
        val m = meta(s)
        assertEquals(2000, m.getValue("duration_ms").jsonPrimitive.int)
        assertEquals(setOf("0", "1"), m.getValue("chunks").jsonObject.keys)
        assertTrue(data.exists("$device/$s/0.opus"))
        assertEquals(200, chunk(s, 0, frames = 70).status)
        assertEquals(1000, meta(s).getValue("chunk_ms").jsonObject.getValue("0").jsonPrimitive.int)
        assertEquals(2000, meta(s).getValue("duration_ms").jsonPrimitive.int)
    }

    @Test
    fun sessionsMinutesApartAreOneTranscript() {
        val now = ZonedDateTime.now()
        seed("f1", now.minusMinutes(8), """"chunks":{"0":"one two three"}""")
        seed("f2", now.minusMinutes(4), """"chunks":{"0":"four five six"}""")
        val items = get("/api/transcripts?device=$device").json.jsonArray
        assertEquals(1, items.size)
        assertEquals("one two three four five six", items[0].jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun finalChunkIsTranscribedAndSummarized() {
        assumeFalse(data.realEngines, "needs the fake engines")
        val s = "5e55a0"
        assertEquals(200, chunk(s, 0).status)
        assertEquals(200, chunk(s, 1, final = true).status)
        val deadline = System.currentTimeMillis() + 30_000
        var item: JsonObject? = null
        while (System.currentTimeMillis() < deadline) {
            item = get("/api/transcripts?device=$device").json.jsonArray.firstOrNull()?.jsonObject
            if (item?.getValue("ready")?.jsonPrimitive?.boolean == true) break
            Thread.sleep(300)
        }
        val ready = item ?: fail("no transcript item")
        assertTrue(ready.getValue("ready").jsonPrimitive.boolean, "session not summarized: $ready")
        assertEquals("Fake title", ready.getValue("title").jsonPrimitive.content)
        assertEquals("Fake summary.", ready.getValue("summary").jsonPrimitive.content)
        assertEquals(listOf("Fake todo"), ready.getValue("todos").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("we agreed to ship the billing report on Friday and Sam checks the numbers".let { "$it $it" }, ready.getValue("text").jsonPrimitive.content)
        assertTrue(Regex("""\d\d:\d\d""").matches(ready.getValue("when").jsonPrimitive.content))
    }

    @Test
    fun transcriptsAreNewestFirstAndLimited() {
        val now = ZonedDateTime.now()
        repeat(20) { i -> seed("a%04x".format(i), now.minusMinutes(12L * (20 - i)), """"chunks":{"0":"words $i"},"title":"T$i","summary":"S","ready":true""") }
        seed("ffff", now, """"chunks":{"0":""},"title":"No speech","ready":true""")
        val items = get("/api/transcripts?device=$device").json.jsonArray
        assertEquals(18, items.size)
        assertEquals("T19", items[0].jsonObject.getValue("title").jsonPrimitive.content)
        assertTrue(items.none { it.jsonObject.getValue("title").jsonPrimitive.content == "No speech" })
        assertEquals("words 19", items[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertTrue(items.all { it.jsonObject.getValue("marks").jsonPrimitive.int == 0 && it.jsonObject.getValue("photos").jsonPrimitive.int == 0 })
        assertEquals(400, get("/api/transcripts?device=NOPE").status)
    }

    @Test
    fun transcriptPlace() {
        val now = ZonedDateTime.now()
        seed("c1", now.minusMinutes(36), """"chunks":{"0":"a"},"title":"Untagged","ready":true""")
        seed("c2", now.minusMinutes(24), """"chunks":{"0":"b"},"title":"Office","ready":true,"place":"Office"""")
        seed("c3", now.minusMinutes(12), """"chunks":{"0":"c"},"title":"Away","ready":true,"place":"Elsewhere","place_lat":25.03,"place_lon":121.57""")
        seed("c4", now, """"chunks":{"0":"d"},"title":"Locating","ready":false,"place":"\u0000pending"""")
        val items = get("/api/transcripts?device=$device").json.jsonArray.map { it.jsonObject }
        assertEquals(listOf("", "Elsewhere", "Office", ""), items.map { it.getValue("place").jsonPrimitive.content })
        assertTrue(items.none { it.containsKey("place_lat") || it.containsKey("place_lon") })
    }

    @Test
    fun longTextIsCut() {
        val long = "x".repeat(150) + " " + "y".repeat(150)
        seed("b1", ZonedDateTime.now(), """"chunks":{"0":"$long"},"ready":false""")
        val text = get("/api/transcripts?device=$device").json.jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content
        assertEquals("..." + long.takeLast(180), text)
    }

    @Test
    fun todoListsAndToggle() {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val yesterday = now.minusDays(1).toOffsetDateTime()
        seed(
            "d0", now.minusHours(1),
            """"chunks":{"0":"t"},"title":"Plan","ready":true,"todos":["A","B","C"],"done":{"1":"${now.toOffsetDateTime()}","2":"$yesterday"}""",
        )
        assertEquals(listOf("A") to listOf("B"), todos())
        val first = get("/api/todos?device=$device").json.jsonObject.getValue("open").jsonArray[0].jsonObject
        assertEquals("d0-0", first.getValue("id").jsonPrimitive.content)
        assertTrue(first.getValue("from").jsonPrimitive.content.startsWith("Plan · "))
        fun put(session: String, index: Int, done: Boolean) = send(
            "PUT", "/api/todos/$device/$session/$index", """{"done":$done,"at":"${OffsetDateTime.now()}"}""".encodeToByteArray(),
            mapOf("Content-Type" to "application/json"),
        ).status
        assertEquals(200, put("d0", 0, true))
        assertEquals(emptyList<String>() to listOf("A", "B"), todos())
        assertEquals(200, put("d0", 0, false))
        assertEquals(listOf("A") to listOf("B"), todos())
        assertEquals(404, put("d0", 9, true))
        assertEquals(404, put("dead", 0, true))
    }

    @Test
    fun todoSetIsRepeatSafe() {
        seed("e0", ZonedDateTime.now().minusHours(1), """"chunks":{"0":"t"},"title":"Set","ready":true,"todos":["A"]""")
        val t = OffsetDateTime.now()
        fun put(done: Boolean, at: OffsetDateTime) = send(
            "PUT", "/api/todos/$device/e0/0", """{"done":$done,"at":"$at"}""".encodeToByteArray(),
            mapOf("Content-Type" to "application/json"),
        )
        assertEquals(200, put(true, t).status)
        assertEquals(200, put(true, t).status)
        assertEquals(emptyList<String>() to listOf("A"), todos())
        val stale = put(false, t.minusMinutes(5))
        assertEquals(200, stale.status)
        assertTrue(stale.json.jsonObject.getValue("done").jsonPrimitive.boolean)
        assertEquals(emptyList<String>() to listOf("A"), todos())
        assertEquals(200, put(false, t.plusSeconds(1)).status)
        assertEquals(listOf("A") to emptyList<String>(), todos())
        assertEquals(404, send("PUT", "/api/todos/$device/e0/5", """{"done":true,"at":"$t"}""".encodeToByteArray()).status)
        assertEquals(400, send("PUT", "/api/todos/$device/e0/0", "bad".encodeToByteArray()).status)
    }

    @Test
    fun screen() {
        val frame = ByteArray(48000) { 0xff.toByte() }
        frame[(479 - 10) * 100 + 20 / 8] = (0xff xor (0x80 shr (20 % 8))).toByte()
        assertEquals(200, send("POST", "/api/screen", frame, mapOf("X-Device" to device)).status)
        assertEquals(400, send("POST", "/api/screen", ByteArray(10), mapOf("X-Device" to device)).status)
        val r = get("/api/screen?device=$device")
        assertEquals(200, r.status)
        val img = ImageIO.read(ByteArrayInputStream(r.body))
        assertEquals(480, img.width)
        assertEquals(800, img.height)
        assertEquals(0, img.raster.getSample(10, 20, 0))
        assertEquals(255, img.raster.getSample(11, 20, 0))
        assertEquals(404, get("/api/screen?device=abc123").status)
    }

    @Test
    fun unknownDeviceListsAreEmpty() {
        assertEquals(JsonArray(emptyList()), get("/api/transcripts?device=abcdef").json)
        assertEquals(emptyList<String>() to emptyList<String>(), todos())
    }

    @Test
    fun weatherSettingsChange() {
        assertEquals(400, get("/api/weather").status)
        assertEquals(400, get("/api/weather?device=XYZ").status)
        val r = get("/api/weather?device=$device")
        assertEquals(200, r.status)
        val before = r.json.jsonObject
        before.getValue("place").jsonPrimitive.content
        assertTrue(before.getValue("mode").jsonPrimitive.content in setOf("phone", "city"))
        val units = before.getValue("units").jsonPrimitive.content
        assertTrue(units in setOf("c", "f"))
        before["now"]?.let { now -> assertTrue(now.jsonObject.getValue("cond").jsonPrimitive.content in setOf("sun", "cloud", "rain", "storm", "snow", "fog")) }
        before["temps"]?.let { assertEquals(13, it.jsonArray.size) }
        before["conds"]?.let { assertEquals(5, it.jsonArray.size) }
        fun put(body: String) = send("PUT", "/api/weather?device=$device", body.encodeToByteArray(), mapOf("Content-Type" to "application/json"))
        assertEquals(400, put("nope").status)
        assertEquals(400, put("""{"units":"k"}""").status)
        assertEquals(400, put("""{"mode":"moon"}""").status)
        val other = if (units == "c") "f" else "c"
        val changed = put("""{"units":"$other"}""")
        assertEquals(200, changed.status)
        assertEquals(other, changed.json.jsonObject.getValue("units").jsonPrimitive.content)
        assertEquals(other, get("/api/weather?device=$device").json.jsonObject.getValue("units").jsonPrimitive.content)
        assertEquals(200, put("""{"units":"$units"}""").status)
    }

    @Test
    fun agendaShape() {
        assertEquals(400, get("/api/agenda?device=XYZ").status)
        val r = get("/api/agenda?device=$device")
        assertEquals(200, r.status)
        val a = r.json.jsonObject
        assertEquals(java.time.LocalDate.now().toString(), a.getValue("today").jsonPrimitive.content)
        a.getValue("show").jsonPrimitive.boolean
        val events = a.getValue("events").jsonArray
        assertTrue(events.size <= 12)
        assertTrue(r.body.size <= 2048)
        events.forEach { e ->
            val o = e.jsonObject
            assertTrue(o.getValue("day").jsonPrimitive.int in 0..1)
            assertTrue(o.getValue("title").jsonPrimitive.content.codePointCount(0, o.getValue("title").jsonPrimitive.content.length) <= 48)
            listOf("start", "end").forEach { k -> assertTrue(Regex("^$|^\\d\\d:\\d\\d$").matches(o.getValue(k).jsonPrimitive.content)) }
        }
    }
}
