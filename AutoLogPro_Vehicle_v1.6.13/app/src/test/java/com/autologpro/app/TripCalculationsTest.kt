package com.autologpro.app

import com.autologpro.data.TaxDocTemplateEngine
import com.autologpro.data.Trip
import org.junit.Assert.*
import org.junit.Test

class TripCalculationsTest {
    private fun trip(id: Long, distance: Double, purpose: Purpose) = TripRecord(
        id, 1_000, 2_000, 500.0, 500.0 + distance, 0, "김대표", purpose, "", "test")

    @Test fun businessRatioExcludesPersonal() {
        val (total, business, ratio) = TripCalculations.monthSummary(listOf(
            trip(1, 30.0, Purpose.BUSINESS), trip(2, 10.0, Purpose.PERSONAL)))
        assertEquals(40.0, total, 0.001)
        assertEquals(30.0, business, 0.001)
        assertEquals(75.0, ratio, 0.001)
    }
    @Test fun unclassifiedTripIsNotBusiness() {
        val (_, business, _) = TripCalculations.monthSummary(listOf(trip(1, 18.0, Purpose.UNCLASSIFIED)))
        assertEquals(0.0, business, 0.001)
    }
    @Test fun openTripIsExcludedFromSummary() {
        val open = trip(1, 20.0, Purpose.BUSINESS).copy(endAt = null)
        assertEquals(0.0, TripCalculations.monthSummary(listOf(open)).first, 0.001)
    }
    @Test fun distanceClampsNegativeToZero() {
        assertEquals(0.0, trip(1, -5.0, Purpose.BUSINESS).distanceKm, 0.001)
    }
    private fun reportTrip(driver: String) = Trip(driverId = driver, startTime = 1000L, endTime = 2000L,
        startOdoKm = 500.0, endOdoKm = 505.0, purpose = com.autologpro.data.Purpose.BUSINESS)

    @Test fun csvQuotesDriverName() {
        val csv = TaxDocTemplateEngine.renderCsv(listOf(reportTrip("김,대표")))
        assertTrue(csv.contains("\"김,대표\""))
    }
    @Test fun htmlEscapesUserValue() {
        val html = TaxDocTemplateEngine.render(listOf(reportTrip("<script>")))
        assertTrue(html.contains("&lt;script&gt;"))
    }
}
