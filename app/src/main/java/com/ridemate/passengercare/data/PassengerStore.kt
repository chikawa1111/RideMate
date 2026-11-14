package com.ridemate.passengercare.data

import android.content.Context
import com.ridemate.passengercare.vehicle.SeatPosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 동승자 프로필 저장소. 차량 안(기기 로컬)에만 저장하고 서버로 보내지 않는다.
 * 데이터가 작아서 SharedPreferences + JSON으로 충분하다.
 */
class PassengerStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("passengers", Context.MODE_PRIVATE)
    private val _passengers = MutableStateFlow(load())
    val passengers: StateFlow<List<Passenger>> = _passengers.asStateFlow()

    fun save(passenger: Passenger) {
        val list = _passengers.value.filterNot { it.id == passenger.id } + passenger
        _passengers.value = list
        persist(list)
    }

    fun delete(id: String) {
        val list = _passengers.value.filterNot { it.id == id }
        _passengers.value = list
        persist(list)
    }

    fun newId(): String = UUID.randomUUID().toString()

    private fun load(): List<Passenger> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun persist(list: List<Passenger>) {
        val array = JSONArray()
        list.forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun toJson(p: Passenger) = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("group", p.group.name)
        put("seat", p.defaultSeat.name)
        put("restraint", p.restraint.name)
        put("comfort", p.comfortRide)
        put("toilet", p.frequentToilet)
        p.preferredTemp?.let { put("temp", it) }
        put("operate", p.canOperate)
        p.restIntervalMin?.let { put("rest", it) }
        put("note", p.note)
    }

    private fun fromJson(o: JSONObject) = Passenger(
        id = o.getString("id"),
        name = o.getString("name"),
        group = PassengerGroup.valueOf(o.getString("group")),
        defaultSeat = SeatPosition.valueOf(o.getString("seat")),
        restraint = Restraint.valueOf(o.optString("restraint", Restraint.SEAT_BELT.name)),
        comfortRide = o.optBoolean("comfort"),
        frequentToilet = o.optBoolean("toilet"),
        preferredTemp = if (o.has("temp")) o.getInt("temp") else null,
        canOperate = o.optBoolean("operate"),
        restIntervalMin = if (o.has("rest")) o.getInt("rest") else null,
        note = o.optString("note")
    )

    private companion object {
        const val KEY = "list"
    }
}
