package com.ridemate.passengercare.care

import com.ridemate.passengercare.data.PassengerGroup
import com.ridemate.passengercare.data.SeatAssignment
import com.ridemate.passengercare.vehicle.DriveState
import com.ridemate.passengercare.vehicle.SeatPosition
import com.ridemate.passengercare.vehicle.SeatState

/**
 * A2 · 차내 잔류 / A3 · 운전자만 하차.
 *
 * 보호 대상: 영유아(G1) · 미취학(G2a) · 반려동물(G3). 혼자 내리거나 알리기 어려운 동승자.
 *
 * "내렸다"의 판단 (예외 E01: 모르면 남아 있다고 본다)
 *  - 좌석 센서가 비어 있음(false) → 내림
 *  - 운전자가 하차 확인에서 눌러 확인함 → 내림
 *    (빈 카시트 무게 때문에 센서가 계속 '착석'일 수 있어서 직접 확인을 인정한다)
 *  - 그 밖(착석 또는 모름) → 아직 차에 있음
 *
 * A3 (도착 전, 정차 중): 운전석 문이 열렸는데 보호 대상이 차에 있음. 예) 편의점 잠깐 정차.
 * A2 (도착 후): 운전자가 내렸고(운전석 비어 있음) 문이 모두 닫혔는데 보호 대상이 차에 있음.
 *
 * 시동이 꺼진 뒤의 감지는 앱이 할 수 없다(차량 기본 기능 영역).
 */
enum class ExitAlarmKind { A2_LEFT_IN_CAR, A3_DRIVER_ONLY }

data class ExitAlert(
    val kind: ExitAlarmKind,
    val names: List<String>,
    val positions: List<SeatPosition>,
    val windowsLocked: Boolean,
    /** 폭염·한파일 때만 채운다(기획서 3장 C 폭염·한파: 경보 문구에 외기 온도, 반복 간격 단축) */
    val outsideTempC: Int? = null,
    /** 남은 보호 대상 중 반려동물이 있음 → 차량 펫 모드 안내 */
    val hasPet: Boolean = false
) {
    private val who: String get() = names.joinToString(", ")

    val extreme: Boolean get() = outsideTempC != null

    private val tempNote: String get() = outsideTempC?.let { " · 바깥 ${it}℃" } ?: ""

    val title: String
        get() = when (kind) {
            ExitAlarmKind.A2_LEFT_IN_CAR -> "$who 아직 차에 있어요"
            ExitAlarmKind.A3_DRIVER_ONLY -> "$who 차에 있어요"
        }

    val detail: String
        get() = when (kind) {
            ExitAlarmKind.A2_LEFT_IN_CAR -> "운전자가 내렸고 문이 모두 닫혔어요 · 실내등을 켰어요" + tempNote + petNote
            ExitAlarmKind.A3_DRIVER_ONLY ->
                "운전석 문이 열렸어요" + (if (windowsLocked) " · 창문은 잠겨 있어요" else "") + tempNote
        }

    /** 시동이 꺼지면 앱이 공조를 유지할 수 없다 → 차량 기본 기능(펫 모드)을 안내한다 */
    private val petNote: String
        get() = if (hasPet && extreme) "\n잠깐 두고 가야 한다면 차량 펫 모드를 켜 주세요. 시동이 꺼지면 이 앱은 공조를 유지할 수 없어요." else ""

    val spoken: String
        get() = when (kind) {
            ExitAlarmKind.A2_LEFT_IN_CAR -> "$who${subjectJosa(who)} 아직 차에 있어요." +
                (outsideTempC?.let { " 바깥 기온이 ${it}도예요." } ?: "") + " 확인해 주세요."
            ExitAlarmKind.A3_DRIVER_ONLY ->
                "$who${subjectJosa(who)} 차에 있어요." + (if (windowsLocked) " 창문은 잠겨 있어요." else "") +
                    (outsideTempC?.let { " 바깥 기온이 ${it}도예요." } ?: "")
        }
}

/** 받침이 있으면 "이", 없으면 "가" (예: 민지가, 지훈이) */
internal fun subjectJosa(word: String): String {
    val c = word.lastOrNull() ?: return "가"
    if (c !in '가'..'힣') return "가"
    return if ((c - '가') % 28 != 0) "이" else "가"
}

object ExitAlarm {

    private val protectedGroups = setOf(PassengerGroup.INFANT, PassengerGroup.PRESCHOOL, PassengerGroup.PET)

    const val REPEAT_MS = 10_000L
    /** 폭염·한파: 차 안 온도가 빨리 변하므로 더 자주 알린다 */
    const val REPEAT_EXTREME_MS = 5_000L

    fun repeatMs(alert: ExitAlert): Long = if (alert.extreme) REPEAT_EXTREME_MS else REPEAT_MS

    fun isProtected(a: SeatAssignment): Boolean = when (a) {
        is SeatAssignment.Known -> a.passenger.group in protectedGroups
        is SeatAssignment.Guest -> a.group in protectedGroups
        SeatAssignment.Luggage -> false
    }

    /** 내림: 센서가 비어 있다고 하거나, 운전자가 확인함 */
    fun isOut(position: SeatPosition, seats: Map<SeatPosition, SeatState>, confirmedOut: Set<SeatPosition>): Boolean =
        position in confirmedOut || seats[position]?.occupied == false

    fun evaluate(
        assignments: Map<SeatPosition, SeatAssignment>,
        seats: Map<SeatPosition, SeatState>,
        drive: DriveState,
        tripActive: Boolean,
        arrived: Boolean,
        confirmedOut: Set<SeatPosition>,
        lockedWindows: Set<SeatPosition>
    ): ExitAlert? {
        if (!tripActive || drive.moving) return null
        val remaining = assignments
            .filter { (pos, a) -> isProtected(a) && !isOut(pos, seats, confirmedOut) }
            .toSortedMap(compareBy { it.ordinal })
        if (remaining.isEmpty()) return null

        val driver = seats[SeatPosition.FRONT_LEFT]
        val kind = if (arrived) {
            val driverGone = driver?.occupied == false
            val allClosed = seats.values.none { it.doorOpen == true }
            if (driverGone && allClosed) ExitAlarmKind.A2_LEFT_IN_CAR else return null
        } else {
            if (driver?.doorOpen == true) ExitAlarmKind.A3_DRIVER_ONLY else return null
        }
        return ExitAlert(
            kind = kind,
            names = remaining.values.map { it.displayName() },
            positions = remaining.keys.toList(),
            windowsLocked = remaining.keys.all { it in lockedWindows },
            hasPet = remaining.values.any {
                (it as? SeatAssignment.Known)?.passenger?.group == PassengerGroup.PET ||
                    (it as? SeatAssignment.Guest)?.group == PassengerGroup.PET
            }
        )
    }

    private fun SeatAssignment.displayName(): String = when (this) {
        is SeatAssignment.Known -> passenger.name
        is SeatAssignment.Guest -> if (group.isChild) "게스트 아이" else "게스트"
        SeatAssignment.Luggage -> "짐"
    }
}
