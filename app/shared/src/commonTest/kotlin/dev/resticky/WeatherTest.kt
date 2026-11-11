package dev.resticky

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeatherTest {
    private fun forecast(): String {
        val hours = (0 until 48).map { "\"2026-11-${11 + it / 24}T${"%02d".format(it % 24)}:00\"" }
        val temps = (0 until 48).map { "${10 + it % 24}.4" }
        val codes = (0 until 48).map { listOf(0, 3, 61, 95)[it % 4] }
        val days = (0 until 7).map { "\"2026-11-${11 + it}\"" }
        return """{"timezone":"America/New_York","current":{"time":"2026-11-11T08:30","temperature_2m":21.7,"apparent_temperature":20.5,"weather_code":2},
            "hourly":{"time":$hours,"temperature_2m":$temps,"weather_code":$codes},
            "daily":{"time":$days,"weather_code":[2,61,0,71,95,45,3],"temperature_2m_max":[24.6,20,21,22,23,24,25],
            "temperature_2m_min":[12.4,11,12,13,14,15,16],"precipitation_probability_max":[20,80,0,10,90,5,null]}}"""
    }

    @Test
    fun wmoCodesMapToSixConditions() {
        assertEquals(listOf("sun", "sun", "cloud", "cloud", "fog", "rain", "rain", "rain", "snow", "snow", "storm"),
            listOf(0, 1, 2, 3, 45, 51, 67, 81, 73, 86, 96).map(::condition))
    }

    @Test
    fun forecastIsCutToWhatTheDeviceShows() {
        val r = parseForecast(forecast(), WeatherSettings(MODE_PHONE, "c"), null, 1_700_000_000)
        assertEquals("New York", r.place)
        assertEquals(WeatherNow(22, 21, "cloud", 25, 12, 20), r.now)
        assertEquals(8, r.hour)
        assertEquals(13, r.temps!!.size)
        assertEquals((8 until 21).map { 10 + it % 24 }, r.temps)
        assertEquals(listOf("sun", "storm", "rain", "cloud", "sun"), r.conds)
        assertEquals(listOf("Thu", "Fri", "Sat", "Sun", "Mon"), r.days!!.map { it.wd })
        assertEquals(WeatherDay("Thu", "rain", 11, 20), r.days!![0])
        assertTrue(hubJson.encodeToString(r).encodeToByteArray().size < MAX_DATA_PAYLOAD)
    }

    @Test
    fun cityModeUsesTheCityName() {
        val r = parseForecast(forecast(), WeatherSettings(MODE_CITY, "f", "taipei"), "Lisbon", 1)
        assertEquals("Lisbon", r.place)
        assertEquals("f", r.units)
        assertEquals(MODE_CITY, r.mode)
    }

    @Test
    fun geocodeIsRounded() {
        val p = parseGeocode("""{"results":[{"id":1,"name":"Lisbon","latitude":25.05306,"longitude":121.52639,"country":"Taiwan"}]}""")
        assertEquals(Place("Lisbon", 25.1, 121.5), p)
        assertEquals(null, parseGeocode("""{"generationtime_ms":0.1}"""))
    }

    @Test
    fun emptyReportHasOnlySettings() {
        assertEquals("""{"place":"","mode":"phone","units":"c"}""", hubJson.encodeToString(WeatherReport(place = "", mode = MODE_PHONE, units = "c")))
    }
}
