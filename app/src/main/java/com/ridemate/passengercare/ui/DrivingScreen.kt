package com.ridemate.passengercare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ridemate.passengercare.care.ActionStatus
import com.ridemate.passengercare.care.BeltAlarm
import com.ridemate.passengercare.care.BeltAlert
import com.ridemate.passengercare.care.CabinAction
import com.ridemate.passengercare.care.ExitAlert
import com.ridemate.passengercare.care.VoiceCommand
import com.ridemate.passengercare.care.RestCandidate
import com.ridemate.passengercare.care.RestResult
import com.ridemate.passengercare.env.EnvState
import com.ridemate.passengercare.MainViewModel.VoiceUi
import com.ridemate.passengercare.ui.components.SecondaryButton
import com.ridemate.passengercare.vehicle.CareScene
import com.ridemate.passengercare.data.PassengerGroup
import com.ridemate.passengercare.data.SeatAssignment
import com.ridemate.passengercare.ui.components.CareFrame
import com.ridemate.passengercare.ui.components.Panel
import com.ridemate.passengercare.ui.components.PrimaryButton
import com.ridemate.passengercare.ui.theme.CareColors
import com.ridemate.passengercare.ui.theme.CareSize
import com.ridemate.passengercare.ui.theme.CareType
import com.ridemate.passengercare.vehicle.DriveState
import com.ridemate.passengercare.vehicle.SeatPosition
import com.ridemate.passengercare.vehicle.SeatState
import com.ridemate.passengercare.vehicle.VehicleSeats

/**
 * 화면 05 · 주행 기본.
 * 주행 중에는 보기만 하는 화면: 좌석별 벨트 상태 + 벨트 풀림 경보(A1).
 * 앱이 먼저 말을 거는 일은 없고, 소리는 안전 경보일 때만 난다(원칙 P1).
 */
