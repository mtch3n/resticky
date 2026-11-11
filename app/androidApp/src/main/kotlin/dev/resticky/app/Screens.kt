package dev.resticky.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import dev.resticky.CLEANED
import dev.resticky.SOUNDS
import dev.resticky.BUZZ_RANGE
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Slider
import dev.resticky.cleanText
import dev.resticky.skipRanges
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.genai.common.FeatureStatus
import dev.resticky.Line
import dev.resticky.RELEASES_REPO
import dev.resticky.isNewer
import dev.resticky.Photo
import android.graphics.BitmapFactory
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.resticky.MARK_WINDOW_MS
import dev.resticky.Session
import dev.resticky.Conversation
import dev.resticky.linkPhoto
import dev.resticky.wallAt
import dev.resticky.timeLabel
import dev.resticky.TodoItem
import dev.resticky.whenLabel
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.DatePeriod
import kotlin.time.Clock
import kotlin.time.Instant
import java.io.File

private const val MARK_LEAD_MS = 10_000L
private val MAIN = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)
private val zone get() = TimeZone.currentSystemDefault()

private fun Instant.date(): LocalDate = toLocalDateTime(zone).date

private val dayFormat = LocalDate.Format { monthName(MonthNames.ENGLISH_FULL); char(' '); day(kotlinx.datetime.format.Padding.NONE) }

private fun dayLabel(d: LocalDate): String {
    val today = Clock.System.now().date()
    return when (d) {
        today -> "Today"
        today.minus(DatePeriod(days = 1)) -> "Yesterday"
        else -> d.format(dayFormat)
    }
}

fun duration(ms: Long): String {
    val min = ((ms + 30_000) / 60_000).coerceAtLeast(1)
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}

private fun hoursToday(ms: Long): String {
    val min = ms / 60_000
    return if (min >= 60) "${min / 60} h ${"%02d".format(min % 60)}" else "$min min"
}

private fun seconds(s: Double) = if (s < 10) "%.1f s".format(s) else "%.0f s".format(s)

fun LazyListScope.gap() = item { Gap(14.dp) }

@Composable
fun Page(title: String, onBack: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null, content: LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title, onBack, action)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = MAIN, content = content)
    }
}

fun linkLabel(state: HubState, ble: BleState): String {
    val seen = state.sticky?.seen
    val recent = seen?.let { state.now - it < kotlin.time.Duration.parse("90s") } == true
    return when {
        ble.status == LinkStatus.Ready -> "Connected over Bluetooth"
        recent -> "On Wi-Fi"
        seen != null -> "Not connected · last seen ${whenLabel(seen)}"
        ble.address == null -> "Not paired"
        else -> "Not connected"
    }
}

fun stickyName(state: HubState, ble: BleState): String =
    state.stickies.firstOrNull { it.address == ble.address }?.let { "Sticky · ${it.name}" } ?: "Sticky"

