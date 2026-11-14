package com.ridemate.passengercare.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ridemate.passengercare.data.Passenger
import com.ridemate.passengercare.data.SavedPlace
import com.ridemate.passengercare.ui.components.CareFrame
import com.ridemate.passengercare.ui.components.ListRow
import com.ridemate.passengercare.ui.components.Panel
import com.ridemate.passengercare.ui.components.PrimaryButton
import com.ridemate.passengercare.ui.components.SecondaryButton
import com.ridemate.passengercare.ui.theme.CareColors
import com.ridemate.passengercare.ui.theme.CareSize
import com.ridemate.passengercare.ui.theme.CareType

/** 정차 중 기본 화면: 등록된 동승자 + 탑승 확인 시작. */
@Composable
fun HomeScreen(
    passengers: List<Passenger>,
    seatSensorConnected: Boolean,
    hvacReady: Boolean?,
    naviConnected: Boolean,
    hospital: SavedPlace?,
    onPickHospital: () -> Unit,
    onStartBoarding: () -> Unit,
    onAddPassenger: () -> Unit,
    onEditPassenger: (Passenger) -> Unit,
    onOpenSeatMonitor: () -> Unit
) {
    CareFrame(stateLabel = "정차 전용") {
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("함께 탄 사람에게\n필요한 도움만", style = CareType.Title)
                Spacer(Modifier.height(16.dp))
                Text("동승자를 확인하고, 부탁할 때 도와드려요.", style = CareType.Sub)
                Spacer(Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PrimaryButton("탑승 확인 시작", onStartBoarding, enabled = passengers.isNotEmpty())
                    SecondaryButton("동승자 등록", onAddPassenger)
                }
                Spacer(Modifier.height(40.dp))
                Text("등록된 동승자 ${passengers.size}명", style = CareType.Main)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (passengers.isEmpty()) {
                        Text("아직 등록된 동승자가 없어요.", style = CareType.Sub)
                    }
                    passengers.forEach { p ->
                        ListRow(
                            title = p.name,
                            value = "${p.group.label} · ${p.defaultSeat.label}",
                            onClick = { onEditPassenger(p) }
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CareSize.Gap)) {
                Panel(Modifier.fillMaxWidth()) {
                    Text("지원 기능 확인", style = CareType.Main)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "좌석 센서 · " + if (seatSensorConnected) "연결됨" else "확인 중",
                        style = CareType.Sub.copy(
                            color = if (seatSensorConnected) CareColors.Primary else CareColors.Caution
                        )
                    )
                    // 원칙 P3: 공조 지원 여부를 실제로 확인한 결과를 보여준다
                    Text(
                        "공조 제어 · " + when (hvacReady) { true -> "연결됨"; false -> "지원 안 됨 · 직접 조작"; null -> "확인 중" },
                        style = CareType.Sub.copy(color = if (hvacReady == true) CareColors.Primary else CareColors.Caution)
                    )
                    Text(
                        "내비 · " + if (naviConnected) "연결됨" else "확인 중",
                        style = CareType.Sub.copy(color = if (naviConnected) CareColors.Primary else CareColors.Caution)
                    )
                }
                Panel(Modifier.fillMaxWidth()) {
                    Text("긴급 병원", style = CareType.Main)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        hospital?.name ?: "등록 안 됨 · 임산부·영유아와 탈 때 등록해 두세요",
                        style = CareType.Sub.copy(color = if (hospital != null) CareColors.Primary else CareColors.TextSub)
                    )
                    Spacer(Modifier.height(12.dp))
                    SecondaryButton(if (hospital == null) "내비에서 고르기" else "바꾸기", onPickHospital)
                }
                Panel(Modifier.fillMaxWidth()) {
                    Text("꼭 알아두세요", style = CareType.Main)
                    Spacer(Modifier.height(12.dp))
                    Text("시동이 꺼진 뒤의 감지·공조 유지는\n차량 기본 기능의 영역입니다.", style = CareType.Sub)
                }
                SecondaryButton("개발자 도구 · 좌석 모니터", onOpenSeatMonitor)
            }
        }
    }
}

/**
 * 긴급 병원 고르기(정차 중). 장소 검색 API가 없어서 길 안내 앱의 즐겨찾기·최근 목적지에서 고른다.
 * 주행 중 "병원 가자" 또는 긴급 버튼 → 이곳으로 바로 안내, "취소"로 되돌림.
 */
@Composable
fun HospitalPickScreen(
    current: SavedPlace?,
    bookmarks: List<SavedPlace>,
    recents: List<SavedPlace>,
    naviConnected: Boolean,
    onRefresh: () -> Unit,
    onPick: (SavedPlace?) -> Unit,
    onBack: () -> Unit
) {
    CareFrame(stateLabel = "정차 전용", onBack = onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Column(Modifier.weight(1.3f).verticalScroll(rememberScrollState())) {
                Text("긴급 병원 정하기", style = CareType.Title)
                Spacer(Modifier.height(8.dp))
                Text("길 안내 앱의 즐겨찾기·최근 목적지에서 골라요.", style = CareType.Sub)
                Spacer(Modifier.height(24.dp))
                val all = (bookmarks.map { it to "즐겨찾기" } + recents.map { it to "최근 목적지" })
                    .distinctBy { it.first.name to it.first.lat }
                if (all.isEmpty()) {
                    Text(
                        if (naviConnected) "목록이 비어 있어요. 내비에서 병원을 한 번 검색해 안내받거나 즐겨찾기에 넣은 뒤 다시 불러오세요."
                        else "내비에 연결되지 않았어요.",
                        style = CareType.Sub.copy(color = CareColors.Caution)
                    )
                }
                all.forEach { (place, kind) ->
                    ListRow(
                        title = place.name,
                        value = kind + if (place == current) " · 지금 등록됨" else "",
                        valueColor = if (place == current) CareColors.Primary else CareColors.TextSub,
                        onClick = { onPick(place) }
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CareSize.Gap)) {
                Panel(Modifier.fillMaxWidth()) {
                    Text("지금 등록된 곳", style = CareType.Main)
                    Spacer(Modifier.height(8.dp))
                    Text(current?.let { it.name + "\n" + it.address } ?: "없음", style = CareType.Sub)
                }
                SecondaryButton("목록 다시 불러오기", onRefresh)
                if (current != null) SecondaryButton("등록 해제", { onPick(null) })
                Panel(Modifier.fillMaxWidth()) {
                    Text("주행 중 쓰는 법", style = CareType.Main)
                    Spacer(Modifier.height(8.dp))
                    Text("\"병원 가자\"라고 말하거나 긴급 버튼을 누르면 이곳으로 바로 안내해요. 잘못 눌렀으면 \"되돌리기\" 한 번으로 원래 목적지로 돌아가요.", style = CareType.Small)
                }
            }
        }
    }
}
