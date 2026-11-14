package com.autologpro.app

import com.autologpro.data.Source
import com.autologpro.data.Trip
import com.autologpro.data.Purpose as DataPurpose
import org.junit.Assert.*
import org.junit.Test

/** Pure adapter mapping tests. Android/Room runtime tests still require emulator. */
class CoreTripGatewayTest {
    @Test fun distinctDisplayIdsForThreeSources() {
        val actual = Trip(id = 1, startTime = 1000L, startOdoKm = 42000.0, startSource = Source.GEAR)
        val simulated = actual.copy(startSource = Source.SIMULATED)
        assertEquals(-2L, CoreTripGateway.displayId(actual))
        assertEquals(-3L, CoreTripGateway.displayId(simulated))
        assertNotEquals(CoreTripGateway.displayId(actual), CoreTripGateway.displayId(simulated))
    }

    @Test fun coreRecordsMapToReadOnlyV14Model() {
        val core = Trip(
            id = 2, startTime = 1000L, endTime = 1200L, startOdoKm = 52000.0,
            endOdoKm = 52017.7, driverId = "이과장", purpose = DataPurpose.BUSINESS,
            startSource = Source.SIMULATED
        )
        val ui = CoreTripGateway.toRecord(core)
        assertEquals(-5L, ui.id)
        assertEquals(Purpose.BUSINESS, ui.purpose)
        assertEquals("이과장", ui.driver)
        assertEquals(17.7, ui.distanceKm, 0.000001)
        assertEquals(0, ui.tollWon) // A v3 model has no toll integration
        assertTrue(ui.source.contains("시연"))
    }
}
