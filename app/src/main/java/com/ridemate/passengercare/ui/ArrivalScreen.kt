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
import com.ridemate.passengercare.care.ExitAlarm
import com.ridemate.passengercare.care.ExitAlert
import com.ridemate.passengercare.data.PassengerGroup
import com.ridemate.passengercare.data.SeatAssignment
import com.ridemate.passengercare.env.EnvState
import com.ridemate.passengercare.env.Weather
import com.ridemate.passengercare.ui.components.CareFrame
import com.ridemate.passengercare.ui.components.Panel
import com.ridemate.passengercare.ui.components.PrimaryButton
import com.ridemate.passengercare.ui.theme.CareColors
import com.ridemate.passengercare.ui.theme.CareSize
import com.ridemate.passengercare.ui.theme.CareType
import com.ridemate.passengercare.vehicle.SeatPosition
import com.ridemate.passengercare.vehicle.SeatState
import com.ridemate.passengercare.vehicle.VehicleSeats

/**
 * 화면 08 · 도착 · 하차 확인.
 * 동승자마다 "내림"을 좌석 센서로 자동 체크하고, 센서로 알 수 없으면 운전자가 눌러 확인한다.
 * 보호 대상이 남은 채 운전자가 내리고 문이 모두 닫히면 A2 경보.
 */
@Composable
fun ArrivalScreen(
    assignments: Map<SeatPosition, SeatAssignment>,
    seats: Map<SeatPosition, SeatState>,
    confirmedOut: Set<SeatPosition>,
    belongingsChecked: Boolean,
    alert: ExitAlert?,
    onToggleOut: (SeatPosition) -> Unit,
    onToggleBelongings: () -> Unit,
    env: EnvState,
    envChecked: Set<String>,
    onToggleEnvItem: (String) -> Unit,
    sleptThisTrip: Boolean,
    onFinish: () -> Unit,
    onBack: () -> Unit
) {
    val people = assignments
        .filter { (_, a) -> a !is SeatAssignment.Luggage }
        .toSortedMap(compareBy { it.ordinal })
    // M12: 날씨에 따라 하차 항목이 늘어난다 (화면만, 소리 없음)
    val careful = people.values.any { a ->
        val g = when (a) {
            is SeatAssignment.Known -> a.passenger.group
            is SeatAssignment.Guest -> a.group
            SeatAssignment.Luggage -> null
        }
        g == PassengerGroup.INFANT || g == PassengerGroup.PRESCHOOL || g == PassengerGroup.SCHOOL ||
            g == PassengerGroup.SENIOR || g == PassengerGroup.PREGNANT || g == PassengerGroup.MOBILITY
    }
    fun has(g: PassengerGroup) = people.values.any {
        (it as? SeatAssignment.Known)?.passenger?.group == g || (it as? SeatAssignment.Guest)?.group == g
    }
    val envItems = buildList {
        // 동승자별 하차 항목 (기획서 2장 G4·G6, #수면중)
        if (has(PassengerGroup.MOBILITY)) add(Triple("mobility", "휠체어·보조기구 꺼내기", "옆 차와 간격 · 하차 공간 먼저 확인"))
        if (has(PassengerGroup.SENIOR)) add(Triple("senior", "천천히 내리기", "손잡이 잡고 · 발밑 확인"))
        if (sleptThisTrip && people.values.any { a ->
                val g = (a as? SeatAssignment.Known)?.passenger?.group ?: (a as? SeatAssignment.Guest)?.group
                g?.isChild == true
            }
        ) add(Triple("sleep", "자는 아이 깨우지 않고 옮기기", "재우기 모드를 썼어요 · 카시트째 옮길지 먼저 정하기"))
        if (env.umbrella) add(Triple("umbrella", "우산 챙기기", "${env.weather?.label ?: "비"} 예보 · 내리기 전에 꺼내 두기"))
        if ((env.iceRisk || env.weather == Weather.SNOW) && careful) {
            add(Triple("slip", "내릴 때 바닥 미끄럼 주의", (if (env.iceRisk) "결빙 위험(추정)" else "눈") + " · 아이 손 잡고 천천히"))
        }
    }
    val remaining = people.count { (pos, _) -> !ExitAlarm.isOut(pos, seats, confirmedOut) } +
        (if (belongingsChecked) 0 else 1) + envItems.count { it.first !in envChecked }

    CareFrame(stateLabel = "도착", onBack = onBack) {
        if (alert != null) {
            ExitAlertBanner(alert)
            Spacer(Modifier.height(20.dp))
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Column(
                Modifier
                    .weight(1.2f)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("하차 확인", style = CareType.Title)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (remaining == 0) "모두 내렸어요." else "확인할 항목이 ${remaining}개 남았어요.",
                    style = CareType.Sub.copy(color = if (remaining == 0) CareColors.Primary else CareColors.Caution)
                )
                Spacer(Modifier.height(24.dp))
                people.forEach { (pos, a) ->
                    val sensorOut = seats[pos]?.occupied == false
                    val done = ExitAlarm.isOut(pos, seats, confirmedOut)
                    ExitRow(
                        title = "${a.title()} 내림",
                        detail = pos.label + if (ExitAlarm.isProtected(a)) " · 보호 대상" else "",
                        status = when {
                            sensorOut -> "센서 · 자리 비었음"
                            pos in confirmedOut -> "확인함"
                            seats[pos]?.occupied == true -> "센서 · 아직 앉아 있음"
                            else -> "눌러서 확인"
                        },
                        done = done,
                        important = ExitAlarm.isProtected(a),
                        onClick = if (sensorOut) null else ({ onToggleOut(pos) })
                    )
                }
                ExitRow(
                    title = "뒷좌석 소지품",
                    detail = "가방 · 휴대폰 · 장난감",
                    status = if (belongingsChecked) "확인함" else "눌러서 확인",
                    done = belongingsChecked,
                    important = false,
                    onClick = onToggleBelongings
                )
                envItems.forEach { (id, title, detail) ->
                    ExitRow(
                        title = title,
                        detail = detail + if (env.simulated && (id == "umbrella" || id == "slip")) " (시뮬레이션)" else "",
                        status = if (id in envChecked) "확인함" else "눌러서 확인",
                        done = id in envChecked,
                        important = false,
                        onClick = { onToggleEnvItem(id) }
                    )
                }
                Spacer(Modifier.height(28.dp))
                PrimaryButton(
                    if (remaining == 0) "운행 종료" else "남은 항목을 확인해 주세요",
                    onFinish,
                    enabled = remaining == 0 && alert == null,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Panel(Modifier.fillMaxWidth()) {
                    Text("문 · 좌석", style = CareType.Main)
                    Spacer(Modifier.height(12.dp))
                    VehicleSeats.active.forEach { pos ->
                        val st = seats[pos]
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(pos.label, style = CareType.Sub, modifier = Modifier.weight(1f))
                            Text(
                                (when (st?.occupied) { true -> "착석"; false -> "빈 자리"; null -> "모름" }) +
                                    " · 문 " + (when (st?.doorOpen) { true -> "열림"; false -> "닫힘"; null -> "모름" }),
                                style = CareType.Small.copy(
                                    color = if (st?.doorOpen == true) CareColors.Caution else CareColors.TextSub
                                )
                            )
                        }
                    }
                }
                Panel(Modifier.fillMaxWidth()) {
                    Text("날씨·도로", style = CareType.Main)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        env.summary + (if (env.simulated) " (시뮬레이션)" else "") + (if (env.stale) " · 최신 아님" else ""),
                        style = CareType.Sub.copy(color = if (env.hasCaution) CareColors.Caution else CareColors.TextSub)
                    )
                    if (env.weather == Weather.HEAT) {
                        Text(
                            "폭염 · 차 안 온도가 빠르게 올라요. 아이·반려동물을 잠깐이라도 차에 두지 마세요.",
                            style = CareType.Sub.copy(color = CareColors.Alert),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                Panel(Modifier.fillMaxWidth()) {
                    Text("꼭 알아두세요", style = CareType.Main)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "영유아·미취학 아이·반려동물이 남은 채로 운전자가 내리고 문이 닫히면 이름을 불러 알려요.\n" +
                            "시동을 끈 뒤에는 앱이 동작하지 않아요. 차량의 후석 승객 알림을 함께 켜 두세요.",
                        style = CareType.Small
                    )
                }
            }
        }
    }
}

