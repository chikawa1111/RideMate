package com.ridemate.passengercare.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** 내비에서 고른 장소(즐겨찾기·최근 목적지). 좌표는 내비가 준 값을 그대로 쓴다. */
data class SavedPlace(
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String,
    val poiId: String,
    val poiSubId: String
)

/**
 * 긴급 병원(분만 병원 등) 저장소. 기기 안에만 저장한다.
 * 장소 검색 API가 없어서, 운전자가 길 안내 앱의 즐겨찾기·최근 목적지에서 고른다(정차 중).
 */
class PlaceStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("places", Context.MODE_PRIVATE)
    private val _hospital = MutableStateFlow(load())
    val hospital: StateFlow<SavedPlace?> = _hospital.asStateFlow()

    fun setHospital(place: SavedPlace?) {
        _hospital.value = place
        prefs.edit().apply {
            if (place == null) remove(KEY) else putString(KEY, toJson(place).toString())
        }.apply()
    }

    private fun load(): SavedPlace? = runCatching {
        prefs.getString(KEY, null)?.let { JSONObject(it) }?.let {
            SavedPlace(
                it.getString("name"), it.getDouble("lat"), it.getDouble("lng"),
                it.optString("address"), it.optString("poiId"), it.optString("poiSubId")
            )
        }
    }.getOrNull()

    private fun toJson(p: SavedPlace) = JSONObject().apply {
        put("name", p.name); put("lat", p.lat); put("lng", p.lng)
        put("address", p.address); put("poiId", p.poiId); put("poiSubId", p.poiSubId)
    }

    private companion object {
        const val KEY = "hospital"
    }
}
