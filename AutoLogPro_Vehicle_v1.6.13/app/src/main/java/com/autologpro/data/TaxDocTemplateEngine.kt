package com.autologpro.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Summary(
    val totalKm: Double,
    val commuteKm: Double,
    val businessKm: Double,
    val personalKm: Double,
    val unclassifiedKm: Double,
) {
    val workKm: Double get() = commuteKm + businessKm
    val businessRatio: Double get() = if (totalKm > 0) workKm / totalKm else 0.0
}

data class VehicleInfo(val model: String? = null, val plateNo: String? = null)

data class ReportInfo(
    val profile: LogbookProfile,
    val fiscal: Pair<LocalDate, LocalDate>,
    val selectedPeriod: String,
    val annual: Summary,
)

object TaxDocTemplateEngine {
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd (E)", Locale.KOREAN)
    private val headings = listOf("사용일자(요일)", "운전자 부서", "운전자 성명", "주행 전 계기판 누적거리(km)",
        "주행 후 계기판 누적거리(km)", "주행거리(km)", "출퇴근용 주행거리(km)", "일반업무용 주행거리(km)", "비고")

    fun validClosed(trips: List<Trip>): List<Trip> = trips.filter {
        it.endTime != null && it.distanceKm()?.let { d -> d.isFinite() && d >= 0 } == true
    }

    fun visibleRows(trips: List<Trip>): List<Trip> = validClosed(trips).filter { it.purpose != Purpose.PERSONAL }

    fun summary(trips: List<Trip>): Summary {
        val values = DoubleArray(Purpose.entries.size)
        for (t in validClosed(trips)) values[t.purpose.ordinal] += t.distanceKm()!!
        return Summary(values.sum(), values[Purpose.COMMUTE.ordinal], values[Purpose.BUSINESS.ordinal],
            values[Purpose.PERSONAL.ordinal], values[Purpose.UNCLASSIFIED.ordinal])
    }

    fun reportRows(
        trips: List<Trip>,
        driverName: (String?) -> String = { it ?: "미확인" },
        vehicle: VehicleInfo? = null,
        verifyCode: String? = null,
        info: ReportInfo? = null,
    ): Pair<List<List<String>>, List<List<String>>> {
        val s = summary(trips)
        val a = info?.annual ?: s
        val meta = buildList {
            info?.let {
                add(listOf("법인명", it.profile.company.ifBlank { "미입력" }, "사업자등록번호", it.profile.businessNo.ifBlank { "미입력" }))
                add(listOf("사업연도", "${it.fiscal.first} ~ ${it.fiscal.second}", "조회 기간", it.selectedPeriod))
            }
            val model = info?.profile?.model ?: vehicle?.model
            val plate = info?.profile?.plate ?: vehicle?.plateNo
            if (model != null || plate != null) add(listOf("차종", model.orEmpty().ifBlank { "미입력" }, "자동차등록번호", plate.orEmpty().ifBlank { "미입력" }))
        }
        val rows = buildList {
            add(headings)
            visibleRows(trips).forEach { add(cells(it, driverName)) }
            add(listOf("조회기간 합계", "", "", "", "", km(s.totalKm), km(s.commuteKm), km(s.businessKm), ""))
            add(listOf("사업연도 총주행거리(km)", km(a.totalKm)))
            add(listOf("사업연도 업무용 사용거리 합계(km)", km(a.workKm)))
            add(listOf("업무사용비율(%)", pct(a)))
            if (s.unclassifiedKm > 0 || a.unclassifiedKm > 0) add(listOf("미분류 거리 확인 필요", "업무용 합계에는 포함되지 않습니다."))
            if (s.personalKm > 0 || a.personalKm > 0) add(listOf("일부 운행은 상세 목록에서 제외되며, 해당 거리는 총주행거리에 포함됩니다."))
            if (info != null && (info.profile.company.isBlank() || info.profile.businessNo.isBlank() || info.profile.plate.isBlank()))
                add(listOf("설정에서 회사·차량 정보를 입력해 주세요."))
            verifyCode?.let { add(listOf("검증 코드", it, "확정 운행까지, 앱 해시 체인 기준")) }
        }
        return meta to rows
    }

