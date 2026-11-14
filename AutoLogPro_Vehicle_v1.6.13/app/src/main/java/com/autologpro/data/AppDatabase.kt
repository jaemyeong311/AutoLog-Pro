package com.autologpro.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

enum class Purpose { COMMUTE, BUSINESS, PERSONAL, UNCLASSIFIED }

/**
 * 출발·도착을 어떻게 알아챘는지. GEAR=기어 변속, FALLBACK=속도 0 대체 감지, RECOVERED=앱 재시작 후 복구,
 * SIMULATED=시연 주행(실제 차량 신호가 아님). Room은 enum을 이름 문자열로 저장하므로 값을 추가해도 스키마는 그대로다.
 * 단, SIMULATED가 들어 있는 DB를 이 값이 없는 옛 코드로 읽으면 변환에서 죽으니 A·B·C가 같은 enum을 써야 한다.
 */
enum class Source { GEAR, FALLBACK, RECOVERED, SIMULATED }

/**
 * 운행 1건. 수집값(시각·계기판 거리)은 닫을 때 해시로 봉인하고,
 * 분류·운전자·장소는 이력을 남기며 고칠 수 있다.
 * 국세청 서식 컬럼으로의 변환은 TaxDocTemplateEngine이 맡는다(서식 항목은 고시 확인 후 확정).
 */
@Entity(tableName = "trip")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val driverId: String? = null, // null = 운전자 미확인
    val startTime: Long,
    val startOdoKm: Double, // 단위·소수점은 SDK 제공 값 그대로
    val endTime: Long? = null, // null = 진행 중
    val endOdoKm: Double? = null,
    val startPlace: String? = null,
    val endPlace: String? = null,
    val purpose: Purpose = Purpose.UNCLASSIFIED, // 무응답은 미분류. 업무로 간주하지 않는다.
    val startSource: Source = Source.GEAR,
    val endSource: Source? = null, // null = 진행 중
    val prevHash: String = "",
    val hash: String = "",
    // Snapshot at departure. New settings never overwrite historical values.
    val driverDepartment: String? = null,
    val legalCompany: String? = null,
    val businessNo: String? = null,
    val vehicleModel: String? = null,
    val vehiclePlate: String? = null,
)

fun Trip.distanceKm(): Double? = endOdoKm?.let { it - startOdoKm }

fun Trip.sealed(prev: String): Trip =
    copy(prevHash = prev, hash = sha256("$prev|$id|$startTime|$startOdoKm|$endTime|$endOdoKm|$startSource|$endSource"))

private fun sha256(s: String) =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/** applied=true: 분류·운전자·장소처럼 바로 반영한 변경 / false: 수집값 정정 요청(원본 유지). */
@Entity(tableName = "correction")
data class Correction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val field: String,
    val oldValue: String?,
    val newValue: String?,
    val reason: String,
    val createdAt: Long,
    val applied: Boolean,
)

@Dao
interface TripDao {
    @Insert suspend fun insertTrip(t: Trip): Long
    @Insert suspend fun insertCorrection(c: Correction)
    @Update suspend fun update(t: Trip)

    @Query("SELECT * FROM trip WHERE id = :id") suspend fun get(id: Long): Trip?
    @Query("DELETE FROM trip WHERE id = :id AND endTime IS NULL AND startSource = 'SIMULATED'")
    suspend fun deleteOpenTrip(id: Long): Int
    @Query("SELECT * FROM trip WHERE endTime IS NULL ORDER BY id DESC LIMIT 1") suspend fun open(): Trip?
    @Query("SELECT * FROM trip WHERE endTime IS NOT NULL ORDER BY id DESC LIMIT 1") suspend fun lastClosed(): Trip?
    @Query("SELECT * FROM trip WHERE endTime IS NOT NULL ORDER BY id") suspend fun allClosed(): List<Trip>
    @Query("SELECT * FROM trip WHERE startTime BETWEEN :from AND :to ORDER BY startTime")
    suspend fun between(from: Long, to: Long): List<Trip>
    @Query("SELECT * FROM correction WHERE tripId = :tripId ORDER BY id") suspend fun correctionsOf(tripId: Long): List<Correction>
}

@Database(entities = [Trip::class, Correction::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("driverDepartment", "legalCompany", "businessNo", "vehicleModel", "vehiclePlate")
                    .forEach { db.execSQL("ALTER TABLE trip ADD COLUMN $it TEXT") }
            }
        }
        /** name을 다르게 주면 실제 운행 DB와 시연 DB를 파일 단위로 나눌 수 있다(기본값은 기존 이름). */
        fun build(context: Context, name: String = "autolog.db"): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
    }
}

