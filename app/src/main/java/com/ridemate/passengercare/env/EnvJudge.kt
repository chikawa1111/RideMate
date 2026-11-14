package com.ridemate.passengercare.env

import com.ridemate.passengercare.care.Position
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * M12 · 날씨·노면 판정. 결과는 화면(주행·하차·쉬어갈 곳)에만 반영한다. 소리는 내지 않는다(P1, E16).
 * 임계값은 앱 기준(APIS.md §4)이며 기획서에도 "앱 기준"으로 표기한다.
 */
enum class Weather(val label: String, val caution: Boolean) {
    CLEAR("맑음·흐림", false),
    RAIN("비", true),
    HEAVY_RAIN("장마·폭우", true),
    SNOW("눈", true),
    HEAT("폭염", true),
    COLD("한파", true)
}

data class AheadEvent(val event: RoadEvent, val distanceKm: Double) {
    val label: String get() = "앞 ${distanceKm.roundToInt()}km ${event.type}"
}

data class EnvState(
    val weather: Weather?,
    val tempC: Double?,
    /** 결빙 위험(추정): 기온 2℃ 이하 + 최근 강수 또는 습도 90% 이상 */
    val iceRisk: Boolean,
    /** 우산: 비·눈이 오고 있거나 6시간 강수확률 60% 이상 */
    val umbrella: Boolean,
    /** 진행 방향 앞쪽 돌발·작업(같은 노선·같은 방향, 30km 이내), 가까운 순 */
    val ahead: List<AheadEvent>,
    /** 임시 폐쇄 공지가 있는 휴게소·쉼터 이름 */
    val closedPlaces: Set<String>,
    val updated: String,
    val simulated: Boolean = false,
    /** 마지막 조회가 실패해 이전 값을 보여주는 중 */
    val stale: Boolean = false
) {
    /** 비·눈·결빙 — 하차 동선과 쉬어갈 곳 조건을 바꾸는 날씨 */
    val wetOrIcy: Boolean
        get() = weather == Weather.RAIN || weather == Weather.HEAVY_RAIN || weather == Weather.SNOW || iceRisk

    val summary: String
        get() = buildList {
            weather?.let { w -> add(w.label + (tempC?.let { " ${it.roundToInt()}℃" } ?: "")) }
            if (iceRisk) add("결빙 위험(추정)")
            ahead.firstOrNull()?.let { add(it.label) }
        }.joinToString(" · ").ifEmpty { "날씨·도로 정보 없음" }

    val hasCaution: Boolean get() = weather?.caution == true || iceRisk || ahead.isNotEmpty()

    companion object {
        val EMPTY = EnvState(null, null, false, false, emptyList(), emptySet(), "")
    }
}

object EnvJudge {

    private const val AHEAD_KM = 30.0

    fun weatherOf(w: WeatherNow): Weather {
        val code = w.weatherCode
        val snowing = w.snowCm > 0 || code in 71..77 || code == 85 || code == 86
        val raining = w.precipMm > 0 || code in 51..67 || code in 80..82 || code in 95..99
        return when {
            snowing -> Weather.SNOW
            raining && w.precipMm >= 15 -> Weather.HEAVY_RAIN
            raining -> Weather.RAIN
            w.tempC >= 33 -> Weather.HEAT
            w.tempC <= -10 -> Weather.COLD
            else -> Weather.CLEAR
        }
    }

    fun iceRisk(w: WeatherNow): Boolean =
        minOf(w.tempC, w.minTemp6h) <= 2.0 && (w.recentPrecipMm > 0 || w.humidity >= 90 || w.snowCm > 0)

    fun judge(w: WeatherNow?, events: List<RoadEvent>?, position: Position, now: String): EnvState {
        val weather = w?.let { weatherOf(it) }
        val ahead = events.orEmpty().mapNotNull { e ->
            if (e.lat == null || e.lng == null) return@mapNotNull null
            if (position.route != null && e.road != position.route) return@mapNotNull null
            if (position.toward != null && e.toward.isNotEmpty() && e.toward != position.toward) return@mapNotNull null
            val d = distanceKm(position.lat, position.lng, e.lat, e.lng)
            if (d > AHEAD_KM) return@mapNotNull null
            if (abs(angleDiff(bearing(position.lat, position.lng, e.lat, e.lng), position.headingDeg)) > 90) null
            else AheadEvent(e, d)
        }.sortedBy { it.distanceKm }
        val closed = events.orEmpty().filter { "폐쇄" in it.text }.flatMap { e ->
            Regex("([가-힣]+)(졸음쉼터|휴게소)").findAll(e.text).map { it.groupValues[1] + it.groupValues[2] }.toList()
        }.toSet()
        return EnvState(
            weather = weather,
            tempC = w?.tempC,
            iceRisk = w?.let { iceRisk(it) } ?: false,
            umbrella = w != null && (weather == Weather.RAIN || weather == Weather.HEAVY_RAIN || weather == Weather.SNOW || w.pop6h >= 60),
            ahead = ahead,
            closedPlaces = closed,
            updated = now
        )
    }

    /** 시연용 날씨. 화면에 "(시뮬레이션)"으로 표시한다. */
    fun simulate(base: EnvState, kind: Weather, ice: Boolean): EnvState = base.copy(
        weather = kind,
        tempC = when (kind) {
            Weather.HEAT -> 35.0
            Weather.COLD -> -12.0
            Weather.SNOW -> -1.0
            else -> if (ice) 0.0 else base.tempC ?: 10.0
        },
        iceRisk = ice,
        umbrella = kind == Weather.RAIN || kind == Weather.HEAVY_RAIN || kind == Weather.SNOW,
        simulated = true
    )

    /** 시연용 도로 상황: 진행 방향 앞 7km 작업 구간. 화면에 "(시뮬레이션)"으로 표시한다. */
    fun simulateRoad(base: EnvState, position: Position): EnvState = base.copy(
        ahead = listOf(
            AheadEvent(
                RoadEvent("작업", position.route ?: "", position.toward ?: "", null, null, "(2차로) 노면보수 작업", ""),
                7.0
            )
        ) + base.ahead,
        simulated = true
    )

    private fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    private fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val φ1 = Math.toRadians(lat1)
        val φ2 = Math.toRadians(lat2)
        val dλ = Math.toRadians(lng2 - lng1)
        val y = sin(dλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(dλ)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun angleDiff(target: Double, heading: Double): Double {
        var d = target - heading
        while (d > 180) d -= 360
        while (d < -180) d += 360
        return d
    }
}
