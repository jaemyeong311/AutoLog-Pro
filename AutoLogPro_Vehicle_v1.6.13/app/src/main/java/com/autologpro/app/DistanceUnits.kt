package com.autologpro.app

/** Use ONLY after the SDK's actual source unit is confirmed by role A. */
enum class DistanceUnit { KILOMETER, MILE, METER }
object DistanceUnits {
    fun toKm(value: Double, unit: DistanceUnit): Double {
        require(value.isFinite() && value >= 0.0)
        return when(unit) {
            DistanceUnit.KILOMETER -> value
            DistanceUnit.MILE -> value * 1.609344
            DistanceUnit.METER -> value / 1000.0
        }
    }
}
