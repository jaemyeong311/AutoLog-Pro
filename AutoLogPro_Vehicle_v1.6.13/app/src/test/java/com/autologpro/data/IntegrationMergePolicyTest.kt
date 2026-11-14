package com.autologpro.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Integration-specific rules agreed for A v3 + B v1.4 + C latest. */
class IntegrationMergePolicyTest {
    @Test fun simulatedTripsNeverMergeWithinFiveMinutes() = runBlocking {
        val dao = FakeTripDao()
        var clock = 1_000_000L
        val repo = TripRepository(dao) { clock }
        val first = repo.start(null, 52340.0, source = Source.SIMULATED)
        clock += 10_000
        repo.end(52357.7, source = Source.SIMULATED)
        clock += 1_000
        val second = repo.start(null, 52357.7, source = Source.SIMULATED)
        assertNotEquals(first.id, second.id)
        clock += 10_000
        repo.end(52375.4, source = Source.SIMULATED)
        assertEquals(2, dao.allClosed().size)
        assertTrue(TaxDocTemplateEngine.render(dao.allClosed()).contains("시연"))
    }

    @Test fun vehicleMergeDistanceAlignedWithCoreV3() = runBlocking {
        assertEquals(0.5, TripRepository.MERGE_ODO_TOLERANCE_KM, 0.000001)
        val dao = FakeTripDao()
        var clock = 1_000_000L
        val repo = TripRepository(dao) { clock }
        val first = repo.start("driver", 60000.0, source = Source.GEAR)
        clock += 10_000
        repo.end(60010.0)
        clock += 60_000
        assertEquals(first.id, repo.start("driver", 60010.4, source = Source.GEAR).id)
        clock += 60_000
        repo.end(60015.0)
        clock += 60_000
        assertNotEquals(first.id, repo.start("driver", 60015.7, source = Source.GEAR).id)
    }
}
