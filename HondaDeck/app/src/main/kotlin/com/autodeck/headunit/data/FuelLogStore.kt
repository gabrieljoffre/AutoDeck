package com.autodeck.headunit.data

import android.content.Context
import androidx.core.content.edit
import com.autodeck.protocol.obd2.FuelEntry
import org.json.JSONArray
import org.json.JSONObject

/** Diário de abastecimentos salvo no tablet (SharedPreferences, JSON). Guarda os últimos 300. */
class FuelLogStore(context: Context) {

    private val prefs = context.getSharedPreferences("hondadeck_fuel_log", Context.MODE_PRIVATE)

    fun load(): List<FuelEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FuelEntry(
                    timestampMs = o.getLong("ts"),
                    odometerKm = o.optDoubleOrNull("odo"),
                    liters = o.getDouble("l"),
                    pricePerLiter = o.optDoubleOrNull("p"),
                    fullTank = o.optBoolean("full", false),
                    appTripKm = o.optDoubleOrNull("tripKm"),
                    appTripFuelL = o.optDoubleOrNull("tripL")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(entries: List<FuelEntry>) {
        val arr = JSONArray()
        entries.sortedBy { it.timestampMs }.takeLast(MAX_ENTRIES).forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("ts", e.timestampMs)
                    e.odometerKm?.let { put("odo", it) }
                    put("l", e.liters)
                    e.pricePerLiter?.let { put("p", it) }
                    put("full", e.fullTank)
                    e.appTripKm?.let { put("tripKm", it) }
                    e.appTripFuelL?.let { put("tripL", it) }
                }
            )
        }
        prefs.edit { putString(KEY, arr.toString()) }
    }

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name).takeIf { !it.isNaN() } else null

    private companion object {
        const val KEY = "entries_json"
        const val MAX_ENTRIES = 300
    }
}
