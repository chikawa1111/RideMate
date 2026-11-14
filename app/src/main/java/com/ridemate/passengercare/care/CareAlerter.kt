package com.ridemate.passengercare.care

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 경보 소리. 짧은 경고음 + 이름 부르기(TTS).
 * Pleos TTS는 콘솔에서 아직 "Coming Soon"이라 안드로이드 기본 TTS를 쓴다.
 * 한국어 음성이 없으면 경고음만 낸다(화면 경보는 항상 표시).
 *
 * - softMode(#수면중): 재우기 모드 중에는 경고음을 작게. 경보 자체는 끄지 않는다.
 * - repliesMuted(조용히): 사용자가 부른 기능의 응답 음성만 끈다. 안전 경보는 그대로.
 * - 예외 E10: 음성은 안내 용도(ASSISTANCE_NAVIGATION_GUIDANCE)로 내보내 내비·미디어와 겹치면 시스템이 조정한다.
 */
class CareAlerter(context: Context, private val log: (String) -> Unit) {

    private var ttsReady = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val r = engine().setLanguage(Locale.KOREAN)
            ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            engine().setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            log("TTS 준비 · 한국어 ${if (ttsReady) "사용 가능" else "음성 없음 → 경고음만"}")
        } else {
            log("TTS 초기화 실패 → 경고음만")
        }
    }

    private fun engine() = tts

    private val toneLoud: ToneGenerator? = tone(90)
    private val toneSoft: ToneGenerator? = tone(45)

    var softMode: Boolean = false
    var repliesMuted: Boolean = false

    private fun tone(volume: Int) = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, volume) }
        .onFailure { Log.w("CareAlerter", "ToneGenerator 실패", it) }
        .getOrNull()

    fun alert(spoken: String) {
        (if (softMode) toneSoft else toneLoud)?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600)
        if (ttsReady) {
            tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "care-alert")
        }
        log("경보 · \"$spoken\"${if (ttsReady) "" else " (음성 없음, 경고음만)"}${if (softMode) " · 작은 소리" else ""}")
    }

    /** 사용자가 부른 기능에 대한 짧은 응답. 경고음 없이 말만(음성이 없으면 화면 표시만). */
    fun say(text: String) {
        if (ttsReady && !repliesMuted) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "care-reply")
    }

    fun release() {
        runCatching { tts.stop(); tts.shutdown() }
        runCatching { toneLoud?.release() }
        runCatching { toneSoft?.release() }
    }
}
