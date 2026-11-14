package com.ridemate.passengercare.care

import com.ridemate.passengercare.data.PassengerGroup
import com.ridemate.passengercare.data.RestArea
import com.ridemate.passengercare.data.SeatAssignment
import com.ridemate.passengercare.env.EnvState
import com.ridemate.passengercare.vehicle.SeatPosition
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * M7 · 쉬어갈 곳 찾기 (사용자가 불렀을 때만).
 *
 * 차별점은 "언제"가 아니라 "어디서"다. 이번에 탄 동승자에게 필요한 장소 조건으로 거른다.
 *  ① 필수 조건: 하나라도 없으면 제외 (데이터 없음도 제외 — 확인 안 된 곳을 권하지 않는다)
 *  ② 점수 = 기본점 + 선택 조건 가점 − 거리(km)
 *  ③ 상위 2곳
 *  ④ 0곳이면 편의 필수 조건(수유실)부터 완화. 거동 불편(G6) 조건은 완화하지 않고 범위 밖 가장 가까운 곳을 알려준다.
 *
 * 경로 좌표를 아직 받지 못해서 APIS.md §7 B안(근사)으로 판정한다:
 *  - 노선·방향을 알 때: 같은 노선 + 같은 방향(휴게소 이름의 괄호, 졸음쉼터의 "OO종점") + 진행 방향 앞쪽
 *  - 모를 때: 현재 위치 기준 [MAX_KM] 안, 진행 방향 ±60° 안. 같은 휴게소가 양방향이면 진행 방향 오른쪽 것을 고른다.
 */
/**
 * 현재 위치. route·toward를 알면(내비 경로 또는 시연 위치) 같은 노선·같은 방향 휴게소만 고른다.
 * 모르면 진행 방위만으로 근사한다(APIS.md §7 B안).
 */
data class Position(
    val lat: Double,
    val lng: Double,
    val headingDeg: Double,
    val label: String,
    val route: String? = null,
    val toward: String? = null
)

data class Need(val label: String, val required: Boolean, val weight: Int, val group: NeedGroup, val test: (RestArea) -> Boolean?) {
    enum class NeedGroup { SAFETY, PHYSIOLOGICAL, CONVENIENCE, OPTIONAL }
}

data class RestCandidate(
    val area: RestArea,
    val distanceKm: Double,
    val matched: List<String>,
    val score: Double
) {
    /** 평균 90km/h로 가정한 도착 예정(분). 직선 거리 기준이라 실제보다 짧을 수 있다 */
    val etaMin: Int get() = (distanceKm / 90.0 * 60).roundToInt().coerceAtLeast(1)
    val distanceLabel: String get() = "직선 ${distanceKm.roundToInt()}km · 약 ${etaMin}분"
}

data class RestResult(
    val candidates: List<RestCandidate>,
    val needs: List<Need>,
    /** 완화한 조건(있으면 화면에 표시) */
    val relaxed: List<String>,
    /** 필수 조건을 끝내 만족하지 못했을 때 범위 밖 가장 가까운 충족 지점 */
    val nearestBeyond: RestCandidate?,
    /** 날씨·도로 때문에 바뀐 점 (화면 표시) */
    val notes: List<String> = emptyList(),
    /** 멀미 모드에서 부른 "가장 가까운 정차 지점" */
    val immediate: Boolean = false
) {
    val spoken: String
        get() = if (candidates.isEmpty()) {
            nearestBeyond?.let { "조건에 맞는 곳이 가까이에 없어요. 가장 가까운 곳은 ${it.area.name}, ${it.distanceKm.roundToInt()}킬로미터예요." }
                ?: "조건에 맞는 곳을 찾지 못했어요."
        } else {
            candidates.mapIndexed { i, c ->
                "${i + 1}번 ${c.area.name}, ${c.distanceKm.roundToInt()}킬로미터" +
                    if (c.matched.isNotEmpty()) ", ${c.matched.joinToString("이랑 ")} 있어요." else "."
            }.joinToString(" ")
        }
}

object RestFinder {

    const val MAX_KM = 60.0
    private const val MIN_KM = 1.0
    private const val AHEAD_DEG = 60.0

    fun needsFor(assignments: Map<SeatPosition, SeatAssignment>): List<Need> {
        val groups = assignments.values.mapNotNull {
            when (it) {
                is SeatAssignment.Known -> it.passenger.group
                is SeatAssignment.Guest -> it.group
                SeatAssignment.Luggage -> null
            }
        }.toSet()
        val frequentToilet = assignments.values.any { it is SeatAssignment.Known && it.passenger.frequentToilet }
        val needs = mutableListOf<Need>()
        if (PassengerGroup.MOBILITY in groups) {
            needs += Need("장애인 화장실", true, 0, Need.NeedGroup.SAFETY) { it.accessibleToilet }
            needs += Need("장애인 주차", true, 0, Need.NeedGroup.SAFETY) { a -> a.accessibleParking?.let { it > 0 } }
        }
        if (frequentToilet) needs += Need("화장실", true, 0, Need.NeedGroup.PHYSIOLOGICAL) { it.toilet }
        if (PassengerGroup.INFANT in groups || PassengerGroup.PRESCHOOL in groups) {
            needs += Need("수유실", true, 0, Need.NeedGroup.CONVENIENCE) { it.nursingRoom }
        }
        if (PassengerGroup.PET in groups) {
            needs += Need("반려견 놀이터", false, 30, Need.NeedGroup.OPTIONAL) { it.petPlayground }
        }
        if (groups.any { it == PassengerGroup.INFANT || it == PassengerGroup.PRESCHOOL || it == PassengerGroup.SENIOR || it == PassengerGroup.PREGNANT }) {
            needs += Need("가족화장실", false, 15, Need.NeedGroup.OPTIONAL) { it.familyToilet }
        }
        return needs
    }

