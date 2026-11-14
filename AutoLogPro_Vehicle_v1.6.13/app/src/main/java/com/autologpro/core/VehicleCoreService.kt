package com.autologpro.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.autologpro.data.LogbookProfileStore
import com.autologpro.data.Source
import com.autologpro.data.Trip
import com.autologpro.data.TripDao
import com.autologpro.data.TripRepository
import com.autologpro.data.Purpose
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 차량 상태 → C의 TripRepository 저장 → B 화면 이벤트.
 *
 * ■ 순서 보장: 모든 저장은 명령 큐 하나에서 차례대로 처리한다(기어 콜백·시뮬 버튼·재시도 타이머가 동시에 와도).
 *   tripStarted / tripClosed 는 **Room 저장이 끝나고 다시 읽어 확인한 뒤에만** 보낸다. 저장 실패 시 보내지 않는다.
 * ■ 예외 격리: 명령 하나가 실패해도 다음 명령은 계속 처리된다.
 * ■ 계기판을 못 읽으면: 출발/도착을 '대기'로 두고 2초마다 다시 읽는다. 지어낸 값은 쓰지 않는다.
 * ■ 앱이 죽었다 켜지면: 주차 상태면 열린 운행을 현재 계기판으로 마감(RECOVERED) + "마지막 주행 확인 시각"을 정정 이력에 남김.
 *   주행 중이면 열린 운행을 그대로 이어감(중복 생성 없음).
 * ■ 시뮬레이션: Debug(디버그 가능) 빌드에서만. 실제와 다른 DB 파일에 저장. 실제 경로는 시뮬 중에도 계속 실제 DB에 기록.
 * ■ 포그라운드 서비스로 승격 시도(주행 중 화면이 가려져도 유지되게). 실패하면 일반 서비스로 계속 동작.
 */
class VehicleCoreService : Service() {

