package dev.resticky.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.resticky.NamedPlace
import dev.resticky.PENDING
import dev.resticky.PLACE_RADII
import dev.resticky.PLACE_RADIUS
import dev.resticky.Session
import java.util.UUID

data class LocationAccess(val precise: Boolean = false, val background: Boolean = false)

data class Here(val locating: Boolean = false, val lat: Double? = null, val lon: Double? = null, val accuracyM: Float? = null)

fun placeLabel(s: Session): String? = s.place.takeIf { it.isNotEmpty() && it != PENDING }

@Composable
fun PlacesScreen(
    places: List<NamedPlace>,
    access: LocationAccess,
    back: () -> Unit,
    allowPrecise: () -> Unit,
    allowBackground: () -> Unit,
    edit: (NamedPlace?) -> Unit,
) = Page("Places", back) {
    item {
        Text(
            "Each new recording gets the name of the place where it starts. Locations stay on this phone and are never sent anywhere.",
            style = Type.small,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    if (!access.precise || !access.background) {
        gap()
        item {
            CardList {
                if (!access.precise) {
                    ListRow("Allow precise location", "Needed to know which of your places you are at", first = true, subtitleLines = 2, onClick = allowPrecise) { Chevron() }
                } else {
                    ListRow(
                        "Allow all the time",
                        "Tags recordings while the app is closed. On the next screen, choose “Allow all the time”",
                        first = true,
                        subtitleLines = 3,
                        onClick = allowBackground,
                    ) { Chevron() }
                }
            }
        }
    }
    gap()
    item { Section("Your places") }
    gap()
    item {
        CardList {
            if (places.isEmpty()) ListRow("No places yet", "Recordings show “Elsewhere” until you add one", first = true, lead = { Glyph(R.drawable.ic_map_pin) })
            places.forEachIndexed { i, p ->
                ListRow(p.name, "${p.radiusM} m radius", first = i == 0, lead = { Glyph(R.drawable.ic_map_pin) }, onClick = { edit(p) }) { Chevron() }
            }
        }
    }
    gap()
    item { OutlineButton("Add this place", R.drawable.ic_plus) { if (access.precise) edit(null) else allowPrecise() } }
}

@Composable
fun PlaceEditScreen(place: NamedPlace?, here: Here, back: () -> Unit, locate: () -> Unit, save: (NamedPlace) -> Unit, delete: (String) -> Unit) {
    var name by remember { mutableStateOf(place?.name.orEmpty()) }
    var radius by remember { mutableIntStateOf(place?.radiusM ?: PLACE_RADIUS) }
    val lat = place?.lat ?: here.lat
    val lon = place?.lon ?: here.lon
    val ready = name.isNotBlank() && lat != null && lon != null
    val submit = {
        if (ready) {
            save(NamedPlace(place?.id ?: UUID.randomUUID().toString(), name.trim(), lat!!, lon!!, radius))
            back()
        }
    }
    Page(place?.name ?: "Add this place", back) {
        item { Field("Name", name, { name = it }, onDone = submit) }
        gap()
        item { Section("Radius") }
        gap()
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PLACE_RADII.forEach { r -> Chip("$r m", r == radius) { radius = r } }
            }
        }
        if (place == null) {
            gap()
            item {
                CardList {
                    ListRow(
                        when {
                            here.locating -> "Getting the precise location…"
                            here.lat != null -> "Location found"
                            else -> "No location"
                        },
                        when {
                            here.locating -> "Stay where the place is"
                            here.lat != null -> "Within ${here.accuracyM?.toInt() ?: "?"} m · tap to measure again"
                            else -> "Tap to try again"
                        },
                        first = true,
                        lead = { Glyph(R.drawable.ic_map_pin) },
                        onClick = if (here.locating) null else locate,
                    ) { if (!here.locating) Glyph(R.drawable.ic_refresh, 20.dp) }
                }
            }
        }
        gap()
        item { FilledButton(if (place == null) "Add place" else "Save", ready, submit) }
        if (place != null) {
            gap()
            item { OutlineButton("Delete this place") { delete(place.id); back() } }
        }
    }
}
