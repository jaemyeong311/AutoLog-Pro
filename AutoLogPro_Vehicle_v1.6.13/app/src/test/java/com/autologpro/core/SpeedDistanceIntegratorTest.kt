package com.autologpro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedDistanceIntegratorTest {
    @Test fun constantSpeedTenSeconds() {
        val m = SpeedDistanceIntegrator()
        for (i in 0..40) m.add(i * 250L, 10.0)
        assertEquals(0.1, m.distanceKm, 1e-7)
        assertEquals(41, m.movingSamples)
    }
    @Test fun integratesAccelerationTrapezoid() {
        val m = SpeedDistanceIntegrator()
        m.add(0, 0.0);m.add(1000, 10.0);m.add(2000, 20.0)
        assertEquals(0.02, m.distanceKm, 1e-7)
    }
    @Test fun noExtrapolationWhenSignalMissingOrStale() {
        val m=SpeedDistanceIntegrator()
        m.add(0, 10.0);m.add(250, 10.0);m.add(10000,null);m.add(10250,10.0)
        assertEquals(0.0025, m.distanceKm, 1e-7)
    }
    @Test fun abnormalSpeedAndBackwardTravel() {
        val m = SpeedDistanceIntegrator()
        m.add(0,-20.0);m.add(1000,-20.0)
        assertEquals(0.02,m.distanceKm,1e-7)
        m.reset()
        m.add(0,25.0);m.add(250,999.0);m.add(500,25.0)
        assertEquals(0.0,m.distanceKm,1e-7)
        assertTrue(m.peakKmh <= 90.0)
    }
}