    // ════════════════════════════ B가 쓰는 API ════════════════════════════
    companion object {
        private const val TAG = "VehicleCore"
        private const val TICK_MS = 250L
        private const val RETRY_EVERY_TICKS = 8          // 2초마다 계기판 재시도
        private const val HEARTBEAT_MS = 60_000L
        // P-entry can arrive before the SDK publishes the last odometer value.
        private const val PARK_ODO_SETTLE_MS = 1_500L
        private const val LATE_NOTE_MS = 1_000L          // 출발 계기판을 1초 넘게 늦게 읽으면 이력 남김
        private const val PENDING_POPUP_DAYS = 7L
        private const val NOTI_CHANNEL = "autolog_core"
        private const val NOTI_ID = 4101
        private const val PREFS = "autolog_core"
        private const val ACCEL_PREF = "accel_demo_enabled"
        private const val ACCEL_LOG = "AccelDemo"

        private val _state = MutableStateFlow(DriveState.UNKNOWN)
        /** 화면에 보여줄 상태. 시뮬레이션 중이면 시뮬 상태, 아니면 실제 차량 상태. 팝업 트리거로 쓰지 말 것(→ tripClosed) */
        val state: StateFlow<DriveState> = _state.asStateFlow()

        private val _realState = MutableStateFlow(DriveState.UNKNOWN)
        /** 실제 차량 상태 (시뮬레이션과 무관) */
        val realState: StateFlow<DriveState> = _realState.asStateFlow()

        private val _currentTrip = MutableStateFlow<Trip?>(null)
        /** 진행 중인 운행 (시뮬 중이면 시뮬 운행). 주차면 null */
        val currentTrip: StateFlow<Trip?> = _currentTrip.asStateFlow()

        private val _simulationActive = MutableStateFlow(false)
        val simulationActive: StateFlow<Boolean> = _simulationActive.asStateFlow()

        private val _diagnostics = MutableStateFlow(Diagnostics())
        /** 신호·권한·주행거리 상태. 디버그 화면이나 Logcat 대신 보기 */
        val diagnostics: StateFlow<Diagnostics> = _diagnostics.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        /** 마지막 기록 오류(계기판 미수집, DB 실패 등). 정상이면 null */
        val lastError: StateFlow<String?> = _lastError.asStateFlow()

        private val _foreground = MutableStateFlow(false)
        /** 포그라운드 서비스로 승격됐는지 (false면 주행 중 백그라운드에서 멈출 수 있음) */
        val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

        private val startedCh = Channel<Trip>(Channel.UNLIMITED)
        private val closedCh = Channel<Trip>(Channel.UNLIMITED)
        /** 출발 저장 완료(DB 확인 후). 구독자는 한 곳만 */
        val tripStarted: Flow<Trip> = startedCh.receiveAsFlow()
        /** 도착 저장·봉인 완료(DB 확인 후) → B의 60초 분류 팝업. 구독자는 한 곳만. 앱이 죽어 놓친 건 pendingPopups()로 */
        val tripClosed: Flow<Trip> = closedCh.receiveAsFlow()

        @Volatile private var instance: VehicleCoreService? = null

        fun start(context: Context) {
            context.startService(Intent(context, VehicleCoreService::class.java))
        }

        /** Emulator debug build defaults ON; release builds are ALWAYS OFF. */
        fun accelDemoAvailable(context: Context): Boolean =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        private fun emulatorDetected(): Boolean =
            listOf(Build.HARDWARE, Build.FINGERPRINT, Build.PRODUCT, Build.MODEL).any {
                it.contains("ranchu", true) || it.contains("goldfish", true) ||
                it.contains("generic", true) || it.contains("emulator", true) ||
                it.contains("sdk_gphone", true) || it.contains("qemu", true)
            }

        /** Explicit setting available in Settings; cannot ever enable for release. */
        fun accelDemoEnabled(context: Context): Boolean {
            if (!accelDemoAvailable(context)) return false
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(ACCEL_PREF, emulatorDetected())
        }

        fun setAccelDemoEnabled(context: Context, value: Boolean): Boolean {
            if (!accelDemoAvailable(context)) return false
            val active = instance?.accelDemoTripActive == true
            if (active) return false // do not switch storage in the middle of a drive
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(ACCEL_PREF, value).apply()
            Log.i(ACCEL_LOG, "ACCEL 시연 모드 = $value (디버그 전용, 실제 차량 DB와 분리)")
            return true
        }

        /** QR 서버 등 '주차 중에만' 허용할 기능용. 시뮬 주행 중이면 false */
        fun isParked(): Boolean {
            val realOk = _realState.value == DriveState.PARKED ||
                (_diagnostics.value.mode == DetectMode.NONE && _simulationActive.value)
            val simOk = !_simulationActive.value || simStateValue() != DriveState.DRIVING
            return realOk && simOk
        }

        /** 감지 방식. 서비스가 안 떠 있으면 null */
        fun detectMode(): DetectMode? = instance?.hw?.mode

        /** 요청해야 할 런타임 권한 (아직 없는 것만). requestPermissions(…)에 그대로 넘기면 됨 */
        fun missingRuntimePermissions(context: Context): List<String> =
            listOf(HardwareTriggerManager.PERM_POWERTRAIN, HardwareTriggerManager.PERM_SPEED)
                .filter { context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }

        /** 권한 결과를 받은 뒤(onRequestPermissionsResult)와 onResume에서 호출 → 차량 신호 재구독. 메인 스레드 OK */
        fun refreshPermissions(): DetectMode? = instance?.hw?.refreshPermissions()

        /**
         * [Debug 전용] 시뮬레이션 출발/도착. odoKm(가상 계기판)은 필수이고 직전 시뮬 도착값 이상이어야 한다.
         * 시뮬 전용 DB에 Source.SIMULATED로 저장된다. 저장이 끝난 뒤 결과를 돌려준다.
         */
        suspend fun simulate(next: DriveState, odoKm: Double): SimResult {
            val svc = instance ?: return SimResult.SERVICE_NOT_RUNNING
            if (!svc.isDebuggable()) return SimResult.NOT_DEBUG_BUILD
            val reply = CompletableDeferred<SimResult>()
            svc.commands.send(Cmd.Sim(next, odoKm, reply))
            return reply.await()
        }

        /** [Debug 전용] 시뮬레이션 끝내고 화면을 실제 차량 상태로 되돌림. 시뮬 기록은 남는다 */
        suspend fun stopSimulation(): SimResult {
            val svc = instance ?: return SimResult.SERVICE_NOT_RUNNING
            val reply = CompletableDeferred<SimResult>()
            svc.commands.send(Cmd.StopSim(reply))
            return reply.await()
        }

        /** [Debug 전용] 시뮬레이션 DB 삭제(초기화). 실제 기록은 그대로 */
        suspend fun resetSimulationData(): SimResult {
            val svc = instance ?: return SimResult.SERVICE_NOT_RUNNING
            if (!svc.isDebuggable()) return SimResult.NOT_DEBUG_BUILD
            val reply = CompletableDeferred<SimResult>()
            svc.commands.send(Cmd.ResetSim(reply))
            return reply.await()
        }

        /**
         * 아직 팝업으로 처리 안 한 마감 운행(미분류, 최근 7일). 화면 시작 때 불러서 놓친 팝업을 띄운다.
         * 앱이 죽어서 tripClosed를 못 받은 경우 대비. 오래된 순.
         */
        suspend fun pendingPopups(context: Context): List<Trip> {
            val store = PopupStore(context)
            val since = System.currentTimeMillis() - PENDING_POPUP_DAYS * 86_400_000L
            fun filter(list: List<Trip>, sim: Boolean) = list.filter {
                it.purpose == Purpose.UNCLASSIFIED && (it.endTime ?: 0) >= since && !store.isHandled(it.id, sim)
            }
            val real = filter(AppDb.get(context).tripDao().allClosed(), false)
            val sim = if (AppDb.simExists(context)) filter(AppDb.simDb(context).tripDao().allClosed(), true) else emptyList()
            return (real + sim).sortedBy { it.endTime }
        }

        /** 팝업이 끝나면(선택·건너뛰기·60초 만료) 호출. 그래야 pendingPopups()에 다시 안 나온다 */
        fun markPopupHandled(context: Context, trip: Trip) =
            PopupStore(context).markHandled(trip.id, trip.startSource == Source.SIMULATED)

        /** 분류·운전자 저장용 저장소 (실제/시뮬 DB 자동 선택) */
        fun repoFor(context: Context, trip: Trip): TripRepository = AppDb.repoFor(context, trip)

        /**
         * 화면 연결 헬퍼. lifecycleScope 안에서 부르면 콜백은 메인 스레드에서 불린다.
         * lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { VehicleCoreService.collectEvents(...) } }
         */
        suspend fun collectEvents(
            onState: (DriveState) -> Unit = {},
            onStarted: (Trip) -> Unit = {},
            onClosed: (Trip) -> Unit = {},
        ) = coroutineScope {
            launch { state.collect { onState(it) } }
            launch { tripStarted.collect { onStarted(it) } }
            launch { tripClosed.collect { onClosed(it) } }
        }

        private fun simStateValue() = instance?.simState ?: DriveState.PARKED
    }

