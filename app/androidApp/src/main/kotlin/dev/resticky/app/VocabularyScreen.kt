package dev.resticky.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.resticky.Rule
import dev.resticky.TermSource
import dev.resticky.VocabularyData

private fun sourceLabel(s: TermSource): String = when (s) {
    TermSource.User -> "Added by you"
    TermSource.Correction -> "From a correction"
    TermSource.Calendar -> "From the calendar"
    TermSource.People -> "From people"
    TermSource.Auto -> "From your recordings"
}

@Composable
private fun DeleteAction(onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp)) { Text("Delete", style = Type.button.copy(fontSize = 14.sp)) }
}

@Composable
fun VocabularyScreen(data: VocabularyData, back: () -> Unit, add: (String) -> Unit, removeTerm: (String) -> Unit, removeRule: (Rule) -> Unit) {
    var term by remember { mutableStateOf("") }
    val submit = {
        if (term.isNotBlank()) add(term)
        term = ""
    }
    Page("Vocabulary", back) {
        item {
            Text(
                "Names and words that speech recognition must know. The app adds names from the calendar, people and words that come back in recordings.",
                style = Type.small,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        gap()
        item { Field("New word or name", term, { term = it }, onDone = submit) }
        gap()
        item { FilledButton("Add", term.isNotBlank(), submit) }
        gap()
        item { Section("Fixes · ${data.rules.size}") }
        gap()
        item {
            CardList {
                if (data.rules.isEmpty()) ListRow("No fixes yet", "Fixes that repeat in transcripts show here", first = true, subtitleLines = 2)
                data.rules.forEachIndexed { i, r ->
                    ListRow("${r.from} → ${r.to}", first = i == 0) { DeleteAction { removeRule(r) } }
                }
            }
        }
        gap()
        item { Section("Words · ${data.terms.size}") }
        gap()
        item {
            CardList {
                if (data.terms.isEmpty()) ListRow("No words yet", first = true)
                data.terms.sortedWith(compareBy({ it.source }, { -it.lastUsed.toEpochMilliseconds() })).forEachIndexed { i, t ->
                    ListRow(t.text, sourceLabel(t.source), first = i == 0) { DeleteAction { removeTerm(t.text) } }
                }
            }
        }
    }
}
