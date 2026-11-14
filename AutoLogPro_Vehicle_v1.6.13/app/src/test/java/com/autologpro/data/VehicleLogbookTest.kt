package com.autologpro.data

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class VehicleLogbookTest {
    private val profile = LogbookProfile("시연 주식회사", "123-45-67890", LogbookProfile.DEFAULT_MODEL,
        "12가 3456", "2026-04-01", "2027-03-31", "영업팀", "김운전")

    @Test fun snapshotDoesNotChangeAndExcludedTripIsNotMerged() = runBlocking {
        val dao = FakeTripDao()
        var now = 1_000_000L
        val repo = TripRepository(dao) { now }
        val first = repo.start(profile.driver, 100.0, profile = profile)
        assertEquals("영업팀", first.driverDepartment)
        assertEquals(profile.plate, first.vehiclePlate)
        now += 1000
        repo.end(110.0)
        repo.classify(first.id, Purpose.PERSONAL)
        now += 1000
        val second = repo.start(profile.driver, 110.0, profile = profile)
        assertNotEquals(first.id, second.id)
        assertEquals(Purpose.PERSONAL, dao.get(first.id)?.purpose)
        assertEquals(10.0, dao.get(first.id)?.distanceKm())
        now += 1000
        repo.end(120.0)
        now += 1000
        val third = repo.start(profile.driver, 120.0, profile = profile.copy(department = "총무팀"))
        assertNotEquals(second.id, third.id)
        assertEquals("영업팀", dao.get(second.id)?.driverDepartment)
        assertEquals("총무팀", third.driverDepartment)
        assertNull(repo.firstBrokenId())
    }

    @Test fun annualReportIncludesAllDistanceButNoPrivateDetail() {
        val day = LocalDate.parse("2026-11-13").atStartOfDay(LogbookProfile.SEOUL).toInstant().toEpochMilli()
        fun row(id: Long, from: Double, to: Double, p: Purpose) =
            Trip(id = id, startTime = day + id, endTime = day + id + 100, startOdoKm = from, endOdoKm = to,
                purpose = p, driverId = profile.driver, driverDepartment = profile.department)
        val work = row(1, 100.0, 170.0, Purpose.BUSINESS)
        val commute = row(2, 170.0, 190.0, Purpose.COMMUTE)
        val hidden = row(3, 190.0, 200.0, Purpose.PERSONAL).copy(endPlace = "숨겨야 하는 주소")
        val totals = TaxDocTemplateEngine.summary(listOf(work, commute, hidden))
        val fiscal = profile.periodFor(2026, 11)
        val info = ReportInfo(profile, fiscal, "2026-11-01 ~ 2026-11-30", totals)
        val csv = TaxDocTemplateEngine.renderCsv(listOf(work, hidden), info = info)
        val html = TaxDocTemplateEngine.render(listOf(work, hidden), info = info)
        assertEquals(100.0, totals.totalKm)
        assertEquals(90.0, totals.workKm)
        assertEquals(0.9, totals.businessRatio)
        assertTrue(csv.contains("\"업무사용비율(%)\",\"90.0\""))
        assertTrue(csv.contains("\"사업연도 총주행거리(km)\",\"100.0\""))
        assertTrue(csv.contains("\"사업연도 업무용 사용거리 합계(km)\",\"90.0\""))
        assertTrue(csv.contains("\"2026-11-13 (금)\",\"영업팀\",\"김운전\""))
        listOf("법인명", "사업자등록번호", "사업연도", "차종", "자동차등록번호", "출퇴근용", "일반업무용").forEach {
            assertTrue(it in csv && it in html)
        }
        assertFalse("숨겨야 하는 주소" in csv || "숨겨야 하는 주소" in html)
        assertEquals(listOf(1L), TaxDocTemplateEngine.visibleRows(listOf(work, hidden)).map { it.id })
        assertEquals("2026-04-01", fiscal.first.toString())
        assertEquals("2027-03-31", profile.periodFor(2027, 2).second.toString())
        assertEquals("2025-01-01", profile.periodFor(2025, 11).first.toString())
    }
}
