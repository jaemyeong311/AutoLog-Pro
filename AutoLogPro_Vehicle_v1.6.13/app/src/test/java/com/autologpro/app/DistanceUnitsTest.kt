package com.autologpro.app

import org.junit.Assert.*
import org.junit.Test

class DistanceUnitsTest {
    @Test fun mileToKm() { assertEquals(1.609344, DistanceUnits.toKm(1.0, DistanceUnit.MILE), 0.000001) }
    @Test fun meterToKm() { assertEquals(1.25, DistanceUnits.toKm(1250.0, DistanceUnit.METER), 0.000001) }
    @Test(expected = IllegalArgumentException::class) fun invalidValue() { DistanceUnits.toKm(-1.0, DistanceUnit.KILOMETER) }
}
