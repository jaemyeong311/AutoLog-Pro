package com.autologpro.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

/** Debug-only substitute for Role C's actual Room repository.
 * No connection to Pleos / AAOS Vehicle SDK is claimed.
 */
class DemoTripRepository(context: Context) : SQLiteOpenHelper(context, "autolog_demo.db", null, 1), TripGateway {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE trips (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            start_at INTEGER NOT NULL,
            end_at INTEGER,
            start_odo REAL NOT NULL,
            end_odo REAL,
            toll INTEGER NOT NULL DEFAULT 0,
            driver TEXT NOT NULL DEFAULT '김대표',
            purpose TEXT NOT NULL DEFAULT 'UNCLASSIFIED',
            end_place TEXT NOT NULL DEFAULT '',
            source TEXT NOT NULL DEFAULT '시연용 샘플 데이터')""")
        db.execSQL("CREATE TABLE corrections (id INTEGER PRIMARY KEY AUTOINCREMENT, trip_id INTEGER NOT NULL, requested_km REAL NOT NULL, reason TEXT NOT NULL, created_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun toTrip(c: Cursor): TripRecord = TripRecord(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        startAt = c.getLong(c.getColumnIndexOrThrow("start_at")),
        endAt = c.getColumnIndexOrThrow("end_at").let { if (c.isNull(it)) null else c.getLong(it) },
        startOdoKm = c.getDouble(c.getColumnIndexOrThrow("start_odo")),
        endOdoKm = c.getColumnIndexOrThrow("end_odo").let { if (c.isNull(it)) null else c.getDouble(it) },
        tollWon = c.getInt(c.getColumnIndexOrThrow("toll")),
        driver = c.getString(c.getColumnIndexOrThrow("driver")),
        purpose = runCatching { Purpose.valueOf(c.getString(c.getColumnIndexOrThrow("purpose"))) }.getOrDefault(Purpose.UNCLASSIFIED),
        endPlace = c.getString(c.getColumnIndexOrThrow("end_place")),
        source = c.getString(c.getColumnIndexOrThrow("source"))
    )
    private fun query(sql: String, args: Array<String>? = null): List<TripRecord> {
        val out = mutableListOf<TripRecord>()
        readableDatabase.rawQuery(sql, args).use { c -> while (c.moveToNext()) out.add(toTrip(c)) }
        return out
    }
    override fun allTrips(): List<TripRecord> = query("SELECT * FROM trips ORDER BY start_at DESC, id DESC")
    override fun tripsIn(year: Int, month: Int): List<TripRecord> {
        val (start, end) = Periods.monthBounds(year, month)
        return query("SELECT * FROM trips WHERE start_at >= ? AND start_at < ? ORDER BY start_at DESC, id DESC", arrayOf(start.toString(), end.toString()))
    }
    override fun recoverOpen(isParked: Boolean): TripRecord? = if (!isParked) null else query("SELECT * FROM trips WHERE end_at IS NULL ORDER BY id DESC LIMIT 1").firstOrNull()
    override fun start(odometerKm: Double): TripRecord {
        require(odometerKm >= 0)
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(db.rawQuery("SELECT id FROM trips WHERE end_at IS NULL LIMIT 1", null).use { !it.moveToFirst() }) { "이미 진행 중인 운행이 있습니다" }
            val values = ContentValues().apply {
                put("start_at", System.currentTimeMillis()); put("start_odo", odometerKm)
                put("purpose", Purpose.UNCLASSIFIED.name); put("driver", "김대표"); put("source", "시연 모드")
            }
            val id = db.insertOrThrow("trips", null, values)
            db.setTransactionSuccessful()
            return query("SELECT * FROM trips WHERE id=?", arrayOf(id.toString())).first()
        } finally { db.endTransaction() }
    }
    override fun end(odometerKm: Double, tollWon: Int): TripRecord? {
        val open = query("SELECT * FROM trips WHERE end_at IS NULL ORDER BY id DESC LIMIT 1").firstOrNull() ?: return null
        require(odometerKm >= open.startOdoKm) { "종료 누적거리가 시작값보다 작을 수 없습니다" }
        val values = ContentValues().apply {
            put("end_at", System.currentTimeMillis()); put("end_odo", odometerKm); put("toll", tollWon.coerceAtLeast(0))
        }
        writableDatabase.update("trips", values, "id=? AND end_at IS NULL", arrayOf(open.id.toString()))
        return query("SELECT * FROM trips WHERE id=?", arrayOf(open.id.toString())).firstOrNull()
    }
    override fun classify(id: Long, purpose: Purpose) = update(id, "purpose", purpose.name)
    override fun setDriver(id: Long, driver: String) = update(id, "driver", driver.take(30))
    override fun setEndPlace(id: Long, place: String) = update(id, "end_place", place.take(120))
    private fun update(id: Long, key: String, value: String) {
        writableDatabase.update("trips", ContentValues().apply { put(key, value) }, "id=?", arrayOf(id.toString()))
    }
    override fun requestCorrection(id: Long, proposedEndKm: Double, reason: String) {
        require(reason.isNotBlank() && proposedEndKm >= 0.0)
        writableDatabase.insertOrThrow("corrections", null, ContentValues().apply {
            put("trip_id", id); put("requested_km", proposedEndKm); put("reason", reason.take(300)); put("created_at", System.currentTimeMillis())
        })
        // Original odo intentionally remains unchanged; approval is external to this UI.
    }
    override fun getCorrections(): List<Correction> {
        val out = mutableListOf<Correction>()
        readableDatabase.rawQuery("SELECT trip_id,requested_km,reason,created_at FROM corrections ORDER BY created_at DESC", null).use { c ->
            while(c.moveToNext()) out += Correction(c.getLong(0), c.getDouble(1), c.getString(2), c.getLong(3))
        }
        return out
    }
    override fun clearAndSeed() {
        writableDatabase.delete("corrections", null, null)
        writableDatabase.delete("trips", null, null)
        seedIfEmpty()
    }
    fun seedIfEmpty() {
        if (allTrips().isNotEmpty()) return
        val now = System.currentTimeMillis()
        var odo = 52075.0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (index in 17 downTo 0) {
                val dayOffset = index * 75L * 60 * 1000
                val start = now - dayOffset - 25 * 60 * 1000L
                val km = when(index % 5) { 0 -> 17.7; 1 -> 12.4; 2 -> 24.3; 3 -> 9.8; else -> 15.5 }
                val p = when (index % 6) { 0 -> Purpose.UNCLASSIFIED; 1 -> Purpose.PERSONAL; 2 -> Purpose.COMMUTE; else -> Purpose.BUSINESS }
                val values = ContentValues().apply {
                    put("start_at", start); put("end_at", start + 25 * 60 * 1000L)
                    put("start_odo", odo); put("end_odo", odo + km)
                    put("toll", if (index % 3 == 0) 2500 else 0)
                    put("purpose", p.name); put("driver", listOf("김대표", "이과장", "박대리")[index % 3])
                    put("end_place", listOf("서울 서초구", "경기 성남시", "대전 서구")[index % 3])
                    put("source", "시연용 샘플 데이터")
                }
                db.insertOrThrow("trips", null, values)
                odo += km
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