/** 차량 앱(담당 B)이 부르는 창구. 상태 판단(P/D)은 호출하는 쪽이 한다. */
class TripRepository(
    private val dao: TripDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val MERGE_WINDOW_MS = 5 * 60 * 1000L

        /**
         * 병합하려면 새 출발 계기판이 직전 도착 계기판과 이 값 이내로 같아야 한다. 그 사이 차가 움직였다면 잠깐 선 것이 아니다.
         * 0.2km는 담당 C의 가정이다. A v3의 검증 규칙을 받으면 같은 값·같은 조건으로 맞춘다.
         */
        const val MERGE_ODO_TOLERANCE_KM = 0.5
        const val CODE_LEN = 16
    }

    // 쓰기 함수를 한 번에 하나씩만 실행해 '진행 중' 운행이 둘 생기거나 서로 덮어쓰는 일을 막는다.
    private val lock = Mutex()

    /**
     * P→D. 이미 진행 중인 운행이 있으면 그대로 돌려준다(중복 호출 방어).
     * 같은 운전자가 5분 안에, 계기판이 직전 도착 값과 같은 채로 다시 출발하면 직전 운행을 이어서 쓴다.
     */
    suspend fun start(
        driverId: String?, odoKm: Double, place: String? = null, source: Source = Source.GEAR,
        profile: LogbookProfile? = null,
    ): Trip = lock.withLock {
        dao.open()?.let { return@withLock it }
        val now = clock()
        val last = dao.lastClosed()
        // A v3와 0.5km 기준을 통일. 시연 운행은 5분 안에 반복해도 별개의 기록으로 보존한다.
        val lastEnd = last?.endTime
        val lastOdo = last?.endOdoKm
        if (source != Source.SIMULATED && last?.startSource != Source.SIMULATED &&
            last != null && last.purpose == Purpose.UNCLASSIFIED && last.driverId == driverId &&
            (profile == null || (last.driverDepartment == profile.department.ifBlank { null } &&
                last.legalCompany == profile.company.ifBlank { null } && last.businessNo == profile.businessNo.ifBlank { null } &&
                last.vehicleModel == profile.model.ifBlank { null } && last.vehiclePlate == profile.plate.ifBlank { null })) &&
            lastEnd != null && lastOdo != null &&
            now >= lastEnd && now - lastEnd <= MERGE_WINDOW_MS &&
            kotlin.math.abs(odoKm - lastOdo) <= MERGE_ODO_TOLERANCE_KM) {
            return@withLock last.copy(endTime = null, endOdoKm = null, endSource = null, hash = "").also { dao.update(it) }
        }
        val t = Trip(
            driverId = driverId, startTime = now, startOdoKm = odoKm, startPlace = place, startSource = source,
            driverDepartment = profile?.department?.ifBlank { null },
            legalCompany = profile?.company?.ifBlank { null }, businessNo = profile?.businessNo?.ifBlank { null },
            vehicleModel = profile?.model?.ifBlank { null }, vehiclePlate = profile?.plate?.ifBlank { null },
        )
        t.copy(id = dao.insertTrip(t))
    }

    /** D→P. 도착 거리로 닫고 해시를 봉인한다. 도착 거리가 출발보다 작으면 무시(null). */
    suspend fun end(odoKm: Double, place: String? = null, source: Source = Source.GEAR): Trip? = lock.withLock {
        val open = dao.open() ?: return@withLock null
        if (odoKm < open.startOdoKm) return@withLock null
        seal(open.copy(endTime = clock(), endOdoKm = odoKm, endPlace = place ?: open.endPlace, endSource = source))
    }

    /** 앱 재시작 때 '진행 중'이 남아 있으면 현재 계기판 값으로 마감한다. 주차(P) 상태에서만 호출할 것. */
    suspend fun recoverOpen(odoKm: Double): Trip? = lock.withLock {
        val open = dao.open() ?: return@withLock null
        seal(open.copy(endTime = clock(), endOdoKm = maxOf(odoKm, open.startOdoKm), endSource = Source.RECOVERED))
    }

    suspend fun classify(id: Long, p: Purpose) =
        change(id, "purpose", p.name, { it.purpose.name }) { it.copy(purpose = p) }

    /** A delayed voice answer can only classify the same, still-unclassified closed trip. */
    suspend fun classifyByVoice(id: Long, expectedEndTime: Long, p: Purpose, allowed: () -> Boolean): Boolean = lock.withLock {
        val trip = dao.get(id) ?: return@withLock false
        if (trip.endTime != expectedEndTime || trip.purpose != Purpose.UNCLASSIFIED || p == Purpose.UNCLASSIFIED ||
            !allowed()) return@withLock false
        dao.update(trip.copy(purpose = p))
        recordEdit(id, "purpose", trip.purpose.name, p.name)
        true
    }

    suspend fun setDriver(id: Long, driverId: String, department: String? = null) = lock.withLock {
        val old = dao.get(id) ?: return@withLock
        if (old.driverId == driverId && old.driverDepartment == department) return@withLock
        dao.update(old.copy(driverId = driverId, driverDepartment = department))
        if (old.driverId != driverId) recordEdit(id, "driverId", old.driverId, driverId)
        if (old.driverDepartment != department) recordEdit(id, "driverDepartment", old.driverDepartment, department)
    }

    private suspend fun recordEdit(id: Long, field: String, old: String?, new: String?) =
        dao.insertCorrection(Correction(tripId = id, field = field, oldValue = old, newValue = new,
            reason = "확정", createdAt = clock(), applied = true))

    suspend fun setEndPlace(id: Long, place: String) =
        change(id, "endPlace", place, { it.endPlace }) { it.copy(endPlace = place) }

    /** 수집값(시각·계기판 거리)은 고치지 않고 정정 요청만 남긴다. 사유는 필수. */
    suspend fun requestCorrection(id: Long, field: String, newValue: String, reason: String) {
        require(reason.isNotBlank()) { "정정 사유는 필수" }
        val t = dao.get(id) ?: return
        val old = when (field) {
            "startOdoKm" -> t.startOdoKm.toString()
            "endOdoKm" -> t.endOdoKm?.toString()
            "startTime" -> t.startTime.toString()
            "endTime" -> t.endTime?.toString()
            else -> throw IllegalArgumentException("수집값 필드가 아님: $field")
        }
        dao.insertCorrection(Correction(tripId = id, field = field, oldValue = old, newValue = newValue,
            reason = reason, createdAt = clock(), applied = false))
    }

    /** 해시 체인 검증. 어긋난 첫 운행의 id, 이상 없으면 null. */
    suspend fun firstBrokenId(): Long? {
        var prev = ""
        for (t in dao.allClosed()) {
            if (!t.intact(prev)) return t.id
            prev = t.hash
        }
        return null
    }

    /**
     * 내보내는 문서에 찍을 검증 코드. 종료 후 5분이 지나 병합될 일이 없는 '확정 운행'까지의 체인 상태를 대표한다.
     * 확정 운행이 없으면 null.
     */
    suspend fun exportCode(): String? =
        dao.allClosed().lastOrNull { it.endTime!! <= clock() - MERGE_WINDOW_MS }?.hash?.take(CODE_LEN)

    /** 문서에 찍힌 코드가 지금 체인에서 그대로 확인되는지(그 운행까지 어긋남 없음). */
    suspend fun verifyCode(code: String): Boolean {
        if (code.length < CODE_LEN) return false
        var prev = ""
        for (t in dao.allClosed()) {
            if (!t.intact(prev)) return false
            if (t.hash.startsWith(code)) return true
            prev = t.hash
        }
        return false
    }

    private fun Trip.intact(prev: String) = prevHash == prev && sealed(prev).hash == hash

    /** 운행 1건의 수정·정정 요청 이력(오래된 순). 화면의 "수정 이력" 팝업용. */
    suspend fun getCorrections(tripId: Long): List<Correction> = dao.correctionsOf(tripId)

    /** Periods.month / Periods.year 등으로 만든 구간의 운행(진행 중 포함). 문서에는 엔진이 종료된 것만 싣는다. */
    suspend fun tripsIn(range: LongRange): List<Trip> = dao.between(range.first, range.last)

    private suspend fun seal(t: Trip): Trip {
        val sealed = t.sealed(dao.lastClosed()?.hash.orEmpty())
        dao.update(sealed)
        return sealed
    }

    private suspend fun change(id: Long, field: String, new: String?, read: (Trip) -> String?, write: (Trip) -> Trip) = lock.withLock {
        val t = dao.get(id) ?: return@withLock
        val old = read(t)
        if (old == new) return@withLock
        dao.update(write(t))
        dao.insertCorrection(Correction(tripId = id, field = field, oldValue = old, newValue = new,
            reason = "확정", createdAt = clock(), applied = true))
    }
}
