package com.autologpro.app

import android.content.Context
import com.autologpro.core.AppDb
import com.autologpro.core.VehicleCoreService
import com.autologpro.data.LogbookProfileStore
import com.autologpro.data.Purpose as CorePurpose
import com.autologpro.data.Source as CoreSource
import com.autologpro.data.Trip as CoreTrip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.Calendar

/**
 * A v3 Room 저장소 + B v1.4 기존 데모 기록 읽기 어댑터.
 * 신규 출발/도착 쓰기는 무조건 VehicleCoreService만 담당한다.
 * 표시 ID를 음수로 매핑하여 v1.4 SQLite(양수 ID)와 충돌하지 않는다.
 *  실제 Room ID 1 -> -2, 시뮬 Room ID 1 -> -3.
 *
 * Data: C latest Room model, A v3 lifecycle/DB split; not the old C v0.7 gateway.
 * New trip start/end are exclusively handled by VehicleCoreService, not this adapter.
 */
class CoreTripGateway(
    private val context: Context,
    private val legacy: DemoTripRepository,
) : TripGateway {
    companion object {
        fun displayId(trip: CoreTrip): Long =
            -(trip.id * 2L + if (trip.startSource == CoreSource.SIMULATED) 1L else 0L)

        fun toRecord(trip: CoreTrip): TripRecord = TripRecord(
            id = displayId(trip),
            startAt = trip.startTime,
            endAt = trip.endTime,
            startOdoKm = trip.startOdoKm,
            endOdoKm = trip.endOdoKm,
            tollWon = 0, // A v3 + C patch do not store tolls; never fabricate a real toll.
            driver = trip.driverId ?: "미확인",
            driverDepartment = trip.driverDepartment.orEmpty(),
            legalCompany = trip.legalCompany, businessNo = trip.businessNo,
            vehicleModel = trip.vehicleModel, vehiclePlate = trip.vehiclePlate,
            purpose = when (trip.purpose) {
                CorePurpose.UNCLASSIFIED -> Purpose.UNCLASSIFIED
                CorePurpose.COMMUTE -> Purpose.COMMUTE
                CorePurpose.BUSINESS -> Purpose.BUSINESS
                CorePurpose.PERSONAL -> Purpose.PERSONAL
            },
            endPlace = trip.endPlace.orEmpty(),
            source = if (trip.startSource == CoreSource.SIMULATED) {
                if (trip.startPlace?.contains("ACCEL") == true) "ACCEL 시연용 추정 거리 · 속도 적분 · 실차/세무 증빙 불가"
                else "시연용 샘플 데이터"
            } else "Pleos 계기판 기반 자동 기록 (실차 검증 필요)"
        )
    }

    private inline fun <T> db(crossinline block: suspend () -> T): T =
        runBlocking(Dispatchers.IO) { block() }

    private fun isSim(id: Long) = (-id) % 2L == 1L
    private fun nativeId(id: Long) = (-id) / 2L

    private suspend fun findNative(id: Long): CoreTrip? {
        if (id >= 0) return null
        if (isSim(id) && !AppDb.simExists(context)) return null
        val store = if (isSim(id)) AppDb.simDb(context) else AppDb.get(context)
        return store.tripDao().get(nativeId(id))
    }

    /** UI가 종료 팝업 처리 여부를 코어에 알려주기 위한 읽기 함수. */
    fun coreTrip(id: Long): CoreTrip? = db { findNative(id) }

    /** 이전 시연의 마지막 계기판값을 이어 쓰기 위해 조회. */
    fun lastSimOdometerKm(): Double = db {
        if (!AppDb.simExists(context)) return@db 52340.0
        AppDb.simDb(context).tripDao().lastClosed()?.endOdoKm ?: 52340.0
    }

    override fun allTrips(): List<TripRecord> {
        val old = legacy.allTrips()
        val current = db {
            val dao = AppDb.get(context).tripDao()
            val real = (dao.allClosed() + listOfNotNull(dao.open())).map(::toRecord)
            val sim = if (AppDb.simExists(context)) {
                val sd = AppDb.simDb(context).tripDao()
                (sd.allClosed() + listOfNotNull(sd.open())).map(::toRecord)
            } else emptyList()
            real + sim
        }
        return (old + current).sortedWith(compareByDescending<TripRecord> { it.startAt }.thenByDescending { it.id })
    }

    override fun tripsIn(year: Int, month: Int): List<TripRecord> = allTrips().filter {
        val c = Calendar.getInstance().apply { timeInMillis = it.startAt }
        c.get(Calendar.YEAR) == year && c.get(Calendar.MONTH) + 1 == month
    }

    override fun start(odometerKm: Double): TripRecord =
        error("A v3 VehicleCoreService가 출발 저장을 담당합니다. UI에서 직접 start() 금지")
    override fun end(odometerKm: Double, tollWon: Int): TripRecord? =
        error("A v3 VehicleCoreService가 도착 저장을 담당합니다. UI에서 직접 end() 금지")
    override fun recoverOpen(isParked: Boolean): TripRecord? = null // A가 실제 상태 확인 후 복구

    override fun classify(id: Long, purpose: Purpose) {
        if (id >= 0) return legacy.classify(id, purpose)
        db {
            val trip = findNative(id) ?: error("운행 $id 없음")
            val mapped = when (purpose) {
                Purpose.UNCLASSIFIED -> CorePurpose.UNCLASSIFIED
                Purpose.COMMUTE -> CorePurpose.COMMUTE
                Purpose.BUSINESS -> CorePurpose.BUSINESS
                Purpose.PERSONAL -> CorePurpose.PERSONAL
            }
            VehicleCoreService.repoFor(context, trip).classify(trip.id, mapped)
        }
    }
    fun classifyByVoice(id: Long, expectedEndTime: Long, purpose: Purpose, allowed: () -> Boolean): Boolean = db {
        val trip = findNative(id) ?: return@db false
        VehicleCoreService.repoFor(context, trip).classifyByVoice(
            trip.id, expectedEndTime, CorePurpose.valueOf(purpose.name), allowed,
        )
    }

    internal fun applySyncedDriver(id: Long, driver: String, department: String) = db {
        findNative(id)?.let { VehicleCoreService.repoFor(context,it).setDriver(it.id,driver.take(50),department.take(60)) }
    }

    override fun setDriver(id: Long, driver: String) {
        if (id >= 0) return legacy.setDriver(id, driver)
        db { findNative(id)?.let {
            val profile = LogbookProfileStore(context).get()
            val dept = if (driver == profile.driver) profile.department.ifBlank { null }
                else if (it.startSource == CoreSource.SIMULATED) "시연팀" else null
            VehicleCoreService.repoFor(context, it).setDriver(it.id, driver, dept)
        } }
    }
    override fun setEndPlace(id: Long, place: String) {
        if (id >= 0) return legacy.setEndPlace(id, place)
        db { findNative(id)?.let { VehicleCoreService.repoFor(context, it).setEndPlace(it.id, place) } }
    }
    override fun requestCorrection(id: Long, proposedEndKm: Double, reason: String) {
        if (id >= 0) return legacy.requestCorrection(id, proposedEndKm, reason)
        db { findNative(id)?.let {
            VehicleCoreService.repoFor(context, it).requestCorrection(it.id, "endOdoKm", proposedEndKm.toString(), reason)
        } }
    }
    override fun getCorrections(): List<Correction> {
        val core = db {
            val actualDao = AppDb.get(context).tripDao()
            val actual = actualDao.allClosed().flatMap { t ->
                actualDao.correctionsOf(t.id).filter { !it.applied && it.field == "endOdoKm" }
                    .map { c -> Correction(displayId(t), c.newValue?.toDoubleOrNull() ?: 0.0, c.reason, c.createdAt) }
            }
            val simulated = if (AppDb.simExists(context)) {
                val dao = AppDb.simDb(context).tripDao()
                dao.allClosed().flatMap { t ->
                    dao.correctionsOf(t.id).filter { !it.applied && it.field == "endOdoKm" }
                        .map { c -> Correction(displayId(t), c.newValue?.toDoubleOrNull() ?: 0.0, c.reason, c.createdAt) }
                }
            } else emptyList()
            actual + simulated
        }
        return (legacy.getCorrections() + core).sortedByDescending { it.at }
    }

    /** v1.4 샘플만 초기화. A의 분리 시뮬DB는 CoreService.resetSimulationData()로 처리. */
    override fun clearAndSeed() = legacy.clearAndSeed()
}
