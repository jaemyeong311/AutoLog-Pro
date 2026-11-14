package com.autologpro.voice

import com.autologpro.app.Purpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenPurposeParserTest {
    private fun purpose(s: String) = (SpokenPurposeParser.parse(s) as? SpokenAnswer.PurposeAnswer)?.purpose

    @Test fun 출퇴근은_여러_말투로_알아듣는다() {
        listOf("출퇴근용이요", "출퇴근", "출퇴근 용이요.", "출 퇴근이요", "퇴근했어요", "출근용입니다").forEach {
            assertEquals(it, Purpose.COMMUTE, purpose(it))
        }
    }

    @Test fun 일반업무는_업무와_일반업무_모두_알아듣는다() {
        listOf("일반업무", "일반 업무요", "업무용이요", "업무", "비즈니스").forEach {
            assertEquals(it, Purpose.BUSINESS, purpose(it))
        }
    }

    @Test fun 개인용은_개인_비업무_모두_알아듣고_업무로_오해하지_않는다() {
        listOf("개인용이요", "개인", "개인적인 용무예요", "비업무", "비업무용이요", "사적인 이동").forEach {
            assertEquals(it, Purpose.PERSONAL, purpose(it))
        }
    }

    @Test fun 건너뛰기는_여러_말투로_알아듣는다() {
        listOf("건너뛰기", "건너뛸게요", "스킵", "패스할게요", "나중에 할게요", "넘어가요").forEach {
            assertTrue(it, SpokenPurposeParser.parse(it) is SpokenAnswer.Skip)
        }
    }

    @Test fun 목적과_상관없는_말은_고르지_않는다() {
        listOf("", "   ", "네", "아니요", "잘 모르겠어요", "음...", "날씨 어때요").forEach {
            assertTrue(it, SpokenPurposeParser.parse(it) is SpokenAnswer.Unrecognized)
        }
    }

    @Test fun 둘_이상이_들리면_고르지_않는다() {
        listOf("출퇴근 말고 업무", "업무인데 출퇴근도 했어요", "출퇴근은 건너뛸게요").forEach {
            assertTrue(it, SpokenPurposeParser.parse(it) is SpokenAnswer.Unrecognized)
        }
    }

    @Test fun 부정어가_섞이면_고르지_않는다() {
        listOf("개인용 아니에요", "출퇴근 아니고", "업무 아닙니다").forEach {
            assertTrue(it, SpokenPurposeParser.parse(it) is SpokenAnswer.Unrecognized)
        }
    }

    @Test fun 단어가_포함된_질문과_불확실한_답은_분류하지_않는다() {
        listOf("업무인지 모르겠어요", "개인정보는 저장되나요", "출퇴근일 수도 있어요", "업무용으로 기록되는 건가요")
            .forEach { assertTrue(it, SpokenPurposeParser.parse(it) is SpokenAnswer.Unrecognized) }
        assertEquals(Purpose.PERSONAL, purpose("업무기록 제외"))
    }

    @Test fun 자연스러운_업무_문장을_일반업무로_분류한다() {
        listOf(
            "미팅이요", "미팅 다녀왔어요", "미팅 갔다 왔습니다", "미팅이 있어서 나갔어요", "오늘 회의가 있었어요",
            "협력업체 미팅 때문에 이동했어요", "거래처 다녀왓어요", "아 거래처 들렀다가 왔어요", "오전에 거래처 들렀어요",
            "고객사에 납품했어요", "협력사에 다녀왔어요", "거래처 방문 후 수금했어요", "영업하러 나갔어요",
            "고객을 만나고 왔어요", "고객 상담하러 다녀왔어요", "회사 일로 다녀왔어요", "업무 보러 갔다 왔습니다",
            "현장 점검하고 왔어요", "현장에 다녀왔어요", "출장 갔다 왔어요", "외근이요", "외근하고 돌아왔어요",
            "서류 전달하고 왔어요", "견적서 제출하러 다녀왔습니다", "장비를 배송했어요", "계약 체결하러 갔어요",
            "사내 교육 참석하고 왔어요", "아마존 거래처 방문했어요", "Client meeting.",
            "공장 보러 갔다 왔어요", "대리점에 다녀왔어요", "물류센터에 들렀어요", "업체에 들렀어요",
            "제품 시연하고 왔어요", "사무용품 사러 갔다 왔어요", "A/S 하러 다녀왔어요"
        ).forEach { assertEquals(it, Purpose.BUSINESS, purpose(it)) }
    }

    @Test fun 일반업무_외_목적도_자연스럽게_말할_수_있다() {
        listOf("오늘 아침에 출근했어요", "퇴근하고 집에 왔습니다", "회사까지 출근하려고 탔어요").forEach {
            assertEquals(it, Purpose.COMMUTE, purpose(it))
        }
        assertEquals(Purpose.PERSONAL, purpose("이번 운행 업무기록에서 빼 주세요"))
    }

    @Test fun 업무_단어만_들었다고_일반업무로_저장하지_않는다() {
        listOf(
            "미팅은 안 갔어요", "회의 안 했어요", "회의가 없었어요", "거래처는 못 들렀어요", "거래처에 안 다녀왔어요",
            "개인용인데 거래처도 들렀어요", "개인 약속이랑 업무 미팅 둘 다 했어요", "퇴근길에 거래처 들렀어요",
            "개인 미팅 다녀왔어요", "친구랑 미팅 다녀왔어요", "회사 동료가 거래처 갔어요", "내일 거래처 미팅 있어요",
            "업무용인지 모르겠어요", "거래처 방문이면 업무용인가요", "거래처에 다녀왔나요?", "미팅 같아요",
            "미팅 녹음기 설정", "개인정보 설정", "출장 마사지 받았어요", "business is not the purpose",
            "고객사 다녀왔는데 나중에 분류할게요", "거래처 전화번호 알려줘요", "거래처 가도 돼요",
            "개인 약속 다녀왔어요", "친구 만나고 왔어요", "마트에 장 보러 갔어요"
        ).forEach { assertTrue(it, SpokenPurposeParser.parse(it) is SpokenAnswer.Unrecognized) }
    }

    @Test fun 알아듣지_못한_경우_들은_문장을_그대로_남긴다() {
        val r = SpokenPurposeParser.parse("네 알겠습니다") as SpokenAnswer.Unrecognized
        assertEquals("네 알겠습니다", r.heard)
    }

    @Test fun 저장_문구는_받침에_맞춰_으로_로를_고른다() {
        assertEquals("출퇴근으로 저장했어요", VoiceMessages.saved(Purpose.COMMUTE))
        assertEquals("일반업무로 저장했어요", VoiceMessages.saved(Purpose.BUSINESS))
        assertEquals("업무기록에서 제외했어요. 총주행거리에는 반영됩니다.", VoiceMessages.saved(Purpose.PERSONAL))
        assertEquals("로", VoiceMessages.euro("서울")) // ㄹ 받침은 '로'
        assertEquals("으로", VoiceMessages.euro("집"))
    }
}
