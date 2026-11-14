package com.ridemate.passengercare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ridemate.passengercare.care.ActionStatus
import com.ridemate.passengercare.care.CabinAction
import com.ridemate.passengercare.care.CheckItem
import com.ridemate.passengercare.ui.components.CareFrame
import com.ridemate.passengercare.ui.components.Panel
import com.ridemate.passengercare.ui.components.PrimaryButton
import com.ridemate.passengercare.ui.theme.CareColors
import com.ridemate.passengercare.ui.theme.CareSize
import com.ridemate.passengercare.ui.theme.CareType
import com.ridemate.passengercare.vehicle.SeatPosition
import com.ridemate.passengercare.vehicle.SeatState

/**
 * 화면 04 · 출발 전 확인.
 * 왼쪽: 확인할 것(센서 자동 + 직접 확인), 오른쪽: 방금 적용한 세팅과 결과.
 */
@Composable
fun PreDepartureScreen(
    checklist: List<CheckItem>,
    manualChecked: Set<String>,
    seats: Map<SeatPosition, SeatState>,
    actions: List<Pair<CabinAction, ActionStatus>>,
    onToggleManual: (String) -> Unit,
    onSimulateBelt: (SeatPosition) -> Unit,
    onDepart: () -> Unit,
    onBack: () -> Unit
) {
    val remaining = checklist.count { item -> !item.isDone(manualChecked, seats) }

    CareFrame(stateLabel = "정차 전용", onBack = onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Column(
                Modifier
                    .weight(1.2f)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("출발 전 확인", style = CareType.Title)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (remaining == 0) "모두 확인됐어요." else "확인할 항목이 ${remaining}개 남았어요.",
                    style = CareType.Sub.copy(color = if (remaining == 0) CareColors.Primary else CareColors.Caution)
                )
                Spacer(Modifier.height(24.dp))
                checklist.forEach { item ->
                    CheckRow(
                        item = item,
                        done = item.isDone(manualChecked, seats),
                        seatState = item.position?.let { seats[it] },
                        doorsOpen = seats.filter { it.value.doorOpen == true }.keys.map { it.label },
                        onToggle = { onToggleManual(item.id) },
                        onSimulateBelt = { item.position?.let(onSimulateBelt) }
                    )
                }
                Spacer(Modifier.height(28.dp))
                PrimaryButton(
                    if (remaining == 0) "출발" else "남은 항목을 확인해 주세요",
                    onDepart,
                    enabled = remaining == 0,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Column(Modifier.weight(1f)) {
                Panel(Modifier.fillMaxWidth()) {
                    Text("적용한 세팅", style = CareType.Main)
                    Spacer(Modifier.height(4.dp))
                    Text("동승자 정보에 맞춰 한 번에 적용했어요.", style = CareType.Small)
                    Spacer(Modifier.height(16.dp))
                    if (actions.isEmpty()) {
                        Text("이번 구성에는 바꿀 세팅이 없어요.", style = CareType.Sub)
                    }
                    actions.forEach { (action, status) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 64.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(action.label, style = CareType.Body.copy(fontSize = CareType.Sub.fontSize), modifier = Modifier.weight(1f))
                            Text(
                                status.label,
                                style = CareType.Small.copy(
                                    color = when (status) {
                                        ActionStatus.DONE -> CareColors.Primary
                                        ActionStatus.PENDING -> CareColors.TextSub
                                        ActionStatus.MANUAL -> CareColors.Caution
                                        ActionStatus.SHARED -> CareColors.TextSub
                                    }
                                )
                            )
                        }
                        HorizontalDivider(color = CareColors.Divider)
                    }
                }
            }
        }
    }
}

private fun CheckItem.isDone(manual: Set<String>, seats: Map<SeatPosition, SeatState>): Boolean = when (kind) {
    CheckItem.Kind.MANUAL -> id in manual
    CheckItem.Kind.SENSOR_BELT -> position?.let { seats[it]?.belted } == true
    CheckItem.Kind.SENSOR_DOORS -> seats.values.none { it.doorOpen == true }
}

@Composable
private fun CheckRow(
    item: CheckItem,
    done: Boolean,
    seatState: SeatState?,
    doorsOpen: List<String>,
    onToggle: () -> Unit,
    onSimulateBelt: () -> Unit
) {
    val clickable = item.kind == CheckItem.Kind.MANUAL
    var rowModifier = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = CareSize.Touch)
    if (clickable) rowModifier = rowModifier.clickable(onClick = onToggle)

    Column {
        Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
            CheckBox(done = done, sensor = !clickable)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = CareType.Body)
                Text(item.detail, style = CareType.Small)
            }
            when (item.kind) {
                CheckItem.Kind.SENSOR_BELT -> Column(horizontalAlignment = Alignment.End) {
                    Text(
                        when (seatState?.belted) {
                            true -> "센서 · 맴"
                            false -> "센서 · 안 맴"
                            null -> "센서 · 확인 중"
                        },
                        style = CareType.Small.copy(color = if (done) CareColors.Primary else CareColors.Alert)
                    )
                    Text(
                        "시뮬레이션: 벨트 바꾸기",
                        style = CareType.Small.copy(color = CareColors.TextDim),
                        modifier = Modifier
                            .clickable(onClick = onSimulateBelt)
                            .padding(vertical = 6.dp)
                    )
                }
                CheckItem.Kind.SENSOR_DOORS -> Text(
                    if (done) "센서 · 닫힘" else "열림: " + doorsOpen.joinToString(", "),
                    style = CareType.Small.copy(color = if (done) CareColors.Primary else CareColors.Alert)
                )
                CheckItem.Kind.MANUAL -> Text(
                    if (done) "확인함" else "눌러서 확인",
                    style = CareType.Small.copy(color = if (done) CareColors.Primary else CareColors.Caution)
                )
            }
        }
        HorizontalDivider(color = CareColors.Divider)
    }
}

@Composable
private fun CheckBox(done: Boolean, sensor: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    val color: Color = if (done) CareColors.Primary else if (sensor) CareColors.Alert else CareColors.Caution
    Box(
        Modifier
            .size(40.dp)
            .background(if (done) CareColors.Primary else Color.Transparent, shape)
            .border(3.dp, color, shape),
        contentAlignment = Alignment.Center
    ) {
        if (done) Text("✓", style = CareType.Body.copy(color = CareColors.OnPrimary))
    }
}