@Composable
fun ExitAlertBanner(alert: ExitAlert) {
    val shape = RoundedCornerShape(CareSize.Radius)
    Column(
        Modifier
            .fillMaxWidth()
            .background(CareColors.Alert.copy(alpha = 0.16f), shape)
            .border(3.dp, CareColors.Alert, shape)
            .padding(horizontal = 28.dp, vertical = 18.dp)
    ) {
        Text(alert.title, style = CareType.Title.copy(color = CareColors.Alert))
        Text(alert.detail, style = CareType.Sub.copy(color = CareColors.Text))
    }
}

@Composable
private fun ExitRow(
    title: String,
    detail: String,
    status: String,
    done: Boolean,
    important: Boolean,
    onClick: (() -> Unit)?
) {
    var rowModifier = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = CareSize.Touch)
    if (onClick != null) rowModifier = rowModifier.clickable(onClick = onClick)
    val pending = if (important) CareColors.Alert else CareColors.Caution
    Column {
        Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .size(40.dp)
                    .background(if (done) CareColors.Primary else Color.Transparent, shape)
                    .border(3.dp, if (done) CareColors.Primary else pending, shape),
                contentAlignment = Alignment.Center
            ) {
                if (done) Text("✓", style = CareType.Body.copy(color = CareColors.OnPrimary))
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = CareType.Body)
                Text(detail, style = CareType.Small)
            }
            Text(status, style = CareType.Small.copy(color = if (done) CareColors.Primary else pending))
        }
        HorizontalDivider(color = CareColors.Divider)
    }
}

private fun SeatAssignment.title(): String = when (this) {
    is SeatAssignment.Known -> passenger.name
    is SeatAssignment.Guest -> if (group.isChild) "게스트 아이" else "게스트 어른"
    SeatAssignment.Luggage -> "짐"
}
