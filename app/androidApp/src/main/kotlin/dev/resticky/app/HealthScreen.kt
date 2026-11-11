package dev.resticky.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.resticky.Drain
import dev.resticky.HealthSample
import dev.resticky.downsample
import java.util.Locale

const val CHART_POINTS = 300

val HEALTH_RANGES = listOf(6 to "6 h", 24 to "24 h", 168 to "7 d")

class Series(val name: String, val points: List<Pair<Long, Float>>, val now: Float?, val min: Float?, val max: Float?)

class Chart(val title: String, val unit: String, val decimals: Int, val series: List<Series>) {
    val min: Float? get() = series.mapNotNull { it.min }.minOrNull()
    val max: Float? get() = series.mapNotNull { it.max }.maxOrNull()
}

class HealthView(val hours: Int, val from: Long, val to: Long, val charts: List<Chart>, val drain: List<Drain>, val boot: String) {
    val empty: Boolean get() = charts.all { c -> c.series.all { it.points.isEmpty() } }
}

private fun series(name: String, samples: List<HealthSample>, value: (HealthSample) -> Float?): Series {
    val all = samples.map { it.t to (value(it) ?: Float.NaN) }
    val valid = all.map { it.second }.filter { !it.isNaN() }
    return Series(name, downsample(all, CHART_POINTS), valid.lastOrNull(), valid.minOrNull(), valid.maxOrNull())
}

fun healthCharts(s: List<HealthSample>): List<Chart> {
    val kb = 1024f
    val mb = 1024f * 1024f
    return listOf(
        Chart("Battery", "%", 0, listOf(series("Battery", s) { it.batteryPct.toFloat() })),
        Chart("Current", "mA", 0, listOf(series("Current", s) { it.currentMa.toFloat() })),
        Chart("Internal RAM", "KB", 0, listOf(
            series("Free", s) { it.health?.heapFree?.div(kb) },
            series("Lowest", s) { it.health?.heapMin?.div(kb) },
            series("Largest block", s) { it.health?.heapLargest?.div(kb) },
        )),
        Chart("PSRAM free", "MB", 2, listOf(
            series("Free", s) { it.health?.psramFree?.div(mb) },
            series("Lowest", s) { it.health?.psramMin?.div(mb) },
        )),
        Chart("Stack free", "B", 0, listOf(
            series("Main", s) { it.health?.stackMain?.toFloat() },
            series("Net", s) { it.health?.stackNet?.toFloat() },
            series("BLE", s) { it.health?.stackBle?.toFloat() },
        )),
        Chart("Audio CPU", "%", 1, listOf(
            series("Filter", s) { it.health?.cpuFilterPm?.div(10f) },
            series("Opus", s) { it.health?.cpuOpusPm?.div(10f) },
            series("VAD", s) { it.health?.cpuVadPm?.div(10f) },
        )),
        Chart("Opus worst frame", "ms", 1, listOf(series("Worst frame", s) { it.health?.opusMaxUs?.div(1000f) })),
        Chart("Chip temperature", "°C", 1, listOf(series("Temperature", s) { it.health?.tempC })),
        Chart("Wi-Fi signal", "dBm", 0, listOf(series("RSSI", s) { it.wifiRssi.takeIf { r -> r != 0 }?.toFloat() })),
        Chart("Outbox", "chunks", 0, listOf(series("Chunks", s) { it.outboxChunks.toFloat() })),
    )
}

private fun format(v: Float?, decimals: Int, unit: String): String =
    if (v == null) "–" else "${String.format(Locale.US, "%.${decimals}f", v).replace("-", "−")} $unit"

private val LINE = listOf(Ink.black, Ink.black, Color(0xFF999999))
private val DASH = listOf(null, PathEffect.dashPathEffect(floatArrayOf(10f, 8f)), null)

