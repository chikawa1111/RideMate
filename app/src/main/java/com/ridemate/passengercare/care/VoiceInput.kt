package com.ridemate.passengercare.care

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent

/**
 * 음성 인식(STT) 한 번 듣기.
 *
 * 앱이 직접 마이크를 열지 않는다(RECORD_AUDIO 미선언).
 * Pleos 에뮬레이터 실험 결과(2026-11-12): RECORD_AUDIO를 선언하면 distractionOptimized를 선언해도
 * 주행 중 앱 전체가 차단된다. 그래서 기기의 음성 인식 화면(RecognizerIntent)을 띄워 결과만 받는다.
 * 그 화면이 없는 차(에뮬레이터 포함)에서는 곧바로 1-a 버튼으로 고른다.
 * Pleos STT · Gleo App Schema가 열리면 이 클래스만 바꾼다.
 */
class VoiceInput(private val context: Context) {

    fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "재우기 · 멀미 · 쉬어갈 곳 · 원래대로")
    }

    /** 음성 인식 화면이 있으면 null, 없으면 이유 */
    fun unavailableReason(): String? =
        if (context.packageManager.queryIntentActivities(intent(), 0).isEmpty()) "이 차에서 음성 인식을 쓸 수 없어요"
        else null
}
