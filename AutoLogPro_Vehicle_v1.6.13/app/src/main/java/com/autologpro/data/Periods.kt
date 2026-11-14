package com.autologpro.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 서울 기준 월·연 구간(밀리초, 양 끝 포함). TripRepository.tripsIn()에 넘긴다. */
object Periods {
    private val zone = ZoneId.of("Asia/Seoul")

    fun inclusive(first: LocalDate, last: LocalDate): LongRange =
        first.atStartOfDay(zone).toInstant().toEpochMilli()..(last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1)

    fun month(year: Int, month: Int): LongRange {
        val start = LocalDate.of(year, month, 1).atStartOfDay(zone)
        return start.toInstant().toEpochMilli()..start.plusMonths(1).toInstant().toEpochMilli() - 1
    }

    fun year(year: Int): LongRange {
        val start = LocalDate.of(year, 1, 1).atStartOfDay(zone)
        return start.toInstant().toEpochMilli()..start.plusYears(1).toInstant().toEpochMilli() - 1
    }

    fun thisMonth(now: Long = System.currentTimeMillis()): LongRange =
        Instant.ofEpochMilli(now).atZone(zone).let { month(it.year, it.monthValue) }

    fun thisYear(now: Long = System.currentTimeMillis()): LongRange =
        Instant.ofEpochMilli(now).atZone(zone).let { year(it.year) }
}
