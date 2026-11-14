package com.autologpro.data

import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 시연용 샘플 데이터(영상·화면 시안용). 릴리스 빌드에 들어가면 안 되므로 src/debug 아래에 두고,
 * 호출할 때도 isDebug(BuildConfig.DEBUG)를 넘겨 한 번 더 막는다. 이미 기록이 있으면 만들지 않는다.
 * 합계·비율은 하드코딩이 아니라 만들어진 기록에서 계산된다. 영상·시안에는 "시연용 샘플 데이터"라고 밝힐 것.
 */
object SampleDataGenerator {
    private val distances = doubleArrayOf(
        52.3, 18.7, 41.2, 63.5, 12.4, 27.8, 74.1, 15.6, 48.9, 33.3, 58.0, 22.5, 69.4,
        11.2, 45.7, 36.1, 61.8, 19.9, 53.6, 28.4, 66.2, 14.3, 42.5, 31.7, 55.9,
    )
    private val places = listOf("서초구 양재동", "강남구 역삼동", "성남시 분당구 정자동", "송파구 잠실동", "마포구 상암동")
    private val zone = ZoneId.of("Asia/Seoul")

    private const val CORRECTION_AT = 3 // 정정 요청 1건(이력 팝업 시연용)
    private const val RECOVER_AT = 10 // 앱 재시작 복구 1건
    private const val FALLBACK_AT = 16 // 기어 대체 감지 1건

    /** 선택한 달에 하루 한 건씩 25건을 만든다. 만든 건수를 돌려준다. */
    suspend fun generate(dao: TripDao, year: Int, month: Int, isDebug: Boolean, startOdoKm: Double = 41_230.5): Int {
        require(isDebug) { "시연 데이터는 디버그 빌드에서만 만든다" }
        require(dao.allClosed().isEmpty() && dao.open() == null) { "이미 기록이 있어 만들지 않는다" }
        var now = 0L
        val repo = TripRepository(dao) { now }
        var odo = startOdoKm
        distances.forEachIndexed { i, d ->
            val source = if (i == FALLBACK_AT) Source.FALLBACK else Source.GEAR
            val begin = ZonedDateTime.of(year, month, 1 + i % 28, 9 + i % 3 * 3, 0, 0, 0, zone).toInstant().toEpochMilli()
            now = begin
            val t = repo.start(if (i % 4 == 3) "박 과장(시연)" else "김 대표(시연)", odo, places[i % places.size], source)
            now = begin + (d / 50 * 3_600_000).toLong()
            odo = Math.round((odo + d) * 10) / 10.0
            if (i == RECOVER_AT) repo.recoverOpen(odo) else repo.end(odo, places[(i + 1) % places.size], source)
            now += 3_600_000
            repo.classify(t.id, purposeOf(i))
            if (i == CORRECTION_AT) repo.requestCorrection(t.id, "endOdoKm", "${odo + 0.5}", "계기판 교체 확인")
        }
        return distances.size
    }

    private fun purposeOf(i: Int) = when {
        i == 1 || i == 7 -> Purpose.UNCLASSIFIED
        i == 4 || i == 13 || i == 21 -> Purpose.PERSONAL
        i % 5 == 0 -> Purpose.COMMUTE
        else -> Purpose.BUSINESS
    }
}
