package com.ridemate.passengercare.care

import com.ridemate.passengercare.data.PassengerGroup
import com.ridemate.passengercare.data.SeatAssignment
import com.ridemate.passengercare.vehicle.DriveState
import com.ridemate.passengercare.vehicle.SeatPosition
import com.ridemate.passengercare.vehicle.SeatState

/**
 * A1 · 주행 중 벨트 풀림 경보 (앱이 먼저 소리 내는 3가지 안전 경보 중 하나).
 *
 * 대상: 차량 벨트 센서로 확인할 수 있는 동승자만.
 *  - 하네스 카시트(영유아 등)는 센서로 알 수 없어서 제외 → 출발 전 확인에서 직접 확인.
 *  - 반려동물, 짐 제외.
 *  - 센서 값이 null(모름)이면 경보하지 않는다. 틀린 경보는 운전자를 더 흔든다.
 */
data class BeltAlert(val position: SeatPosition, val name: String) {
    val spoken: String get() = "$name, 안전벨트를 매 주세요."
}

object BeltAlarm {

    fun targets(
        assignments: Map<SeatPosition, SeatAssignment>,
        seats: Map<SeatPosition, SeatState>,
        drive: DriveState
    ): List<BeltAlert> {
        if (!drive.moving) return emptyList()
        return assignments.mapNotNull { (pos, a) ->
            if (!beltSensorApplies(a)) return@mapNotNull null
            val state = seats[pos] ?: return@mapNotNull null
            if (state.occupied == false) return@mapNotNull null
            if (state.belted == false) BeltAlert(pos, a.name()) else null
        }.sortedBy { it.position.ordinal }
    }

    fun beltSensorApplies(a: SeatAssignment): Boolean = when (a) {
        SeatAssignment.Luggage -> false
        is SeatAssignment.Guest -> a.group != PassengerGroup.PET && a.group != PassengerGroup.INFANT
        is SeatAssignment.Known -> a.passenger.group != PassengerGroup.PET &&
            a.passenger.restraint.beltSensorWorks
    }

    private fun SeatAssignment.name(): String = when (this) {
        is SeatAssignment.Known -> passenger.name
        is SeatAssignment.Guest -> if (group.isChild) "게스트 아이" else "게스트 어른"
        SeatAssignment.Luggage -> "짐"
    }

    /** 소리 경보를 반복하는 시간. 이후에는 화면 표시만 남긴다(차량 기본 벨트 경고음과 같은 방식). */
    /** 해제가 3초 이어져야 경보 (기획서 1장 앱 발화 규칙) */
    const val DEBOUNCE_MS = 3_000L
    const val SOUND_REPEAT_MS = 15_000L
    const val SOUND_MAX_MS = 90_000L
}
