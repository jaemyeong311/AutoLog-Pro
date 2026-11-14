package com.autologpro.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.TimeUnit

data class MaintenanceRecord(
    val id: Long,
    val performedAt: Long,
    val description: String,
    val odometerKm: Double,
    val costWon: Int,
    val shop: String
)

/** Local maintenance demo only. Does not claim evidence/receipt storage. */
class DemoMaintenanceRepository(context: Context) :
    SQLiteOpenHelper(context, "autolog_maintenance_demo.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE maintenance (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            performed_at INTEGER NOT NULL,
            description TEXT NOT NULL,
            odometer_km REAL NOT NULL,
            cost INTEGER NOT NULL,
            shop TEXT NOT NULL DEFAULT ''
        )""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun all(): List<MaintenanceRecord> {
        val items = ArrayList<MaintenanceRecord>()
        readableDatabase.rawQuery(
            "SELECT id, performed_at, description, odometer_km, cost, shop FROM maintenance ORDER BY performed_at DESC, id DESC", null
        ).use { c -> while (c.moveToNext()) items.add(
            MaintenanceRecord(c.getLong(0), c.getLong(1), c.getString(2), c.getDouble(3), c.getInt(4), c.getString(5))
        ) }
        return items
    }
    fun add(description: String, odometerKm: Double, costWon: Int, shop: String = "", at: Long = System.currentTimeMillis()) {
        require(description.isNotBlank() && odometerKm >= 0 && costWon >= 0)
        writableDatabase.insertOrThrow("maintenance", null, ContentValues().apply {
            put("performed_at", at)
            put("description", description.take(100)); put("odometer_km", odometerKm)
            put("cost", costWon); put("shop", shop.take(80))
        })
    }
    fun clearAndSeed() {
        writableDatabase.delete("maintenance", null, null)
        seedIfEmpty()
    }
    fun seedIfEmpty() {
        if (all().isNotEmpty()) return
        val now = System.currentTimeMillis()
        val samples = listOf(
            Triple("에어컨 필터 교체 및 구동 배터리 점검", 145_000, "시연용 정비소"),
            Triple("와이퍼 블레이드 교체", 38_000, "시연용 정비소"),
            Triple("타이어 위치교환", 45_000, "시연용 정비소")
        )
        val db = writableDatabase
        db.beginTransaction()
        try {
            samples.forEachIndexed { i, (description, cost, shop) ->
                db.insertOrThrow("maintenance", null, ContentValues().apply {
                    put("performed_at", now - TimeUnit.DAYS.toMillis((i + 1) * 30L))
                    put("description", description); put("odometer_km", 41_000.0 - i * 1_500)
                    put("cost", cost); put("shop", shop)
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
