package com.autologpro.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import com.autologpro.core.VehicleCoreService
import com.autologpro.core.DriveState
import com.autologpro.core.SimResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import com.autologpro.data.QrExportManager
import com.autologpro.data.Source
import com.autologpro.voice.VoiceMessages
import com.autologpro.voice.VoiceOutcome
import com.autologpro.voice.VoicePurposeController
import com.autologpro.data.LogbookProfile
import com.autologpro.data.LogbookProfileStore
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.time.format.DateTimeFormatter

/** B v1.4 ORIGINAL offline UI. A v3 controls trip lifecycle; C Room persists records.
 * C's tax templates and parked-state QR export are used for report actions.
 * Pleos hardware signal support still needs on-emulator verification.
 */
@Suppress("DEPRECATION")
class InCarDashboardActivity : Activity() {
    private val legacyRepo by lazy { DemoTripRepository(applicationContext) }
    private val repo by lazy { CoreTripGateway(applicationContext, legacyRepo) }
    private var uiScope: CoroutineScope? = null
    // Simulator runs to completion even if the UI becomes temporarily invisible.
    private val demoScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var demoJob: Job? = null
    private val shownCorePopupIds = mutableSetOf<Long>()
    private val automation by lazy { DriveAutomation(this,repo,{ !driving && canShare() },
        { demo -> repo.allTrips().filter { it.id < 0 && (it.id % 2L != 0L) == demo } }, ::refresh) }
    private val automationTick = object : Runnable {
        override fun run() {
            automation.sync()
            handler.postDelayed(this,12_000)
        }
    }
    internal fun cloudConnected() = automation.enabled
    internal fun chooseCloudDriver(id:Long) = automation.chooseDriverForTrip(id)
    internal fun classifyFromCar(id:Long,purpose:Purpose) {
        repo.classify(id,purpose)
        automation.changed(id,purpose,"car_ui")
    }
    internal fun purposeTip(t: TripRecord, rows: List<TripRecord>): PurposeTip? = automation.tip(t, rows)
    private val voice by lazy { VoicePurposeController(applicationContext) }
    private var voiceTripId: Long? = null
    @Volatile private var resumedForVoice = false
    private val maint by lazy { DemoMaintenanceRepository(applicationContext) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    @Volatile private var driving = false
    private var exportCsvText: String? = null
    private var cloudFile: ByteArray? = null
    private var qrManager: QrExportManager? = null
    private val profileStore by lazy { LogbookProfileStore(applicationContext) }
    internal fun driverForNewTrips(): LogbookProfile = profileStore.get()
    private val cReports by lazy { CReportCoordinator(applicationContext, legacyRepo) }
    private val seoul = TimeZone.getTimeZone("Asia/Seoul")
    private val reportCalendar = Calendar.getInstance(seoul)
    @Volatile private var reportYear: Int = reportCalendar.get(Calendar.YEAR)
    @Volatile private var reportMonth: Int = reportCalendar.get(Calendar.MONTH) + 1
    private val fullFmt = DateTimeFormatter.ofPattern("yyyy.MM.dd (E) HH:mm", Locale.KOREA).withZone(LogbookProfile.SEOUL)
    private val shortFmt = DateTimeFormatter.ofPattern("MM.dd (E) HH:mm", Locale.KOREA).withZone(LogbookProfile.SEOUL)
    private val dateFmt = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) { legacyRepo.seedIfEmpty(); maint.seedIfEmpty() }
        // One-time upgrade preference: this release opens in v1.4's 기본(라이트) mode.
        // Afterwards an explicit dark-mode selection continues to persist across launches.
        val appearancePrefs = getPreferences(MODE_PRIVATE)
        if (!appearancePrefs.getBoolean("appearance_light_default_v143", false)) {
            appearancePrefs.edit()
                .putString("appearance", "light")
                .putBoolean("appearance_light_default_v143", true)
                .apply()
        }
        VehicleCoreService.start(this)
        web = WebView(this).apply {
            setBackgroundColor(Color.rgb(246, 247, 248))
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.useWideViewPort = true
            // Avoid WebView scaling the entire 1280px design down on Pleos split displays.
            // CSS now reflows at the measured app-pane viewport instead.
            settings.loadWithOverviewMode = false
            settings.textZoom = 100
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.setAllowFileAccessFromFileURLs(false)
            settings.setAllowUniversalAccessFromFileURLs(false)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            addJavascriptInterface(NativeBridge(), "AutoLogNative")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    url?.startsWith("file:///android_asset/") != true
            }
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web, ViewGroup.LayoutParams(-1, -1))
    }

    fun tripRepository(): TripGateway = repo
    /** Dialogs must reflect the WebView's persisted v1.4 appearance. */
    fun currentAppearance(): String =
        getPreferences(MODE_PRIVATE).getString("appearance", "light") ?: "light"

    fun onTripContextCompleted(id: Long) {
        cancelVoiceFor(id)
        if (id < 0) {
            repo.coreTrip(id)?.let { VehicleCoreService.markPopupHandled(this, it) }
        }
        refresh()
        automation.sync()
    }
    private fun js(command: String) = handler.post {
        if (!isFinishing && !isDestroyed) web.evaluateJavascript(command, null)
    }
    private fun refresh() { js("refreshFromAndroid()") }
    private fun toast(msg: String) = handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    override fun onStart() {
        super.onStart()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        uiScope = scope
        handler.post(automationTick)
        scope.launch {
            // Saved trip recoveries must be queried because Channel events are not process-persistent.
            runCatching { VehicleCoreService.pendingPopups(this@InCarDashboardActivity) }
                .onSuccess { pending -> pending.lastOrNull()?.let { showCoreModal(it) } }
                .onFailure { android.util.Log.w("AutoLogB14", "pendingPopup 실패", it) }
        }
        scope.launch {
            VehicleCoreService.tripClosed.collect { trip ->
                driving = false
                js("stopDriving()")
                automation.captureDriver(CoreTripGateway.toRecord(trip))
                showCoreModal(trip)
                automation.sync()
            }
        }
        scope.launch {
            VehicleCoreService.state.collect { state ->
                if (state != DriveState.PARKED) cancelVoiceIfVehicleMoved()
                if (state == DriveState.DRIVING) automation.closePairingDialog()
                // The OS owns driving-time display restrictions. No touch UI is forced while driving.
                if (VehicleCoreService.simulationActive.value && state == DriveState.DRIVING) js("showDriving()")
                else if (!driving) refresh()
            }
        }
        scope.launch {
            VehicleCoreService.realState.collect { state ->
                cancelVoiceIfVehicleMoved()
                if (state == DriveState.DRIVING) automation.closePairingDialog()
            }
        }
        scope.launch { VehicleCoreService.lastError.collect { message ->
            if (message != null) android.util.Log.w("AutoLogB14", message)
        } }
    }
    private fun showCoreModal(trip: com.autologpro.data.Trip) {
        if (trip.startSource != com.autologpro.data.Source.SIMULATED && !VehicleCoreService.isParked()) return
        if (!shownCorePopupIds.add(CoreTripGateway.displayId(trip))) return
        showModal(CoreTripGateway.displayId(trip))
    }
    override fun onResume() {
        super.onResume()
        resumedForVoice = true
        VehicleCoreService.refreshPermissions()
        // CAR_SPEED may be a runtime permission; user can grant it in Android settings.
        val missing = VehicleCoreService.missingRuntimePermissions(this)
        if (missing.isNotEmpty() && !getPreferences(MODE_PRIVATE).getBoolean("car_permissions_asked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("car_permissions_asked", true).apply()
            requestPermissions(missing.toTypedArray(), 4201)
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 4201) VehicleCoreService.refreshPermissions()
    }
    override fun onPause() {
        resumedForVoice = false
        cancelVoiceFor()
        super.onPause()
    }

    override fun onStop() {
        handler.removeCallbacks(automationTick)
        automation.closePairingDialog()
        uiScope?.cancel()
        uiScope = null
        qrManager?.stop()
        qrManager = null
        super.onStop()
    }
    override fun onDestroy() {
        // Do not cancel a started simulation: A's Room trip must receive its closing event.
        handler.removeCallbacksAndMessages(null)
        web.removeJavascriptInterface("AutoLogNative")
        web.destroy()
        super.onDestroy()
    }
    private fun showModal(id: Long) {
        if (!isFinishing && !isDestroyed && fragmentManager.findFragmentByTag("trip_context") == null)
            ContextModalFragment.show(fragmentManager, id)
    }

    internal fun voiceEnabled(): Boolean = voice.isEnabled

    private fun voiceAllowed(simulated: Boolean): Boolean {
        val real = VehicleCoreService.realState.value
        val active = VehicleCoreService.state.value
        return resumedForVoice && !driving && active == DriveState.PARKED &&
            (real == DriveState.PARKED || (simulated && BuildConfig.DEBUG &&
                real == DriveState.UNKNOWN && VehicleCoreService.simulationActive.value))
    }

    private fun cancelVoiceIfVehicleMoved() {
        val id = voiceTripId ?: return
        if (!voiceAllowed(id < 0 && id % 2L != 0L)) cancelVoiceFor(id)
    }

    internal fun cancelVoiceFor(id: Long? = null) {
        if (voiceTripId == null || (id != null && voiceTripId != id)) return
        voiceTripId = null
        voice.cancel()
    }

    internal fun startVoiceFor(dialog: ContextModalFragment, id: Long) {
        if (!voice.isEnabled || !dialog.isShowingTrip(id) || voiceTripId != null) return
        val trip = repo.coreTrip(id) ?: return
        val end = trip.endTime ?: return
        val simulated = trip.startSource == Source.SIMULATED
        if (trip.purpose != com.autologpro.data.Purpose.UNCLASSIFIED || !voiceAllowed(simulated)) return
        voiceTripId = id
        val started = runCatching {
            voice.startFor(id) { tripId, outcome ->
                if (voiceTripId != tripId) return@startFor
                voiceTripId = null
                if (!voice.isEnabled || !dialog.isShowingTrip(tripId) || !voiceAllowed(simulated)) return@startFor
                when (outcome) {
                    is VoiceOutcome.Classified -> {
                        val saved = runCatching {
                            repo.classifyByVoice(tripId, end, outcome.purpose) { voiceAllowed(simulated) }
                        }.onFailure { android.util.Log.w("AutoLogVoice", "목적 저장 실패", it) }.getOrDefault(false)
                        if (saved) {
                            automation.changed(tripId,outcome.purpose,"car_voice")
                            toast(VoiceMessages.saved(outcome.purpose))
                            dialog.dismissAllowingStateLoss()
                        } else toast("음성으로 저장하지 못했어요. 화면에서 확인해 주세요.")
                    }
                    VoiceOutcome.Skipped -> dialog.dismissAllowingStateLoss()
                    is VoiceOutcome.NotUnderstood, VoiceOutcome.NoAnswer -> toast(VoiceMessages.NOT_UNDERSTOOD)
                    is VoiceOutcome.Failed -> {
                        android.util.Log.w("AutoLogVoice", "음성 분류 불가: ${outcome.reason}")
                        toast("음성 분류를 사용할 수 없어요. 화면에서 선택해 주세요.")
                    }
                    VoiceOutcome.Cancelled -> Unit
                }
            }
        }.onFailure {
            android.util.Log.w("AutoLogVoice", "음성 세션 시작 실패", it)
            cancelVoiceFor(id)
            toast("음성 분류를 사용할 수 없어요. 화면에서 선택해 주세요.")
        }.getOrDefault(false)
        if (!started && voiceTripId == id) voiceTripId = null
    }

    private fun state(): String {
        val trips = JSONArray()
        val all = repo.allTrips()
        val profile = profileStore.get()
        val real = all.filter { it.id < 0 && it.id % 2L == 0L }
        val matchingReal = real.filter {
            (it.vehiclePlate.isNullOrBlank() || it.vehiclePlate.filterNot(Char::isWhitespace) == profile.plate.filterNot(Char::isWhitespace)) &&
                (it.businessNo.isNullOrBlank() || it.businessNo.filter(Char::isDigit) == profile.businessNo.filter(Char::isDigit))
        }
        val reportDemo = matchingReal.none { it.endAt != null }
        val displayed = if (reportDemo) all.filterNot { it.id < 0 && it.id % 2L == 0L } else matchingReal
        val reportBase = if (reportDemo) profile.forDemo() else profile
        val fiscal = reportBase.periodFor(reportYear, reportMonth)
        val fiscalTrips = displayed.filter { it.endAt != null && LogbookProfile.dateAt(it.startAt) in fiscal.first..fiscal.second }
        val latest = fiscalTrips.firstOrNull { val d = LogbookProfile.dateAt(it.startAt); d.year == reportYear && d.monthValue == reportMonth }
            ?: fiscalTrips.firstOrNull()
        val reportProfile = if (reportDemo || latest == null) reportBase else reportBase.copy(
            company = latest.legalCompany ?: reportBase.company, businessNo = latest.businessNo ?: reportBase.businessNo,
            model = if (reportBase.model == LogbookProfile.DEFAULT_MODEL) reportBase.model else latest.vehicleModel ?: reportBase.model, plate = latest.vehiclePlate ?: reportBase.plate,
        )
        displayed.forEach { t ->
            val c=Calendar.getInstance(seoul).apply { timeInMillis=t.startAt }
            if (t.purpose == Purpose.PERSONAL) {
                // The web UI gets only date buckets and distance needed for aggregate totals.
                trips.put(JSONObject().apply {
                    put("year",c.get(Calendar.YEAR)); put("month",c.get(Calendar.MONTH)+1)
                    put("day",c.get(Calendar.DAY_OF_MONTH)); put("closed",t.endAt != null)
                    put("dateIso",LogbookProfile.dateAt(t.startAt).toString())
                    put("purpose","personal"); put("dist",t.distanceKm)
                })
                return@forEach
            }
            val duration=TripPresentation.duration(t.startAt, t.endAt)
            trips.put(JSONObject().apply {
                put("id",t.id);put("year",c.get(Calendar.YEAR));put("month",c.get(Calendar.MONTH)+1)
                put("day",c.get(Calendar.DAY_OF_MONTH));put("closed",t.endAt != null)
                val instant = java.time.Instant.ofEpochMilli(t.startAt)
                put("date",shortFmt.format(instant));put("fullDate",fullFmt.format(instant))
                put("dateIso", LogbookProfile.dateAt(t.startAt).toString())
                put("driverDepartment", t.driverDepartment.ifBlank { if (t.id >= 0) "시연팀" else "미입력" })
                put("route","출발지 미기록 → " + t.endPlace.ifBlank { "도착지 미입력" })
                put("dist",t.distanceKm);put("time",duration)
                put("startOdo",t.startOdoKm);put("endOdo",t.endOdoKm?:t.startOdoKm)
                put("purpose",when(t.purpose){Purpose.COMMUTE->"commute";Purpose.BUSINESS->"business";Purpose.PERSONAL->"personal";Purpose.UNCLASSIFIED->"unclassified"})
                put("syncMemo",automation.memo(t.id));put("driver",t.driver);put("endPlace",t.endPlace);put("source",t.source)
            })
        }
        val maintenance=JSONArray()
        maint.all().forEach { m ->
            maintenance.put(JSONObject().apply {
                put("id",m.id);put("date",dateFmt.format(Date(m.performedAt)))
                put("year",Calendar.getInstance().apply{timeInMillis=m.performedAt}.get(Calendar.YEAR))
                put("description",m.description);put("odo",m.odometerKm);put("cost",m.costWon);put("shop",m.shop)
            })
        }
        return JSONObject().apply {
            put("trips",trips);put("maintenance",maintenance)
            put("odometer",displayed.firstOrNull()?.let{it.endOdoKm?:it.startOdoKm}?:0.0)
            put("driver",profile.driver.ifBlank { "미입력" })
            put("profile", profile.json())
            put("reportProfile", reportProfile.json())
            put("reportFiscalStart", fiscal.first.toString()); put("reportFiscalEnd", fiscal.second.toString())
            put("reportDemo", reportDemo)
            put("voiceClassify", voice.isEnabled)
            put("automationStatus",automation.status)
            put("cloudConnected",automation.enabled)
            put("detectMode", VehicleCoreService.detectMode()?.name ?: "NONE")
            put("vehicleStatus", VehicleCoreService.realState.value.name)
            put("simulationActive", VehicleCoreService.simulationActive.value)
            put("accelDemoAvailable", VehicleCoreService.accelDemoAvailable(this@InCarDashboardActivity))
            put("accelDemoEnabled", VehicleCoreService.accelDemoEnabled(this@InCarDashboardActivity))
        }.toString()
    }

    private fun LogbookProfile.json() = JSONObject().apply {
        put("company", company); put("businessNo", businessNo); put("model", model); put("plate", plate)
        put("periodStart", periodStart); put("periodEnd", periodEnd); put("department", department); put("driver", driver)
    }

    inner class NativeBridge {
        @JavascriptInterface fun saveLogbookProfile(value: String): Boolean {
            if (value.length > 4096 || driving || VehicleCoreService.realState.value == DriveState.DRIVING ||
                VehicleCoreService.state.value == DriveState.DRIVING) return false
            return runCatching {
                val form = JSONObject(value)
                fun get(key: String) = form.optString(key).trim()
                profileStore.save(LogbookProfile(get("company"), get("businessNo"), get("model"),
                    get("plate"), get("periodStart"), get("periodEnd"), get("department"), get("driver")))
            }.getOrDefault(false)
        }
        @JavascriptInterface fun openAutomation(action: String) = handler.post {
            when(action) {
                "server" -> automation.configure()
                "invite" -> automation.invite()
                "service" -> automation.service()
                "driver" -> automation.selectDriver()
                "sync" -> automation.sync()
            }
        }
        @JavascriptInterface fun getState(): String = state()
        @JavascriptInterface fun getTheme(): String = currentAppearance()
        @JavascriptInterface fun toggleAccelDemo(): Boolean {
            val current = VehicleCoreService.accelDemoEnabled(this@InCarDashboardActivity)
            val ok = VehicleCoreService.setAccelDemoEnabled(this@InCarDashboardActivity, !current)
            if (!ok) toast("주행 중에는 시연 모드를 변경할 수 없습니다.")
            else if (!current) handler.post {
                // CAR_SPEED is a dangerous AAOS permission; users may have denied it before.
                val speedPermission = "android.car.permission.CAR_SPEED"
                if (checkSelfPermission(speedPermission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(speedPermission), 4201)
                } else {
                    VehicleCoreService.refreshPermissions()
                }
            }
            refresh()
            return ok
        }

        @JavascriptInterface fun toggleVoiceClassify() {
            handler.post {
                if (isFinishing || isDestroyed) return@post
                if (driving || VehicleCoreService.realState.value == DriveState.DRIVING ||
                    VehicleCoreService.state.value == DriveState.DRIVING) {
                    toast("주차한 뒤 음성 분류 설정을 변경해 주세요.")
                    return@post
                }
                voice.settings.enabled = !voice.settings.enabled
                if (!voice.isEnabled) cancelVoiceFor()
                toast(if (voice.isEnabled) "음성 분류를 켰어요." else "음성 분류를 껐어요.")
                refresh()
            }
        }

        @JavascriptInterface fun setReportPeriod(year: Int, month: Int) {
            if (year in 2020..2100 && month in 1..12) {
                reportYear = year; reportMonth = month
            }
        }

        @JavascriptInterface fun setTheme(theme: String) {
            if (theme != "light" && theme != "dark") return
            getPreferences(MODE_PRIVATE).edit().putString("appearance", theme).apply()
        }

        @JavascriptInterface fun classify(id: Int, purpose: String): Boolean = runCatching {
            val mapped=when(purpose){"commute"->Purpose.COMMUTE;"business"->Purpose.BUSINESS;"excluded"->Purpose.PERSONAL;"unclassified"->Purpose.UNCLASSIFIED;else->return false}
            repo.classify(id.toLong(),mapped);handler.post { automation.changed(id.toLong(),mapped,"car_ui") };true
        }.getOrDefault(false)
        @JavascriptInterface fun saveDestination(id: Int, destination: String): Boolean = runCatching {repo.setEndPlace(id.toLong(),destination);true}.getOrDefault(false)
        @JavascriptInterface fun requestCorrection(id: Int, proposed: Double, reason: String): Boolean = runCatching {repo.requestCorrection(id.toLong(),proposed,reason);true}.getOrDefault(false)
        @JavascriptInterface fun addMaintenance(date: String, description: String, odo: Double, cost: Int, shop: String): Boolean = runCatching {
            maint.add(description,odo,cost,shop,isoFmt.parse(date)?.time?:System.currentTimeMillis());true
        }.getOrDefault(false)
        @JavascriptInterface fun startDemo(): Boolean {
            synchronized(this@InCarDashboardActivity) {
                if (!BuildConfig.DEBUG || driving || VehicleCoreService.simulationActive.value && VehicleCoreService.state.value == DriveState.DRIVING) return false
                driving = true
            }
            handler.post { beginDemo() }
            return true
        }
        @JavascriptInterface fun saveCloudMemo(id:Int,value:String){
            if(value.length>200)return
            handler.post {
                if(!automation.saveMemo(id.toLong(),value))toast("서버 연결과 운행 상태를 확인하세요.")
                else {toast("메모를 저장했어요.");refresh()}
            }
        }
        @JavascriptInterface fun cloudReport() { handler.post { chooseCloudReport() } }
        @JavascriptInterface fun previewReport() { handler.post { previewHtml() } }
        @JavascriptInterface fun exportCsv() { handler.post { export() } }
        @JavascriptInterface fun openQr() { handler.post { showQr() } }
        @JavascriptInterface fun resetDemo() {
            if (!BuildConfig.DEBUG || driving) return
            handler.post {
                uiScope?.launch {
                    val result = VehicleCoreService.resetSimulationData()
                    if (result == SimResult.OK) {
                        cancelVoiceFor()
                        voice.resetDemoHistory()
                        legacyRepo.clearAndSeed(); maint.clearAndSeed(); shownCorePopupIds.clear(); refresh()
                    } else toast("시연 초기화 실패: $result")
                }
            }
        }
    }

    private fun beginDemo() {
        // A v3 simulation only: separate Room DB, never B legacyRepo.start/end.
        demoJob?.cancel()
        demoJob = demoScope.launch {
            try {
                val startOdo = repo.lastSimOdometerKm()
                val departure = VehicleCoreService.simulate(DriveState.DRIVING, startOdo)
                if (departure != SimResult.OK) {
                    driving = false; js("stopDriving()"); toast("A 코어 시연 시작 실패: $departure"); return@launch
                }
                delay(10_000L)
                val arrival = VehicleCoreService.simulate(DriveState.PARKED, startOdo + 17.7)
                driving = false
                if (arrival != SimResult.OK) { js("stopDriving()"); toast("A 코어 시연 종료 실패: $arrival") }
                // Success popup is displayed ONLY by the tripClosed event, after Room confirmed save.
            } catch (e: Exception) {
                driving = false
                js("stopDriving()")
                toast("시연 오류: ${e.message}")
            }
        }
    }
    /** Never allow a report/QR workflow while a known vehicle is driving.
     * A real vehicle report additionally requires positively confirmed PARKED.
     * In debug-only simulation, UNKNOWN is acceptable for demo data only.
     */
    private fun canShare(): Boolean {
        if (driving || VehicleCoreService.realState.value == DriveState.DRIVING ||
            VehicleCoreService.state.value == DriveState.DRIVING) return false
        return VehicleCoreService.realState.value == DriveState.PARKED ||
            (BuildConfig.DEBUG && VehicleCoreService.realState.value == DriveState.UNKNOWN)
    }

    private fun chooseReportScope(then: (ReportScope) -> Unit) {
        if (!canShare()) { toast("주차 상태에서만 이용할 수 있습니다."); return }
        uiScope?.launch {
            val hasReal = runCatching { cReports.hasVehicleTrips() }.getOrDefault(false)
            if (!hasReal || VehicleCoreService.realState.value != DriveState.PARKED) {
                then(ReportScope.DEMO)
            } else {
                AlertDialog.Builder(this@InCarDashboardActivity)
                    .setTitle("내보낼 기록 선택")
                    .setItems(arrayOf("시연용 샘플 데이터", "차량 운행기록 (실차 검증 필요)")) { _, index ->
                        then(if (index == 0) ReportScope.DEMO else ReportScope.VEHICLE)
                    }.setNegativeButton("취소", null).show()
            }
        }
    }

    private fun previewHtml() = chooseReportScope { selected ->
        uiScope?.launch {
            runCatching { cReports.build(selected, reportYear, reportMonth) }
                .onSuccess { bundle ->
                    val view = WebView(this@InCarDashboardActivity).apply {
                        loadDataWithBaseURL(null, bundle.html.replace(Regex("""<div class=bar>.*?</div>"""), ""), "text/html", "UTF-8", null)
                    }
                    AlertDialog.Builder(this@InCarDashboardActivity)
                        .setTitle(bundle.title + " · 운행기록부")
                        .setView(view).setPositiveButton("닫기", null).show()
                }.onFailure { toast("문서 생성 실패: ${it.message}") }
        }
    }

    private fun export() = chooseReportScope { selected ->
        uiScope?.launch {
            runCatching { cReports.build(selected, reportYear, reportMonth) }
                .onSuccess { bundle ->
                    exportCsvText = bundle.csv
                    try {
                        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "text/csv"
                            putExtra(Intent.EXTRA_TITLE, "AutoLogPro_${if (selected == ReportScope.DEMO) "시연" else "차량"}_${reportYear}_${reportMonth}.csv")
                        }, 4071)
                    } catch (e: Exception) { toast("파일 저장 앱을 사용할 수 없습니다: ${e.message}") }
                }.onFailure { toast("CSV 생성 실패: ${it.message}") }
        }
    }

    private fun chooseCloudReport() {
        if(!canShare()){toast("주차한 뒤 이용하세요.");return}
        if(!automation.enabled){toast("설정에서 차량 계정으로 먼저 로그인하세요.");return}
        if(cloudFile!=null)return
        AlertDialog.Builder(this).setTitle("서버 운행기록부 다운로드")
            .setItems(arrayOf("엑셀 (.xlsx)","CSV (.csv)","HTML (.html)")){_,index->
                val format=listOf("xlsx","csv","html")[index]
                automation.report(reportYear,reportMonth,format){name,bytes,_->
                    if(!canShare()||cloudFile!=null)return@report
                    cloudFile=bytes
                    try {
                        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type=when(format){"xlsx"->"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";"csv"->"text/csv";else->"text/html"}
                            putExtra(Intent.EXTRA_TITLE,name.ifBlank{"AutoLogPro."+format})
                        },4072)
                    }catch(e:Exception){cloudFile=null;toast("파일 저장 화면을 열 수 없어요.")}
                }
            }.setNegativeButton("취소",null).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==4072){
            val bytes=cloudFile;cloudFile=null
            val uri=data?.data
            if(resultCode==RESULT_OK&&uri!=null&&bytes!=null)runCatching{
                contentResolver.openOutputStream(uri)?.use{it.write(bytes)}?:error("파일 생성 실패")
            }.onSuccess{toast("서버 운행기록부 저장 완료")}.onFailure{toast("파일 저장 실패")}
            return
        }
        if (requestCode != 4071) return
        val content = exportCsvText ?: return
        exportCsvText = null
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        runCatching { contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: error("파일 생성 실패") }
            .onSuccess { toast("운행기록부 CSV 저장 완료") }.onFailure { toast("저장 실패: ${it.message}") }
    }

    private fun showQr() = chooseReportScope { selected ->
        uiScope?.launch {
            runCatching { cReports.build(selected, reportYear, reportMonth) }
                .onSuccess { bundle ->
                    // C's short-lived token server, max 5 accesses. No production HTTPS guarantee.
                    qrManager?.stop()
                    val manager = QrExportManager(isParked = { canShare() &&
                        (selected == ReportScope.DEMO || VehicleCoreService.realState.value == DriveState.PARKED)
                    })
                    qrManager = manager
                    val url = manager.start(bundle.html, bundle.csv)
                    if (url == null) {
                        AlertDialog.Builder(this@InCarDashboardActivity)
                            .setMessage("같은 Wi-Fi에 연결되지 않았거나 차량이 주차 상태가 아니라서 QR 링크를 만들 수 없습니다. HTML 미리보기를 이용하세요.")
                            .setPositiveButton("확인", null).show()
                        return@onSuccess
                    }
                    val panel = LinearLayout(this@InCarDashboardActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        addView(ImageView(this@InCarDashboardActivity).apply {
                            setImageBitmap(manager.qr(url, 300))
                        })
                        addView(TextView(this@InCarDashboardActivity).apply {
                            text = url; textSize = 12f; setPadding(8, 10, 8, 12)
                        })
                    }
                    AlertDialog.Builder(this@InCarDashboardActivity)
                        .setTitle("${bundle.title} · 같은 Wi-Fi 필요")
                        .setView(panel)
                        .setPositiveButton("닫기") { _, _ -> manager.stop() }
                        .setOnCancelListener { manager.stop() }.show()
                }.onFailure { toast("QR 생성 실패: ${it.message}") }
        }
    }

    override fun onBackPressed() { js("closeAllDrawers();navigateTo('home')") }
}
