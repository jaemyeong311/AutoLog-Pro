package com.autologpro.core

import android.content.Context
import com.autologpro.data.AppDatabase
import com.autologpro.data.Source
import com.autologpro.data.Trip
import com.autologpro.data.TripRepository

/**
 * DB·저장소를 앱 전체에서 하나씩만 쓰게 한다. (TripRepository 안의 Mutex는 인스턴스마다 따로라서 여러 개면 동시 쓰기 방지가 깨진다)
 *
 * 실제 운행:     autolog.db      (C의 AppDatabase.build)
 * 시뮬레이션:   autolog_sim.db  (파일 자체가 분리 → 실제 기록과 섞일 수 없음, 초기화도 이 파일만 삭제)
 */
object AppDb {
    const val SIM_DB_NAME = "autolog_sim.db"

    @Volatile private var real: AppDatabase? = null
    @Volatile private var realRepo: TripRepository? = null
    @Volatile private var sim: AppDatabase? = null
    @Volatile private var simRepo: TripRepository? = null

    fun get(context: Context): AppDatabase =
        real ?: synchronized(this) { real ?: AppDatabase.build(context.applicationContext).also { real = it } }

    /** 실제 운행 저장소 */
    fun repo(context: Context): TripRepository =
        realRepo ?: synchronized(this) { realRepo ?: TripRepository(get(context).tripDao()).also { realRepo = it } }

    fun simDb(context: Context): AppDatabase =
        sim ?: synchronized(this) {
            sim ?: AppDatabase.build(context.applicationContext, SIM_DB_NAME).also { sim = it }
        }

    /** 시뮬레이션 저장소 (Debug 전용으로만 쓰임) */
    fun simRepo(context: Context): TripRepository =
        simRepo ?: synchronized(this) { simRepo ?: TripRepository(simDb(context).tripDao()).also { simRepo = it } }

    fun simExists(context: Context): Boolean = sim != null || context.getDatabasePath(SIM_DB_NAME).exists()

    /** 운행이 어느 DB 것인지 보고 맞는 저장소를 준다. 분류(classify)·운전자(setDriver) 저장 때 이걸 쓸 것. */
    fun repoFor(context: Context, trip: Trip): TripRepository =
        if (trip.startSource == Source.SIMULATED) simRepo(context) else repo(context)

    /** 시뮬레이션 DB만 닫고 파일을 지운다. 실제 DB는 건드리지 않는다. */
    fun deleteSim(context: Context) = synchronized(this) {
        runCatching { sim?.close() }
        sim = null
        simRepo = null
        context.applicationContext.deleteDatabase(SIM_DB_NAME)
    }
}
