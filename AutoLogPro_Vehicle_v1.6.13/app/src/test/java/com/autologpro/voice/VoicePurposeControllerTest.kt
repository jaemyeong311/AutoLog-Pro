package com.autologpro.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.autologpro.app.Purpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoicePurposeControllerTest {
    private lateinit var ctx: Context
    private val clock = FakeScheduler()
    private var factoryCalls = 0
    private lateinit var out: FakeOutput
    private lateinit var input: FakeInput
    private lateinit var controller: VoicePurposeController
    private val results = mutableListOf<Pair<Long, VoiceOutcome>>()
    private val listener = VoicePurposeController.Listener { id, o -> results += id to o }

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("autolog_voice", Context.MODE_PRIVATE).edit().clear().commit()
        controller = VoicePurposeController(ctx, scheduler = clock, speech = {
            factoryCalls++
            out = FakeOutput(); input = FakeInput()
            out to input
        })
    }

    private fun listenAndAnswer(text: String) {
        out.listener.onDone()
        clock.advance(VoiceConfig().listenGuardDelayMs)
        input.listener.onFinalText(text)
    }

    @Test fun 기본은_꺼져_있어_묻지_않고_음성_엔진도_만들지_않는다() {
        assertFalse(controller.isEnabled)
        assertFalse(controller.startFor(-2, listener))
        assertEquals(0, factoryCalls)
    }

    @Test fun 켜면_그_운행의_분류_결과만_돌려준다() {
        controller.settings.enabled = true
        assertTrue(controller.startFor(-2, listener))
        assertEquals(listOf(VoiceConfig().prompt), out.spoken)
        listenAndAnswer("출퇴근용이요")
        assertEquals(listOf<Pair<Long, VoiceOutcome>>(-2L to VoiceOutcome.Classified(Purpose.COMMUTE)), results)
    }

    @Test fun 같은_운행은_한_번만_묻는다() {
        controller.settings.enabled = true
        assertTrue(controller.startFor(-2, listener))
        listenAndAnswer("개인용")
        assertFalse("이미 물은 운행", controller.startFor(-2, listener))
        assertEquals(1, factoryCalls)
        assertTrue("다른 운행은 물을 수 있다", controller.startFor(-4, listener))
    }

    @Test fun 팝업이_닫히면_취소하고_늦게_온_답은_저장하지_않는다() {
        controller.settings.enabled = true
        controller.startFor(-2, listener)
        controller.cancel()
        assertEquals(listOf<Pair<Long, VoiceOutcome>>(-2L to VoiceOutcome.Cancelled), results)
        assertEquals(1, out.cancelled)
        input.let { assertEquals(0, it.listening) }
    }

    @Test fun 진행_중에_새_운행을_묻기_전에_이전_세션을_취소한다() {
        controller.settings.enabled = true
        controller.startFor(-2, listener)
        controller.startFor(-4, listener)
        assertEquals(-2L to VoiceOutcome.Cancelled, results.first())
    }

    @Test fun 시연_초기화는_재사용된_시연_ID만_다시_허용한다() {
        controller.settings.enabled = true
        assertTrue(controller.startFor(-2, listener))
        listenAndAnswer("업무용")
        assertTrue(controller.startFor(-3, listener))
        controller.resetDemoHistory()
        assertFalse(controller.startFor(-2, listener))
        assertTrue(controller.startFor(-3, listener))
    }

    @Test fun 취소할_세션이_없어도_안전하다() {
        controller.cancel()
        assertTrue(results.isEmpty())
    }

    @Test fun 설정은_앱을_다시_열어도_유지된다() {
        controller.settings.enabled = true
        assertTrue(VoiceClassifySettings(ctx).enabled)
        controller.settings.enabled = false
        assertFalse(VoiceClassifySettings(ctx).enabled)
    }
}
