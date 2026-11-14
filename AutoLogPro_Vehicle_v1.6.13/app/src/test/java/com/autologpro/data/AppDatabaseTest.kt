package com.autologpro.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 진짜 Room(메모리 DB)으로 쿼리·엔티티·enum/null 변환을 확인한다. 에뮬레이터나 기기가 필요하다(src/androidTest에 둘 것).
 * 필요한 의존성: androidx.test.ext:junit, androidx.test:core, kotlinx-coroutines (androidTestImplementation).
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    private lateinit var db: AppDatabase
    private var now = 1_000_000L

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    }

    @After fun close() = db.close()

    @Test fun 저장한_운행과_이력을_쿼리로_읽는다() = runBlocking {
        val dao = db.tripDao()
        val repo = TripRepository(dao) { now }

        repo.start("a", 100.0, "서초구")
        assertNull(dao.open()!!.endSource) // 진행 중: null enum이 저장·복원된다
        now += 600_000
        repo.end(110.0, "강남구")
        assertNull(dao.open())

        repo.classify(1, Purpose.BUSINESS)
        repo.requestCorrection(1, "endOdoKm", "111.0", "확인")

        val t = dao.get(1)!!
        assertEquals(Purpose.BUSINESS, t.purpose)
        assertEquals(Source.GEAR, t.endSource)
        assertEquals(1, dao.allClosed().size)
        assertEquals(1, dao.between(0L, 2_000_000L).size)
        assertEquals(2, repo.getCorrections(1).size)
        assertNull(repo.firstBrokenId())
        assertNotNull(dao.lastClosed())
    }
}
