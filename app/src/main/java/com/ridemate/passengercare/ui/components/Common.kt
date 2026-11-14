package com.ridemate.passengercare.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ridemate.passengercare.ui.theme.CareColors
import com.ridemate.passengercare.ui.theme.CareSize
import com.ridemate.passengercare.ui.theme.CareType

/**
 * 모든 화면의 공통 골격: 상단 상태 줄 → 가운데 내용.
 * stateLabel 예: "정차 전용", "주행 중", "사용자 호출 후"
 */
@Composable
fun CareFrame(
    stateLabel: String,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CareColors.Background)
            .padding(horizontal = CareSize.ScreenPadding, vertical = 28.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                Text(
                    "‹ 뒤로",
                    style = CareType.Sub,
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(end = 24.dp, top = 8.dp, bottom = 8.dp)
                )
            }
            Text("동승자 케어", style = CareType.Small, modifier = Modifier.weight(1f))
            Text(stateLabel, style = CareType.Small)
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = CareColors.Divider)
        Spacer(Modifier.height(32.dp))
        content()
    }
}

/** 정보 패널(둥근 사각형). */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    borderColor: Color = CareColors.PanelBorder,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(CareSize.Radius)
    var m = modifier
        .background(CareColors.Panel, shape)
        .border(2.dp, borderColor, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(modifier = m.padding(24.dp), content = content)
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(CareSize.Radius)
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 200.dp, minHeight = CareSize.Touch)
            .background(if (enabled) CareColors.Primary else CareColors.TextDim, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = CareType.Body.copy(color = CareColors.OnPrimary))
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(CareSize.Radius)
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 200.dp, minHeight = CareSize.Touch)
            .border(2.dp, CareColors.TextSub, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = CareType.Body, textAlign = TextAlign.Center)
    }
}

/** 공통 표시: 추정 / 확인 / 미확인 / 경보 */
enum class Certainty(val label: String, val color: Color) {
    ESTIMATED("추정 · 확인 필요", CareColors.Caution),
    CONFIRMED("탑승 확인", CareColors.Primary),
    UNKNOWN("미확인", CareColors.TextDim),
    ALERT("확인 필요", CareColors.Alert)
}

@Composable
fun StatusChip(text: String, color: Color = CareColors.Primary) {
    Text(
        text,
        style = CareType.Small.copy(color = color),
        modifier = Modifier
            .background(CareColors.Background, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    )
}

/** 목록 한 줄: 왼쪽 이름, 오른쪽 값. 같은 높이·순서로 반복(R6). */
@Composable
fun ListRow(title: String, value: String, valueColor: Color = CareColors.TextSub, onClick: (() -> Unit)? = null) {
    Column {
        var m = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = CareSize.Touch)
        if (onClick != null) m = m.clickable(onClick = onClick)
        Row(m, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = CareType.Body, modifier = Modifier.weight(1f))
            Text(value, style = CareType.Sub.copy(color = valueColor))
        }
        HorizontalDivider(color = CareColors.Divider)
    }
}

/** 여러 개 중 하나를 고르는 큰 선택 버튼 줄. */
@Composable
fun <T> ChoiceRow(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            val shape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 120.dp, minHeight = 64.dp)
                    .background(if (isSelected) CareColors.Primary else CareColors.Panel, shape)
                    .border(2.dp, if (isSelected) CareColors.Primary else CareColors.PanelBorder, shape)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label(option),
                    style = CareType.Sub.copy(color = if (isSelected) CareColors.OnPrimary else CareColors.Text)
                )
            }
        }
    }
}