    fun render(
        trips: List<Trip>,
        driverName: (String?) -> String = { it ?: "미확인" },
        vehicle: VehicleInfo? = null,
        verifyCode: String? = null,
        info: ReportInfo? = null,
    ): String {
        val (meta, rows) = reportRows(trips, driverName, vehicle, verifyCode, info)
        val body = rows.joinToString("") { row ->
            val cells = row.joinToString("") { cell -> "<td>${esc(cell)}</td>" }
            if (row === rows.first()) "<tr>" + row.joinToString("") { "<th>${esc(it)}</th>" } + "</tr>"
            else if (row.size < headings.size) "<tr><td colspan=9>${row.joinToString(" · ") { esc(it) }}</td></tr>"
            else "<tr>$cells</tr>"
        }
        val metaText = meta.joinToString("") { "<p>${it.chunked(2).joinToString(" · ") { pair -> pair.joinToString(": ") { cell -> esc(cell) } }}</p>" }
        return """<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>업무용승용차 운행기록부</title>
<style>body{font:14px sans-serif;margin:16px}.s{overflow-x:auto}table{border-collapse:collapse;min-width:850px;width:100%}
th,td{border:1px solid #a1aab7;padding:7px 9px;text-align:left}th{background:#edf1f7}.bar{margin-bottom:12px}
.bar a,.bar button{display:inline-block;margin-right:8px;padding:8px 12px;border:1px solid #444;border-radius:6px;background:#fff;color:#000;font:inherit;text-decoration:none}
@page{size:A4 landscape;margin:10mm}@media print{.bar{display:none}}</style></head><body>
<div class=bar><a href="?csv">CSV 다운로드</a><button onclick="window.print()">인쇄 / PDF 저장</button></div>
<h2>업무용승용차 운행기록부</h2>$metaText
<div class=s><table>$body</table></div>
<p>업무사용비율은 사업연도 업무용 거리(출퇴근 + 일반업무)를 총주행거리로 나눈 값입니다.</p>
</body></html>"""
    }

    fun renderCsv(
        trips: List<Trip>,
        driverName: (String?) -> String = { it ?: "미확인" },
        vehicle: VehicleInfo? = null,
        verifyCode: String? = null,
        info: ReportInfo? = null,
    ): String {
        val (meta, rows) = reportRows(trips, driverName, vehicle, verifyCode, info)
        return "\uFEFF" + (meta + rows).joinToString("\r\n") { row -> row.joinToString(",") { csv(it) } } + "\r\n"
    }

    private fun cells(t: Trip, driverName: (String?) -> String): List<String> {
        val d = t.distanceKm()!!
        val note = buildList {
            if (t.purpose == Purpose.UNCLASSIFIED) add("미분류")
            if (t.startPlace != null || t.endPlace != null) add("${t.startPlace.orEmpty()} → ${t.endPlace.orEmpty()}")
            if (t.startSource == Source.SIMULATED || t.endSource == Source.SIMULATED) add("시연")
            if (t.endSource == Source.RECOVERED) add("복구됨")
            if (t.startSource == Source.FALLBACK || t.endSource == Source.FALLBACK) add("대체 감지")
        }.joinToString(" / ")
        return listOf(dateFormat.format(LogbookProfile.dateAt(t.startTime)), t.driverDepartment.orEmpty().ifBlank { "미입력" }, driverName(t.driverId),
            km(t.startOdoKm), km(t.endOdoKm), km(d), km(d.takeIf { t.purpose == Purpose.COMMUTE }),
            km(d.takeIf { t.purpose == Purpose.BUSINESS }), note)
    }

    private fun csv(c: String): String {
        val safe = if (c.trimStart().firstOrNull()?.let { it in "=+-@" } == true) "'$c" else c
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }

    private fun pct(s: Summary) = String.format(Locale.ROOT, "%.1f", s.businessRatio * 100)
    private fun km(x: Double?) = x?.let { String.format(Locale.ROOT, "%.3f", it).trimEnd('0').trimEnd('.').let { n -> if ('.' in n) n else "$n.0" } }.orEmpty()
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
