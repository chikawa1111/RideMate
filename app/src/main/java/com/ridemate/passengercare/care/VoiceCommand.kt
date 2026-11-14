package com.ridemate.passengercare.care

/**
 * 사용자가 부를 수 있는 기능(입력 채널 ② 화면 마이크 버튼).
 * 앱이 먼저 묻지 않는다(P1). 알아듣지 못하면 같은 기능을 큰 버튼으로 보여준다(1-a, 예외 E08).
 */
enum class VoiceCommand(val label: String, val reply: String) {
    SLEEP("재우기", "재우기 모드를 켰어요. 실내등을 끄고 바람을 약하게 했어요."),
    MOTION("멀미", "멀미 모드를 켰어요. 바깥 공기로 바꾸고 바람을 세게 했어요."),
    QUIET("조용히", "조용히 모드를 켰어요. 안내 음성을 끄고 바람을 줄였어요. 안전 경보는 그대로 울려요."),
    RESTORE("원래대로", "원래 설정으로 돌렸어요."),
    REST("쉬어갈 곳", ""),
    ETA("다 왔어?", ""),
    COMFORT("편안한 길", ""),
    /** 기획서 S10·M9 긴급: 등록한 병원으로 바로 안내. "취소"로 되돌린다 */
    HOSPITAL("긴급 병원", "")
}

object VoiceParser {

    private val rules: List<Pair<VoiceCommand, List<String>>> = listOf(
        // "원래대로"를 먼저 본다: "재우기 꺼 줘"는 해제다
        VoiceCommand.RESTORE to listOf("원래", "해제", "꺼", "끄", "그만", "취소해"),
        // 긴급은 다른 말과 섞여도 먼저 잡는다
        VoiceCommand.HOSPITAL to listOf("병원", "응급", "진통", "양수", "구급"),
        VoiceCommand.MOTION to listOf("멀미", "속이", "속 안", "토할", "어지러", "울렁"),
        VoiceCommand.QUIET to listOf("조용", "시끄러", "소리 줄"),
        VoiceCommand.ETA to listOf("다 왔", "얼마나 남", "남았", "언제 도착", "멀었"),
        VoiceCommand.COMFORT to listOf("편안한 길", "편한 길", "공사 피해", "덜 흔들"),
        // "잠깐 쉬자"가 재우기로 가지 않게 쉬어갈 곳을 재우기보다 먼저 본다
        VoiceCommand.REST to listOf("쉬어", "쉬자", "쉴 곳", "휴게소", "화장실", "휴식", "쉼터", "수유"),
        VoiceCommand.SLEEP to listOf("재우", "재워", "자장", "잠들", "잠재", "졸려")
    )

    /** 여러 인식 후보 중 처음으로 규칙에 맞는 명령. 없으면 null → 버튼으로. */
    fun parse(candidates: List<String>): VoiceCommand? {
        for (text in candidates) {
            val t = text.replace(" ", "")
            for ((cmd, words) in rules) {
                if (words.any { t.contains(it.replace(" ", "")) }) return cmd
            }
        }
        return null
    }
}
