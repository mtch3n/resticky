package dev.resticky.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.resticky.HOUR_FORMATS
import dev.resticky.WEATHER_INTERVALS
import dev.resticky.Checkin
import dev.resticky.MODE_CITY
import dev.resticky.MODE_PHONE
import dev.resticky.WeatherReport
import dev.resticky.WeatherSettings
import dev.resticky.whenLabel
import kotlin.time.Instant

data class PickerOption(val id: String, val name: String, val image: Int)

val CLOCK_FACE = listOf(
    PickerOption("dial", "Dial", R.drawable.clock_face_dial),
    PickerOption("digits", "Digits", R.drawable.clock_face_digits),
    PickerOption("words", "Words", R.drawable.clock_face_words),
    PickerOption("note", "Note", R.drawable.clock_face_note),
    PickerOption("flip", "Flip", R.drawable.clock_face_flip),
)

val WEATHER_STYLE = listOf(
    PickerOption("sketch", "Sketch", R.drawable.weather_style_sketch),
    PickerOption("big", "Big picture", R.drawable.weather_style_big),
    PickerOption("journal", "Journal", R.drawable.weather_style_journal),
    PickerOption("week", "Week", R.drawable.weather_style_week),
)

fun optionName(options: List<PickerOption>, id: String): String = options.firstOrNull { it.id == id }?.name ?: id

private val CONDITION = mapOf("sun" to "Sunny", "cloud" to "Cloudy", "rain" to "Rain", "storm" to "Storm", "snow" to "Snow", "fog" to "Fog")

@Composable
fun CalendarsScreen(
    allowed: Boolean,
    calendars: List<CalendarInfo>,
    choice: CalendarChoice,
    back: () -> Unit,
    allow: () -> Unit,
    change: (CalendarChoice) -> Unit,
    addAccount: () -> Unit,
) = Page("Calendars", back) {
    item {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Show on the Sticky", style = Type.heading)
                    Text("Clock shows today and tomorrow", style = Type.small)
                }
                Toggle(choice.show) { change(choice.copy(show = it)) }
            }
        }
    }
    if (!allowed) {
        gap()
        item { CardList { ListRow("Allow calendar access", "The calendars stay on this phone", first = true, onClick = allow) { Chevron() } } }
    } else if (calendars.isEmpty()) {
        gap()
        item { CardList { ListRow("No calendars on this phone", first = true) } }
    }
    calendars.groupBy { it.account }.forEach { (account, list) ->
        gap()
        item { GroupHeader(account) }
        gap()
        item {
            CardList {
                list.forEachIndexed { i, c ->
                    val on = c.id in choice.selected
                    val toggle = { change(choice.copy(selected = if (on) choice.selected - c.id else choice.selected + c.id)) }
                    ListRow(c.name, first = i == 0, lead = { CheckBox(on) { toggle() } }, onClick = toggle)
                }
            }
        }
    }
    gap()
    item { OutlineButton("Add a Google account", R.drawable.ic_plus, addAccount) }
}

@Composable
fun WeatherScreen(
    settings: WeatherSettings,
    report: WeatherReport,
    style: String,
    back: () -> Unit,
    refresh: () -> Unit,
    selectStyle: (String) -> Unit,
    change: (WeatherSettings) -> Unit,
) = Page("Weather", back) {
    item { Section("Style") }
    gap()
    item { FaceStrip(WEATHER_STYLE, style, selectStyle) }
    gap()
    item {
        val now = report.now
        CardList {
            ListRow(
                if (now != null) "${report.place.ifEmpty { "Here" }} · ${now.temp}° · ${CONDITION[now.cond] ?: now.cond}" else "No forecast yet",
                if (now != null) "High ${now.hi}° · low ${now.lo}° · rain ${now.rain}%${report.at?.let { " · Updated ${whenLabel(Instant.fromEpochSeconds(it))}" }.orEmpty()}"
                else if (settings.mode == MODE_CITY && settings.city.isEmpty()) "Type a city below" else "Tap to get it now",
                first = true,
                onClick = refresh,
            ) { Glyph(R.drawable.ic_refresh, 20.dp) }
        }
    }
    gap()
    item { Section("Location") }
    gap()
    item {
        CardList {
            ChoiceRow("From phone", "Approximate location, about 10 km", settings.mode == MODE_PHONE, true, false, { change(settings.copy(mode = MODE_PHONE)) })
            ChoiceRow("Fixed city", settings.place?.name ?: settings.city.ifEmpty { "Not set" }, settings.mode == MODE_CITY, false, false, { change(settings.copy(mode = MODE_CITY)) })
        }
    }
    if (settings.mode == MODE_CITY) {
        gap()
        item {
            var city by remember(settings.city) { mutableStateOf(settings.city) }
            Field("City", city, { city = it }, onDone = { change(settings.copy(city = city)) })
        }
    }
    gap()
    item { Section("Units") }
    gap()
    item {
        CardList {
            ChoiceRow("°C", "Celsius", settings.units == "c", true, false, { change(settings.copy(units = "c")) })
            ChoiceRow("°F", "Fahrenheit", settings.units == "f", false, false, { change(settings.copy(units = "f")) })
        }
    }
    gap()
    item { Section("Refresh") }
    gap()
    item {
        Segmented(WEATHER_INTERVALS.map { it to if (it < 60) "$it min" else "${it / 60} h" }, settings.intervalMin, big = true) {
            change(settings.copy(intervalMin = it))
        }
    }
}

