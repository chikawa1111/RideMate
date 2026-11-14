package com.ridemate.passengercare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ridemate.passengercare.data.Passenger
import com.ridemate.passengercare.data.PassengerGroup
import com.ridemate.passengercare.data.SeatAssignment
import com.ridemate.passengercare.ui.components.CareFrame
import com.ridemate.passengercare.ui.components.Certainty
import com.ridemate.passengercare.ui.components.ChoiceRow
import com.ridemate.passengercare.ui.components.Panel
import com.ridemate.passengercare.ui.components.PrimaryButton
import com.ridemate.passengercare.ui.components.SecondaryButton
import com.ridemate.passengercare.ui.theme.CareColors
import com.ridemate.passengercare.ui.theme.CareType
import com.ridemate.passengercare.vehicle.SeatPosition
import com.ridemate.passengercare.vehicle.SeatState
import com.ridemate.passengercare.vehicle.VehicleSeats

/** 좌석 하나를 화면에 그리기 위한 정리된 정보. */
data class SeatView(
    val position: SeatPosition,
    val title: String,
    val certainty: Certainty,
    val estimate: Passenger?
)

/**
 * 화면 03 · 탑승 확인(좌석 지도).
 * 센서는 "누가 앉았다"만 안다. 이름은 추정(노랑)으로 보여주고 운전자가 확정(청록)한다.
 */
@Composable
fun BoardingScreen(
    seats: Map<SeatPosition, SeatState>,
    passengers: List<Passenger>,
    assignments: Map<SeatPosition, SeatAssignment>,
    onConfirm: (SeatPosition, SeatAssignment) -> Unit,
    onClear: (SeatPosition) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit
) {
    val views = VehicleSeats.active.associateWith { pos ->
        buildSeatView(pos, seats[pos], passengers, assignments[pos])
    }
    var selected by remember {
        mutableStateOf(views.values.firstOrNull { it.certainty == Certainty.ESTIMATED }?.position)
    }
    var editing by remember { mutableStateOf(false) }

    CareFrame(stateLabel = "정차 전용", onBack = onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            // 좌석 지도: 위쪽이 앞좌석
            Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SeatTile(views.getValue(SeatPosition.FRONT_LEFT), false, Modifier.weight(1f)) {}
                    SeatTile(views.getValue(SeatPosition.FRONT_RIGHT), selected == SeatPosition.FRONT_RIGHT, Modifier.weight(1f)) {
                        selected = SeatPosition.FRONT_RIGHT; editing = false
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    VehicleSeats.rear.forEach { pos ->
                        SeatTile(views.getValue(pos), selected == pos, Modifier.weight(1f)) {
                            selected = pos; editing = false
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                val allDone = views.values.none { it.certainty == Certainty.ESTIMATED }
                PrimaryButton(
                    if (allDone) "출발 전 확인으로" else "추정된 좌석을 먼저 확인해 주세요",
                    onDone,
                    enabled = allDone,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 선택한 좌석의 확인 패널
            Column(Modifier.weight(1f)) {
                Text("누가 탔나요?", style = CareType.Title)
                Spacer(Modifier.height(12.dp))
                Text("이름이 맞는지 확인해 주세요.", style = CareType.Sub)
                Spacer(Modifier.height(28.dp))

                val view = selected?.let { views[it] }
                if (view == null) {
                    Text("왼쪽에서 좌석을 눌러 주세요.", style = CareType.Sub)
                } else {
                    Row {
                        Text(view.position.label, style = CareType.Main, modifier = Modifier.weight(1f))
                        Text(
                            when (view.certainty) {
                                Certainty.ESTIMATED -> "${view.estimate?.name ?: "누군가"}로 추정"
                                else -> view.certainty.label
                            },
                            style = CareType.Sub.copy(color = view.certainty.color)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = CareColors.Divider)
                    Spacer(Modifier.height(24.dp))

                    if (!editing) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (view.certainty == Certainty.ESTIMATED && view.estimate != null) {
                                PrimaryButton("맞아요", { onConfirm(view.position, SeatAssignment.Known(view.estimate)) })
                            }
                            SecondaryButton(if (view.certainty == Certainty.UNKNOWN) "수동 추가" else "수정", { editing = true })
                            if (assignments.containsKey(view.position)) {
                                SecondaryButton("비우기", { onClear(view.position) })
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("수동 추가 · 사람 / 짐 구분은 정차 중에", style = CareType.Small)
                    } else {
                        Text("이 자리에 앉은 사람", style = CareType.Body)
                        Spacer(Modifier.height(12.dp))
                        passengers.chunked(4).forEach { rowItems ->
                            ChoiceRow(rowItems, null, { it.name }) { p ->
                                onConfirm(view.position, SeatAssignment.Known(p)); editing = false
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        ChoiceRow(
                            listOf("게스트 아이", "게스트 어른", "짐"),
                            null,
                            { it }
                        ) { choice ->
                            val a = when (choice) {
                                "게스트 아이" -> SeatAssignment.Guest(PassengerGroup.PRESCHOOL)
                                "게스트 어른" -> SeatAssignment.Guest(PassengerGroup.ADULT)
                                else -> SeatAssignment.Luggage
                            }
                            onConfirm(view.position, a); editing = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeatTile(view: SeatView, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val border = when {
        view.position == SeatPosition.FRONT_LEFT -> CareColors.PanelBorder
        selected -> CareColors.Text
        else -> view.certainty.color
    }
    Panel(modifier = modifier.height(180.dp), borderColor = border, onClick = onClick) {
        Text("▢", style = CareType.Main.copy(color = CareColors.TextSub))
        Spacer(Modifier.height(8.dp))
        Text(view.title, style = CareType.Main)
        Spacer(Modifier.height(4.dp))
        Text(
            if (view.position == SeatPosition.FRONT_LEFT) "운전석" else view.certainty.label,
            style = CareType.Small.copy(color = if (view.position == SeatPosition.FRONT_LEFT) CareColors.TextSub else view.certainty.color)
        )
    }
    Spacer(Modifier.width(0.dp))
}

private fun buildSeatView(
    pos: SeatPosition,
    state: SeatState?,
    passengers: List<Passenger>,
    assignment: SeatAssignment?
): SeatView {
    if (pos == SeatPosition.FRONT_LEFT) return SeatView(pos, "운전자", Certainty.CONFIRMED, null)
    if (assignment != null) {
        val title = when (assignment) {
            is SeatAssignment.Known -> assignment.passenger.name
            is SeatAssignment.Guest -> "게스트 (${assignment.group.label})"
            SeatAssignment.Luggage -> "짐"
        }
        return SeatView(pos, title, Certainty.CONFIRMED, null)
    }
    return if (state?.occupied == true) {
        val estimate = passengers.firstOrNull { it.defaultSeat == pos }
        SeatView(pos, estimate?.let { "${it.name}?" } ?: "누군가?", Certainty.ESTIMATED, estimate)
    } else {
        SeatView(pos, "빈 자리", Certainty.UNKNOWN, null)
    }
}
