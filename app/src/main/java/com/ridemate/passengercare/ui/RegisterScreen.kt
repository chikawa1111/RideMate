package com.ridemate.passengercare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ridemate.passengercare.data.Passenger
import com.ridemate.passengercare.data.PassengerGroup
import com.ridemate.passengercare.data.Restraint
import com.ridemate.passengercare.ui.components.CareFrame
import com.ridemate.passengercare.ui.components.ChoiceRow
import com.ridemate.passengercare.ui.components.PrimaryButton
import com.ridemate.passengercare.ui.components.SecondaryButton
import com.ridemate.passengercare.ui.theme.CareColors
import com.ridemate.passengercare.ui.theme.CareType
import com.ridemate.passengercare.vehicle.SeatPosition
import com.ridemate.passengercare.vehicle.VehicleSeats


/** 화면 02 · 동승자 등록: 이름·유형·좌석 먼저, 추가 배려는 오른쪽. */
@Composable
fun RegisterScreen(
    existing: Passenger?,
    newId: () -> String,
    onSave: (Passenger) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var group by remember { mutableStateOf(existing?.group ?: PassengerGroup.PRESCHOOL) }
    var seat by remember { mutableStateOf(existing?.defaultSeat ?: SeatPosition.REAR_RIGHT) }
    var restraint by remember { mutableStateOf(existing?.restraint ?: Restraint.BOOSTER) }
    var comfort by remember { mutableStateOf(existing?.comfortRide ?: false) }
    var toilet by remember { mutableStateOf(existing?.frequentToilet ?: false) }
    var temp by remember { mutableStateOf(existing?.preferredTemp) }
    var canOperate by remember { mutableStateOf(existing?.canOperate ?: false) }
    var restInterval by remember { mutableStateOf(existing?.restIntervalMin) }

    CareFrame(stateLabel = "정차 전용", onBack = onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(56.dp)) {
            // 왼쪽: 기본 정보
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    if (name.isBlank()) "새 동승자" else "${name}의 케어 정보",
                    style = CareType.Title
                )
                Spacer(Modifier.height(28.dp))

                Text("이름", style = CareType.Body)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(10) },
                    singleLine = true,
                    textStyle = CareType.Body,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CareColors.Primary,
                        unfocusedBorderColor = CareColors.PanelBorder,
                        cursorColor = CareColors.Primary
                    )
                )
                Spacer(Modifier.height(28.dp))

                Text("동승자 유형", style = CareType.Body)
                Spacer(Modifier.height(8.dp))
                val groups = PassengerGroup.entries
                ChoiceRow(groups.take(4), group, { it.label }) { group = it; if (it == PassengerGroup.INFANT) restraint = Restraint.HARNESS }
                Spacer(Modifier.height(12.dp))
                ChoiceRow(groups.drop(4), group, { it.label }) { group = it }
                Spacer(Modifier.height(28.dp))

                Text("기본 좌석", style = CareType.Body)
                Spacer(Modifier.height(8.dp))
                ChoiceRow(VehicleSeats.passengerSeats, seat, { it.label }) { seat = it }
            }

            // 오른쪽: 필요한 배려
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("필요한 배려", style = CareType.Main)
                Spacer(Modifier.height(20.dp))

                if (group.isChild) {
                    Text("앉는 장치", style = CareType.Body)
                    Spacer(Modifier.height(8.dp))
                    ChoiceRow(Restraint.entries, restraint, { it.label }) { restraint = it }
                    if (!restraint.beltSensorWorks) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "하네스 풀림은 차량 센서로 알 수 없어요. 출발 전 확인 목록에 넣을게요.",
                            style = CareType.Small.copy(color = CareColors.Caution)
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }

                when (group) {
                    PassengerGroup.INFANT -> Text(
                        "생후 3~4개월 미만 신생아는 카시트에 오래 앉히지 않는 것이 좋아요. 장거리는 가능하면 줄여 주세요.",
                        style = CareType.Small.copy(color = CareColors.Caution),
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    PassengerGroup.PREGNANT -> Text(
                        "탑승할 때 벨트 위치를 확인 목록에 넣어요. 임신 후기(28주~)에는 아래 휴식 간격을 직접 줄여 주세요.",
                        style = CareType.Small.copy(color = CareColors.Caution),
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    else -> Unit
                }
                if (group == PassengerGroup.SCHOOL) {
                    ToggleRow("창문·문을 스스로 조작해요", "켜면 이 아이 쪽 뒷창문을 잠가요", canOperate) { canOperate = it }
                }
                ToggleRow("편안한 주행", "공사 구간이 앞에 있으면 화면에 알려요", comfort) { comfort = it }
                ToggleRow("잦은 화장실", "쉬어갈 곳을 찾을 때 화장실을 꼭 포함해요", toilet) { toilet = it }

                Spacer(Modifier.height(16.dp))
                Text("선호 온도", style = CareType.Body)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton("−", { temp = ((temp ?: 23) - 1).coerceAtLeast(17) }, Modifier.width(96.dp))
                    Text(
                        temp?.let { "$it℃" } ?: "직접 입력",
                        style = CareType.Main,
                        modifier = Modifier.width(200.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    SecondaryButton("+", { temp = ((temp ?: 21) + 1).coerceAtMost(30) }, Modifier.width(96.dp))
                }
                Text("기본값은 두지 않아요. 원할 때만 입력하세요.", style = CareType.Small)

                Spacer(Modifier.height(24.dp))
                Text("권장 휴식 간격", style = CareType.Body)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton("−", { restInterval = ((restInterval ?: 120) - 15).coerceAtLeast(30) }, Modifier.width(96.dp))
                    Text(
                        restInterval?.let { "${it}분" } ?: "기본 2시간",
                        style = CareType.Main,
                        modifier = Modifier.width(200.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    SecondaryButton("+", {
                        val next = (restInterval ?: 105) + 15
                        restInterval = if (next >= 120) null else next
                    }, Modifier.width(96.dp))
                }
                Text("운전자 기준 2시간마다 쉬기를 권해요. 더 자주 쉬어야 하면 줄여 주세요.", style = CareType.Small)

                Spacer(Modifier.height(36.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PrimaryButton(
                        "저장",
                        enabled = name.isNotBlank(),
                        onClick = {
                            onSave(
                                Passenger(
                                    id = existing?.id ?: newId(),
                                    name = name.trim(),
                                    group = group,
                                    defaultSeat = seat,
                                    restraint = if (group.isChild) restraint else Restraint.SEAT_BELT,
                                    comfortRide = comfort,
                                    frequentToilet = toilet,
                                    preferredTemp = temp,
                                    canOperate = group == PassengerGroup.SCHOOL && canOperate,
                                    restIntervalMin = restInterval
                                )
                            )
                        }
                    )
                    if (existing != null) {
                        SecondaryButton("삭제", { onDelete(existing.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = CareType.Body)
            Text(desc, style = CareType.Small)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = CareColors.Primary)
        )
    }
}
