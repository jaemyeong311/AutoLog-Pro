package com.autologpro.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaxDocTemplateEngineTest {
    private fun trip(id: Long, from: Double, to: Double, p: Purpose) = Trip(
        id = id, startTime = 1_700_000_000_000 + id, startOdoKm = from,
        endTime = 1_700_000_100_000 + id, endOdoKm = to, endSource = Source.GEAR, purpose = p,
    ).sealed("")

    @Test fun 비율은_출퇴근과_업무를_더하고_미분류는_분모에만_넣는다() {
        val s = TaxDocTemplateEngine.summary(listOf(
            trip(1, 0.0, 10.0, Purpose.COMMUTE), trip(2, 10.0, 30.0, Purpose.BUSINESS),
            trip(3, 30.0, 40.0, Purpose.PERSONAL), trip(4, 40.0, 50.0, Purpose.UNCLASSIFIED),
        ))
        assertEquals(50.0, s.totalKm)
        assertEquals(0.6, s.businessRatio, 1e-9)
    }

    @Test fun 비고의_HTML은_이스케이프한다() {
        val html = TaxDocTemplateEngine.render(listOf(trip(1, 0.0, 1.0, Purpose.BUSINESS).copy(endPlace = "<script>")))
        assertTrue("&lt;script&gt;" in html)
        assertTrue("<script>" !in html)
    }

    @Test fun CSV는_BOM과_합계를_갖고_따옴표와_수식_시작을_막는다() {
        val csv = TaxDocTemplateEngine.renderCsv(
            listOf(trip(1, 0.0, 10.0, Purpose.BUSINESS).copy(startPlace = "=SUM(1)", endPlace = "a\"b,c")),
        )
        val lines = csv.split("\r\n")
        assertTrue(csv.startsWith(Char(0xFEFF).toString() + "\"사용일자(요일)\""))
        assertTrue("\"'=SUM(1) → a\"\"b,c\"" in lines[1])
        assertTrue(lines.any { it.startsWith("\"조회기간 합계\"") })
        assertTrue(lines.none { it.startsWith("\"비업무(km)\"") })
        assertTrue(lines.any { it.startsWith("\"사업연도 총주행거리(km)\"") })
        assertTrue(lines.any { it.startsWith("\"업무사용비율(%)\",\"100.0\"") })
    }

    @Test fun HTML에_액션바_인쇄_스타일_요약_검증문구가_있고_하드웨어_봉인_문구는_없다() {
        val html = TaxDocTemplateEngine.render(
            listOf(trip(1, 0.0, 10.0, Purpose.BUSINESS), trip(2, 10.0, 20.0, Purpose.PERSONAL)),
            verifyCode = "abc123",
        )
        assertTrue("window.print()" in html)
        assertTrue("?csv" in html)
        assertTrue("@media print" in html)
        assertTrue("landscape" in html)
        assertTrue("비업무 10.0km" !in html)
        assertTrue("업무사용비율(%) · 50.0" in html)
        assertTrue("abc123" in html && "앱 해시 체인 기준" in html)
        assertTrue("하드웨어" !in html)
    }

    @Test fun 차량_정보와_검증_코드는_HTML과_CSV에_찍히고_없으면_안_찍힌다() {
        val trips = listOf(trip(1, 0.0, 1.0, Purpose.BUSINESS))
        val v = VehicleInfo(model = "아이오닉 5", plateNo = "12가3456")
        val html = TaxDocTemplateEngine.render(trips, vehicle = v, verifyCode = "abc123")
        assertTrue("차종: 아이오닉 5 · 자동차등록번호: 12가3456" in html)
        assertTrue("abc123" in html)
        val lines = TaxDocTemplateEngine.renderCsv(trips, vehicle = v, verifyCode = "abc123").split("\r\n")
        assertTrue("\"차종\",\"아이오닉 5\",\"자동차등록번호\",\"12가3456\"" in lines[0])
        assertTrue(lines.any { "abc123" in it })
        assertTrue("차종" !in TaxDocTemplateEngine.render(trips))
    }

    @Test fun 비고에_복구됨과_대체_감지가_표시된다() {
        val t = trip(1, 0.0, 1.0, Purpose.BUSINESS).copy(startSource = Source.FALLBACK, endSource = Source.RECOVERED)
        val html = TaxDocTemplateEngine.render(listOf(t))
        assertTrue("복구됨" in html)
        assertTrue("대체 감지" in html)
    }

    @Test fun 해시는_수집값이_바뀌면_어긋난다() {
        val t = trip(1, 0.0, 10.0, Purpose.BUSINESS)
        assertEquals(t.hash, t.sealed("").hash)
        assertTrue(t.copy(endOdoKm = 11.0).sealed("").hash != t.hash)
        assertTrue(t.copy(endSource = Source.FALLBACK).sealed("").hash != t.hash)
    }
}
