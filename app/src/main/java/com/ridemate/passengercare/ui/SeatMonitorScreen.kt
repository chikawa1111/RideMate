package com.ridemate.passengercare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridemate.passengercare.BuildConfig
import com.ridemate.passengercare.ui.theme.AlertRed
import com.ridemate.passengercare.ui.theme.CareColors
import com.ridemate.passengercare.ui.theme.Muted
import com.ridemate.passengercare.ui.theme.Navy
import com.ridemate.passengercare.ui.theme.SafeGreen
import com.ridemate.passengercare.vehicle.SeatPosition
import com.ridemate.passengercare.vehicle.SeatState

/**
 * 1단계 확인용 화면: 좌석별 착석·벨트 상태를 실시간으로 보여준다.
 * 버추얼 콘솔에서 Seat Occupancy를 바꾸면 카드가 바로 바뀌어야 한다.
 */
@Composable
fun SeatMonitorScreen(
    seats: Map<SeatPosition, SeatState>,
    log: List<String>,
    invertOccupancy: Boolean,
    onToggleInvert: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onSimulateBelt: (SeatPosition, Boolean) -> Unit,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(CareColors.Background)
            .padding(32.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        Column(modifier = Modifier.weight(2f)) {
            Text("동승케어", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Navy)
            Text(
                "좌석 모니터 · Vehicle SDK ${BuildConfig.VEHICLE_SDK_VERSION}",
                fontSize = 16.sp,
                color = Muted
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onBack) { Text("‹ 홈으로") }
                OutlinedButton(onClick = { onToggleInvert(!invertOccupancy) }) {
                    Text("착석 값 반전 보정: ${if (invertOccupancy) "켬" else "끔"}")
                }
                OutlinedButton(onClick = onRefresh) { Text("좌석 다시 읽기") }
            }
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SeatCard(SeatPosition.FRONT_LEFT, seats, onSimulateBelt, Modifier.weight(1f))
                SeatCard(SeatPosition.FRONT_RIGHT, seats, onSimulateBelt, Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SeatCard(SeatPosition.REAR_LEFT, seats, onSimulateBelt, Modifier.weight(1f))
                SeatCard(SeatPosition.REAR_RIGHT, seats, onSimulateBelt, Modifier.weight(1f))
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(CareColors.Panel, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Text("SDK 로그", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CareColors.Text)
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                items(log) { line ->
                    Text(line, fontSize = 13.sp, color = CareColors.Text, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun SeatCard(
    position: SeatPosition,
    seats: Map<SeatPosition, SeatState>,
    onSimulateBelt: (SeatPosition, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val state = seats[position] ?: SeatState()
    val occupied = state.occupied == true
    val warning = occupied && state.belted == false
    val borderColor = when {
        warning -> AlertRed
        occupied -> SafeGreen
        else -> CareColors.PanelBorder
    }

    Column(
        modifier = modifier
            .background(CareColors.Panel, RoundedCornerShape(16.dp))
            .border(3.dp, borderColor, RoundedCornerShape(16.dp))
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(14.dp)
                    .background(if (occupied) SafeGreen else Muted, RoundedCornerShape(7.dp))
            )
            Spacer(Modifier.width(8.dp))
            Text(position.label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = CareColors.Text)
        }
        Spacer(Modifier.height(12.dp))
        Text("착석: ${state.occupied.describe("앉아 있음", "비어 있음")}", fontSize = 16.sp, color = CareColors.Text)
        Text(
            "벨트: ${state.belted.describe("맴", "안 맴")}",
            fontSize = 16.sp,
            color = if (warning) AlertRed else CareColors.Text
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { onSimulateBelt(position, state.belted != true) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.belted == true) "벨트 풀기 (시뮬)" else "벨트 매기 (시뮬)")
        }
    }
}

private fun Boolean?.describe(yes: String, no: String) = when (this) {
    true -> yes
    false -> no
    null -> "확인 중"
}