@Composable
private fun Tile(icon: Int, name: String, status: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(CardShape).background(Ink.card).clickable(onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Glyph(icon, 28.dp)
        Column {
            Text(name, style = Type.heading.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

class HubTile(val icon: Int, val name: String, val status: String, val open: () -> Unit)

@Composable
fun HubScreen(state: HubState, ble: BleState, tiles: List<HubTile>, settings: () -> Unit, choose: () -> Unit) = Page(
    "Hub",
    action = { Box(Modifier.clip(CircleShape).clickable(onClick = settings).padding(8.dp)) { Glyph(R.drawable.ic_settings, 24.dp) } },
) {
    item {
        val c = state.sticky
        Column(Modifier.fillMaxWidth().clip(CardShape).background(Ink.black).clickable(onClick = choose).padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Glyph(R.drawable.ic_device_mobile, 26.dp, Ink.white)
                Column(Modifier.weight(1f)) {
                    Text(stickyName(state, ble), style = Type.heading.copy(color = Ink.white), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(linkLabel(state, ble), style = Type.small.copy(color = Ink.mutedDark), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Glyph(R.drawable.ic_chevron_down, 22.dp, Ink.white)
            }
            if (c != null || state.todayMs > 0) {
                Gap(14.dp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (c != null) {
                        if (c.recording) Pill("REC", Ink.pillDark, Ink.white) { Dot(Ink.white) } else Pill("Muted", Ink.pillDark, Ink.white)
                        Pill("${c.batteryPct}%", Ink.pillDark, Ink.white)
                    }
                    Pill("${hoursToday(state.todayMs)} today", Ink.pillDark, Ink.white)
                    if (c != null) Pill("${c.outboxChunks} waiting", Ink.pillDark, Ink.white)
                }
            }
        }
    }
    gap()
    tiles.chunked(2).forEachIndexed { i, pair ->
        if (i > 0) item { Gap(12.dp) }
        item {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { t -> Tile(t.icon, t.name, t.status, Modifier.weight(1f).fillMaxHeight(), t.open) }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun PipelineScreen(state: HubState, runs: List<Run>, back: () -> Unit) = Page("Pipeline", back) {
    item {
        val shown = runs.filterIndexed { i, r -> r.error == null || runs.subList(0, i).none { it.key == r.key } }
        val known = shown.map { it.key }.toSet()
        val waiting = state.received.filter { it !in known }
        CardList {
            if (waiting.isEmpty() && shown.isEmpty()) ListRow("Nothing yet", "Chunks show here when they arrive", first = true)
            waiting.forEachIndexed { i, key ->
                ListRow("Chunk ${key.substringAfter('/')} · received", "waiting for speech-to-text", first = i == 0, lead = { Glyph(R.drawable.ic_circle_dashed) })
            }
            shown.take(30).forEachIndexed { i, r -> RunRow(r, state.now, first = waiting.isEmpty() && i == 0) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickySheet(state: HubState, ble: BleState, dismiss: () -> Unit, pair: () -> Unit) {
    ModalBottomSheet(
        dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = Ink.white,
        scrimColor = Color.Black.copy(alpha = 0.45f),
        dragHandle = { Box(Modifier.padding(top = 14.dp).width(40.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Ink.off)) },
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Choose a Sticky", style = Type.heading.copy(fontSize = 20.sp), modifier = Modifier.padding(horizontal = 4.dp))
            if (state.stickies.isNotEmpty()) {
                CardList {
                    state.stickies.forEachIndexed { i, s ->
                        val current = s.address == ble.address
                        val status = if (current) listOfNotNull(linkLabel(state, ble), state.sticky?.let { "${it.batteryPct}%" }).joinToString(" · ") else "Paired"
                        ListRow("Sticky · ${s.name}", status, first = i == 0, lead = { Radio(current) }, onClick = dismiss)
                    }
                }
            }
            OutlineButton("Pair a new Sticky", R.drawable.ic_plus, pair)
        }
    }
}

@Composable
private fun RunRow(r: Run, now: Instant, first: Boolean) {
    val speech = r.job == "speech"
    val speakers = r.job == "speakers"
    val name = when {
        speech -> "Chunk ${r.key.substringAfter('/')}"
        speakers -> "Speakers"
        else -> "Summary${r.summary?.let { " · ${it.title}" } ?: ""}"
    }
    val (title, subtitle, icon) = when {
        r.seconds == null -> Triple(
            "$name · ${if (speech) "transcribing" else if (speakers) "finding" else "summarizing"}",
            "${r.engine} · started ${((now - r.started).inWholeSeconds).coerceAtLeast(0)} s ago",
            R.drawable.ic_loader_2,
        )
        r.error != null -> Triple("$name · waiting", r.error, R.drawable.ic_circle_dashed)
        speech -> Triple("$name · done", "${r.engine} · ${seconds(r.seconds)} · ${r.words ?: 0} words", R.drawable.ic_circle_check)
        speakers -> Triple("$name · done", "${r.engine} · ${seconds(r.seconds)} · ${r.speakers ?: 0} found", R.drawable.ic_circle_check)
        else -> Triple(name, "${r.engine} · ${seconds(r.seconds)} · ${r.summary?.todos?.size ?: 0} to-dos", R.drawable.ic_brain)
    }
    ListRow(title, subtitle, first = first, lead = { Glyph(if (!speech && !speakers && r.seconds != null && r.error == null) R.drawable.ic_brain else icon) })
}

data class Spot(val session: String, val ms: Long)

private class Said(val session: Session, val line: Line, val at: Long)

private class Shot(val session: Session, val photo: Photo, val at: Long)

private fun Conversation.at(s: Session, ms: Long): Long = (s.wallAt(ms) - start).inWholeMilliseconds

private fun Conversation.end(): Instant = parts.last().let { it.session.wallAt(it.toMs) }

private fun Conversation.sessions(): List<Session> = parts.map { it.session }.distinctBy { it.id }

private fun Conversation.shots(): List<Shot> = parts.flatMap { p -> p.session.photos.filter { p.covers(it.offsetMs) }.map { Shot(p.session, it, at(p.session, it.offsetMs)) } }

private fun clock(t: Instant): String = whenLabel(t).substringAfterLast(' ')

private fun conversationTitle(v: ConversationView): String = v.recap.title.ifEmpty { "Untitled session" }

private fun conversationSubtitle(v: ConversationView): String {
    val n = v.recap.todos.size
    val todos = when (n) {
        0 -> "no to-dos"
        1 -> "1 to-do"
        else -> "$n to-dos"
    }
    val c = v.conversation
    return listOfNotNull("${clock(c.start)}–${clock(c.end())}", placeLabel(c.head.session), if (v.ready) todos else "summarizing").joinToString(" · ")
}

@Composable
fun TranscriptsScreen(conversations: List<ConversationView>, open: (ConversationView) -> Unit) = Page("Transcripts") {
    if (conversations.isEmpty()) item { CardList { ListRow("No transcripts yet", "Sessions show here after the Sticky records", first = true) } }
    conversations.groupBy { it.conversation.start.date() }.entries.forEachIndexed { g, (day, list) ->
        if (g > 0) gap()
        item { Section(dayLabel(day)) }
        gap()
        item {
            CardList {
                list.forEachIndexed { i, v ->
                    val c = v.conversation
                    if (v.live) {
                        val tail = c.transcript().takeLast(80).substringAfter(' ').trim()
                        ListRow(
                            listOfNotNull("Live · started ${clock(c.start)}", placeLabel(c.head.session)).joinToString(" · "),
                            if (tail.isEmpty()) "Listening" else "“…$tail…”",
                            first = i == 0,
                            subtitleLines = 2,
                            onClick = { open(v) },
                        ) { Pill("Live", Ink.black, Ink.white) }
                    } else {
                        ListRow(conversationTitle(v), conversationSubtitle(v), first = i == 0, onClick = { open(v) }) {
                            val marks = c.marks()
                            val photos = c.photos()
                            if (marks > 0) Pill("$marks") { Glyph(R.drawable.ic_star_filled, 13.dp) }
                            if (photos > 0) Pill("$photos") { Glyph(R.drawable.ic_photo, 13.dp) }
                            Chevron()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimedLine(line: Line, at: Long, who: String?, cleanUp: Boolean, now: Boolean, near: Boolean, marked: Boolean, onWho: () -> Unit, onClick: () -> Unit) {
    val shape = if (now) RoundedCornerShape(14.dp) else RectangleShape
    val background = when {
        now -> Ink.black
        near -> Ink.card
        else -> Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().clip(shape).background(background).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.width(66.dp).height(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(timeLabel(at), style = Type.pill.copy(fontSize = 13.sp, color = if (now) Ink.mutedDark else Ink.muted))
            if (marked) Glyph(R.drawable.ic_star_filled, 13.dp, if (now) Ink.mutedDark else Ink.muted)
        }
        val style = if (now) Type.body.copy(color = Ink.white, fontWeight = FontWeight.SemiBold) else Type.body
        val text = if (cleanUp && line.words.isNotEmpty()) cleanText(line.words) else line.text
        val body = buildAnnotatedString {
            if (who != null) appendInlineContent("who")
            text.split(CLEANED).forEachIndexed { k, part ->
                if (k > 0) withStyle(SpanStyle(color = if (now) Ink.struck else Ink.off, letterSpacing = 1.sp)) { append(CLEANED) }
                append(part)
            }
        }
        if (who == null) Text(body, style = style)
        else {
            val chip = mapOf(
                "who" to InlineTextContent(Placeholder((who.length * 6.6 + 20).sp, 20.sp, PlaceholderVerticalAlign.TextCenter)) {
                    Box(
                        Modifier.fillMaxSize().padding(end = 6.dp).clip(CircleShape).background(if (now) Ink.pillDark else Ink.divider).clickable(onClick = onWho),
                        contentAlignment = Alignment.Center,
                    ) { Text(who, style = Type.pill.copy(fontSize = 11.sp, color = if (now) Ink.white else Ink.black), maxLines = 1) }
                },
            )
            Text(body, style = style, inlineContent = chip)
        }
    }
}

@Composable
fun Thumb(file: File, modifier: Modifier, maxPx: Int = 320) {
    val image by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Ink.divider), contentAlignment = Alignment.Center) {
        val img = image
        if (img == null) Glyph(R.drawable.ic_photo, 24.dp, Ink.muted)
        else androidx.compose.foundation.Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun PhotoRow(photo: Photo, at: Long, file: File, near: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (near) Ink.card else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.width(66.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(timeLabel(at), style = Type.pill.copy(fontSize = 13.sp, color = Ink.muted))
            Glyph(R.drawable.ic_camera, 13.dp, Ink.muted)
        }
        Thumb(file, Modifier.width(96.dp).height(68.dp))
        Column(Modifier.weight(1f)) {
            Text("Photo", style = Type.row.copy(fontSize = 14.sp))
            if (photo.text.isNotBlank()) Text("“${photo.text.replace('\n', ' ')}”", style = Type.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PhotoViewer(photo: Photo, at: Long, file: File, dismiss: () -> Unit, show: () -> Unit, play: () -> Unit) {
    androidx.compose.ui.window.Dialog(dismiss, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Ink.white).navigationBarsPadding()) {
            TopBar("Photo · ${timeLabel(at)}", dismiss)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = MAIN) {
                item { Thumb(file, Modifier.fillMaxWidth().height(420.dp), maxPx = 2048) }
                gap()
                item { Section("Text in the photo") }
                gap()
                item { Card { Text(photo.text.ifBlank { "No text found." }, style = Type.body) } }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton("Show on Sticky", R.drawable.ic_device_mobile, show)
                Row(
                    Modifier.fillMaxWidth().height(48.dp).clip(CircleShape).background(Ink.black).clickable(onClick = play),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(R.drawable.ic_player_play, 20.dp, Ink.white)
                    Text("Play from ${timeLabel(at)}", style = Type.button.copy(color = Ink.white))
                }
            }
        }
    }
}

@Composable
fun PhotosScreen(
    photos: List<Unlinked>,
    conversations: List<ConversationView>,
    photoFile: (Session, Photo) -> File,
    play: (ConversationView, Session, Photo) -> Unit,
    showOnSticky: (File) -> Unit,
    attach: (Unlinked, Session) -> Unit,
    back: () -> Unit,
) {
    var choosing by remember { mutableStateOf<Unlinked?>(null) }
    var viewing by remember { mutableStateOf<Pair<ConversationView, Shot>?>(null) }
    val withPhotos = conversations.mapNotNull { v -> v.conversation.shots().takeIf { it.isNotEmpty() }?.let { v to it } }
    Page("Photos", back) {
        if (withPhotos.isEmpty() && photos.isEmpty()) item { CardList { ListRow("No photos yet", "Tap Snap during a meeting", first = true) } }
        withPhotos.forEachIndexed { k, (v, shots) ->
            val start = v.conversation.start
            if (k > 0) gap()
            item { Section("${conversationTitle(v)} · ${dayLabel(start.date()).lowercase().replaceFirstChar { it.uppercase() }} ${clock(start)}") }
            gap()
            shots.chunked(3).forEachIndexed { r, row ->
                if (r > 0) item { Gap(8.dp) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { s -> Thumb(photoFile(s.session, s.photo), Modifier.weight(1f).aspectRatio(1f).clickable { viewing = v to s }) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (photos.isNotEmpty()) {
            gap()
            item { Section("Not linked") }
            gap()
        }
        if (photos.isNotEmpty()) item {
            CardList {
                photos.forEachIndexed { i, p ->
                    val taken = kotlin.time.Instant.fromEpochMilliseconds(p.takenMs)
                    ListRow(
                        dayLabel(taken.date()) + " " + whenLabel(taken).substringAfterLast(' '),
                        p.text.replace('\n', ' ').ifBlank { "No text" },
                        first = i == 0,
                        lead = { Thumb(p.file, Modifier.width(72.dp).height(52.dp)) },
                    ) { Box(Modifier.clip(CircleShape).clickable { choosing = p }.padding(horizontal = 4.dp, vertical = 6.dp)) { Text("Attach", style = Type.button.copy(fontSize = 14.sp)) } }
                }
            }
        }
    }
    viewing?.let { (v, s) ->
        val file = photoFile(s.session, s.photo)
        PhotoViewer(s.photo, s.at, file, { viewing = null }, { showOnSticky(file) }) {
            viewing = null
            play(v, s.session, s.photo)
        }
    }
    choosing?.let { photo ->
        AttachSheet(conversations, { choosing = null }) { v ->
            val c = v.conversation
            attach(photo, linkPhoto(c.sessions(), Instant.fromEpochMilliseconds(photo.takenMs)) ?: c.head.session)
            choosing = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachSheet(conversations: List<ConversationView>, dismiss: () -> Unit, pick: (ConversationView) -> Unit) {
    ModalBottomSheet(
        dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = Ink.white,
        scrimColor = Color.Black.copy(alpha = 0.45f),
        dragHandle = { Box(Modifier.padding(top = 14.dp).width(40.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Ink.off)) },
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Attach to a session", style = Type.heading.copy(fontSize = 20.sp), modifier = Modifier.padding(horizontal = 4.dp))
            CardList {
                conversations.take(8).forEachIndexed { i, v ->
                    ListRow(conversationTitle(v), conversationSubtitle(v), first = i == 0, onClick = { pick(v) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameSheet(label: String, quote: String?, dismiss: () -> Unit, save: (String) -> Unit) {
    var name by remember { mutableStateOf(if (label.startsWith("Speaker ")) "" else label) }
    ModalBottomSheet(
        dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = Ink.white,
        scrimColor = Color.Black.copy(alpha = 0.45f),
        dragHandle = { Box(Modifier.padding(top = 14.dp).width(40.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Ink.off)) },
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 28.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Name $label", style = Type.heading.copy(fontSize = 20.sp), modifier = Modifier.padding(horizontal = 4.dp))
            Text("For the whole session. The summary and the to-dos use the name.", style = Type.small, modifier = Modifier.padding(horizontal = 4.dp))
            Field("Name", name, { name = it }, onDone = { save(name) })
            quote?.let { Text("“$it”", style = Type.small, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp)) }
            FilledButton("Save") { save(name) }
        }
    }
}

@Composable
fun TranscriptScreen(
    view: ConversationView?,
    back: () -> Unit,
    audio: suspend (Session) -> SessionAudio?,
    denoiserModel: () -> String?,
    prefs: PlayerPrefs,
    setPrefs: (PlayerPrefs) -> Unit,
    rename: (Session, Int, String) -> Unit,
    photoFile: (Session, Photo) -> File,
    showOnSticky: (File) -> Unit,
    startAt: Spot?,
    started: () -> Unit,
    onTodo: (String, Int, Boolean) -> Unit,
) {
    val v = view ?: return Page("Transcript", back) {}
    val c = v.conversation
    val r = v.recap
    val sessions = remember(c) { c.sessions() }
    var naming by remember { mutableStateOf<Pair<Session, Int>?>(null) }
    var viewing by remember { mutableStateOf<Shot?>(null) }
    var playingId by remember(c.key) { mutableStateOf(startAt?.session ?: c.head.session.id) }
    var pending by remember { mutableStateOf<Pair<Long, Boolean>?>(null) }
    val playing = sessions.find { it.id == playingId } ?: c.head.session
    val skips = remember(playing, prefs) { playing.skipRanges(prefs.cleanUp, prefs.skipSilence) }
    val playback = rememberPlayback(playing.device, playing.id, skips, prefs.crisp, denoiserModel) { audio(playing) }
    val ready = playback.audio == Audio.Ready
    val go = { session: String, ms: Long, play: Boolean ->
        if (session == playingId && ready) {
            playback.seek(ms)
            if (play && !playback.playing) playback.toggle()
        } else {
            playingId = session
            pending = ms to play
        }
    }
    val said = remember(c) { c.parts.flatMap { p -> p.lines().map { Said(p.session, it, c.at(p.session, it.atMs)) } } }
    val marks = remember(c) { c.parts.flatMap { p -> p.session.marks.filter(p::covers).map { p.session to it } } }
    val near = remember(c) {
        said.indices.filter { i ->
            val s = said[i]
            val next = said.getOrNull(i + 1)?.takeIf { it.session.id == s.session.id }?.line?.atMs ?: Long.MAX_VALUE
            marks.any { (ms, m) -> ms.id == s.session.id && s.line.atMs <= m && next > m - MARK_WINDOW_MS }
        }.toSet()
    }
    val starred = remember(c) { marks.map { (ms, m) -> said.indexOfLast { it.session.id == ms.id && it.line.atMs <= m } }.toSet() }
    val current = if (ready && (playback.playing || playback.positionMs > 0)) said.indexOfLast { it.session.id == playing.id && it.line.atMs <= playback.positionMs } else -1
    val shots = remember(c) { c.shots() }
    val rows = remember(c) {
        val out = mutableListOf<Any>()
        var p = 0
        said.forEachIndexed { i, l ->
            while (p < shots.size && shots[p].at < l.at) out += shots[p++]
            out += i
        }
        out.addAll(shots.drop(p))
        out
    }
    val lineRow = remember(rows) { IntArray(said.size).also { a -> rows.forEachIndexed { k, x -> if (x is Int) a[x] = k } } }
    val list = rememberLazyListState()
    LaunchedEffect(startAt) {
        val at = startAt ?: return@LaunchedEffect
        go(at.session, at.ms, true)
        started()
    }
    LaunchedEffect(pending, ready) {
        val (ms, play) = pending ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        playback.seek(ms)
        if (play && !playback.playing) playback.toggle()
        pending = null
    }
    LaunchedEffect(current) {
        if (current < 0 || !playback.playing) return@LaunchedEffect
        val visible = list.layoutInfo.visibleItemsInfo
        val row = lineRow[current] + 1
        val item = visible.find { it.index == row }
        val end = list.layoutInfo.viewportEndOffset
        if (item == null || item.offset < 0 || item.offset + item.size > end - end / 4) list.animateScrollToItem((row - 2).coerceAtLeast(1))
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(r.title.ifEmpty { if (v.live) "Live session" else "Untitled session" }, back)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = MAIN) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(whenLabel(c.start), Ink.card)
                        placeLabel(c.head.session)?.let { Pill(it, Ink.card) }
                        Pill(duration((c.end() - c.start).inWholeMilliseconds), Ink.card)
                        if (r.engine.isNotEmpty()) Pill(r.engine, Ink.card)
                        if (shots.isNotEmpty()) Pill("${shots.size}", Ink.card) { Glyph(R.drawable.ic_photo, 14.dp) }
                    }
                    Card {
                        Text("SUMMARY", style = Type.section, modifier = Modifier.padding(bottom = 8.dp))
                        Text(
                            when {
                                v.ready -> r.summary.ifEmpty { "No summary." }
                                v.live -> "The summary comes when the session ends."
                                else -> "The summary is in progress."
                            },
                            style = Type.body,
                        )
                    }
                    if (r.todos.isNotEmpty()) {
                        Section("To-dos · ${r.todos.size}")
                        CardList {
                            r.todos.forEachIndexed { i, t ->
                                val done = "$i" in r.done
                                val quote = r.quotes.getOrNull(i)?.takeIf { q -> q.atMs >= 0 && sessions.any { it.id == q.session } }
                                ListRow(
                                    t,
                                    first = i == 0,
                                    lead = {
                                        CheckBox(done) {
                                            val id = c.todoId(i)
                                            onTodo(id.substringBeforeLast('-'), id.substringAfterLast('-').toInt(), it)
                                        }
                                    },
                                    titleStyle = if (done) Type.row.copy(color = Ink.struck, textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough) else Type.row,
                                    trail = quote?.let { q ->
                                        {
                                            Box(Modifier.clip(CircleShape).clickable { go(q.session, q.atMs, true) }.padding(6.dp)) { Glyph(R.drawable.ic_player_play, 18.dp) }
                                        }
                                    },
                                )
                            }
                        }
                    }
                    Section("Transcript")
                    if (marks.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                            Text("Marks", style = Type.small.copy(fontWeight = FontWeight.Bold))
                            marks.forEach { (ms, m) ->
                                Pill(timeLabel(c.at(ms, m)), Ink.card, onClick = { go(ms.id, m - MARK_LEAD_MS, false) }) { Glyph(R.drawable.ic_star_filled, 13.dp) }
                            }
                        }
                    }
                    if (sessions.any { it.speakersFailed }) Text("Speakers not available", style = Type.small)
                    if (playback.audio == Audio.Missing) Text("The audio is deleted 7 days after the recording.", style = Type.small)
                    if (said.isEmpty()) Text(if (v.live) "Listening" else "No words yet.", style = Type.body)
                    Gap(0.dp)
                }
            }
            items(rows.size) { k ->
                val row = rows[k]
                if (row is Shot) {
                    val nearPhoto = said.indices.any { it in near && said[it].session.id == row.session.id && said[it].line.atMs <= row.photo.offsetMs }
                    PhotoRow(row.photo, row.at, photoFile(row.session, row.photo), nearPhoto) { viewing = row }
                    return@items
                }
                val i = row as Int
                val s = said[i]
                val speaker = s.line.speaker
                TimedLine(s.line, s.at, if (speaker >= 0) s.session.speakerName(speaker) else null, prefs.cleanUp, i == current, i in near, i in starred, { naming = s.session to speaker }) {
                    go(s.session.id, s.line.atMs, false)
                }
            }
        }
        if (playback.audio != Audio.Missing) {
            PlayerBar(
                playback,
                marks.filter { it.first.id == playing.id }.map { it.second },
                shots.filter { it.session.id == playing.id }.map { it.photo.offsetMs },
                prefs,
                setPrefs,
            ) { c.at(playing, it) }
        }
    }
    viewing?.let { shot ->
        val file = photoFile(shot.session, shot.photo)
        PhotoViewer(shot.photo, shot.at, file, { viewing = null }, { showOnSticky(file) }) {
            viewing = null
            go(shot.session.id, shot.photo.offsetMs, true)
        }
    }
    naming?.let { (session, speaker) ->
        val quote = said.filter { it.session.id == session.id && it.line.speaker == speaker }.maxByOrNull { it.line.text.length }?.line?.text
        RenameSheet(session.speakerName(speaker), quote, { naming = null }) {
            rename(session, speaker, it)
            naming = null
        }
    }
}

@Composable
fun TodoScreen(todos: List<DeviceTodos>, onSet: (String, TodoItem, Boolean) -> Unit) {
    var showDone by rememberSaveable { mutableStateOf(false) }
    val open = todos.flatMap { d -> d.list.open.map { d.device to it } }
    val done = todos.flatMap { d -> d.list.done.map { d.device to it } }
    Page("To-do") {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Open · ${open.size}", if (showDone) Ink.card else Ink.black, if (showDone) Ink.black else Ink.white, onClick = { showDone = false })
                Pill("Done today · ${done.size}", if (showDone) Ink.black else Ink.card, if (showDone) Ink.white else Ink.black, onClick = { showDone = true })
            }
        }
        gap()
        item {
            val list = if (showDone) done else open
            CardList {
                if (list.isEmpty()) ListRow(if (showDone) "Nothing done today" else "No open to-dos", first = true)
                list.forEachIndexed { i, (device, t) ->
                    ListRow(t.text, t.from, first = i == 0, lead = { CheckBox(showDone) { onSet(device, t, it) } })
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    hub: HubState,
    transcribe: TranscribeMode,
    speech: Model,
    places: String,
    calendars: String,
    updates: String,
    back: () -> Unit,
    onHub: (Boolean) -> Unit,
    selectTranscribe: (TranscribeMode) -> Unit,
    open: (Detail) -> Unit,
) = Page("App settings", back) {
    item {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("This phone is a hub", style = Type.heading)
                    Text("The Sticky sends audio here", style = Type.small)
                }
                Toggle(hub.on, onHub)
            }
        }
    }
    gap()
    item {
        CardList {
            ListRow("Models", speech.label, first = true, lead = { Glyph(R.drawable.ic_brain) }, onClick = { open(Detail.Models) }) { Chevron() }
            ListRow("Pipeline", if (hub.received.isEmpty()) "Idle" else "${hub.received.size} waiting", lead = { Glyph(R.drawable.ic_activity) }, onClick = { open(Detail.Pipeline) }) { Chevron() }
            ListRow("Places", places, lead = { Glyph(R.drawable.ic_map_pin) }, onClick = { open(Detail.Places) }) { Chevron() }
            ListRow("Calendars", calendars, lead = { Glyph(R.drawable.ic_calendar) }, onClick = { open(Detail.Calendars) }) { Chevron() }
            ListRow("Updates", updates, lead = { Glyph(R.drawable.ic_download) }, onClick = { open(Detail.Updates) }) { Chevron() }
        }
    }
    gap()
    item { Section("Transcribe") }
    gap()
    item {
        CardList {
            TranscribeMode.entries.forEachIndexed { i, m ->
                val subtitle = when (m) {
                    TranscribeMode.Now -> "Each chunk when it arrives"
                    TranscribeMode.Every15 -> "In one batch every 15 minutes"
                    TranscribeMode.Charging -> "Chunks wait until the phone charges"
                }
                ChoiceRow(m.label, subtitle, transcribe == m, i == 0, i == 0, { selectTranscribe(m) })
            }
        }
    }
    gap()
    item { Section("Hubs") }
    gap()
    item {
        CardList {
            ListRow("This phone", if (hub.running) hub.address ?: "No Wi-Fi" else "Off", first = true, lead = { Glyph(R.drawable.ic_server) })
        }
    }
}

@Composable
fun ChoiceRow(title: String, subtitle: String, selected: Boolean, first: Boolean, isDefault: Boolean, onClick: () -> Unit, trail: (@Composable () -> Unit)? = null) {
    ListRow(title, subtitle, first = first, lead = { Radio(selected) }, onClick = onClick) {
        when {
            trail != null -> trail()
            isDefault -> Pill("Default")
        }
    }
}

private fun modelSubtitle(m: Model, state: ModelState?, speed: Double?, per: String): String {
    val size = if (m.megabytes >= 1000) "%.1f GB".format(m.megabytes / 1000.0) else "${m.megabytes} MB"
    return when (state) {
        is ModelState.Downloading -> "Downloading ${state.percent}% · $size"
        is ModelState.Failed -> "Download failed · $size"
        else -> "On phone · $size" + (speed?.let { " · ${seconds(it)}$per" } ?: "")
    }
}

@Composable
fun ModelsScreen(
    state: ModelsState,
    speech: Model,
    open: SummaryEngine,
    closed: SummaryEngine,
    back: () -> Unit,
    selectSpeech: (Model) -> Unit,
    selectSummary: (Boolean, SummaryEngine) -> Unit,
    download: (Model) -> Unit,
    downloadNano: () -> Unit,
) = Page("Models", back) {
    item { Section("Speech-to-text") }
    gap()
    item {
        CardList {
            SPEECH_MODELS.forEachIndexed { i, m ->
                val s = state.models[m]
                ChoiceRow(m.label, modelSubtitle(m, s, state.speeds[m.label], " per 30 s"), speech == m, i == 0, m == DEFAULT_SPEECH, { selectSpeech(m) },
                    if (s == ModelState.Missing || s is ModelState.Failed) ({ Box(Modifier.clickable { download(m) }) { Glyph(R.drawable.ic_download, 20.dp) } }) else null)
            }
        }
    }
    listOf("Speakers" to (SPEAKER_MODELS to "Downloads when a session ends"), "Crisp voice" to (listOf(Model.Denoiser) to "Downloads when you choose Clear + denoise")).forEach { (title, group) ->
        gap()
        item { Section(title) }
        gap()
        item {
            CardList {
                group.first.forEachIndexed { i, m ->
                    val s = state.models[m]
                    val subtitle = if (s == ModelState.Missing) "${group.second} · ${m.megabytes} MB" else modelSubtitle(m, s, null, "")
                    ListRow(m.label, subtitle, first = i == 0) {
                        if (s == ModelState.Missing || s is ModelState.Failed) Box(Modifier.clickable { download(m) }) { Glyph(R.drawable.ic_download, 20.dp) }
                    }
                }
            }
        }
    }
    listOf(true to OPEN_SUMMARY, false to CLOSED_SUMMARY).forEach { (isOpen, options) ->
        gap()
        item { Section(if (isOpen) "Summaries · app open" else "Summaries · app closed") }
        gap()
        item {
            CardList {
                options.forEachIndexed { i, e ->
                    val subtitle = when (e) {
                        SummaryEngine.Nano -> when (state.nano) {
                            FeatureStatus.AVAILABLE -> "Built in" + (state.speeds[e.label]?.let { " · ${seconds(it)}" } ?: "")
                            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> "Built in · not downloaded"
                            FeatureStatus.UNAVAILABLE -> "Not available on this phone"
                            else -> "Built in"
                        }
                        SummaryEngine.Gemma -> modelSubtitle(Model.Gemma4E2B, state.models[Model.Gemma4E2B], state.speeds[e.label], "")
                        SummaryEngine.Laptop -> "Codex gpt-6-luna · after hub sync"
                    }
                    val missing = e == SummaryEngine.Gemma && state.models[Model.Gemma4E2B].let { it == ModelState.Missing || it is ModelState.Failed }
                    val nanoMissing = e == SummaryEngine.Nano && state.nano == FeatureStatus.DOWNLOADABLE
                    ChoiceRow(e.label, subtitle, (if (isOpen) open else closed) == e, i == 0, i == 0, { selectSummary(isOpen, e) },
                        when {
                            missing -> ({ Box(Modifier.clickable { download(Model.Gemma4E2B) }) { Glyph(R.drawable.ic_download, 20.dp) } })
                            nanoMissing -> ({ Box(Modifier.clickable(onClick = downloadNano)) { Glyph(R.drawable.ic_download, 20.dp) } })
                            else -> null
                        })
                }
            }
        }
    }
}

@Composable
private fun CodeEntry(enabled: Boolean, onCode: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(enabled) {
        code = ""
        if (enabled) focus.requestFocus()
    }
    Box {
        BasicTextField(
            code,
            { v ->
                val digits = v.filter(Char::isDigit).take(6)
                code = digits
                if (digits.length == 6) onCode(digits)
            },
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.focusRequester(focus).height(58.dp).fillMaxWidth(),
            textStyle = Type.big.copy(color = Color.Transparent),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
        )
        Row(Modifier.fillMaxWidth().clickable(enabled) { focus.requestFocus() }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(6) { i ->
                val current = enabled && i == code.length
                Box(
                    Modifier.weight(1f).height(58.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (current) Color.Transparent else Ink.pillDark)
                        .then(if (current) Modifier.border(2.dp, Ink.white, RoundedCornerShape(14.dp)) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    code.getOrNull(i)?.let { Text("$it", style = Type.big.copy(color = Ink.white, fontSize = 28.sp, letterSpacing = 0.sp)) }
                }
            }
        }
    }
}

@Composable
private fun BuzzPitch(hz: Int, set: (Int) -> Unit, test: (Int) -> Unit) {
    var value by remember(hz) { mutableStateOf(hz.toFloat()) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Buzz pitch", style = Type.row, modifier = Modifier.weight(1f))
            Text("${value.toInt()} Hz", style = Type.row)
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { set(value.toInt()) },
            valueRange = BUZZ_RANGE.first.toFloat()..BUZZ_RANGE.last.toFloat(),
            steps = (BUZZ_RANGE.last - BUZZ_RANGE.first) / 20 - 1,
            colors = SliderDefaults.colors(thumbColor = Ink.black, activeTrackColor = Ink.black, inactiveTrackColor = Ink.navLine, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
        )
        OutlinedButton(onClick = { test(value.toInt()) }, modifier = Modifier.fillMaxWidth()) { Text("Test", style = Type.row) }
    }
}

class StickyDisplay(val home: String, val clock: String, val weather: String, val sound: String, val night: String, val wifi: String, val health: String, val firmware: FirmwareView)

class FirmwareView(val current: String?, val latest: String?, val checkedMs: Long, val state: FwState, val connected: Boolean) {
    val newer: Boolean get() = latest != null && current != null && isNewer(latest, current)
    val checked: String get() = if (checkedMs > 0) "checked ${whenLabel(Instant.fromEpochMilliseconds(checkedMs))}" else "not checked yet"
}

@Composable
fun FirmwareRow(name: String, f: FirmwareView, first: Boolean, check: () -> Unit, update: () -> Unit, cancel: () -> Unit) {
    val (title, subtitle, percent) = when (val s = f.state) {
        FwState.Checking -> Triple(name, "Checking GitHub…", null)
        is FwState.Downloading -> Triple("1 Download ${s.version}", "${s.percent} % · from GitHub", s.percent)
        is FwState.Sending -> Triple("2 Send to the Sticky", "${s.percent} %${if (s.kbps > 0) " · ${s.kbps} KB/s" else ""} · keep the phone near", s.percent)
        is FwState.Installing -> Triple("3 Install ${s.version}", "The Sticky checks the image", null)
        is FwState.Restarting -> Triple("3 Install ${s.version}", "The Sticky restarts · waiting for it to connect again", null)
        FwState.Cancelled -> Triple("Update cancelled", "Update now starts again from the download", null)
        is FwState.Updated -> Triple("4 Done · ${s.version}", "The Sticky checks itself for 3 minutes", null)
        is FwState.RolledBack -> Triple("Rolled back to ${s.version}", "The new firmware failed its check", null)
        is FwState.Failed -> Triple("Update failed", s.message, null)
        FwState.Idle -> Triple(
            name,
            when {
                f.current == null -> "Unknown until the first check-in"
                f.newer -> "Latest ${f.latest} · ${f.checked}" + if (f.connected) "" else " · connect the Sticky over Bluetooth"
                f.latest == null -> "No release found · ${f.checked}"
                else -> "Up to date · latest release ${f.latest} · ${f.checked}"
            },
            null,
        )
    }
    if (!first) HorizontalDivider(thickness = 1.dp, color = Ink.divider)
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = Type.row, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = Type.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
            percent?.let {
                Gap(5.dp)
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Ink.divider)) {
                    Box(Modifier.fillMaxWidth(it / 100f).height(6.dp).clip(CircleShape).background(Ink.black))
                }
            }
        }
        when (f.state) {
            FwState.Idle -> if (f.newer && f.connected) Pill("Update now", Ink.black, Ink.white, onClick = update)
                else Pill("Check for updates", Ink.white, onClick = check)
            is FwState.Failed, is FwState.RolledBack, FwState.Cancelled -> Pill("Try again", Ink.white, onClick = update)
            is FwState.Downloading, is FwState.Sending -> Pill("Cancel", Ink.white, onClick = cancel)
            is FwState.Updated -> Pill("Check for updates", Ink.white, onClick = check)
            else -> Unit
        }
    }
}

@Composable
fun UpdatesScreen(
    appVersion: String,
    release: dev.resticky.Release?,
    checkedMs: Long,
    status: String,
    sticky: String,
    firmware: FirmwareView,
    back: () -> Unit,
    check: () -> Unit,
    installApp: () -> Unit,
    updateSticky: () -> Unit,
    cancelSticky: () -> Unit,
) = Page("Updates", back) {
    item { Section("Phone app") }
    gap()
    item {
        Card {
            Text("resticky $appVersion", style = Type.heading)
            Text(
                when {
                    release == null -> "No release found yet"
                    isNewer(release.version, appVersion) -> "Latest release ${release.version}${release.published.take(10).let { if (it.isEmpty()) "" else " · $it" }}"
                    else -> "Up to date · latest release ${release.version}"
                },
                style = Type.small,
            )
            if (release != null && release.apk != null && isNewer(release.version, appVersion)) {
                Gap(14.dp)
                FilledButton("Download and install", onClick = installApp)
                Gap(10.dp)
                Text(status.ifEmpty { "Android asks you to confirm the install." }, style = Type.small)
            } else if (status.isNotEmpty()) {
                Gap(10.dp)
                Text(status, style = Type.small)
            }
        }
    }
    gap()
    item { Section("Sticky firmware") }
    gap()
    item { CardList { FirmwareRow(sticky, firmware, true, check, updateSticky, cancelSticky) } }
    gap()
    item {
        Text(
            (if (checkedMs > 0) "Checked ${whenLabel(Instant.fromEpochMilliseconds(checkedMs))}" else "Not checked yet") + " · github.com/$RELEASES_REPO",
            style = Type.small,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    gap()
    item { OutlineButton("Check for updates", R.drawable.ic_refresh, check) }
}

fun wifiSummary(sticky: dev.resticky.Checkin?): String {
    val saved = sticky?.wifiSaved.orEmpty()
    return when (saved.size) {
        0 -> if (sticky == null) "Unknown until the first check-in" else "None saved"
        1 -> saved.first()
        else -> "${saved.first()} and ${saved.size - 1} more"
    }
}

@Composable
fun StickyScreen(
    title: String,
    ble: BleState,
    sticky: dev.resticky.Checkin?,
    display: StickyDisplay,
    back: () -> Unit,
    onCode: (String) -> Unit,
    pair: () -> Unit,
    forget: () -> Unit,
    setSound: (String) -> Unit,
    buzzHz: Int,
    setBuzzHz: (Int) -> Unit,
    buzzTest: (Int) -> Unit,
    checkFirmware: () -> Unit,
    updateFirmware: () -> Unit,
    cancelFirmware: () -> Unit,
    open: (Detail) -> Unit,
) = Page("Sticky settings", back) {
    val paired = ble.address != null
    item {
        Card(dark = true, padding = PaddingValues(22.dp)) {
            if (paired && !ble.passkeyWanted) {
                Text(title, style = Type.heading.copy(color = Ink.white))
                Gap(4.dp)
                Text(
                    when (ble.status) {
                        LinkStatus.Ready -> "Connected over Bluetooth${sticky?.firmware?.takeIf { it.isNotEmpty() }?.let { " · firmware $it" }.orEmpty()}"
                        LinkStatus.Off -> "Not connected · the hub is off"
                        else -> "Waiting for the Sticky"
                    },
                    style = Type.small.copy(color = Ink.mutedDark),
                )
                ble.lastChunk?.let {
                    Gap(4.dp)
                    Text("Last chunk $it", style = Type.small.copy(color = Ink.mutedDark), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Text("Pair the Sticky", style = Type.heading.copy(color = Ink.white))
                Gap(4.dp)
                Text(
                    when {
                        ble.passkeyWanted -> "Type the code that the Sticky shows"
                        paired -> "Connecting to the Sticky"
                        else -> "Tap Pair, choose the Sticky, then type the code that it shows"
                    },
                    style = Type.small.copy(color = Ink.mutedDark),
                )
                Gap(16.dp)
                CodeEntry(ble.passkeyWanted, onCode)
                if (!paired) {
                    Gap(16.dp)
                    Pill("Pair", Ink.white, Ink.black, onClick = pair)
                }
            }
        }
    }
    gap()
    item { Section("Display") }
    gap()
    item {
        CardList {
            ListRow("Home screen", display.home, first = true, lead = { Glyph(R.drawable.ic_home) }, onClick = { open(Detail.HomeScreen) }) { Chevron() }
            ListRow("Clock", display.clock, lead = { Glyph(R.drawable.ic_clock) }, onClick = { open(Detail.Clock) }) { Chevron() }
            ListRow("Weather", display.weather, lead = { Glyph(R.drawable.ic_cloud) }, onClick = { open(Detail.Weather) }) { Chevron() }
        }
    }
    gap()
    item { Section("Feedback") }
    gap()
    item { Segmented(SOUNDS.map { it to it.replaceFirstChar(Char::uppercase) }, display.sound, big = true, onSelect = setSound) }
    if (display.sound == "buzz") {
        gap()
        item { BuzzPitch(buzzHz, setBuzzHz, buzzTest) }
    }
    gap()
    item { Section("Sticky") }
    gap()
    item {
        CardList {
            ListRow("Night", display.night, first = true, lead = { Glyph(R.drawable.ic_moon) }, onClick = { open(Detail.Night) }) { Chevron() }
            ListRow("Wi-Fi networks", display.wifi, lead = { Glyph(R.drawable.ic_wifi) }, onClick = { open(Detail.Wifi) }) { Chevron() }
            ListRow("Health", display.health, lead = { Glyph(R.drawable.ic_heart_rate_monitor) }, onClick = { open(Detail.Health) }) { Chevron() }
        }
    }
    gap()
    item { Section("Device") }
    gap()
    item {
        CardList {
            FirmwareRow("Firmware${sticky?.firmware?.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()}", display.firmware, true, checkFirmware, updateFirmware, cancelFirmware)
            ListRow(
                "Link",
                when {
                    ble.status == LinkStatus.Ready -> "Bluetooth · Wi-Fi for large backlogs"
                    sticky?.link == "wifi" -> "Wi-Fi"
                    else -> "Not connected"
                },
            )
        }
    }
    if (paired) {
        gap()
        item { OutlineButton("Forget this Sticky", onClick = forget) }
    }
}