    // ════════════════════════════ 내부 ════════════════════════════

    private sealed interface Cmd {
        data class Initial(val s: DriveState) : Cmd
        data class Hw(val t: Transition) : Cmd
        object Retry : Cmd
        data class SpeedSample(val elapsedRealtimeMs: Long, val speedMps: Double?) : Cmd
        data class Sim(val to: DriveState, val odoKm: Double, val reply: CompletableDeferred<SimResult>) : Cmd
        data class StopSim(val reply: CompletableDeferred<SimResult>) : Cmd
        data class ResetSim(val reply: CompletableDeferred<SimResult>) : Cmd
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = Channel<Cmd>(Channel.UNLIMITED)
    private lateinit var hw: HardwareTriggerManager
    private lateinit var repo: TripRepository
    private lateinit var dao: TripDao
    private lateinit var popups: PopupStore

    // 실제 경로 상태 (명령 큐 안에서만 바꿈)
    private var realTrip: Trip? = null
    private var pendingDeparture: Transition? = null
    private var pendingDepartureNote: String? = null
    private var pendingArrival: Transition? = null
    private var pendingRecovery = false
    private var lastHeartbeat = 0L
    /** Highest *actually observed* SDK odometer reading during the current trip; never inferred from speed. */
    private var observedOdoPeakKm: Double? = null

    // ACCEL demo observes speed while the real gear signal still drives transitions.
    // It uses the C SIMULATED database, never the verified real-trip database.
    @Volatile private var accelDemoTripActive = false
    private val accelMeter = SpeedDistanceIntegrator()
    private var accelTripId: Long? = null
    private var accelBaseKm = 0.0
    private var accelLastLoggedSeconds = 0L
    private var accelStartElapsedMs = 0L

    // 시뮬레이션 상태
    @Volatile private var simState = DriveState.PARKED
    private var simTrip: Trip? = null