@Composable
fun DrivingScreen(
    drive: DriveState,
    driveSimulated: DriveState?,
    seats: Map<SeatPosition, SeatState>,
    assignments: Map<SeatPosition, SeatAssignment>,
    alerts: List<BeltAlert>,
    beltSimulated: Set<SeatPosition>,
    actions: List<Pair<CabinAction, ActionStatus>>,
    exitAlert: ExitAlert?,
    voice: VoiceUi,
    scene: CareScene?,
    onMicTap: () -> Unit,
    onVoiceCommand: (VoiceCommand) -> Unit,
    onDismissVoice: () -> Unit,
    nextStop: RestCandidate?,
    demoPositionLabel: String,
    onChooseRest: (RestCandidate) -> Unit,
    onCycleDemoPosition: () -> Unit,
    env: EnvState,
    onCycleWeather: () -> Unit,
    roadSimulated: Boolean,
    onToggleRoad: () -> Unit,
    weatherSimLabel: String?,
    restDue: com.ridemate.passengercare.MainViewModel.RestDue?,
    comfortAhead: Boolean,
    onMarkRested: () -> Unit,
    onAdvanceClock: () -> Unit,
    naviDestination: com.ridemate.passengercare.vehicle.NaviGateway.Destination?,
    naviConnected: Boolean,
    hospitalName: String?,
    pregnantAboard: Boolean,
    onUndoHospital: () -> Unit,
    onSimulateDrive: (DriveState?) -> Unit,
    onSimulateBelt: (SeatPosition) -> Unit,
    onArrive: () -> Unit
) {
    val stateLabel = drive.label + if (driveSimulated != null) " · 시뮬레이션" else ""
    CareFrame(stateLabel = stateLabel, onBack = null) {
        if (alerts.isNotEmpty()) {
            AlertBanner(alerts)
            Spacer(Modifier.height(20.dp))
        }
        if (exitAlert != null) {
            ExitAlertBanner(exitAlert)
            Spacer(Modifier.height(20.dp))
        }

        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            // 좌석 지도 (앞 2 + 뒤 2)
            Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    if (drive.moving) "안전하게 가는 중" else "정차 중",
                    style = CareType.Title
                )
                val rows = listOf(
                    VehicleSeats.active.filterNot { it.isRear },
                    VehicleSeats.rear
                )
                rows.forEach { row ->
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        row.forEach { pos ->
                            DriveSeatTile(
                                position = pos,
                                assignment = assignments[pos],
                                state = seats[pos],
                                alerting = alerts.any { it.position == pos },
                                simulated = pos in beltSimulated,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Panel(Modifier.fillMaxWidth()) {
                    Text("이번 운행", style = CareType.Main)
                    Spacer(Modifier.height(12.dp))
                    Text("출발 전 확인 완료", style = CareType.Sub.copy(color = CareColors.Primary))
                    if (actions.isNotEmpty()) {
                        Text(
                            actions.joinToString(" · ") { (a, st) -> a.label + if (st == ActionStatus.DONE) "" else "(${st.label})" },
                            style = CareType.Small.copy(
                                color = if (actions.all { it.second == ActionStatus.DONE }) CareColors.TextSub else CareColors.Caution
                            ),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    Text(
                        naviDestination?.let { "목적지 ${it.name} · ${formatMin(it.remainMin)} 남음" }
                            ?: if (naviConnected) "내비 연결됨 · 목적지 없음" else "내비 연결 안 됨",
                        style = CareType.Small.copy(color = CareColors.TextSub),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    restDue?.let { r ->
                        Text(
                            if (r.remainingMin > 0) "권장 휴식까지 ${formatMin(r.remainingMin)} · ${r.basis}"
                            else "권장 휴식 시간이 지났어요 · ${r.basis}",
                            style = CareType.Sub.copy(color = if (r.remainingMin > 15) CareColors.Text else CareColors.Caution),
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                    Text(
                        "날씨·도로 · " + env.summary +
                            (if (env.simulated) " (시뮬레이션)" else "") + (if (env.stale) " · 최신 아님" else ""),
                        style = CareType.Small.copy(color = if (env.hasCaution) CareColors.Caution else CareColors.TextSub),
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    if (comfortAhead) {
                        Text(
                            "편안한 주행 동승자 · 앞에 작업·돌발 구간이 있어요",
                            style = CareType.Small.copy(color = CareColors.Caution),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    if (nextStop != null) {
                        Text(
                            "다음 휴식지 · ${nextStop.area.displayName}",
                            style = CareType.Sub.copy(color = CareColors.Primary),
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                    if (scene != null) {
                        Text(
                            "${scene.label} 모드 켜짐",
                            style = CareType.Sub.copy(color = CareColors.Primary),
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // 기획서 G5: 임산부 동승 + 긴급 병원 등록 시 긴급 버튼을 늘 보여준다(누르면 바로 안내, 되돌리기 가능)
                if (pregnantAboard && hospitalName != null && voice !is VoiceUi.HospitalStarted) {
                    EmergencyButton(hospitalName) { onVoiceCommand(VoiceCommand.HOSPITAL) }
                }
                VoicePanel(voice, onMicTap, onVoiceCommand, onDismissVoice, onChooseRest, onUndoHospital)
                if (!drive.moving) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SecondaryButton("여기서 쉬었어요", onMarkRested, modifier = Modifier.weight(1f))
                        PrimaryButton("도착 · 하차 확인", onArrive, modifier = Modifier.weight(1f))
                    }
                } else {
                    Text(
                        "하차 확인은 정차(P) 후에 할 수 있어요.",
                        style = CareType.Small,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        DemoStrip(driveSimulated, assignments, demoPositionLabel, onSimulateDrive, onSimulateBelt, onCycleDemoPosition, env, onCycleWeather, roadSimulated, onToggleRoad, weatherSimLabel, onAdvanceClock)
    }
}

@Composable
private fun AlertBanner(alerts: List<BeltAlert>) {
    val shape = RoundedCornerShape(CareSize.Radius)
    Column(
        Modifier
            .fillMaxWidth()
            .background(CareColors.Alert.copy(alpha = 0.16f), shape)
            .border(3.dp, CareColors.Alert, shape)
            .padding(horizontal = 28.dp, vertical = 18.dp)
    ) {
        Text(
            alerts.joinToString(", ") { it.name } + " 안전벨트가 풀렸어요",
            style = CareType.Title.copy(color = CareColors.Alert)
        )
        Text(
            alerts.joinToString(" · ") { it.position.label } + " · 벨트를 매면 경보가 멈춰요",
            style = CareType.Sub.copy(color = CareColors.Text)
        )
    }
}

@Composable
private fun DriveSeatTile(
    position: SeatPosition,
    assignment: SeatAssignment?,
    state: SeatState?,
    alerting: Boolean,
    simulated: Boolean,
    modifier: Modifier
) {
    val (title, status, color) = when {
        position == SeatPosition.FRONT_LEFT -> Triple("운전자", "", CareColors.PanelBorder)
        assignment == null -> Triple("빈 자리", "", CareColors.PanelBorder)
        assignment is SeatAssignment.Luggage -> Triple("짐", "", CareColors.PanelBorder)
        !BeltAlarm.beltSensorApplies(assignment) -> Triple(
            assignment.title(),
            if (assignment.isPet()) "안전 고정 · 출발 전 확인함" else "하네스 · 출발 전 확인함",
            CareColors.TextDim
        )
        alerting -> Triple(assignment.title(), "벨트 풀림", CareColors.Alert)
        state?.belted == true -> Triple(assignment.title(), "벨트 맴", CareColors.Primary)
        else -> Triple(assignment.title(), "벨트 확인 중", CareColors.TextDim)
    }
    val shape = RoundedCornerShape(CareSize.Radius)
    Box(
        modifier
            .background(if (alerting) CareColors.Alert.copy(alpha = 0.16f) else CareColors.Panel, shape)
            .border(if (alerting) 4.dp else 2.dp, color, shape)
            .padding(20.dp)
    ) {
        Column(Modifier.align(Alignment.CenterStart)) {
            Text(position.label, style = CareType.Small)
            Text(title, style = CareType.Main)
            if (status.isNotEmpty()) {
                Text(
                    status + if (simulated) " (시뮬레이션)" else "",
                    style = CareType.Sub.copy(color = if (color == CareColors.TextDim) CareColors.TextSub else color)
                )
            }
        }
    }
}

/**
 * 입력 채널 ② 화면 마이크 버튼. 사용자가 누를 때만 듣는다.
 * 알아듣지 못하면 1-a: 같은 기능을 큰 버튼으로 보여주고, 10초 안에 고르지 않으면 조용히 닫는다.
 */
@Composable
private fun VoicePanel(
    voice: VoiceUi,
    onMicTap: () -> Unit,
    onCommand: (VoiceCommand) -> Unit,
    onDismiss: () -> Unit,
    onChooseRest: (RestCandidate) -> Unit,
    onUndoHospital: () -> Unit
) {
    when (voice) {
        is VoiceUi.RestResults -> RestPanel(voice.result, onChooseRest, onDismiss)

        is VoiceUi.HospitalStarted -> Panel(Modifier.fillMaxWidth()) {
            Text(voice.message, style = CareType.Sub.copy(color = CareColors.Alert))
            Spacer(Modifier.height(12.dp))
            SecondaryButton(
                voice.previousName?.let { "되돌리기 · $it" } ?: "되돌리기 · 안내 취소",
                onUndoHospital,
                modifier = Modifier.fillMaxWidth()
            )
        }

        VoiceUi.Idle -> Column {
            MicButton(onMicTap)
            Text(
                "재우기 · 멀미 · 쉬어갈 곳 · 다 왔어? · 편안한 길 · 병원",
                style = CareType.Small,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        VoiceUi.Listening -> Panel(Modifier.fillMaxWidth()) {
            Text("듣고 있어요…", style = CareType.Main.copy(color = CareColors.Primary))
            Text("예: \"아이 재워 줘\", \"멀미 나\"", style = CareType.Small)
            Spacer(Modifier.height(12.dp))
            SecondaryButton("취소", onDismiss, modifier = Modifier.fillMaxWidth())
        }

        is VoiceUi.Choices -> Panel(Modifier.fillMaxWidth()) {
            Text(voice.reason, style = CareType.Sub)
            Spacer(Modifier.height(12.dp))
            VoiceCommand.entries.chunked(4).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    row.forEach { cmd ->
                        ChoiceButton(cmd.label, { onCommand(cmd) }, Modifier.weight(1f))
                    }
                }
            }
            SecondaryButton("닫기", onDismiss, modifier = Modifier.fillMaxWidth())
        }

        is VoiceUi.Done -> Panel(Modifier.fillMaxWidth()) {
            Text(voice.message, style = CareType.Sub.copy(color = CareColors.Primary))
        }
    }
}

/**
 * M7 쉬어갈 곳 결과. 상위 2곳을 큰 카드로 보여주고, 누르면 다음 휴식지로 정한다.
 * 주행 중에 읽기 쉽도록 이름·거리·맞는 조건만 쓴다.
 */
@Composable
private fun RestPanel(result: RestResult, onChoose: (RestCandidate) -> Unit, onDismiss: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Text(if (result.immediate) "가까운 정차 지점" else "쉬어갈 곳", style = CareType.Main)
        val required = result.needs.filter { it.required }.map { it.label }
        val optional = result.needs.filter { !it.required }.map { it.label }
        Text(
            if (result.immediate) "멀미 · 화장실 있는 가장 가까운 곳" else buildString {
                append(if (required.isEmpty()) "필수 조건 없음" else "필수: " + required.joinToString(" · "))
                if (optional.isNotEmpty()) append("  가점: " + optional.joinToString(" · "))
            },
            style = CareType.Small
        )
        result.notes.forEach { note ->
            Text(note, style = CareType.Small.copy(color = CareColors.Caution))
        }
        if (result.relaxed.isNotEmpty()) {
            Text("가까이에 없어 조건을 줄였어요: " + result.relaxed.joinToString(" · "), style = CareType.Small.copy(color = CareColors.Caution))
        }
        Spacer(Modifier.height(10.dp))
        if (result.candidates.isEmpty()) {
            Text(
                result.nearestBeyond?.let { "가까운 곳이 없어요 · 가장 가까운 곳: ${it.area.displayName} ${it.distanceLabel}" }
                    ?: "조건에 맞는 곳을 찾지 못했어요",
                style = CareType.Sub.copy(color = CareColors.Caution)
            )
        }
        result.candidates.forEachIndexed { i, c ->
            val shape = RoundedCornerShape(CareSize.Radius)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .background(CareColors.Background, shape)
                    .border(2.dp, CareColors.Primary, shape)
                    .clickable { onChoose(c) }
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Text("${i + 1}  ${c.area.displayName}", style = CareType.Body)
                Text(
                    (listOf(c.distanceLabel) + c.matched).joinToString(" · "),
                    style = CareType.Sub.copy(color = CareColors.Primary)
                )
            }
        }
        SecondaryButton("닫기", onDismiss, modifier = Modifier.fillMaxWidth())
    }
}

/** 긴급 병원 버튼: 경보색 테두리, 한 번 누르면 바로 안내(되돌리기 20초 제공) */
@Composable
private fun EmergencyButton(hospitalName: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(CareSize.Radius)
    Box(
        Modifier
            .fillMaxWidth()
            .height(CareSize.Touch)
            .background(CareColors.Alert.copy(alpha = 0.12f), shape)
            .border(3.dp, CareColors.Alert, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("긴급 · $hospitalName", style = CareType.Sub.copy(color = CareColors.Alert), maxLines = 1)
    }
}

/** 1-a 응답 버튼: 4개가 한 줄에 들어가도록 최소 폭 없이, 높이는 터치 기준(76dp) 유지 */
@Composable
private fun ChoiceButton(text: String, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(CareSize.Radius)
    Box(
        modifier
            .height(CareSize.Touch)
            .background(CareColors.Primary, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = CareType.Sub.copy(color = CareColors.OnPrimary), maxLines = 1)
    }
}

private fun formatMin(m: Int): String = if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분"

/** 마이크 버튼: 하차 확인(채움 버튼)과 헷갈리지 않게 테두리형 + 더 큰 높이 */
@Composable
private fun MicButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(CareSize.Radius)
    Box(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .background(CareColors.Panel, shape)
            .border(3.dp, CareColors.Primary, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("🎤  말로 부탁하기", style = CareType.Main.copy(color = CareColors.Primary))
    }
}

/** 시연용 조작. 버추얼 콘솔에 좌석별 벨트가 없어서 앱에서 흉내 낸다. 화면에 '시연용'으로 표시. */
@Composable
private fun DemoStrip(
    driveSimulated: DriveState?,
    assignments: Map<SeatPosition, SeatAssignment>,
    demoPositionLabel: String,
    onSimulateDrive: (DriveState?) -> Unit,
    onSimulateBelt: (SeatPosition) -> Unit,
    onCycleDemoPosition: () -> Unit,
    env: EnvState,
    onCycleWeather: () -> Unit,
    roadSimulated: Boolean,
    onToggleRoad: () -> Unit,
    weatherSimLabel: String?,
    onAdvanceClock: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("시연용", style = CareType.Small.copy(color = CareColors.TextDim))
        DemoChip("주행", driveSimulated == DriveState.DRIVING) { onSimulateDrive(DriveState.DRIVING) }
        DemoChip("정차", driveSimulated == DriveState.PARKED) { onSimulateDrive(DriveState.PARKED) }
        DemoChip("기어 값", driveSimulated == null) { onSimulateDrive(null) }
        assignments.filter { (_, a) -> BeltAlarm.beltSensorApplies(a) }.forEach { (pos, a) ->
            DemoChip("${a.title()} 벨트", false) { onSimulateBelt(pos) }
        }
        DemoChip("위치: $demoPositionLabel", false, onCycleDemoPosition)
        DemoChip("날씨: " + (weatherSimLabel ?: "실제"), weatherSimLabel != null, onCycleWeather)
        DemoChip(if (roadSimulated) "도로: 앞 작업" else "도로: 실제", roadSimulated, onToggleRoad)
        DemoChip("시간 +30분", false, onAdvanceClock)
    }
}

@Composable
private fun DemoChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        text,
        style = CareType.Small.copy(color = if (selected) CareColors.OnPrimary else CareColors.TextSub),
        modifier = Modifier
            .background(if (selected) CareColors.Primary else Color.Transparent, shape)
            .border(1.dp, CareColors.PanelBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

private fun SeatAssignment.title(): String = when (this) {
    is SeatAssignment.Known -> passenger.name
    is SeatAssignment.Guest -> if (group.isChild) "게스트 아이" else "게스트 어른"
    SeatAssignment.Luggage -> "짐"
}

private fun SeatAssignment.isPet(): Boolean = when (this) {
    is SeatAssignment.Known -> passenger.group == PassengerGroup.PET
    is SeatAssignment.Guest -> group == PassengerGroup.PET
    SeatAssignment.Luggage -> false
}
