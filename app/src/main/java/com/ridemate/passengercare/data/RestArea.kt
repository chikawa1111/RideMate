package com.ridemate.passengercare.data

import android.content.Context
import org.json.JSONObject

/**
 * 휴게소·졸음쉼터 한 곳. assets/rest_areas.json (tools/build_rest_areas.py로 생성)에서 읽는다.
 * 편의시설 값이 null이면 "데이터 없음"이다. 없음(false)과 구분한다.
 */
data class RestArea(
    val id: String,
    val name: String,
    /** 휴게소: 진행 방향 도시명(예: "부산"), 졸음쉼터: "OO기점 + OO종점" */
    val direction: String,
    val type: Type,
    val route: String,
    val lat: Double,
    val lng: Double,
    val nursingRoom: Boolean?,
    val familyToilet: Boolean?,
    val accessibleToilet: Boolean?,
    val accessibleParking: Int?,
    val petPlayground: Boolean?,
    val toilet: Boolean?
) {
    enum class Type { SERVICE_AREA, SHELTER }

    val displayName: String
        get() = if (type == Type.SERVICE_AREA && direction.isNotEmpty()) "$name($direction 방향)" else name
}

object RestAreaRepository {

    @Volatile private var cache: List<RestArea>? = null

    fun load(context: Context): List<RestArea> = cache ?: synchronized(this) {
        cache ?: parse(context.assets.open("rest_areas.json").bufferedReader().use { it.readText() }).also { cache = it }
    }

    private fun parse(text: String): List<RestArea> {
        val items = JSONObject(text).getJSONArray("items")
        return List(items.length()) { i ->
            val o = items.getJSONObject(i)
            val f = o.getJSONObject("facilities")
            fun bool(k: String): Boolean? = if (!f.has(k) || f.isNull(k)) null else f.getBoolean(k)
            fun int(k: String): Int? = if (!f.has(k) || f.isNull(k)) null else f.getInt(k)
            RestArea(
                id = o.getString("id"),
                name = o.getString("name"),
                direction = o.optString("direction"),
                type = RestArea.Type.valueOf(o.getString("type")),
                route = o.optString("route"),
                lat = o.getDouble("lat"),
                lng = o.getDouble("lng"),
                nursingRoom = bool("nursingRoom"),
                familyToilet = bool("familyToilet"),
                accessibleToilet = bool("accessibleToilet"),
                accessibleParking = int("accessibleParking"),
                petPlayground = bool("petPlayground"),
                toilet = bool("toilet")
            )
        }
    }
}
