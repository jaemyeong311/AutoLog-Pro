package com.autologpro.voice

import com.autologpro.app.Purpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 가짜 시계: advance로 시간을 흘려보내며 예약된 작업을 실행한다. */
class FakeScheduler : VoiceScheduler {
    private class Task(val at: Long, val run: () -> Unit, var cancelled: Boolean = false)
    private val tasks = mutableListOf<Task>()
    var now = 0L; private set
    override fun postDelayed(delayMs: Long, task: () -> Unit): () -> Unit {
        val t = Task(now + delayMs, task); tasks += t
        return { t.cancelled = true }
    }
    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
            now = next.at; tasks.remove(next); next.run()
        }
        now = end
    }
}

class FakeOutput : SpeechOutput {
    val spoken = mutableListOf<String>()
    var cancelled = 0
    lateinit var listener: SpeechOutput.Listener
    override fun speak(text: String, listener: SpeechOutput.Listener) { spoken += text; this.listener = listener }
    override fun cancel() { cancelled++ }
}

class FakeInput : SpeechInput {
    var listening = 0
    var cancelled = 0
    lateinit var listener: SpeechInput.Listener
    override fun listen(listener: SpeechInput.Listener) { listening++; this.listener = listener }
    override fun cancel() { cancelled++ }
}

class VoiceSessionTest {
    private val clock = FakeScheduler()
    private val out = FakeOutput()
    private val input = FakeInput()
    private val outcomes = mutableListOf<VoiceOutcome>()
    private val config = VoiceConfig()
    private val session = VoiceSession(out, input, clock, config) { outcomes += it }

    /** 질문을 끝까지 읽어 주고 듣기가 시작되는 지점까지 진행한다. */
    private fun askAndStartListening() {
        session.start()
        out.listener.onDone()
        clock.advance(config.listenGuardDelayMs)
    }

    @Test fun 질문이_끝난_뒤에야_듣기를_시작한다() {
        session.start()
        assertEquals(listOf(config.prompt), out.spoken)
        assertEquals("읽는 동안에는 듣지 않는다", 0, input.listening)
        out.listener.onDone()
        assertEquals("끝난 직후에도 잠깐 기다린다", 0, input.listening)
        clock.advance(config.listenGuardDelayMs)
        assertEquals(1, input.listening)
        assertTrue(outcomes.isEmpty())
    }

    @Test fun 출퇴근용이요를_분류_결과로_넘긴다() {
        askAndStartListening()
        input.listener.onFinalText("출퇴근용이요")
        assertEquals(listOf<VoiceOutcome>(VoiceOutcome.Classified(Purpose.COMMUTE)), outcomes)
        assertEquals("끝나면 스피커와 마이크를 놓는다", 1, out.cancelled)
        assertEquals(1, input.cancelled)
    }

    @Test fun 일반업무_개인용_건너뛰기도_각각_넘긴다() {
        fun run(heard: String): VoiceOutcome {
            val o = FakeOutput(); val i = FakeInput(); val c = FakeScheduler(); var r: VoiceOutcome? = null
            val s = VoiceSession(o, i, c, config) { r = it }
            s.start(); o.listener.onDone(); c.advance(config.listenGuardDelayMs)
            i.listener.onFinalText(heard)
            return r!!
        }
        assertEquals(VoiceOutcome.Classified(Purpose.BUSINESS), run("일반업무요"))
        assertEquals(VoiceOutcome.Classified(Purpose.PERSONAL), run("개인용이요"))
        assertEquals(VoiceOutcome.Skipped, run("건너뛰기"))
    }

    @Test fun 거래처_방문을_말하면_일반업무_결과를_한번만_넘긴다() {
        askAndStartListening()
        input.listener.onFinalText("거래처 다녀왔어요")
        input.listener.onFinalText("미팅하고 왔어요")
        assertEquals(listOf<VoiceOutcome>(VoiceOutcome.Classified(Purpose.BUSINESS)), outcomes)
    }

    @Test fun 업무_단어가_있어도_목적이_충돌하면_변경하지_않는다() {
        askAndStartListening()
        val heard = "퇴근길에 거래처도 들렀어요"
        input.listener.onFinalText(heard)
        assertEquals(listOf<VoiceOutcome>(VoiceOutcome.NotUnderstood(heard)), outcomes)
    }

    @Test fun 알아듣지_못하면_미분류로_두고_다시_묻지_않는다() {
        askAndStartListening()
        input.listener.onFinalText("네 알겠습니다")
        assertEquals(listOf<VoiceOutcome>(VoiceOutcome.NotUnderstood("네 알겠습니다")), outcomes)
        clock.advance(60_000)
        assertEquals("다시 묻지 않는다", 1, out.spoken.size)
        assertEquals("다시 듣지 않는다", 1, input.listening)
    }

    @Test fun 답이_없으면_제한_시간_뒤_NoAnswer() {
        askAndStartListening()
        clock.advance(config.listenTimeoutMs - 1)
        assertTrue(outcomes.isEmpty())
        clock.advance(1)
        assertEquals(listOf<VoiceOutcome>(VoiceOutcome.NoAnswer), outcomes)
    }

