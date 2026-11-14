package com.ridemate.passengercare.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 디자인 가이드(2026.11.08) 색상 토큰.
 * 화면 코드는 색 값을 직접 쓰지 말고 아래 이름만 사용한다.
 */
object CareColors {
    val Background = Color(0xFF101B26)   // 배경
    val Panel = Color(0xFF1D3041)        // 정보 패널
    val PanelBorder = Color(0xFF2E4458)
    val Text = Color(0xFFF2F6F9)         // 주요 글자
    val TextSub = Color(0xFFAFC0CE)      // 보조 글자
    val TextDim = Color(0xFF6E8396)      // 비활성
    val Primary = Color(0xFF71D9D0)      // 확인·주요 행동
    val OnPrimary = Color(0xFF0E2A2E)
    val Caution = Color(0xFFFFC56B)      // 확인 필요(추정)
    val Alert = Color(0xFFFF8585)        // 안전 경보
    val Divider = Color(0xFF2A3D4F)
}

/** 가이드 크기 기준: 터치 76dp, 주요 글자 32, 보조 24. */
object CareSize {
    val Touch = 76.dp
    val Radius = 18.dp
    val Gap = 24.dp
    val ScreenPadding = 40.dp
}

object CareType {
    val Title = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold, color = CareColors.Text)
    val Main = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = CareColors.Text)
    val Body = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Medium, color = CareColors.Text)
    val Sub = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium, color = CareColors.TextSub)
    val Small = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, color = CareColors.TextSub)
}

// 좌석 모니터(개발용) 화면에서 쓰는 예전 이름 — 같은 토큰을 가리킨다.
val Navy = CareColors.Primary
val SafeGreen = CareColors.Primary
val AlertRed = CareColors.Alert
val Muted = CareColors.TextDim

private val colors = darkColorScheme(
    primary = CareColors.Primary,
    onPrimary = CareColors.OnPrimary,
    secondary = CareColors.Caution,
    error = CareColors.Alert,
    background = CareColors.Background,
    surface = CareColors.Panel,
    onBackground = CareColors.Text,
    onSurface = CareColors.Text
)

@Composable
fun RideMateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        content = content
    )
}
