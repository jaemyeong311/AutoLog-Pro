package com.autologpro.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** src/testDebug에 둘 것(FakeTripDao는 TripRepositoryTest.kt에 있다). */
class SampleDataGeneratorTest {
    @Test fun 릴리스에서는_만들지_않고_기록이_있으면_만들지_않는다() = runBlocking {
        val dao = FakeTripDao()
        assertTrue(runCatching { SampleDataGenerator.generate(dao, 2026, 11, isDebug = false) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, dao.trips.size)
        SampleDataGenerator.generate(dao, 2026, 11, isDebug = true)
        assertTrue(runCatching { SampleDataGenerator.generate(dao, 2026, 11, isDebug = true) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun 스물다섯_건을_만들고_체인이_정상이며_비율은_계산된_값이다() = runBlocking {
        val dao = FakeTripDao()
        val repo = TripRepository(dao)
        assertEquals(25, SampleDataGenerator.generate(dao, 2026, 11, isDebug = true))
        assertEquals(25, dao.trips.size)
        assertEquals(null, repo.firstBrokenId())

        val s = TaxDocTemplateEngine.summary(repo.tripsIn(Periods.month(2026, 11)))
        assertEquals(1005.0, s.totalKm, 1e-6)
        assertEquals(0.928, s.businessRatio, 0.001) // (1005 - 비업무 37.9 - 미분류 34.3) / 1005

        assertTrue(dao.trips.any { it.endSource == Source.RECOVERED })
        assertTrue(dao.trips.any { it.startSource == Source.FALLBACK })
        assertTrue(dao.trips.any { it.purpose == Purpose.UNCLASSIFIED })
        assertEquals(1, dao.corrections.count { !it.applied })
        assertEquals(25, repo.tripsIn(Periods.year(2026)).size)
    }
}
