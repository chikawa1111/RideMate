package com.ridemate.passengercare.care

import com.ridemate.passengercare.data.PassengerGroup
import com.ridemate.passengercare.data.Restraint
import com.ridemate.passengercare.data.SeatAssignment
import com.ridemate.passengercare.vehicle.SeatPosition

/*
 * 판단 로직(care/)은 차량 SDK와 분리된 순수 Kotlin이다.
 * "누가 어디에 탔는가"만 받아서 "무엇을 해야 하는가"를 돌려준다. 단위 테스트 대상.
 */

/** 적용할 실내 세팅 하나. */
sealed interface CabinAction {
    val position: SeatPosition
    val label: String

    data class LockWindow(override val position: SeatPosition, val reason: String) : CabinAction {
        override val label get() = "${position.label} 창문 잠금"
    }

    /** priority: 같은 공조 존을 쓰면 값이 작은(더 취약한) 동승자 기준 (예외 E07) */
    data class SetTemperature(override val position: SeatPosition, val celsius: Int, val who: String, val priority: Int = 9) : CabinAction {
        override val label get() = "${position.label} 공조 ${celsius}℃ ($who 선호)"
    }

    data class SeatHeatOff(override val position: SeatPosition, val reason: String) : CabinAction {
        override val label get() = "${position.label} 열선 끄기"
    }
}

enum class ActionStatus(val label: String) {
    PENDING("적용 중"),
    DONE("적용됨"),
    MANUAL("직접 해 주세요"),
    SHARED("같은 공조 존 · 다른 동승자 기준")
}

/** 출발 전 확인 목록의 한 줄. */
data class CheckItem(
    val id: String,
    val title: String,
    val detail: String,
    val kind: Kind,
    val position: SeatPosition? = null
) {
    enum class Kind {
        /** 센서로 자동 확인 (벨트) */
        SENSOR_BELT,
        /** 센서가 없어서 운전자가 눌러서 확인 */
        MANUAL,
        /** 문 열림 센서로 자동 확인 (모든 문 닫힘) */
        SENSOR_DOORS
    }
}

object CabinPlanner {

    /** 근거 등급 B: 차량 취급설명서의 어린이 탑승 시 뒷창문 잠금 권고 */
    private val lockGroups = setOf(PassengerGroup.INFANT, PassengerGroup.PRESCHOOL, PassengerGroup.PET)

    /** 예외 E07: 같은 공조 존을 쓸 때 기준이 되는 순서 (G1 > G2a > G4 > G5 > G6 > G2b > G3 > G7) */
    private val zonePriority = listOf(
        PassengerGroup.INFANT, PassengerGroup.PRESCHOOL, PassengerGroup.SENIOR, PassengerGroup.PREGNANT,
        PassengerGroup.MOBILITY, PassengerGroup.SCHOOL, PassengerGroup.PET, PassengerGroup.ADULT
    )

    fun actions(assignments: Map<SeatPosition, SeatAssignment>): List<CabinAction> {
        val result = mutableListOf<CabinAction>()
        assignments.forEach { (pos, a) ->
            val group = a.groupOrNull() ?: return@forEach
            val name = a.displayName()
            val operates = group == PassengerGroup.SCHOOL && a is SeatAssignment.Known && a.passenger.canOperate
            if (pos.isRear && pos.windowCode != null && (group in lockGroups || operates)) {
                result += CabinAction.LockWindow(pos, "$name(${group.label})")
            }
            if (a is SeatAssignment.Known) {
                a.passenger.preferredTemp?.let {
                    result += CabinAction.SetTemperature(pos, it, name, zonePriority.indexOf(group))
                }
                if (group == PassengerGroup.INFANT || a.passenger.restraint == Restraint.HARNESS) {
                    result += CabinAction.SeatHeatOff(pos, "카시트 좌석")
                }
            }
        }
        return result
    }

    fun checklist(assignments: Map<SeatPosition, SeatAssignment>): List<CheckItem> {
        val items = mutableListOf<CheckItem>()
        var hasChild = false
        assignments.forEach { (pos, a) ->
            val group = a.groupOrNull() ?: return@forEach
            val name = a.displayName()
            if (group.isChild) hasChild = true
            when {
                group == PassengerGroup.PET -> items += CheckItem(
                    "pet_$pos", "$name 안전 고정", "켄넬·안전줄 확인", CheckItem.Kind.MANUAL, pos
                )
                a.restraint() == Restraint.HARNESS -> items += CheckItem(
                    "harness_$pos", "$name 하네스 체결", "차량 센서로 알 수 없어요 · 직접 확인", CheckItem.Kind.MANUAL, pos
                )
                else -> items += CheckItem(
                    "belt_$pos", "$name 안전벨트", pos.label, CheckItem.Kind.SENSOR_BELT, pos
                )
            }
            if (group == PassengerGroup.INFANT || group == PassengerGroup.PRESCHOOL) {
                items += CheckItem(
                    "carseat_$pos", "$name 카시트·부스터 장착", "6세 미만 유아보호용 장구 의무", CheckItem.Kind.MANUAL, pos
                )
            }
            if (group == PassengerGroup.PREGNANT) {
                items += CheckItem(
                    "pregbelt_$pos", "$name 벨트 위치", "골반벨트는 배 아래로, 어깨벨트는 가슴 사이로", CheckItem.Kind.MANUAL, pos
                )
            }
        }
        if (assignments.isNotEmpty()) {
            items += CheckItem("doors", "문 모두 닫힘", "문 열림 센서", CheckItem.Kind.SENSOR_DOORS)
        }
        if (hasChild) {
            items += CheckItem("childlock", "뒷문 차일드락", "제어 기능이 없어 직접 확인", CheckItem.Kind.MANUAL)
        }
        return items
    }

    private fun SeatAssignment.groupOrNull(): PassengerGroup? = when (this) {
        is SeatAssignment.Known -> passenger.group
        is SeatAssignment.Guest -> group
        SeatAssignment.Luggage -> null
    }

    private fun SeatAssignment.displayName(): String = when (this) {
        is SeatAssignment.Known -> passenger.name
        is SeatAssignment.Guest -> "게스트"
        SeatAssignment.Luggage -> "짐"
    }

    private fun SeatAssignment.restraint(): Restraint = when (this) {
        is SeatAssignment.Known -> passenger.restraint
        is SeatAssignment.Guest -> if (group.isChild) Restraint.BOOSTER else Restraint.SEAT_BELT
        SeatAssignment.Luggage -> Restraint.SEAT_BELT
    }
}
