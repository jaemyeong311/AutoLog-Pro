package com.autologpro.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogbookMigrationTest {
    @Test fun migrationKeepsOldRecordAndStoresNewDriverAndVehicle() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "logbook-migration.db"
        context.deleteDatabase(name)
        val old = Trip(id = 1, driverId = "이전운전자", startTime = 1000, startOdoKm = 100.0,
            endTime = 2000, endOdoKm = 110.0, endSource = Source.GEAR, purpose = Purpose.PERSONAL).sealed("")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE trip (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, driverId TEXT, startTime INTEGER NOT NULL, startOdoKm REAL NOT NULL, endTime INTEGER, endOdoKm REAL, startPlace TEXT, endPlace TEXT, purpose TEXT NOT NULL, startSource TEXT NOT NULL, endSource TEXT, prevHash TEXT NOT NULL, hash TEXT NOT NULL)")
            db.execSQL("CREATE TABLE correction (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, tripId INTEGER NOT NULL, field TEXT NOT NULL, oldValue TEXT, newValue TEXT, reason TEXT NOT NULL, createdAt INTEGER NOT NULL, applied INTEGER NOT NULL)")
            db.execSQL("INSERT INTO trip(id,driverId,startTime,startOdoKm,endTime,endOdoKm,purpose,startSource,endSource,prevHash,hash) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(old.id, old.driverId, old.startTime, old.startOdoKm, old.endTime, old.endOdoKm, old.purpose.name, old.startSource.name, old.endSource!!.name, old.prevHash, old.hash))
            db.version = 1
        }
        try {
            val profile = LogbookProfile("법인", "123-45-67890", LogbookProfile.DEFAULT_MODEL, "12가 3456", department = "영업", driver = "새운전자")
            assertTrue(LogbookProfileStore(context).save(profile))
            assertEquals(profile, LogbookProfileStore(context).get())
            val db = AppDatabase.build(context, name)
            try {
                val dao = db.tripDao()
                assertEquals(old, dao.get(1))
                var now = 1_000_000L
                val repo = TripRepository(dao) { now }
                val trip = repo.start(profile.driver, 110.0, profile = profile)
                now += 1000
                repo.end(127.7)
                repo.classify(trip.id, Purpose.COMMUTE)
                assertEquals("영업", dao.get(trip.id)?.driverDepartment)
                assertEquals("12가 3456", dao.get(trip.id)?.vehiclePlate)
                assertNull(repo.firstBrokenId())
                assertEquals(27.7, TaxDocTemplateEngine.summary(dao.allClosed()).totalKm, 0.001)
            } finally { db.close() }
        } finally {
            context.deleteDatabase(name)
            LogbookProfileStore(context).save(LogbookProfile())
        }
    }
}
