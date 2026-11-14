package com.autologpro.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode

/**
 * Pleos SDK(3.3.1)를 부르는 부분. 한국어 온디바이스(ON_DEVICE)가 기본이다.
 * HYBRID·SERVER는 Pleos My Project 앱 등록이 필요해서 첫 버전에서는 쓰지 않는다.
 * SDK 콜백이 어느 스레드로 오든 메인 스레드로 옮겨서 VoiceSession에 전달한다.
 * 초기화 성공 후 리스너와 언어를 설정하고 요청한다. onReady는 상태 알림이다.
 */

class PleosSpeechOutput(
    private val context: Context,
    private val mode: TtsMode = TtsMode.ON_DEVICE,
    private val locale: Locale = Locale.KOREA,
    private val main: Handler = Handler(Looper.getMainLooper()),
) : SpeechOutput {
    private var engine: TextToSpeech? = null
    private var events: EventListener? = null
    private var released = false

    override fun speak(text: String, listener: SpeechOutput.Listener) {
        val tts = TextToSpeech(context, mode)
        engine = tts
        var spoken = false
        fun speakOnce() {
            if (released || spoken) return
            spoken = true
            runCatching { tts.speak(text) }.onFailure { listener.onError(it.message ?: "speak 실패") }
        }
        val e = object : EventListener {
            override fun onReady() {}
            override fun onStart() {}
            override fun onUpdatedRms(rms: Double) {}
            override fun onDone() { main.post { if (!released) listener.onDone() } }
            override fun onStop() {}
            override fun onError(errorType: TtsError) { main.post { if (!released) listener.onError("TTS ${errorType.name}") } }
        }
        events = e
        tts.initialize(object : TtsInitListener {
            override fun onSuccess() {
                main.post {
                    if (released) return@post
                    runCatching {
                        tts.addEventListener(e)
                        tts.setLocale(locale)
                        speakOnce()
                    }.onFailure { listener.onError("TTS 설정: ${it.javaClass.simpleName}") }
                }
            }
            override fun onFailure(errorType: TtsError) { main.post { if (!released) listener.onError("TTS 초기화 ${errorType.name}") } }
        })
    }

    override fun cancel() {
        if (released) return
        released = true
        val tts = engine ?: return
        runCatching { events?.let { tts.removeEventListener(it) } }
        runCatching { tts.stop() }
        runCatching { tts.release() }
        engine = null
    }
}

class PleosSpeechInput(
    private val context: Context,
    private val mode: SttMode = SttMode.ON_DEVICE,
    private val locale: Locale = Locale.KOREA,
    private val main: Handler = Handler(Looper.getMainLooper()),
) : SpeechInput {
    private var engine: SpeechToText? = null
    private var results: ResultListener? = null
    private var released = false

    override fun listen(listener: SpeechInput.Listener) {
        val stt = SpeechToText(context, mode)
        engine = stt
        var requested = false
        fun requestOnce() {
            if (released || requested) return
            requested = true
            runCatching { stt.request() }.onFailure { listener.onError(it.message ?: "request 실패", false) }
        }
        val r = object : ResultListener {
            override fun onReady() {}
            override fun onStartedRecognition() {}
            override fun onUpdated(stt: String, completed: Boolean) {
                if (completed) main.post { if (!released) listener.onFinalText(stt) }
            }
            override fun onUpdatedEpdData(on: Long, off: Long) {}
            override fun onUpdatedRms(rms: Double) {}
            override fun onEndedRecognition() { main.post { if (!released) listener.onEnded() } }
            override fun onError(errorType: SttError) {
                main.post { if (!released) listener.onError("STT ${errorType.name}", errorType == SttError.TIMEOUT) }
            }
        }
        results = r
        stt.initialize(object : SttInitListener {
            override fun onSuccess() {
                main.post {
                    if (released) return@post
                    runCatching {
                        stt.addListener(r)
                        stt.setLocale(locale)
                        requestOnce()
                    }.onFailure { listener.onError("STT 설정: ${it.javaClass.simpleName}", false) }
                }
            }
            override fun onFailure(errorType: SttError) { main.post { if (!released) listener.onError("STT 초기화 ${errorType.name}", false) } }
        })
    }

    override fun cancel() {
        if (released) return
        released = true
        val stt = engine ?: return
        runCatching { results?.let { stt.removeListener(it) } }
        runCatching { stt.stop() }
        runCatching { stt.release() }
        engine = null
    }
}
