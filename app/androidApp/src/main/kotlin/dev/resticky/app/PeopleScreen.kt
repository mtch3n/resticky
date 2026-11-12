package dev.resticky.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.resticky.Person

sealed interface Enroll {
    data object Idle : Enroll
    data class Recording(val ms: Long) : Enroll
    data object Saving : Enroll
    data class Failed(val message: String) : Enroll
}

const val ENROLL_MS = 45_000L

const val READ_ALOUD = "The morning train was a little late, so I stood on the platform and watched the clouds move over the hills. " +
    "A man next to me was reading a paper map, and a young woman was talking quietly on the phone about a birthday dinner. " +
    "When the train came, I found a seat by the window. We passed fields, a river, a small farm with brown horses, " +
    "and a long row of houses with red roofs. At the next station, a family with two children got on and shared a bag of oranges. " +
    "I thought about the list of things to do today: call the bank, buy bread and milk, answer three emails, " +
    "and finish the report before five o'clock."

fun peopleLabel(people: List<Person>): String {
    val others = people.count { !it.isMe }
    return if (others == 0) "None yet" else "$others ${if (others == 1) "person" else "people"}"
}

@Composable
fun PeopleScreen(people: List<Person>, back: () -> Unit, voice: () -> Unit, edit: (Person) -> Unit, delete: (String) -> Unit) = Page("People", back) {
    val me = people.find { it.isMe }
    val others = people.filter { !it.isMe }.sortedWith(compareBy({ it.unnamed }, { it.name }))
    item {
        CardList {
            if (others.isEmpty()) ListRow("No people yet", "Voices that come back in recordings show here", first = true, subtitleLines = 2, lead = { Glyph(R.drawable.ic_users) })
            others.forEachIndexed { i, p ->
                ListRow(p.name, duration(p.talkMs), first = i == 0, lead = { Glyph(R.drawable.ic_users) }, onClick = { edit(p) }) {
                    Pill("Delete", onClick = { delete(p.id) })
                }
            }
        }
    }
    gap()
    item {
        CardList {
            val found = if (me == null) "Optional · found from your recordings" else if (me.auto) "Found from your recordings" else "Recorded · ${duration(me.talkMs)}"
            ListRow("Your voice", found, first = true, lead = { Glyph(R.drawable.ic_microphone) }, onClick = voice) { Chevron() }
        }
    }
    gap()
    item { Text("Voiceprints stay on this phone.", style = Type.small, modifier = Modifier.padding(horizontal = 4.dp)) }
}

@Composable
fun PersonScreen(person: Person?, back: () -> Unit, rename: (String, String) -> Unit, delete: (String) -> Unit) {
    val p = person ?: return Page("Person", back) {}
    var name by remember(p.id) { mutableStateOf(p.name) }
    val submit = {
        if (name.isNotBlank()) {
            rename(p.id, name)
            back()
        }
    }
    Page(p.name, back) {
        item { Field("Name", name, { name = it }, onDone = submit) }
        gap()
        item { FilledButton("Save", name.isNotBlank(), submit) }
        gap()
        item { OutlineButton(if (p.isMe) "Delete your voice" else "Delete this voice") { delete(p.id); back() } }
    }
}

@Composable
fun VoiceScreen(me: Person?, state: Enroll, back: () -> Unit, record: () -> Unit, stop: () -> Unit, edit: (Person) -> Unit, delete: (String) -> Unit) {
    DisposableEffect(Unit) { onDispose(stop) }
    Page("Your voice", back) {
        item { Text("Read aloud in your usual voice", style = Type.small, modifier = Modifier.padding(horizontal = 4.dp)) }
        gap()
        item { Card { Text(READ_ALOUD, style = Type.body) } }
        gap()
        item {
            when (state) {
                is Enroll.Recording -> FilledButton("Stop · ${state.ms / 1000} of ${ENROLL_MS / 1000} s", onClick = stop)
                Enroll.Saving -> FilledButton("Saving…", false) {}
                else -> FilledButton(if (me == null) "Record" else "Record again", onClick = record)
            }
        }
        if (state is Enroll.Failed) {
            gap()
            item { Text(state.message, style = Type.small, modifier = Modifier.padding(horizontal = 4.dp)) }
        }
        if (me != null && state !is Enroll.Recording && state != Enroll.Saving) {
            gap()
            item { CardList { ListRow("Name", me.name, first = true, lead = { Glyph(R.drawable.ic_users) }, onClick = { edit(me) }) { Chevron() } } }
            gap()
            item { OutlineButton("Delete your voice") { delete(me.id) } }
        }
    }
}
