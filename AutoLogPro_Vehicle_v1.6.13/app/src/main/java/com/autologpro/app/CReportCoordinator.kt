package com.autologpro.app

import android.content.Context
import com.autologpro.core.AppDb
import com.autologpro.data.LogbookProfile
import com.autologpro.data.LogbookProfileStore
import com.autologpro.data.Periods
import com.autologpro.data.Purpose as DbPurpose
import com.autologpro.data.ReportInfo
import com.autologpro.data.Source
import com.autologpro.data.TaxDocTemplateEngine
import com.autologpro.data.Trip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.YearMonth

enum class ReportScope { DEMO, VEHICLE }
data class ReportBundle(val html: String, val csv: String, val scope: ReportScope, val title: String)

class CReportCoordinator(private val context: Context, private val legacy: DemoTripRepository) {
    suspend fun hasVehicleTrips(): Boolean = withContext(Dispatchers.IO) {
        AppDb.get(context).tripDao().allClosed().isNotEmpty()
    }

    suspend fun build(scope: ReportScope, year: Int, month: Int): ReportBundle = withContext(Dispatchers.IO) {
        val demo = scope == ReportScope.DEMO
        val saved = LogbookProfileStore(context).get()
        val current = if (demo) saved.forDemo() else saved
        val period = current.periodFor(year, month)
        val monthRange = YearMonth.of(year, month)
        val bounds = Periods.month(year, month)
        val fiscalBounds = Periods.inclusive(period.first, period.second)
        val records: List<Trip> = if (!demo) {
            AppDb.get(context).tripDao().allClosed().filter(current::matches)
        } else {
            val room = if (AppDb.simExists(context)) AppDb.simDb(context).tripDao().allClosed() else emptyList()
            val old = legacy.allTrips().filter { it.endAt != null }.map { t ->
                Trip(
                    id = t.id, driverId = t.driver, driverDepartment = "시연팀",
                    startTime = t.startAt, endTime = t.endAt,
                    startOdoKm = t.startOdoKm, endOdoKm = t.endOdoKm, endPlace = t.endPlace,
                    purpose = DbPurpose.valueOf(t.purpose.name), startSource = Source.SIMULATED, endSource = Source.SIMULATED,
                )
            }
            room + old
        }
        val annualRecords = records.filter { it.startTime in fiscalBounds }
        val monthlyRecords = annualRecords.filter { it.startTime in bounds }.sortedBy { it.startTime }
        val latest = monthlyRecords.lastOrNull() ?: annualRecords.maxByOrNull { it.startTime }
        val profile = if (demo || latest == null) current else current.copy(
            company = latest.legalCompany ?: current.company, businessNo = latest.businessNo ?: current.businessNo,
            model = if (current.model == LogbookProfile.DEFAULT_MODEL) current.model else latest.vehicleModel ?: current.model, plate = latest.vehiclePlate ?: current.plate,
        )
        val info = ReportInfo(profile, period, "${monthRange.atDay(1)} ~ ${monthRange.atEndOfMonth()}", TaxDocTemplateEngine.summary(annualRecords))
        val verification = if (demo) null else AppDb.repo(context).exportCode()
        val rawHtml = TaxDocTemplateEngine.render(monthlyRecords, verifyCode = verification, info = info)
        val rawCsv = TaxDocTemplateEngine.renderCsv(monthlyRecords, verifyCode = verification, info = info)
        val html = if (demo) rawHtml.replace("<body>", "<body><p style='font-weight:700;background:#fff3cd;padding:10px'>시연용 샘플 데이터 — 실제 차량 운행기록이 아닙니다.</p>") else rawHtml
        val csv = if (demo) "\uFEFF\"시연용 샘플 데이터 — 실제 차량 기록이 아닙니다\"\r\n" + rawCsv.removePrefix("\uFEFF") else rawCsv
        val title = "${if (demo) "시연용 샘플 데이터" else "차량 운행기록"} · ${year}년 ${month}월"
        ReportBundle(html, csv, scope, title)
    }
}