    override fun onCreate() {
        super.onCreate()
        repo = AppDb.repo(this)
        dao = AppDb.get(this).tripDao()
        popups = PopupStore(this)
        hw = HardwareTriggerManager(applicationContext, PleosOdometerReader(applicationContext))
        instance = this

        scope.launch { hw.state.collect { _realState.value = it; publish() } }
        scope.launch { hw.diagnostics.collect { _diagnostics.value = it } }
        // 첫 상태 → 그다음 전환들, 순서대로 큐에 넣는다
        scope.launch {
            commands.send(Cmd.Initial(hw.initialState.await()))
            hw.transitions.collect { commands.send(Cmd.Hw(it)) }
        }
        // 명령 처리 (하나씩, 예외 격리)
        scope.launch {
            for (c in commands) {
                try { process(c) } catch (t: Throwable) {
                    report("명령 처리 실패 ${c.javaClass.simpleName}: $t")
                    when (c) {
                        is Cmd.Sim -> c.reply.complete(SimResult.FAILED)
                        is Cmd.StopSim -> c.reply.complete(SimResult.FAILED)
                        is Cmd.ResetSim -> c.reply.complete(SimResult.FAILED)
                        else -> Unit
                    }
                }
            }
        }
        // 주기: 대체 감지 판정(0.25초) + 계기판 재시도·하트비트(2초)
        scope.launch {
            var n = 0
            while (isActive) {
                delay(TICK_MS)
                runCatching { hw.onTick() }
                // Poll speed independently of GEAR/FALLBACK subscriptions; the GEAR
                // detector intentionally unsubscribes SPEED to avoid false transitions.
                if (accelDemoTripActive) {
                    commands.trySend(Cmd.SpeedSample(SystemClock.elapsedRealtime(), hw.readSpeedMpsForDemo()))
                }
                if (++n % RETRY_EVERY_TICKS == 0) commands.trySend(Cmd.Retry)
            }
        }

        val mode = hw.start()
        Log.i(TAG, "서비스 시작, 감지 모드 = $mode")
        Log.i(ACCEL_LOG, "ACCEL mode enabled=${accelDemoEnabled(this)} / emulator=${emulatorDetected()} / debug=${isDebuggable()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { hw.stop() }
        accelDemoTripActive = false
        instance = null
        scope.cancel()
        _realState.value = DriveState.UNKNOWN
        _state.value = DriveState.UNKNOWN
        _currentTrip.value = null
        _foreground.value = false
        super.onDestroy()
    }

    // ───────────────────────── 명령 처리 ─────────────────────────

    private suspend fun process(c: Cmd) {
        when (c) {
            is Cmd.Initial -> onInitial(c.s)
            is Cmd.Hw -> onTransition(c.t)
            is Cmd.Retry -> onRetry()
            is Cmd.SpeedSample -> onSpeedSample(c)
            is Cmd.Sim -> c.reply.complete(onSim(c.to, c.odoKm))
            is Cmd.StopSim -> c.reply.complete(onStopSim())
            is Cmd.ResetSim -> c.reply.complete(onResetSim())
        }
    }

    private suspend fun onInitial(s: DriveState) {
        if (s == DriveState.DRIVING && accelDemoEnabled(this)) {
            Log.w(ACCEL_LOG, "앱이 D단에서 실행되어 시작 시각 이전의 시연 거리는 알 수 없습니다")
            startAccelDemoTrip()
            return
        }
        val open = dao.open()
        Log.i(TAG, "첫 상태 $s, 열린 운행 = ${open?.id}")
        when (s) {
            DriveState.PARKED -> if (open != null) {
                val odo = hw.readOdometerKm()
                if (odo == null) { pendingRecovery = true; report("복구 대기: 계기판을 못 읽어 열린 운행 #${open.id}를 아직 마감 못함 [${hw.odometerFailureReason()}] (2초마다 재시도)") }
                else recover(odo)
            }
            DriveState.DRIVING -> if (open != null) {
                realTrip = open // 이어서 기록 (DB 쓰기 없음 → 중복 생성 없음)
                observedOdoPeakKm = open.startOdoKm
                Log.i(TAG, "주행 중 재시작: 운행 #${open.id} 이어서 기록")
                publish()
                startedCh.trySend(open)
            } else {
                // 앱이 켜졌을 때 이미 주행 중 → 지금 계기판을 출발값으로 (정확한 출발값은 알 수 없음을 이력에 남김)
                val t = Transition(DriveState.DRIVING, hw.mode.toSource(), System.currentTimeMillis())
                departOrWait(t, note = "앱 시작 시 이미 주행 중이어서 시작 시점 계기판을 출발값으로 기록함. 실제 출발값은 이보다 작을 수 있음")
            }
            DriveState.UNKNOWN -> Unit
        }
    }

    private suspend fun onTransition(t: Transition) {
        // The debug ACCEL route is entirely separate from the verified odometer route.
        if (accelDemoTripActive || (t.to == DriveState.DRIVING && accelDemoEnabled(this) && simState != DriveState.DRIVING)) {
            when (t.to) {
                DriveState.DRIVING -> if (!accelDemoTripActive) startAccelDemoTrip()
                DriveState.PARKED -> finishAccelDemoTrip()
                DriveState.UNKNOWN -> Unit
            }
            return
        }
        when (t.to) {
            DriveState.DRIVING -> {
                if (pendingRecovery) {
                    tryRecover()
                    if (pendingRecovery) {
                        // 복구 마감 전에 다시 출발함 → 남은 운행은 이번 출발에 이어짐(depart에서 이력 남김).
                        // 대기를 지우지 않으면 나중에 계기판이 읽힐 때 '지금 주행 중인' 운행을 마감해 버린다.
                        pendingRecovery = false
                    }
                }
                if (pendingArrival != null) {
                    // 직전 도착을 끝내 못 읽음 → 다음 출발과 합쳐질 수밖에 없음 (계기판은 연속이라 총거리는 맞음)
                    tryArrival()
                    if (pendingArrival != null) {
                        report("도착 계기판 미수집으로 운행 #${realTrip?.id}가 다음 주행과 합쳐짐")
                        realTrip?.let { note(it.id, "endTime", "도착 계기판을 못 읽어 다음 주행과 합쳐짐 (도착 감지 시각 ${fmt(pendingArrival!!.at)})") }
                        pendingArrival = null
                        return
                    }
                }
                departOrWait(t)
            }
            DriveState.PARKED -> {
                if (pendingDeparture != null) {
                    report("출발 계기판을 주행 내내 못 읽어 이번 운행은 기록하지 못함 (출발 감지 ${fmt(pendingDeparture!!.at)})")
                    pendingDeparture = null
                    pendingDepartureNote = null
                    return
                }
                arriveOrWait(t)
            }
            DriveState.UNKNOWN -> Unit
        }
    }

    /** Called only on verified GEAR events in debuggable builds with ACCEL demo enabled. */
    private suspend fun startAccelDemoTrip() {
        if (!accelDemoEnabled(this) || accelDemoTripActive) return
        // Do not start on top of the old 10-second button simulation.
        if (simState == DriveState.DRIVING) {
            Log.w(ACCEL_LOG, "10초 시연 진행 중 → ACCEL 시연 시작 생략")
            return
        }
        val simDao = AppDb.simDb(this).tripDao()
        simDao.open()?.let { old ->
            // Incomplete prior debug demo; never touch the real-trip DB.
            simDao.deleteOpenTrip(old.id)
            Log.w(ACCEL_LOG, "이전 미완료 시연 기록 #${old.id} 정리")
        }
        accelBaseKm = simDao.lastClosed()?.endOdoKm ?: 52_340.0
        accelMeter.reset()
        accelStartElapsedMs = SystemClock.elapsedRealtime()
        accelLastLoggedSeconds = 0L
        val profile = LogbookProfileStore(this).get().forDemo()
        val trip = AppDb.simRepo(this).start(profile.driver, accelBaseKm, "ACCEL 시연: 속도 기반 추정", Source.SIMULATED, profile)
        val saved = simDao.get(trip.id) ?: return
        accelTripId = saved.id
        accelDemoTripActive = true
        simState = DriveState.DRIVING
        simTrip = saved
        _simulationActive.value = true
        publish()
        startedCh.trySend(saved)
        Log.i(ACCEL_LOG, "[D] ACCEL 시연 시작 #${saved.id}, base=$accelBaseKm km / 속도=m/s / 상태=${hw.demoSpeedStatus()}")
    }

    private fun onSpeedSample(cmd: Cmd.SpeedSample) {
        if (!accelDemoTripActive) return
        accelMeter.add(cmd.elapsedRealtimeMs, cmd.speedMps)
        val seconds = (cmd.elapsedRealtimeMs - accelStartElapsedMs) / 1000L
        if (seconds >= accelLastLoggedSeconds + 2L) {
            accelLastLoggedSeconds = seconds
            Log.i(ACCEL_LOG, "ACCEL 속도=${cmd.speedMps?.times(3.6)?.let { "%.1f".format(Locale.US, it) } ?: "수신 실패"} km/h, 추정 거리=${"%.4f".format(Locale.US, accelMeter.distanceKm)} km (samples=${accelMeter.validSamples})")
        }
    }

    private suspend fun finishAccelDemoTrip() {
        if (!accelDemoTripActive) return
        // Stop recording first; the latest speed sample comes from the 250ms sampler.
        accelDemoTripActive = false
        val id = accelTripId ?: return
        accelTripId = null
        val distance = accelMeter.distanceKm
        val seen = accelMeter.validSamples
        val moving = accelMeter.movingSamples
        val simDao = AppDb.simDb(this).tripDao()
        if (seen < 2 || moving < 2 || distance <= 0.00001) {
            simDao.deleteOpenTrip(id)
            Log.w(ACCEL_LOG, "[P] 시연 기록 보류: 속도 수신 부족/실제 움직임 없음 (유효=$seen, 이동=$moving, 상태=${hw.demoSpeedStatus()}). CAR_SPEED 권한과 Virtual Console ACCEL 확인")
            _lastError.value = "ACCEL 시연 기록 안 됨: 속도 신호가 없거나 주행하지 않았습니다. CAR_SPEED 권한을 확인하세요."
        } else {
            val endOdo = accelBaseKm + distance
            val closed = AppDb.simRepo(this).end(endOdo, place = "ACCEL 시연 종료 (추정)", source = Source.SIMULATED)
            val saved = closed?.let { simDao.get(it.id) }
            if (saved?.endTime != null) {
                Log.i(ACCEL_LOG, "[P] ACCEL 시연 저장 #${saved.id}: ${"%.4f".format(Locale.US, distance)} km, 최고 ${"%.1f".format(Locale.US, accelMeter.peakKmh)} km/h, $seen samples, SIMULATED DB")
                closedCh.trySend(saved)
                clearError()
            } else {
                report("ACCEL 시연 저장 실패: #$id")
            }
        }
        simState = DriveState.PARKED
        simTrip = null
        _simulationActive.value = false
        publish()
    }

    private suspend fun onRetry() {
        if (pendingRecovery) tryRecover()
        pendingDeparture?.let { p ->
            val odo = hw.readOdometerKm() ?: return@let
            val n = pendingDepartureNote
            pendingDeparture = null
            pendingDepartureNote = null
            depart(p, odo, lateMs = System.currentTimeMillis() - p.at, note = n)
        }
        if (pendingArrival != null) tryArrival()
        // Sample actual Odometer while driving. Merely changing speed in Virtual Console
        // does not imply the cumulative Odometer has changed; never synthesize a distance.
        val trip = realTrip
        if (trip != null && _realState.value == DriveState.DRIVING) {
            val sample = hw.readOdometerKm()
            if (sample != null && sample >= trip.startOdoKm) {
                val old = observedOdoPeakKm ?: trip.startOdoKm
                if (sample > old + 0.00001) {
                    observedOdoPeakKm = sample
                    Log.i(TAG, "주행 중 누적거리 갱신: $old → $sample km (SDK 실측)")
                }
            }
        }
        // 하트비트: 주행 중 '마지막으로 주행을 확인한 시각'
        val now = System.currentTimeMillis()
        if (trip != null && _realState.value == DriveState.DRIVING && now - lastHeartbeat >= HEARTBEAT_MS) {
            popups.setAlive(trip.id, now)
            lastHeartbeat = now
        }
    }

    // ───────────────────────── 실제 경로: 출발 ─────────────────────────

    private suspend fun departOrWait(t: Transition, note: String? = null) {
        val odo = hw.readOdometerKm()
        if (odo == null) {
            pendingDeparture = t
            pendingDepartureNote = note
            report("출발 대기: 계기판을 못 읽음 [${hw.odometerFailureReason()}]. 2초마다 재시도")
            return
        }
        depart(t, odo, lateMs = 0, note = note)
    }

    private suspend fun depart(t: Transition, odo: Double, lateMs: Long, note: String?) {
        dao.open()?.let { stale ->
            // 마감 못 한 운행이 남아 있음 → C가 새로 만들지 않고 그 운행을 이어 씀(중복 방지). 기록으로 남김
            report("열린 운행 #${stale.id}가 남아 있어 이어서 기록함")
            note(stale.id, "endTime", "마감되지 않은 상태에서 다음 출발(${fmt(t.at)})이 감지되어 이어서 기록됨")
        }
        val profile = LogbookProfileStore(this).get()
        val trip = repo.start(profile.driver.ifBlank { null }, odo, source = t.source, profile = profile)
        val saved = dao.get(trip.id)
        if (saved == null || saved.endTime != null) {
            report("출발 저장 확인 실패 (운행 #${trip.id})")
            return
        }
        realTrip = saved
        observedOdoPeakKm = maxOf(odo, saved.startOdoKm)
        lastHeartbeat = System.currentTimeMillis()
        popups.setAlive(saved.id, lastHeartbeat)
        if (lateMs > LATE_NOTE_MS) note(saved.id, "startOdoKm", "출발 계기판 지연 수집: 출발 감지 ${lateMs / 1000}초 뒤 값. 실제 출발값은 이보다 작을 수 있음")
        if (note != null) note(saved.id, "startOdoKm", note)
        clearError()
        Log.i(TAG, "출발 저장 #${saved.id} 출발 계기판 $odo km (${t.source}, DB 확인됨)")
        publish()
        startedCh.trySend(saved)
    }

    // ───────────────────────── 실제 경로: 도착 ─────────────────────────

    private suspend fun arriveOrWait(t: Transition) {
        pendingArrival = t
        // Wait a brief interval for the VHAL/SDK value to settle after P engagement.
        // Cmd.Retry runs every two seconds, then closes with the actual sampled value.
        Log.i(TAG, "P단 감지: 도착 누적거리 반영 대기 (${PARK_ODO_SETTLE_MS}ms 이상)")
    }

    private suspend fun tryArrival() {
        val t = pendingArrival ?: return
        if (System.currentTimeMillis() - t.at < PARK_ODO_SETTLE_MS) return
        val freshOdo = hw.readOdometerKm()
        if (freshOdo == null) {
            report("도착 대기: 계기판을 못 읽음 [${hw.odometerFailureReason()}]. 2초마다 재시도 (도착 감지 ${fmt(t.at)})")
            return
        }
        // A peak sampled from the same verified SDK while DRIVING is evidence of
        // distance, even if the immediate parked-state callback momentarily lags.
        val peak = observedOdoPeakKm
        val odo = if (peak != null && peak > freshOdo) {
            Log.w(TAG, "도착 SDK 값 지연: 현재=$freshOdo km, 주행 중 실측 최고=$peak km 사용")
            peak
        } else freshOdo
        pendingArrival = null
        val open = dao.open()
        if (open == null) {
            Log.w(TAG, "도착: 진행 중 운행 없음 (이미 마감됨) → 무시")
            realTrip = null
            publish()
            return
        }
        if (odo - open.startOdoKm < 0.05) {
            Log.w(TAG, "계기판 누적거리 변화 없음: 출발=${open.startOdoKm}km 도착=${odo}km. Virtual Console에서 속도만 바꾸면 거리는 증가하지 않을 수 있음. Odometer 항목을 직접 올려서 검사하세요.")
        }
        val closed = repo.end(odo, source = t.source)
        if (closed == null) {
            report("도착 저장 거부: 도착 계기판($odo)이 출발(${open.startOdoKm})보다 작음 → 운행 #${open.id} 유지, 단위/값 확인 필요")
            return
        }
        val saved = dao.get(closed.id)
        if (saved?.endTime == null) {
            report("도착 저장 확인 실패 (운행 #${closed.id})")
            return
        }
        if (t.at < saved.endTime!! - 5_000) {
            note(saved.id, "endTime", "도착 계기판 지연 수집: 실제 도착 감지 시각은 ${fmt(t.at)}")
        }
        popups.clearAlive(saved.id)
        realTrip = null
        observedOdoPeakKm = null
        clearError()
        Log.i(TAG, "도착 저장 #${saved.id} 출발 ${saved.startOdoKm} km → 도착 ${saved.endOdoKm} km = ${"%.1f".format(saved.endOdoKm!! - saved.startOdoKm)} km (${t.source}, DB 확인됨)")
        publish()
        closedCh.trySend(saved)
    }

    // ───────────────────────── 실제 경로: 복구 ─────────────────────────

    private suspend fun tryRecover() {
        val odo = hw.readOdometerKm() ?: return
        recover(odo)
    }

    private suspend fun recover(odo: Double) {
        pendingRecovery = false
        val open = dao.open() ?: return
        val alive = popups.getAlive(open.id)
        val r = repo.recoverOpen(odo) ?: return
        val saved = dao.get(r.id)
        if (saved?.endTime == null) { report("복구 저장 확인 실패 (운행 #${r.id})"); return }
        // 원본(종료 시각 = 복구 처리 시각)은 그대로 두고, 실제 종료 시각 단서를 정정 이력으로 남김
        val why = if (alive != null) "앱이 마지막으로 주행을 확인한 시각 ${fmt(alive)} 이후 도착" else "마지막 주행 확인 시각 기록 없음"
        note(saved.id, "endTime", "복구 마감: 종료 시각은 복구 처리 시각(${fmt(saved.endTime!!)}). $why", alive?.toString())
        popups.clearAlive(saved.id)
        realTrip = null
        observedOdoPeakKm = null
        Log.i(TAG, "복구 마감 #${saved.id} ${"%.1f".format(saved.endOdoKm!! - saved.startOdoKm)}km ($why)")
        publish()
        closedCh.trySend(saved)
    }

    // ───────────────────────── 시뮬레이션 (Debug 전용, 별도 DB) ─────────────────────────

    private suspend fun onSim(to: DriveState, odoKm: Double): SimResult {
        if (accelDemoTripActive) return SimResult.ALREADY_IN_STATE
        if (!odoKm.isFinite() || odoKm < 0 || odoKm > HardwareTriggerManager.ODO_MAX_KM) return SimResult.ODO_INVALID
        if (to == DriveState.UNKNOWN) return SimResult.ODO_INVALID
        val simRepo = AppDb.simRepo(this)
        val simDao = AppDb.simDb(this).tripDao()
        _simulationActive.value = true
        when (to) {
            DriveState.DRIVING -> {
                if (simState == DriveState.DRIVING) return SimResult.ALREADY_IN_STATE
                val last = simDao.lastClosed()
                if (last?.endOdoKm != null && odoKm < last.endOdoKm!! - 0.05) {
                    Log.w(TAG, "시뮬 출발값 $odoKm < 직전 시뮬 도착값 ${last.endOdoKm} → 거부")
                    return SimResult.ODO_INVALID
                }
                val profile = LogbookProfileStore(this).get().forDemo()
                val trip = simRepo.start(profile.driver, odoKm, source = Source.SIMULATED, profile = profile)
                val saved = simDao.get(trip.id) ?: return SimResult.FAILED
                simState = DriveState.DRIVING
                simTrip = saved
                Log.i(TAG, "[시뮬] 출발 #${saved.id} odo=$odoKm")
                publish()
                startedCh.trySend(saved)
            }
            DriveState.PARKED -> {
                if (simState != DriveState.DRIVING) return SimResult.ALREADY_IN_STATE
                val closed = simRepo.end(odoKm, source = Source.SIMULATED) ?: return SimResult.ODO_INVALID
                val saved = simDao.get(closed.id)?.takeIf { it.endTime != null } ?: return SimResult.FAILED
                simState = DriveState.PARKED
                simTrip = null
                Log.i(TAG, "[시뮬] 도착 #${saved.id} ${"%.1f".format(saved.endOdoKm!! - saved.startOdoKm)}km")
                publish()
                closedCh.trySend(saved)
            }
            DriveState.UNKNOWN -> return SimResult.ODO_INVALID
        }
        return SimResult.OK
    }

    private fun onStopSim(): SimResult {
        if (simState == DriveState.DRIVING) return SimResult.STILL_DRIVING
        _simulationActive.value = false
        publish()
        return SimResult.OK
    }

    private fun onResetSim(): SimResult {
        if (accelDemoTripActive || simState == DriveState.DRIVING) return SimResult.STILL_DRIVING
        AppDb.deleteSim(this)
        popups.clearSim()
        simTrip = null
        _simulationActive.value = false
        publish()
        Log.i(TAG, "[시뮬] 데이터 초기화 (실제 기록 유지)")
        return SimResult.OK
    }

    // ───────────────────────── 보조 ─────────────────────────

    @Synchronized
    private fun publish() {
        val sim = _simulationActive.value
        _state.value = if (sim) simState else _realState.value
        _currentTrip.value = if (sim) simTrip else realTrip
    }

    /** 수집값은 바꾸지 않고 C의 정정 요청 이력으로 메모를 남긴다(원본 보존). */
    private suspend fun note(tripId: Long, field: String, reason: String, value: String? = null) {
        runCatching {
            val t = dao.get(tripId) ?: return
            val current = when (field) {
                "startOdoKm" -> t.startOdoKm.toString()
                "endOdoKm" -> t.endOdoKm?.toString()
                "startTime" -> t.startTime.toString()
                else -> t.endTime?.toString()
            }
            repo.requestCorrection(tripId, field, value ?: current ?: "", reason)
        }.onFailure { Log.w(TAG, "이력 기록 실패: $it") }
    }

    private fun report(msg: String) {
        Log.e(TAG, msg)
        _lastError.value = msg
    }

    private fun clearError() { _lastError.value = null }

    private fun isDebuggable() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun DetectMode.toSource() = if (this == DetectMode.FALLBACK) Source.FALLBACK else Source.GEAR

    private fun fmt(ms: Long) = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(Date(ms))

    private fun promoteToForeground() {
        if (_foreground.value) return
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(NotificationChannel(NOTI_CHANNEL, "운행 자동 기록", NotificationManager.IMPORTANCE_LOW))
            val n: Notification = Notification.Builder(this, NOTI_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("오토로그 프로")
                .setContentText("차량 운행을 자동으로 기록하고 있습니다")
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTI_ID, n)
            }
            _foreground.value = true
            Log.i(TAG, "포그라운드 서비스 승격 성공")
        } catch (t: Throwable) {
            // 매니페스트 누락·백그라운드 시작 제한 등 → 일반 서비스로 계속 (주행 중 백그라운드에서 멈출 수 있음)
            Log.e(TAG, "포그라운드 승격 실패 → 일반 서비스로 동작: $t")
        }
    }
}

