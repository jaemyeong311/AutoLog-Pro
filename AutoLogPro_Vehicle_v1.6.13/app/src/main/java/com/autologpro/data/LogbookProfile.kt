package com.autologpro.data

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class LogbookProfile(
    val company: String = "",
    val businessNo: String = "",
    val model: String = DEFAULT_MODEL,
    val plate: String = "",
    val periodStart: String = "",
    val periodEnd: String = "",
    val department: String = "",
    val driver: String = "",
) {
    companion object {
        const val DEFAULT_MODEL = "제네시스 GV90"
        const val DEMO_VEHICLE_ID = "c6a0145d-06e8-49e4-8ab1-62ba1c0aad89"
        fun currentModel(model: String, plate: String): String {
            if (plate.filterNot(Char::isWhitespace) == "12가3456" && model.contains("아반떼")) return DEFAULT_MODEL
            return model.ifBlank { DEFAULT_MODEL }
        }
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        fun dateAt(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(SEOUL).toLocalDate()
    }

    fun configuredPeriod(): Pair<LocalDate, LocalDate>? {
        if (periodStart.isBlank() || periodEnd.isBlank()) return null
        return runCatching { LocalDate.parse(periodStart) to LocalDate.parse(periodEnd) }
            .getOrNull()?.takeIf { (start, end) -> !end.isBefore(start) && !end.isAfter(start.plusYears(1).minusDays(1)) }
    }

    fun periodFor(year: Int, month: Int): Pair<LocalDate, LocalDate> {
        val selected = java.time.YearMonth.of(year, month)
        return configuredPeriod()?.takeIf { (start, end) ->
            !selected.atEndOfMonth().isBefore(start) && !selected.atDay(1).isAfter(end)
        } ?: (LocalDate.of(year, 1, 1) to LocalDate.of(year, 12, 31))
    }

    fun forDemo(): LogbookProfile = copy(
        company = "오토로그 시연 법인", businessNo = "000-00-00000",
        model = DEFAULT_MODEL, plate = "12가 3456",
        department = department.ifBlank { "시연팀" }, driver = driver.ifBlank { "김대표" },
    )

    fun matches(trip: Trip): Boolean {
        fun plateKey(s: String) = s.filterNot { it.isWhitespace() }
        val priorPlate = trip.vehiclePlate
        val priorNo = trip.businessNo
        return (priorPlate.isNullOrBlank() || (!plate.isBlank() && plateKey(priorPlate) == plateKey(plate))) &&
            (priorNo.isNullOrBlank() || (!businessNo.isBlank() &&
                priorNo.filter { it.isDigit() } == businessNo.filter { it.isDigit() }))
    }

}

class LogbookProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("autolog_logbook_profile", Context.MODE_PRIVATE)
    fun get(): LogbookProfile = LogbookProfile(
        company = prefs.getString("company", "").orEmpty(),
        businessNo = prefs.getString("businessNo", "").orEmpty(),
        model = LogbookProfile.currentModel(prefs.getString("model", "").orEmpty(), prefs.getString("plate", "").orEmpty()),
        plate = prefs.getString("plate", "").orEmpty(),
        periodStart = prefs.getString("periodStart", "").orEmpty(),
        periodEnd = prefs.getString("periodEnd", "").orEmpty(),
        department = prefs.getString("department", "").orEmpty(),
        driver = prefs.getString("driver", "").orEmpty(),
    )

    fun save(p: LogbookProfile): Boolean {
        val no = p.businessNo.filter { it.isDigit() }
        if (p.businessNo.isNotBlank() && (no.length != 10 || p.businessNo.any { it !in "0123456789- " })) return false
        if ((p.periodStart.isNotBlank() || p.periodEnd.isNotBlank()) && p.configuredPeriod() == null) return false
        if (p.driver.length > 50 || p.department.length > 60 || p.company.length > 100 ||
            p.model.length > 70 || p.plate.length > 25) return false
        return prefs.edit()
            .putString("company", p.company).putString("businessNo", if (no.isBlank()) "" else "${no.take(3)}-${no.substring(3, 5)}-${no.drop(5)}")
            .putString("model", p.model.ifBlank { LogbookProfile.DEFAULT_MODEL }).putString("plate", p.plate)
            .putString("periodStart", p.periodStart).putString("periodEnd", p.periodEnd)
            .putString("department", p.department).putString("driver", p.driver).commit()
    }
}
