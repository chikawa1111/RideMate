package com.ridemate.passengercare.data

import com.ridemate.passengercare.vehicle.SeatPosition

/** 동승자 그룹 (기획서 2장). */
enum class PassengerGroup(val label: String, val isChild: Boolean = false) {
    INFANT("영유아", isChild = true),
    PRESCHOOL("미취학", isChild = true),
    SCHOOL("학령", isChild = true),
    PET("반려동물"),
    SENIOR("고령"),
    PREGNANT("임산부"),
    MOBILITY("거동 불편"),
    ADULT("일반 성인")
}

/** 아이가 앉는 장치. 하네스 카시트는 차량 벨트 센서로 풀림을 감지할 수 없다. */
enum class Restraint(val label: String, val beltSensorWorks: Boolean) {
    HARNESS("하네스 카시트", beltSensorWorks = false),
    BOOSTER("부스터", beltSensorWorks = true),
    SEAT_BELT("차량 벨트", beltSensorWorks = true)
}

data class Passenger(
    val id: String,
    val name: String,
    val group: PassengerGroup,
    val defaultSeat: SeatPosition,
    val restraint: Restraint = Restraint.SEAT_BELT,
    val comfortRide: Boolean = false,      // #편안한주행
    val frequentToilet: Boolean = false,   // #잦은화장실
    val preferredTemp: Int? = null,        // C등급: 보호자가 입력한 경우에만
    /** 학령(G2b) 아이가 창문·문을 스스로 조작하는지. 체크하면 그 좌석 창문을 잠근다(기획서 2장) */
    val canOperate: Boolean = false,
    /** C등급: 보호자가 줄여서 입력한 권장 휴식 간격(분). null = 기본 2시간 */
    val restIntervalMin: Int? = null,
    val note: String = ""
)

/** 탑승 확인 화면에서 좌석에 배정된 것. */
sealed interface SeatAssignment {
    data class Known(val passenger: Passenger) : SeatAssignment
    data class Guest(val group: PassengerGroup) : SeatAssignment
    data object Luggage : SeatAssignment
}