private fun DrawScope.plot(chart: Chart, from: Long, to: Long) {
    val grid = Ink.divider
    listOf(0f, 0.5f, 1f).forEach { f -> drawLine(grid, Offset(0f, size.height * f), Offset(size.width, size.height * f), 1.dp.toPx()) }
    val lo0 = chart.min ?: return
    val hi0 = chart.max ?: return
    val pad = if (hi0 - lo0 < 1e-3f) maxOf(1f, kotlin.math.abs(hi0) * 0.1f) else (hi0 - lo0) * 0.08f
    val lo = lo0 - pad
    val hi = hi0 + pad
    val range = (to - from).coerceAtLeast(1)
    val gap = maxOf(300L, range * 3 / CHART_POINTS)
    fun x(t: Long) = (t - from).toFloat() / range * size.width
    fun y(v: Float) = size.height - (v - lo) / (hi - lo) * size.height
    chart.series.forEachIndexed { i, s ->
        val color = LINE[i % LINE.size]
        val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = DASH[i % DASH.size])
        val path = Path()
        var prev: Long? = null
        s.points.forEachIndexed { k, (t, v) ->
            val alone = (prev == null || t - prev!! > gap) && (k + 1 >= s.points.size || s.points[k + 1].first - t > gap)
            if (prev == null || t - prev!! > gap) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v))
            if (alone) drawCircle(color, 2.dp.toPx(), Offset(x(t), y(v)))
            prev = t
        }
        drawPath(path, color, style = stroke)
    }
}

@Composable
private fun Swatch(i: Int) {
    Canvas(Modifier.width(18.dp).height(10.dp)) {
        drawLine(LINE[i % LINE.size], Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(), StrokeCap.Round, DASH[i % DASH.size])
    }
}

@Composable
private fun ChartCard(c: Chart, from: Long, to: Long, rangeLabel: String) = Card {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(c.title, style = Type.heading)
            Text("Min ${format(c.min, c.decimals, c.unit)} · max ${format(c.max, c.decimals, c.unit)}", style = Type.small)
        }
        Text(format(c.series.first().now, c.decimals, c.unit), style = Type.heading.copy(fontSize = 20.sp))
    }
    Gap(14.dp)
    Canvas(Modifier.fillMaxWidth().height(110.dp)) { plot(c, from, to) }
    Gap(6.dp)
    Row {
        Text("$rangeLabel ago", style = Type.small)
        Spacer(Modifier.weight(1f))
        Text("Now", style = Type.small)
    }
    if (c.series.size > 1) {
        Gap(10.dp)
        c.series.forEachIndexed { i, s ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Swatch(i)
                Text(s.name, style = Type.small.copy(color = Ink.black), modifier = Modifier.weight(1f))
                Text(format(s.now, c.decimals, c.unit), style = Type.small.copy(color = Ink.black))
            }
        }
    }
}

private fun hoursLabel(h: Float): String = String.format(Locale.US, if (h < 10f) "%.1f h" else "%.0f h", h)

@Composable
private fun DrainCard(drain: List<Drain>) = Card(padding = PaddingValues(0.dp)) {
    Text("Battery drain", style = Type.heading, modifier = Modifier.padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 4.dp))
    drain.asReversed().forEachIndexed { i, d ->
        val full = d.fullHours?.let { " · 100 % in ${hoursLabel(it)}" }.orEmpty()
        ListRow(d.firmware.ifEmpty { "Unknown version" }, "${hoursLabel(d.hours)} of data$full", first = i == 0) {
            Text(d.pctPerHour?.let { String.format(Locale.US, "%.2f %%/h", it) } ?: "–", style = Type.heading)
        }
    }
}

@Composable
private fun BootCard(boot: String) = Card {
    Text("Last boot", style = Type.heading)
    Gap(6.dp)
    Text(boot, style = Type.small.copy(color = Ink.black))
}

@Composable
fun HealthScreen(view: HealthView?, hours: Int, back: () -> Unit, select: (Int) -> Unit) = Page("Health", back) {
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HEALTH_RANGES.forEach { (h, label) -> Chip(label, h == hours) { select(h) } }
        }
    }
    val label = HEALTH_RANGES.firstOrNull { it.first == hours }?.second ?: "$hours h"
    when {
        view == null -> {}
        view.empty -> {
            gap()
            item { CardList { ListRow("No health data yet", "The Sticky sends it with each check-in, firmware 0.11 and later", first = true, subtitleLines = 2) } }
        }
        else -> {
            if (view.drain.isNotEmpty()) {
                gap()
                item(key = "drain") { DrainCard(view.drain) }
            }
            if (view.boot.isNotEmpty()) {
                gap()
                item(key = "boot") { BootCard(view.boot) }
            }
            view.charts.forEach { c ->
                gap()
                item(key = c.title) { ChartCard(c, view.from, view.to, label) }
            }
        }
    }
}

fun healthStatus(sticky: dev.resticky.Checkin?): String {
    val h = sticky?.health ?: return "No data yet"
    return "RAM ${h.heapFree / 1024} KB · ${String.format(Locale.US, "%.0f", h.tempC)} °C"
}