/** 팝업 처리 여부(apply, 메인 스레드용)·주행 확인 시각(commit, 서비스 백그라운드에서 즉시 디스크 기록) 저장 */
internal class PopupStore(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("autolog_core", Context.MODE_PRIVATE)

    private fun key(id: Long, sim: Boolean) = (if (sim) "S:" else "R:") + id

    fun isHandled(id: Long, sim: Boolean): Boolean = p.getStringSet("handled", emptySet())?.contains(key(id, sim)) == true

    fun markHandled(id: Long, sim: Boolean) {
        val set = (p.getStringSet("handled", emptySet()) ?: emptySet()).toMutableSet()
        set += key(id, sim)
        // 오래된 것부터 정리 (최대 500개)
        val trimmed = if (set.size > 500) set.sortedBy { it.substringAfter(':').toLongOrNull() ?: 0 }.takeLast(500).toSet() else set
        p.edit().putStringSet("handled", trimmed).apply() // B가 메인 스레드에서 부르므로 비동기 저장
    }

    fun clearSim() {
        val set = (p.getStringSet("handled", emptySet()) ?: emptySet()).filterNot { it.startsWith("S:") }.toSet()
        p.edit().putStringSet("handled", set).apply()
    }

    fun setAlive(tripId: Long, at: Long) { p.edit().putLong("alive_$tripId", at).commit() }
    fun getAlive(tripId: Long): Long? = p.getLong("alive_$tripId", -1L).takeIf { it > 0 }
    fun clearAlive(tripId: Long) { p.edit().remove("alive_$tripId").commit() }
}
