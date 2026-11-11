package dev.resticky.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Montserrat = FontFamily(
    Font(R.font.montserrat_medium, FontWeight.Medium),
    Font(R.font.montserrat_semibold, FontWeight.SemiBold),
    Font(R.font.montserrat_bold, FontWeight.Bold),
)

object Ink {
    val black = Color(0xFF111111)
    val white = Color.White
    val card = Color(0xFFF3F3F3)
    val divider = Color(0xFFE2E2E2)
    val navLine = Color(0xFFE5E5E5)
    val muted = Color(0xFF666666)
    val mutedDark = Color(0xFFBBBBBB)
    val nav = Color(0xFF555555)
    val struck = Color(0xFF888888)
    val pillDark = Color(0xFF2A2A2A)
    val off = Color(0xFFCCCCCC)
}

private fun style(size: Int, weight: FontWeight, color: Color = Ink.black, spacing: Double = 0.0) =
    TextStyle(fontFamily = Montserrat, fontSize = size.sp, fontWeight = weight, color = color, letterSpacing = spacing.sp)

object Type {
    val title = style(26, FontWeight.Bold)
    val heading = style(17, FontWeight.Bold)
    val row = style(15, FontWeight.SemiBold)
    val body = style(15, FontWeight.Medium).copy(lineHeight = 22.5.sp)
    val small = style(13, FontWeight.Medium, Ink.muted)
    val big = style(30, FontWeight.Bold, spacing = -0.5)
    val pill = style(12, FontWeight.Bold)
    val section = style(12, FontWeight.Bold, Ink.muted, 1.0)
    val nav = style(12, FontWeight.Bold, Ink.nav)
    val button = style(15, FontWeight.Bold)
}

val CardShape = RoundedCornerShape(24.dp)

@Composable
fun Glyph(icon: Int, size: Dp = 22.dp, tint: Color = Ink.black) =
    Icon(painterResource(icon), null, Modifier.size(size), tint = tint)

@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) Box(Modifier.clip(CircleShape).clickable(onClick = onBack)) { Glyph(R.drawable.ic_chevron_left, 26.dp) }
        Text(title, style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun Section(text: String) {
    Text(text.uppercase(), style = Type.section, modifier = Modifier.padding(start = 4.dp, top = 6.dp, end = 4.dp))
}

@Composable
fun Card(dark: Boolean = false, padding: PaddingValues = PaddingValues(18.dp), content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(CardShape).background(if (dark) Ink.black else Ink.card).padding(padding), content = content)
}

@Composable
fun CardList(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(CardShape).background(Ink.card), content = content)
}

@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    first: Boolean = false,
    lead: (@Composable () -> Unit)? = null,
    titleStyle: TextStyle = Type.row,
    subtitleLines: Int = 1,
    onClick: (() -> Unit)? = null,
    trail: (@Composable RowScope.() -> Unit)? = null,
) {
    if (!first) HorizontalDivider(thickness = 1.dp, color = Ink.divider)
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        lead?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = Type.small, maxLines = subtitleLines, overflow = TextOverflow.Ellipsis) }
        }
        trail?.invoke(this)
    }
}

@Composable
fun Chevron() = Glyph(R.drawable.ic_chevron_right, 20.dp)

@Composable
fun Pill(text: String, background: Color = Ink.white, color: Color = Ink.black, onClick: (() -> Unit)? = null, lead: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.clip(CircleShape).background(background).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        lead?.invoke()
        Text(text, style = Type.pill.copy(color = color))
    }
}

@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = Type.button.copy(fontSize = 14.sp, color = if (on) Ink.white else Ink.black),
        modifier = Modifier.clip(CircleShape).background(if (on) Ink.black else Ink.card).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 9.dp),
    )
}

@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, big: Boolean = false, onSelect: (T) -> Unit) {
    Row(Modifier.clip(CircleShape).background(Ink.card).padding(3.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Text(
                label,
                style = Type.button.copy(fontSize = if (big) 14.sp else 13.sp, color = if (on) Ink.white else Ink.nav),
                modifier = Modifier.clip(CircleShape).background(if (on) Ink.black else Color.Transparent).clickable { onSelect(value) }
                    .padding(horizontal = if (big) 22.dp else 12.dp, vertical = if (big) 9.dp else 5.dp),
            )
        }
    }
}

