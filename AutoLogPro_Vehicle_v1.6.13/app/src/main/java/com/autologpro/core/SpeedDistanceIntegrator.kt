package com.autologpro.core

import kotlin.math.abs

/**
 * Debug emulator ACCEL demo ONLY: integrate confirmed AAOS speed samples.
 * Meters per second (PERF_VEHICLE_SPEED) integrated using monotonic timestamps.
 * Does not replace or modify a real odometer, and must never be exported as verified mileage.
 * Missing, stale, implausible speed samples are not extrapolated.
 */
internal class SpeedDistanceIntegrator {
    private var previousTimeMs: Long? = null
    private var previousSpeedMps: Double? = null
    var distanceKm: Double = 0.0
        private set
    var validSamples: Int = 0
        private set
    var movingSamples: Int = 0
        private set
    var peakKmh: Double = 0.0
        private set

    fun reset() {
        previousTimeMs = null
        previousSpeedMps = null
        distanceKm = 0.0
        validSamples = 0
        movingSamples = 0
        peakKmh = 0.0
    }

    fun add(timeMs: Long, rawSpeedMps: Double?): Double {
        if (rawSpeedMps == null || !rawSpeedMps.isFinite() || abs(rawSpeedMps) > 85.0) {
            previousSpeedMps = null
            previousTimeMs = null // never bridge across a gap in samples
            return distanceKm
        }
        val speed = abs(rawSpeedMps)
        validSamples++
        if (speed > 0.5) movingSamples++
        peakKmh = maxOf(peakKmh, speed * 3.6)
        val prevTime = previousTimeMs
        val prevSpeed = previousSpeedMps
        if (prevTime != null && prevSpeed != null) {
            val dt = timeMs - prevTime
            if (dt in 1..1_250) {
                // trapezoidal integration: (m/s) * (s) / 1000 = km
                distanceKm += (prevSpeed + speed) * 0.5 * (dt / 1000.0) / 1000.0
            }
        }
        previousTimeMs = timeMs
        previousSpeedMps = speed
        return distanceKm
    }
}
