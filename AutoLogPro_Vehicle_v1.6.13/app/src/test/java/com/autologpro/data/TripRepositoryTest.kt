package com.autologpro.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Room 없이 로직만 검증하는 메모리 DAO. */
class FakeTripDao : TripDao {
    val trips = mutableListOf<Trip>()
    val corrections = mutableListOf<Correction>()
    override suspend fun insertTrip(t: Trip): Long { val id = (trips.maxOfOrNull { it.id } ?: 0) + 1; trips += t.copy(id = id); return id }
    override suspend fun insertCorrection(c: Correction) { corrections += c }
    override suspend fun update(t: Trip) { trips[trips.indexOfFirst { it.id == t.id }] = t }
    override suspend fun get(id: Long) = trips.firstOrNull { it.id == id }
    override suspend fun deleteOpenTrip(id: Long): Int = if (trips.removeAll { it.id == id && it.endTime == null && it.startSource == Source.SIMULATED }) 1 else 0
    override suspend fun open() = trips.filter { it.endTime == null }.maxByOrNull { it.id }
    override suspend fun lastClosed() = trips.filter { it.endTime != null }.maxByOrNull { it.id }
    override suspend fun allClosed() = trips.filter { it.endTime != null }.sortedBy { it.id }
    override suspend fun between(from: Long, to: Long) = trips.filter { it.startTime in from..to }
    override suspend fun correctionsOf(tripId: Long) = corrections.filter { it.tripId == tripId }
}

class TripRepositoryTest {
    private val dao = FakeTripDao()
    private var now = 1_000_000L
    private val repo = TripRepository(dao) { now }
    private val min = 60_000L

    private suspend fun drive(driver: String?, from: Double, to: Double) {
        repo.start(driver, from)
        now += 10 * min
        repo.end(to)
    }

    @Test fun 시작과_도착은_거리와_봉인을_만들고_중복_시작은_무시한다() = runBlocking {
        assertEquals(1L, repo.start("a", 100.0).id)
        assertEquals(100.0, repo.start("a", 999.0).startOdoKm)
        now += 10 * min
        val e = repo.end(110.0, "서초구")!!
        assertEquals(10.0, e.distanceKm())
        assertEquals("", e.prevHash)
        assertEquals(Source.GEAR, e.endSource)
        assertTrue(e.hash.isNotEmpty())
    }

    @Test fun 진행중이_없거나_도착_거리가_작으면_도착을_무시한다() = runBlocking {
        assertEquals(null, repo.end(120.0))
        repo.start("a", 100.0)
        assertEquals(null, repo.end(50.0))
        assertEquals(1L, dao.open()?.id)
    }

    @Test fun 같은_운전자가_5분_안에_다시_출발하면_병합하고_아니면_새_운행이다() = runBlocking {
        drive("a", 100.0, 110.0)
        now += min
        val merged = repo.start("a", 110.0)
        assertTrue(merged.id == 1L && merged.endTime == null && merged.endSource == null)
        now += 2 * min
        assertEquals(25.0, repo.end(125.0)!!.distanceKm())

        now += 6 * min
        assertEquals(2L, repo.start("a", 125.0).id)
        now += min
        repo.end(130.0)
        now += min
        assertEquals(3L, repo.start("b", 130.0).id)
    }

    @Test fun 복구는_현재_거리로_마감하고_복구됨으로_기록한다() = runBlocking {
        repo.start("a", 130.0)
        val r = repo.recoverOpen(140.0)!!
        assertTrue(r.endSource == Source.RECOVERED && r.endOdoKm == 140.0)

        now += 10 * min
        repo.start("a", 140.0)
        assertEquals(140.0, repo.recoverOpen(120.0)!!.endOdoKm)
    }

    @Test fun 감지_방식이_기록된다() = runBlocking {
        repo.start("a", 1.0, source = Source.FALLBACK)
        now += min
        val t = repo.end(2.0, source = Source.FALLBACK)!!
        assertTrue(t.startSource == Source.FALLBACK && t.endSource == Source.FALLBACK)
    }

    @Test fun 계기판이_움직였으면_5분_안이어도_병합하지_않는다() = runBlocking {
        drive("a", 100.0, 110.0)
        now += min
        assertEquals(2L, repo.start("a", 115.0).id) // 그 사이 5km를 달렸다
        now += min
        repo.end(120.0)
        now += min
        assertEquals(2L, repo.start("a", 120.1).id) // 허용 오차(0.2km) 이내면 병합
        assertEquals(2, dao.trips.size)
    }

    @Test fun 시연_출처가_기록되고_서식_비고에_시연이_찍힌다() = runBlocking {
        repo.start("a", 1.0, source = Source.SIMULATED)
        now += min
        val t = repo.end(2.0, source = Source.SIMULATED)!!
        assertTrue(t.startSource == Source.SIMULATED && t.endSource == Source.SIMULATED)
        assertTrue("시연" in TaxDocTemplateEngine.render(dao.trips))
        assertTrue(repo.firstBrokenId() == null) // 새 enum 값이 해시 체인 검증을 깨지 않는다
    }

    @Test fun 분류는_이력을_남기고_같은_값은_무시한다() = runBlocking {
        drive("a", 0.0, 10.0)
        repo.classify(1, Purpose.BUSINESS)
        assertTrue(dao.get(1)!!.purpose == Purpose.BUSINESS && dao.corrections.last().applied)
        val n = dao.corrections.size
        repo.classify(1, Purpose.BUSINESS)
        assertEquals(n, dao.corrections.size)
    }