    fun find(
        areas: List<RestArea>,
        position: Position,
        assignments: Map<SeatPosition, SeatAssignment>,
        env: EnvState = EnvState.EMPTY,
        /** 권장 휴식까지 남은 분. 도착 예정이 이 시점과 가까울수록 점수가 높다(기획서 M7 ②) */
        restRemainingMin: Int? = null
    ): RestResult {
        val needs = needsFor(assignments)
        val notes = mutableListOf<String>()

        // M12: 임시 폐쇄 공지가 있는 곳은 뺀다 (도로공사 실시간 돌발 정보)
        val closedKeys = env.closedPlaces.map { it.replace(" ", "") }.toSet()
        var pool = areas
        if (closedKeys.isNotEmpty()) {
            val closedAhead = aheadOnRightSide(areas, position).map { it.first }
                .filter { it.name.replace(" ", "") in closedKeys }
            pool = pool.filterNot { it.name.replace(" ", "") in closedKeys }
            if (closedAhead.isNotEmpty()) notes += "임시 폐쇄로 제외: " + closedAhead.joinToString(" · ") { it.name }
        }
        // M12: 비·눈·결빙 + 아이·고령·임산부·거동 불편 동승 → 건물이 없는 졸음쉼터는 빼고 휴게소만
        val groups = assignments.values.mapNotNull {
            when (it) {
                is SeatAssignment.Known -> it.passenger.group
                is SeatAssignment.Guest -> it.group
                SeatAssignment.Luggage -> null
            }
        }.toSet()
        val vulnerable = groups.any {
            it == PassengerGroup.INFANT || it == PassengerGroup.PRESCHOOL || it == PassengerGroup.SENIOR ||
                it == PassengerGroup.PREGNANT || it == PassengerGroup.MOBILITY
        }
        if (env.wetOrIcy && vulnerable) {
            pool = pool.filter { it.type == RestArea.Type.SERVICE_AREA }
            notes += "${if (env.iceRisk) "결빙 위험" else env.weather?.label ?: "궂은 날씨"}: 졸음쉼터는 빼고 휴게소만"
        }
        // 거동 불편 + 궂은 날씨: 장애인 주차면이 많을수록 건물 가까이 댈 가능성이 높다(가점, 최대 20)
        val parkingBonus = env.wetOrIcy && PassengerGroup.MOBILITY in groups
        val ahead = aheadOnRightSide(pool, position)

        fun rank(required: List<Need>, pool: List<Pair<RestArea, Double>>): List<RestCandidate> =
            pool.filter { (a, _) -> required.all { it.test(a) == true } }
                .filter { (a, _) -> a.type == RestArea.Type.SERVICE_AREA || required.all { it.label == "화장실" } }
                .map { (a, d) ->
                    val optional = needs.filter { !it.required && it.test(a) == true }
                    val matched = (required + optional).map { it.label }
                    val shelterPenalty = if (a.type == RestArea.Type.SHELTER) 20 else 0
                    val bonus = if (parkingBonus) minOf(a.accessibleParking ?: 0, 20) else 0
                    val timing = restRemainingMin?.let { abs((d / 90.0 * 60) - it.coerceAtLeast(0)) * 0.3 } ?: 0.0
                    RestCandidate(a, d, matched, 100.0 + optional.sumOf { it.weight } + bonus - d - shelterPenalty - timing)
                }
                .sortedByDescending { it.score }

        var required = needs.filter { it.required }
        val relaxed = mutableListOf<String>()
        var result = rank(required, ahead)
        // 완화 순서: 편의 필수 → 생리적 필요. 안전(G6)은 완화하지 않는다.
        for (g in listOf(Need.NeedGroup.CONVENIENCE, Need.NeedGroup.PHYSIOLOGICAL)) {
            if (result.isNotEmpty()) break
            val drop = required.filter { it.group == g }
            if (drop.isEmpty()) continue
            required = required - drop.toSet()
            relaxed += drop.map { it.label }
            result = rank(required, ahead)
        }
        val nearestBeyond = if (result.isEmpty()) {
            val all = pool.map { it to distanceKm(position, it) }.filter { (a, d) ->
                d >= MIN_KM && required.all { it.test(a) == true }
            }
            all.minByOrNull { it.second }?.let { (a, d) -> RestCandidate(a, d, required.map { it.label }, 0.0) }
        } else null
        return RestResult(result.take(2), needs, relaxed, nearestBeyond, notes)
    }

