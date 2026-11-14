package com.autologpro.voice

import android.os.Handler
import android.os.Looper
import com.autologpro.app.Purpose

/** 음성 분류가 끝났을 때 앱에 넘기는 결과. 운행 기록을 만들거나 DB를 건드리는 값은 없다. */
sealed interface VoiceOutcome {
    /** 운전자가 목적을 말했다. 앱이 repo.classify(tripId, purpose)로 저장하면 된다. */
    data class Classified(val purpose: Purpose) : VoiceOutcome
    /** "건너뛰기"라고 말했다. 앱은 건너뛰기 버튼과 똑같이 팝업을 닫으면 된다. */
    data object Skipped : VoiceOutcome
    /** 들리긴 했지만 목적을 고를 수 없었다. 미분류 그대로 둔다. */
    data class NotUnderstood(val heard: String) : VoiceOutcome
    /** 제한 시간 안에 답이 없었다. 미분류 그대로 둔다. */
    data object NoAnswer : VoiceOutcome
    /** 음성 기능을 쓸 수 없었다(권한·엔진·마이크 등). 미분류 그대로 둔다. */
    data class Failed(val reason: String) : VoiceOutcome
    /** 팝업을 닫았거나 주행이 시작되어 중단했다. 앱은 아무것도 하지 않는다. */
    data object Cancelled : VoiceOutcome
}

data class VoiceConfig(
    val prompt: String = "이번 운행은 어떤 용도였나요? 거래처 방문이나 출퇴근처럼 편하게 말씀해 주세요.",
    /** 안내 음성이 끝났다는 신호를 기다리는 최대 시간. */
    val speakTimeoutMs: Long = 15_000,
    /** 안내가 끝난 뒤 듣기를 시작하기까지의 간격. 차량 스피커 소리가 마이크에 되돌아오는 것을 피한다. */
    val listenGuardDelayMs: Long = 400,
    /** 듣기를 시작한 뒤 답을 기다리는 최대 시간. */
    val listenTimeoutMs: Long = 8_000,
    /** 인식이 끝났다고 알렸는데 최종 문장이 없을 때 기다리는 시간. */
    val endedGraceMs: Long = 600,
)

/** 차량 스피커로 읽어 주는 쪽. 모든 콜백은 메인 스레드에서 불려야 한다. */
interface SpeechOutput {
    interface Listener {
        fun onDone()
        fun onError(reason: String)
    }
    fun speak(text: String, listener: Listener)
    /** 읽던 중이면 멈추고 SDK를 해제한다. 여러 번 불려도 된다. */
    fun cancel()
}

/** 차량 마이크로 듣는 쪽. 모든 콜백은 메인 스레드에서 불려야 한다. */
interface SpeechInput {
    interface Listener {
        /** 인식이 끝난 최종 문장. */
        fun onFinalText(text: String)
        /** 인식이 끝났다는 알림(최종 문장이 없을 수도 있다). */
        fun onEnded()
        /** timedOut=true면 말이 없어 시간이 다 된 경우. */
        fun onError(reason: String, timedOut: Boolean)
    }
    fun listen(listener: Listener)
    fun cancel()
}

/** 지연 실행. 테스트에서는 가짜 시계로 바꾼다. 돌려주는 함수를 부르면 취소된다. */
interface VoiceScheduler {
    fun postDelayed(delayMs: Long, task: () -> Unit): () -> Unit
}

class MainHandlerScheduler(private val handler: Handler = Handler(Looper.getMainLooper())) : VoiceScheduler {
    override fun postDelayed(delayMs: Long, task: () -> Unit): () -> Unit {
        val r = Runnable { task() }
        handler.postDelayed(r, delayMs)
        return { handler.removeCallbacks(r) }
    }
}

/**
 * 한 운행에 대해 "묻기 → (안내가 끝난 뒤) 듣기 → 해석" 을 한 번만 진행한다. 메인 스레드에서만 쓴다.
 * 어떤 경우에도 onOutcome은 정확히 한 번 불리고, 그 뒤에는 마이크와 스피커를 놓는다.
 */
class VoiceSession(
    private val output: SpeechOutput,
    private val input: SpeechInput,
    private val scheduler: VoiceScheduler,
    private val config: VoiceConfig = VoiceConfig(),
    private val onOutcome: (VoiceOutcome) -> Unit,
) {
    private enum class Phase { IDLE, ASKING, GUARD, LISTENING, FINISHED }

    private var phase = Phase.IDLE
    private var cancelTimer: (() -> Unit)? = null

    fun start() {
        check(phase == Phase.IDLE) { "이미 시작한 세션입니다" }
        phase = Phase.ASKING
        timer(config.speakTimeoutMs) { finish(VoiceOutcome.Failed("안내 음성이 끝나지 않았습니다")) }
        try { output.speak(config.prompt, object : SpeechOutput.Listener {
            override fun onDone() {
                if (phase != Phase.ASKING) return
                phase = Phase.GUARD
                timer(config.listenGuardDelayMs) { beginListening() }
            }
            override fun onError(reason: String) {
                if (phase == Phase.ASKING) finish(VoiceOutcome.Failed(reason))
            }
        }) } catch (e: Exception) {
            if (phase == Phase.ASKING) finish(VoiceOutcome.Failed("TTS 시작: ${e.javaClass.simpleName}"))
        }
    }

    fun cancel() = finish(VoiceOutcome.Cancelled)

    private fun beginListening() {
        if (phase != Phase.GUARD) return
        phase = Phase.LISTENING
        timer(config.listenTimeoutMs) { finish(VoiceOutcome.NoAnswer) }
        try { input.listen(object : SpeechInput.Listener {
            override fun onFinalText(text: String) {
                if (phase != Phase.LISTENING) return
                finish(interpret(text))
            }
            override fun onEnded() {
                if (phase != Phase.LISTENING) return
                timer(config.endedGraceMs) { finish(VoiceOutcome.NoAnswer) }
            }
            override fun onError(reason: String, timedOut: Boolean) {
                if (phase != Phase.LISTENING) return
                finish(if (timedOut) VoiceOutcome.NoAnswer else VoiceOutcome.Failed(reason))
            }
        }) } catch (e: Exception) {
            if (phase == Phase.LISTENING) finish(VoiceOutcome.Failed("STT 시작: ${e.javaClass.simpleName}"))
        }
    }

    private fun interpret(text: String): VoiceOutcome {
        if (text.isBlank()) return VoiceOutcome.NoAnswer
        return when (val a = SpokenPurposeParser.parse(text)) {
            is SpokenAnswer.PurposeAnswer -> VoiceOutcome.Classified(a.purpose)
            SpokenAnswer.Skip -> VoiceOutcome.Skipped
            is SpokenAnswer.Unrecognized -> VoiceOutcome.NotUnderstood(a.heard)
        }
    }

    private fun timer(delayMs: Long, task: () -> Unit) {
        cancelTimer?.invoke()
        cancelTimer = scheduler.postDelayed(delayMs, task)
    }

    private fun finish(outcome: VoiceOutcome) {
        if (phase == Phase.FINISHED) return
        phase = Phase.FINISHED
        cancelTimer?.invoke()
        cancelTimer = null
        runCatching { output.cancel() }
        runCatching { input.cancel() }
        onOutcome(outcome)
    }
}
