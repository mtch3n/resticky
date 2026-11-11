package dev.resticky.app

import android.content.Context
import dev.resticky.NamedPlace
import dev.resticky.hubJson
import kotlinx.coroutines.flow.MutableStateFlow

class PlaceStore(context: Context) {
    private val prefs = context.getSharedPreferences("places", Context.MODE_PRIVATE)

    val list = MutableStateFlow(runCatching { hubJson.decodeFromString<List<NamedPlace>>(prefs.getString("list", "[]")!!) }.getOrElse { emptyList() })

    private fun set(places: List<NamedPlace>) {
        prefs.edit().putString("list", hubJson.encodeToString(places)).apply()
        list.value = places
    }

    fun save(place: NamedPlace) {
        val old = list.value
        set(if (old.any { it.id == place.id }) old.map { if (it.id == place.id) place else it } else old + place)
    }

    fun delete(id: String) = set(list.value.filterNot { it.id == id })
}