    /**
     * 멀미 모드 "즉시 모드": 조건 점수 없이 화장실 있는 가장 가까운 정차 지점 2곳.
     * 비·눈·결빙에 취약 동승자가 있으면 휴게소만.
     */
    fun findNearestStop(
        areas: List<RestArea>,
        position: Position,
        assignments: Map<SeatPosition, SeatAssignment>,
        env: EnvState
    ): RestResult {
        val closedKeys = env.closedPlaces.map { it.replace(" ", "") }.toSet()
        val vulnerable = assignments.values.any {
            val g = (it as? SeatAssignment.Known)?.passenger?.group ?: (it as? SeatAssignment.Guest)?.group
            g == PassengerGroup.INFANT || g == PassengerGroup.PRESCHOOL || g == PassengerGroup.SENIOR ||
                g == PassengerGroup.PREGNANT || g == PassengerGroup.MOBILITY
        }
        val serviceOnly = env.wetOrIcy && vulnerable
        val list = aheadOnRightSide(areas, position)
            .filter { (a, _) -> a.toilet == true && a.name.replace(" ", "") !in closedKeys }
            .filter { (a, _) -> !serviceOnly || a.type == RestArea.Type.SERVICE_AREA }
            .sortedBy { it.second }
            .take(2)
            .map { (a, d) -> RestCandidate(a, d, listOf("화장실"), -d) }
        val notes = if (serviceOnly) listOf("궂은 날씨: 졸음쉼터는 빼고 휴게소만") else emptyList()
        return RestResult(list, emptyList(), emptyList(), null, notes, immediate = true)
    }

    /** 졸음쉼터 방향 "서울기점 + 부산종점" → "부산" */
    private fun towardOf(a: RestArea): String =
        if (a.type == RestArea.Type.SHELTER) a.direction.substringAfter("+").trim().removeSuffix("종점").trim()
        else a.direction

    private fun aheadOnRightSide(areas: List<RestArea>, p: Position): List<Pair<RestArea, Double>> =
        if (p.route != null && p.toward != null) onSameRoute(areas, p) else approximate(areas, p)

    /** 같은 노선·같은 방향. 방향 표기가 없는 휴게소(양방향 공용)도 포함한다. */
    private fun onSameRoute(areas: List<RestArea>, p: Position): List<Pair<RestArea, Double>> =
        areas.mapNotNull { a ->
            if (a.route != p.route) return@mapNotNull null
            val toward = towardOf(a)
            if (toward.isNotEmpty() && toward != p.toward) return@mapNotNull null
            val d = distanceKm(p, a)
            if (d < MIN_KM || d > MAX_KM) return@mapNotNull null
            if (kotlin.math.abs(angleDiff(bearingDeg(p, a), p.headingDeg)) > 90) null else a to d
        }

    /** 노선을 모를 때의 근사. 같은 이름 휴게소가 양방향이면 진행 방향 오른쪽(우측통행) 것만 남긴다. */
    private fun approximate(areas: List<RestArea>, p: Position): List<Pair<RestArea, Double>> {
        val inRange = areas.mapNotNull { a ->
            val d = distanceKm(p, a)
            if (d < MIN_KM || d > MAX_KM) return@mapNotNull null
            val diff = angleDiff(bearingDeg(p, a), p.headingDeg)
            if (kotlin.math.abs(diff) > AHEAD_DEG) null else Triple(a, d, diff)
        }
        return inRange.groupBy { it.first.type to it.first.name }.flatMap { (_, same) ->
            if (same.size == 1) same else {
                // 오른쪽 = 진행 방향 기준 각도 차이가 더 큰(시계 방향) 쪽
                listOf(same.maxBy { it.third })
            }
        }.map { it.first to it.second }
    }

    fun distanceKm(p: Position, a: RestArea): Double {
        val r = 6371.0
        val dLat = Math.toRadians(a.lat - p.lat)
        val dLng = Math.toRadians(a.lng - p.lng)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(p.lat)) * cos(Math.toRadians(a.lat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    private fun bearingDeg(p: Position, a: RestArea): Double {
        val φ1 = Math.toRadians(p.lat)
        val φ2 = Math.toRadians(a.lat)
        val dλ = Math.toRadians(a.lng - p.lng)
        val y = sin(dλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(dλ)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** -180..180, 양수 = 진행 방향 기준 오른쪽 */
    private fun angleDiff(target: Double, heading: Double): Double {
        var d = target - heading
        while (d > 180) d -= 360
        while (d < -180) d += 360
        return d
    }

    /** 시연용 위치 (에뮬레이터는 실제 이동이 없다). 화면에 "시연용 위치"로 표시한다. */
    val demoPositions = listOf(
        Position(37.2560, 127.1900, 95.0, "영동선 용인 부근 · 강릉 방향", "영동선", "강릉"),
        Position(37.2600, 127.1090, 175.0, "경부선 신갈 부근 · 부산 방향", "경부선", "부산"),
        Position(36.9900, 126.8600, 200.0, "서해안선 서평택 부근 · 목포 방향", "서해안선", "목포")
    )
}
