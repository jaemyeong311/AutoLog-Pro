package com.autologpro.voice

import android.content.Context
import com.autologpro.app.Purpose

/** 음성 분류 켜짐/꺼짐. 기본은 꺼짐이고, 켠 경우에만 주차 후 한 번 묻는다. */
class VoiceClassifySettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("autolog_voice", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    private companion object { const val KEY_ENABLED = "voice_classify_enabled" }
}

/** "출퇴근으로 저장했어요" 같은 화면 문구. 받침 유무에 맞춰 '으로/로'를 고른다. */
object VoiceMessages {
    fun saved(purpose: Purpose): String = if (purpose == Purpose.PERSONAL) "업무기록에서 제외했어요. 총주행거리에는 반영됩니다."
        else "${purpose.label}${euro(purpose.label)} 저장했어요"
    const val NOT_UNDERSTOOD = "잘 알아듣지 못했어요. 화면에서 선택해 주세요"

    /** 받침이 없거나 ㄹ 받침이면 '로', 그 밖의 받침이면 '으로'. */
    fun euro(word: String): String {
        val c = word.lastOrNull() ?: return "로"
        if (c !in '가'..'힣') return "로"
        val jong = (c - '가') % 28
        return if (jong == 0 || jong == 8) "로" else "으로"
    }
}

/**
 * 앱이 부르는 입구. 분류 결과만 [Listener]로 돌려주고, 운행 기록·DB·계기판·보고서는 건드리지 않는다.
 *  - 켜 둔 경우에만 시작한다(설정 [VoiceClassifySettings]).
 *  - 같은 운행은 한 번만 묻는다.
 *  - 진행 중인 세션이 있으면 먼저 취소한다.
 * 메인 스레드에서만 부른다.
 */
class VoicePurposeController(
    appContext: Context,
    val settings: VoiceClassifySettings = VoiceClassifySettings(appContext),
    private val scheduler: VoiceScheduler = MainHandlerScheduler(),
    private val config: VoiceConfig = VoiceConfig(),
    private val speech: () -> Pair<SpeechOutput, SpeechInput> = { PleosSpeechOutput(appContext) to PleosSpeechInput(appContext) },
) {
    fun interface Listener { fun onOutcome(tripId: Long, outcome: VoiceOutcome) }

    private var session: VoiceSession? = null
    private val asked = mutableSetOf<Long>()

    val isEnabled: Boolean get() = settings.enabled

    /** @return 실제로 묻기를 시작했으면 true. 꺼져 있거나 이미 물었던 운행이면 false. */
    fun startFor(tripId: Long, listener: Listener): Boolean {
        if (!settings.enabled || !asked.add(tripId)) return false
        cancel()
        val (out, input) = speech()
        val s = VoiceSession(out, input, scheduler, config) { outcome ->
            session = null
            listener.onOutcome(tripId, outcome)
        }
        session = s
        s.start()
        return true
    }

    fun resetDemoHistory() {
        cancel()
        asked.removeAll { it < 0 && it % 2L != 0L }
    }

    /** 팝업이 닫혔거나 주행이 시작되었을 때 부른다. 진행 중이 아니면 아무 일도 없다. */
    fun cancel() {
        session?.cancel()
        session = null
    }
}