data class ScanItem(val ssid: String, val level: Int, val secured: Boolean)

private val WIFI_LEVEL = listOf(R.drawable.ic_wifi_1, R.drawable.ic_wifi_2, R.drawable.ic_wifi)

@Composable
fun WifiScreen(sticky: Checkin?, scan: List<ScanItem>, back: () -> Unit, forget: (String) -> Unit, pick: (ScanItem?) -> Unit) = Page("Wi-Fi networks", back) {
    val saved = sticky?.wifiSaved.orEmpty()
    item { Section("On the Sticky") }
    gap()
    item {
        CardList {
            if (saved.isEmpty()) ListRow("No saved networks", first = true, lead = { Glyph(R.drawable.ic_wifi) })
            saved.forEachIndexed { i, ssid ->
                val connected = ssid == sticky?.wifiSsid
                ListRow(ssid, if (connected) "Connected · ${sticky?.wifiRssi?.let { "−${-it} dBm" }}" else "Saved", first = i == 0, lead = { Glyph(R.drawable.ic_wifi) }) {
                    Box(Modifier.clip(CircleShape).clickable { forget(ssid) }.padding(horizontal = 4.dp, vertical = 6.dp)) { Text("Forget", style = Type.button.copy(fontSize = 14.sp)) }
                }
            }
        }
    }
    gap()
    item { Section("Add a network") }
    gap()
    item {
        CardList {
            scan.filter { it.ssid !in saved }.forEachIndexed { i, n ->
                ListRow(n.ssid, first = i == 0, lead = { Glyph(WIFI_LEVEL[n.level.coerceIn(0, 2)]) }, onClick = { pick(n) }) {
                    if (n.secured) Glyph(R.drawable.ic_lock, 18.dp)
                }
            }
            ListRow("Other network…", first = scan.none { it.ssid !in saved }, lead = { Glyph(R.drawable.ic_plus) }, onClick = { pick(null) })
        }
    }
}

@Composable
fun WifiAddScreen(network: ScanItem?, back: () -> Unit, add: (String, String) -> Boolean) {
    var ssid by remember { mutableStateOf(network?.ssid.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val passwordOk = password.length >= 8 || password.isEmpty() && network?.secured != true
    val ready = ssid.isNotBlank() && ssid.encodeToByteArray().size <= 32 && password.encodeToByteArray().size <= 64 && passwordOk
    val submit = { if (ready) { if (add(ssid, password)) back() else failed = true } }
    Page(network?.ssid ?: "Other network", back) {
        if (network == null) {
            item { Field("Network name", ssid, { ssid = it }) }
            gap()
        }
        if (network?.secured != false) {
            item { Field("Password", password, { password = it }, secret = true, onDone = submit) }
            gap()
        }
        if (failed) {
            item { Text("The Sticky is not connected over Bluetooth", style = Type.small, modifier = Modifier.padding(horizontal = 4.dp)) }
            gap()
        }
        item { FilledButton("Add to the Sticky", ready, submit) }
    }
}

@Composable
private fun FaceTile(option: PickerOption, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.width(118.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Image(
            painterResource(option.image),
            option.name,
            Modifier.fillMaxWidth().aspectRatio(0.6f).clip(shape).border(if (selected) 4.dp else 2.dp, if (selected) Ink.black else Ink.divider, shape),
            contentScale = ContentScale.FillBounds,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Radio(selected, 16.dp)
            Text(option.name, style = Type.row.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        }
    }
}

@Composable
private fun FaceStrip(options: List<PickerOption>, selected: String, select: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(options, key = { it.id }) { o -> FaceTile(o, o.id == selected) { select(o.id) } }
    }
}

@Composable
fun ClockScreen(clockFace: String, hourFormat: String, back: () -> Unit, selectClockFace: (String) -> Unit, selectHourFormat: (String) -> Unit) = Page("Clock", back) {
    item { Section("Clock face") }
    gap()
    item { FaceStrip(CLOCK_FACE, clockFace, selectClockFace) }
    gap()
    item { Section("Hours") }
    gap()
    item { Segmented(HOUR_FORMATS.map { it to "$it h" }, hourFormat, big = true, onSelect = selectHourFormat) }
}