@Composable
fun CheckBox(checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(if (checked) Ink.black else Color.Transparent)
            .border(2.5.dp, Ink.black, RoundedCornerShape(8.dp)).clickable { onChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Glyph(R.drawable.ic_check, 18.dp, Ink.white)
    }
}

@Composable
fun Radio(selected: Boolean, size: Dp = 22.dp) {
    Box(Modifier.size(size).border(if (selected) 7.dp else 2.5.dp, Ink.black, CircleShape))
}

@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    Box(Modifier.width(50.dp).height(30.dp).clip(CircleShape).background(if (on) Ink.black else Ink.off).clickable { onChange(!on) }) {
        Box(Modifier.offset(x = if (on) 24.dp else 4.dp, y = 4.dp).size(22.dp).clip(CircleShape).background(Ink.white))
    }
}

@Composable
fun OutlineButton(text: String, icon: Int? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(CircleShape).border(2.dp, Ink.black, CircleShape).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        icon?.let { Glyph(it, 20.dp) }
        Text(text, style = Type.button)
    }
}

@Composable
fun FilledButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(48.dp).clip(CircleShape).background(if (enabled) Ink.black else Ink.off).clickable(enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.button.copy(color = Ink.white))
    }
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = false, onDone: (() -> Unit)? = null) {
    Card {
        Text(label, style = Type.small)
        Gap(6.dp)
        BasicTextField(
            value,
            onChange,
            singleLine = true,
            textStyle = style(20, FontWeight.SemiBold),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun GroupHeader(text: String) {
    Text(text, style = Type.section.copy(fontSize = 14.sp, letterSpacing = 0.sp), modifier = Modifier.padding(start = 4.dp, top = 6.dp, end = 4.dp))
}

@Composable
fun Dot(color: Color) = Box(Modifier.size(8.dp).clip(CircleShape).background(color))

@Composable
fun Struck(text: String) = Text(text, style = Type.row.copy(color = Ink.struck, textDecoration = TextDecoration.LineThrough), maxLines = 1, overflow = TextOverflow.Ellipsis)

enum class Tab(val label: String, val icon: Int) {
    Hub("Hub", R.drawable.ic_home),
    Transcripts("Transcripts", R.drawable.ic_file_text),
    Todo("To-do", R.drawable.ic_list_check),
}

@Composable
fun BottomNav(selected: Tab, onSelect: (Tab) -> Unit, onSnap: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Ink.white).navigationBarsPadding()) {
        HorizontalDivider(thickness = 1.dp, color = Ink.navLine)
        Row(Modifier.fillMaxWidth().height(79.dp).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Tab.entries.forEach { tab ->
                if (tab == Tab.Todo) SnapButton(onSnap, Modifier.weight(1f))
                val on = tab == selected
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.width(56.dp).height(32.dp).clip(CircleShape).background(if (on) Ink.black else Color.Transparent), contentAlignment = Alignment.Center) {
                        Glyph(tab.icon, 22.dp, if (on) Ink.white else Ink.nav)
                    }
                    Text(tab.label, style = Type.nav.copy(color = if (on) Ink.black else Ink.nav), maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun SnapButton(onSnap: () -> Unit, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.offset(y = (-12).dp).size(58.dp).clip(CircleShape).background(Ink.white).padding(4.dp).clip(CircleShape).background(Ink.black).clickable(onClick = onSnap),
            contentAlignment = Alignment.Center,
        ) { Glyph(R.drawable.ic_camera, 26.dp, Ink.white) }
        Text("Snap", style = Type.nav, maxLines = 1, softWrap = false, modifier = Modifier.offset(y = (-12).dp))
    }
}

@Composable
fun Gap(height: Dp) = Spacer(Modifier.height(height))