    @Test fun 음성은_해당_종료건만_수정하고_수동분류를_덮어쓰지_않는다() = runBlocking {
        val open = repo.start("a", 100.0)
        assertTrue(!repo.classifyByVoice(open.id, now, Purpose.BUSINESS) { true })
        now += min
        val closed = repo.end(110.0)!!
        assertTrue(!repo.classifyByVoice(closed.id, now - 1, Purpose.BUSINESS) { true })
        assertTrue(!repo.classifyByVoice(closed.id, now, Purpose.BUSINESS) { false })
        assertTrue(repo.classifyByVoice(closed.id, now, Purpose.PERSONAL) { true })
        assertTrue(!repo.classifyByVoice(closed.id, now, Purpose.BUSINESS) { true })
        val saved = dao.get(closed.id)!!
        assertEquals(Purpose.PERSONAL, saved.purpose)
        assertEquals(closed.hash, saved.hash)
        assertEquals(10.0, saved.distanceKm())
        assertEquals(1, dao.corrections.size)
        assertEquals(10.0, TaxDocTemplateEngine.summary(dao.trips).totalKm)
        assertTrue(TaxDocTemplateEngine.visibleRows(dao.trips).isEmpty())
    }

    @Test fun 정정_요청은_원본을_유지하고_사유와_수집값_필드만_받는다() = runBlocking {
        drive("a", 100.0, 125.0)
        repo.requestCorrection(1, "endOdoKm", "120.0", "계기판 교체")
        assertTrue(!dao.corrections.last().applied && dao.get(1)!!.endOdoKm == 125.0)
        assertTrue(runCatching { repo.requestCorrection(1, "endOdoKm", "1", " ") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { repo.requestCorrection(1, "purpose", "x", "r") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun 수집값을_바꾸면_체인_검증이_그_운행을_가리킨다() = runBlocking {
        drive("a", 0.0, 10.0)
        now += min
        drive("b", 10.0, 20.0)
        assertEquals(null, repo.firstBrokenId())
        dao.trips[0] = dao.trips[0].copy(endOdoKm = 999.0)
        assertEquals(1L, repo.firstBrokenId())
    }

    @Test fun 동시_start는_운행_하나만_만든다() = runBlocking {
        (1..50).map { async(Dispatchers.Default) { repo.start("a", 1.0) } }.awaitAll()
        assertEquals(1, dao.trips.size)
    }

    @Test fun 확정된_운행이_없으면_검증_코드가_없다() = runBlocking {
        drive("a", 0.0, 10.0)
        assertEquals(null, repo.exportCode())
    }

    @Test fun 검증_코드는_확정_운행까지만_대표하고_병합에_흔들리지_않으며_변조를_잡는다() = runBlocking {
        drive("a", 0.0, 10.0)
        now += 6 * min
        val code = repo.exportCode()!!
        assertTrue(repo.verifyCode(code))

        drive("b", 10.0, 20.0)
        assertEquals(code, repo.exportCode()) // 방금 끝난 운행은 아직 병합될 수 있어 코드에 안 들어간다
        now += min
        repo.start("b", 20.0) // 병합: 2번 운행의 해시가 바뀐다
        now += min
        repo.end(30.0)
        assertTrue(repo.verifyCode(code))

        assertTrue(!repo.verifyCode("0".repeat(16)))
        assertTrue(!repo.verifyCode(code.take(8)))
        dao.trips[0] = dao.trips[0].copy(endOdoKm = 999.0)
        assertTrue(!repo.verifyCode(code))
    }

    @Test fun 정정_이력은_오래된_순으로_조회되고_없으면_빈_목록이다() = runBlocking {
        drive("a", 0.0, 10.0)
        repo.classify(1, Purpose.BUSINESS)
        repo.requestCorrection(1, "endOdoKm", "11.0", "확인")
        val c = repo.getCorrections(1)
        assertEquals(2, c.size)
        assertTrue(c[0].applied && c[0].field == "purpose")
        assertTrue(!c[1].applied && c[1].field == "endOdoKm")
        assertEquals(0, repo.getCorrections(99).size)
    }

    @Test fun 구간은_서울_기준_월과_연_경계를_따른다() {
        val m = Periods.month(2026, 11)
        assertEquals(Instant.parse("2026-10-31T15:00:00Z").toEpochMilli(), m.first)
        assertEquals(Instant.parse("2026-11-30T15:00:00Z").toEpochMilli() - 1, m.last)
        assertEquals(Instant.parse("2026-12-31T15:00:00Z").toEpochMilli() - 1, Periods.month(2026, 12).last)
        val y = Periods.year(2026)
        assertEquals(Instant.parse("2025-12-31T15:00:00Z").toEpochMilli(), y.first)
        assertEquals(Instant.parse("2026-12-31T15:00:00Z").toEpochMilli() - 1, y.last)
        val at = Instant.parse("2026-11-09T06:44:00Z").toEpochMilli()
        assertEquals(m, Periods.thisMonth(at))
        assertEquals(y, Periods.thisYear(at))
    }

    @Test fun tripsIn은_구간_안_운행만_돌려준다() = runBlocking {
        now = Instant.parse("2026-11-10T01:00:00Z").toEpochMilli()
        drive("a", 0.0, 10.0)
        now = Instant.parse("2026-12-05T01:00:00Z").toEpochMilli()
        drive("b", 10.0, 20.0)
        assertEquals(1, repo.tripsIn(Periods.month(2026, 11)).size)
        assertEquals(2, repo.tripsIn(Periods.year(2026)).size)
    }
}
