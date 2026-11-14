package com.autologpro.app

import java.util.Calendar

enum class Purpose(val label: String) {
    UNCLASSIFIED("미분류"), COMMUTE("출퇴근"), BUSINESS("일반업무"), PERSONAL("비업무")
}

data class TripRecord(
    val id: Long,
    val startAt: Long,
    val endAt: Long?,
    val startOdoKm: Double,
    val endOdoKm: Double?,
    val tollWon: Int,
    val driver: String,
    val purpose: Purpose,
    val endPlace: String,
    val source: String,
    val driverDepartment: String = "",
    val legalCompany: String? = null,
    val businessNo: String? = null,
    val vehicleModel: String? = null,
    val vehiclePlate: String? = null,
) {
    val distanceKm: Double get() = ((endOdoKm ?: startOdoKm) - startOdoKm).coerceAtLeast(0.0)
}

data class Correction(val tripId: Long, val requestedKm: Double, val reason: String, val at: Long)

/**
 * UI contract for Role C. DemoTripRepository is a standalone substitute, NOT C's Room code.
 * Rename or map parameters to C's exact signatures after obtaining SDK_DB_서식_대응표.md.
 */
interface TripGateway {
    fun start(odometerKm: Double): TripRecord
    fun end(odometerKm: Double, tollWon: Int = 0): TripRecord?
    fun recoverOpen(isParked: Boolean): TripRecord?
    fun classify(id: Long, purpose: Purpose)
    fun setDriver(id: Long, driver: String)
    fun setEndPlace(id: Long, place: String)
    fun requestCorrection(id: Long, proposedEndKm: Double, reason: String)
    fun getCorrections(): List<Correction>
    fun tripsIn(year: Int, month: Int): List<TripRecord>
    fun allTrips(): List<TripRecord>
    fun clearAndSeed()
}

object Periods {
    fun monthBounds(year: Int, month: Int): Pair<Long, Long> {
        require(month in 1..12)
        val c = Calendar.getInstance().apply {
            set(Calendar.YEAR, year); set(Calendar.MONTH, month - 1); set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = c.timeInMillis
        c.add(Calendar.MONTH, 1)
        return start to c.timeInMillis
    }
}

object TripCalculations {
    fun monthSummary(records: List<TripRecord>): Triple<Double, Double, Double> {
        val closed = records.filter { it.endAt != null }
        val total = closed.sumOf { it.distanceKm }
        val business = closed.filter { it.purpose == Purpose.BUSINESS || it.purpose == Purpose.COMMUTE }.sumOf { it.distanceKm }
        return Triple(total, business, if (total > 0) business * 100 / total else 0.0)
    }
    fun currentPeriod(): Pair<Int, Int> = Calendar.getInstance().let { it.get(Calendar.YEAR) to (it.get(Calendar.MONTH) + 1) }
}