    @Test fun 빈_문장은_NoAnswer() {
        askAndStartListening()
        input.listener.onFinalText("  ")
        assertEquals(listOf<VoiceOutcome>(VoiceOutcome.NoAnswer), outcomes)
    }

    @Test fun 최종_문장_없이_인식이_끝나면_잠깐_뒤_NoAnswer() {
        askAndStartListening()
        input.listener.onEnded()
        assertTrue(outcomes.isEmpty())
        clock.advance(config.endedGraceMs)
        assertEquals(listOf<VoiceOutcome>(VoiceOutcome.NoAnswer), outcomes)
    }

    @Test fun 인식이_끝났어도_그_뒤_최종_문장이_오면_받아들인다() {
        askAndStartListening()
        input.listener.onEnded()
        input.listener.onFinalText("출퇴근")
        assertEquals(listOf<VoiceOutcome>(VoiceOutcome.Classified(Purpose.COMMUTE)), outcomes)
        clock.advance(10_000)
        assertEquals("결과는 한 번만", 1, outcomes.size)
    }

    @Test fun 인식_시간초과는_NoAnswer_다른_오류는_Failed() {
        askAndStartListening()
        input.listener.onError("STT TIMEOUT", timedOut = true)
        assertEquals(VoiceOutcome.NoAnswer, outcomes.single())

        outcomes.clear()
        val o = FakeOutput(); val i = FakeInput(); val c = FakeScheduler()
        val s = VoiceSession(o, i, c, config) { outcomes += it }
        s.start(); o.listener.onDone(); c.advance(config.listenGuardDelayMs)
        i.listener.onError("STT UNAVAILABLE_LANGUAGE", timedOut = false)
        assertEquals(VoiceOutcome.Failed("STT UNAVAILABLE_LANGUAGE"), outcomes.single())
    }

    @Test fun 안내_음성_오류면_듣지_않고_Failed() {
        session.start()
        out.listener.onError("TTS ON_DEVICE_ENGINE_ERROR")
        assertEquals(listOf<VoiceOutcome>(VoiceOutcome.Failed("TTS ON_DEVICE_ENGINE_ERROR")), outcomes)
        clock.advance(config.listenGuardDelayMs + 1)
        assertEquals(0, input.listening)
    }

    @Test fun 안내_음성이_끝났다는_신호가_안_오면_Failed() {
        session.start()
        clock.advance(config.speakTimeoutMs)
        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is VoiceOutcome.Failed)
        assertEquals(0, input.listening)
    }

    @Test fun 질문하는_중_취소하면_듣지_않고_Cancelled() {
        session.start()
        session.cancel()
        assertEquals(listOf<VoiceOutcome>(VoiceOutcome.Cancelled), outcomes)
        out.listener.onDone() // 취소 뒤에 늦게 온 신호는 무시
        clock.advance(config.listenGuardDelayMs + config.listenTimeoutMs)
        assertEquals(0, input.listening)
        assertEquals(1, outcomes.size)
    }

    @Test fun 듣는_중_취소하면_늦게_온_답을_무시한다() {
        askAndStartListening()
        session.cancel()
        input.listener.onFinalText("출퇴근")
        assertEquals(listOf<VoiceOutcome>(VoiceOutcome.Cancelled), outcomes)
    }

    @Test fun 한_번_시작한_세션은_다시_시작할_수_없다() {
        session.start()
        assertFalse(runCatching { session.start() }.isSuccess)
    }

    @Test fun 어댑터가_해제_중_예외를_던져도_결과는_전달된다() {
        val throwing = object : SpeechOutput {
            lateinit var l: SpeechOutput.Listener
            override fun speak(text: String, listener: SpeechOutput.Listener) { l = listener }
            override fun cancel() = error("release 실패")
        }
        val s = VoiceSession(throwing, input, clock, config) { outcomes += it }
        s.start()
        s.cancel()
        assertEquals(listOf<VoiceOutcome>(VoiceOutcome.Cancelled), outcomes)
    }
    @Test fun 초기화_호출이_예외를_던져도_세션은_안전하게_종료된다() {
        val failureOut = object : SpeechOutput {
            override fun speak(text: String, listener: SpeechOutput.Listener) { error("SDK unavailable") }
            override fun cancel() {}
        }
        val failureIn = object : SpeechInput {
            override fun listen(listener: SpeechInput.Listener) { error("SDK unavailable") }
            override fun cancel() {}
        }
        val result = mutableListOf<VoiceOutcome>()
        val fake = FakeOutput()
        VoiceSession(failureOut, FakeInput(), FakeScheduler(), onOutcome = { result.add(it) }).start()
        val clock = FakeScheduler()
        VoiceSession(fake, failureIn, clock, onOutcome = { result.add(it) }).start()
        fake.listener.onDone()
        clock.advance(VoiceConfig().listenGuardDelayMs)
        assertEquals(2, result.size)
        assertTrue(result.all { it is VoiceOutcome.Failed })
    }
}
