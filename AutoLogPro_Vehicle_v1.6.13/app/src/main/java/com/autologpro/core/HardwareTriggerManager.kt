package com.autologpro.core

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.autologpro.data.Source
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.math.abs

/**
 * 차량 신호 → 주행 상태(PARKED / DRIVING / UNKNOWN).
 *
 * ■ 메인: GEAR_SELECTION (권한 CAR_POWERTRAIN)
 *   P → PARKED, D·수동 1~9단 → DRIVING, R·N → 상태 유지(P→R→P로 운행이 생기지 않게)
 *
 * ■ 대체(FALLBACK): 기어를 못 쓸 때만. 주차브레이크(CAR_POWERTRAIN)와 속도(CAR_SPEED)가 **둘 다** 정상일 때만 사용.
 *   출발: 브레이크 해제 + 속도 > 0.5m/s 가 1.5초 연속
 *   도착: 브레이크 체결 + 속도 ≤ 0.5m/s 가 1초 연속
 *   속도 값이 2초 넘게 안 오면 STALE → 판단 보류(직접 한 번 읽어 봄)
 *   브레이크만 풀린 상태, 신호대기(브레이크 해제 + 0km/h)는 상태를 바꾸지 않는다.
 *
 * ■ NONE: 둘 다 안 되면 자동 기록을 보류하고 상태를 UNKNOWN으로 둔다(지어내지 않음).
 *
 * 스레드: 차량 콜백·onTick()·refreshPermissions()가 어느 스레드에서 오든 내부 잠금으로 순서대로 처리한다.
 * onTick()은 VehicleCoreService가 0.25초마다 부른다(대체 감지의 연속 시간 판정용).
 */
class HardwareTriggerManager(
    private val context: Context,
    private val odometer: OdometerReader? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val TAG = "HwTrigger"

        const val PERM_POWERTRAIN = "android.car.permission.CAR_POWERTRAIN" // 기어, 주차브레이크
        const val PERM_SPEED = "android.car.permission.CAR_SPEED"           // 속도 (실행 중 허용 필요)
        const val PERM_MILEAGE = "android.car.permission.CAR_MILEAGE"       // AAOS 주행거리 (일반 앱에 안 줄 수 있음)

        // VHAL VehicleGear 값 (android.car.VehicleGear와 동일)
        const val GEAR_UNKNOWN = 0x0000
        const val GEAR_NEUTRAL = 0x0001
        const val GEAR_REVERSE = 0x0002
        const val GEAR_PARK = 0x0004
        const val GEAR_DRIVE = 0x0008
        const val GEAR_1 = 0x0010

        const val MOVING_MPS = 0.5f        // 약 1.8km/h
        const val DEPART_CONFIRM_MS = 1_500L
        const val ARRIVE_CONFIRM_MS = 1_000L
        const val SPEED_STALE_MS = 2_000L
        const val SPEED_POLL_MS = 1_000L
        private const val SPEED_RATE_HZ = 10f

        const val ODO_MAX_KM = 2_000_000.0
        const val ODO_DECREASE_TOLERANCE_KM = 0.1

        private fun name(prop: Int) = when (prop) {
            VehiclePropertyIds.GEAR_SELECTION -> "GEAR"
            VehiclePropertyIds.PARKING_BRAKE_ON -> "PARKING_BRAKE"
            VehiclePropertyIds.PERF_VEHICLE_SPEED -> "SPEED"
            VehiclePropertyIds.PERF_ODOMETER -> "ODOMETER"
            else -> "PROP($prop)"
        }

        private fun label(s: SignalStatus) = when (s) {
            SignalStatus.UNSUPPORTED -> "미지원"
            SignalStatus.NO_PERMISSION -> "권한 없음"
            SignalStatus.SUBSCRIBE_FAILED -> "구독 실패"
            SignalStatus.ERROR -> "오류"
            SignalStatus.STALE -> "값 끊김"
            SignalStatus.NO_CAR -> "차량 없음"
            SignalStatus.OK -> "정상"
            SignalStatus.NOT_CHECKED -> "미확인"
        }
    }

    private val lock = Any()
    private var car: Car? = null
    private var cpm: CarPropertyManager? = null
    private val subscribed = mutableSetOf<Int>()
    private var gearBroken = false // 구독 후 오류가 난 기어는 refreshPermissions() 전까지 다시 쓰지 않음

    private val _state = MutableStateFlow(DriveState.UNKNOWN)
    val state: StateFlow<DriveState> = _state.asStateFlow()

    private val _diag = MutableStateFlow(Diagnostics())
    val diagnostics: StateFlow<Diagnostics> = _diag.asStateFlow()

    private val channel = Channel<Transition>(Channel.UNLIMITED)
    /** 상태 전환. 구독자는 하나(VehicleCoreService). 늦게 구독해도 쌓여 있다가 전달된다. */
    val transitions: Flow<Transition> = channel.receiveAsFlow()

    private val _initial = CompletableDeferred<DriveState>()
    /** 처음으로 확정된 상태(앱이 켜졌을 때 주차였는지 주행 중이었는지) */
    val initialState: Deferred<DriveState> = _initial

    private var lastKnown: DriveState? = null // 마지막으로 확정했던 상태(UNKNOWN 제외)

    // 대체 감지용
    private var brakeOn: Boolean? = null
    private var speedMps: Float? = null
    private var speedAt: Long? = null
    private var departSince: Long? = null
    private var arriveSince: Long? = null
    private var lastPollAt = 0L

    // 주행거리 검증용 (이번 실행 중 마지막 정상값)
    private var lastGoodOdo: Double? = null
    private var aaosOdoStatusLogged: OdoStatus? = null

    val mode: DetectMode get() = _diag.value.mode
    fun isParked(): Boolean = _state.value == DriveState.PARKED

    // ───────────────────────── 시작 / 종료 / 권한 ─────────────────────────

    fun start(): DetectMode = synchronized(lock) {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            Log.w(TAG, "차량(AAOS) 기기가 아님 → 자동 기록 보류")
            setAll(SignalStatus.NO_CAR, "차량 기기 아님")
            return DetectMode.NONE
        }
        val c = try { Car.createCar(context) } catch (t: Throwable) { Log.e(TAG, "Car.createCar 예외: $t"); null }
        val m = c?.let { runCatching { it.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager }.getOrNull() }
        if (m == null) {
            Log.e(TAG, "Car 서비스 연결 실패 → 자동 기록 보류")
            setAll(SignalStatus.NO_CAR, "Car 서비스 연결 실패")
            return DetectMode.NONE
        }
        car = c
        cpm = m
        chooseMode()
        mode
    }

    /**
     * 권한을 새로 받았거나 앱 화면이 다시 보일 때 호출. 기어부터 다시 시도하고, 안 되면 대체 감지를 다시 시도한다.
     * 앱 재설치·재시작 없이 재구독된다.
     */
    fun refreshPermissions(): DetectMode = synchronized(lock) {
        if (cpm == null) return start()
        gearBroken = false
        if (mode != DetectMode.GEAR) chooseMode()
        mode
    }

    fun stop() = synchronized(lock) {
        for (p in subscribed.toList()) unsubscribe(p)
        runCatching { car?.disconnect() }
        car = null
        cpm = null
        odometer?.release()
    }

    /** 앱이 요청해야 할 런타임 권한 중 아직 없는 것 */
    fun missingPermissions(): List<String> =
        listOf(PERM_POWERTRAIN, PERM_SPEED).filter { !granted(it) }

    /**
     * Read the simulator's REAL speed property for ACCEL demo distance integration.
     * Unlike fallback detection, this intentionally works when GEAR mode is active.
     * Android Automotive reports PERF_VEHICLE_SPEED in m/s (not km/h).
     * No imaginary speed is returned on denied permission / unsupported properties.
     */
    fun readSpeedMpsForDemo(): Double? {
        val manager = synchronized(lock) { cpm } ?: return null
        if (!granted(PERM_SPEED)) return null
        return try {
            val speedProperty = when {
                manager.getCarPropertyConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED) != null ->
                    VehiclePropertyIds.PERF_VEHICLE_SPEED
                manager.getCarPropertyConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED_DISPLAY) != null ->
                    VehiclePropertyIds.PERF_VEHICLE_SPEED_DISPLAY
                else -> return null
            }
            val speed = manager.getFloatProperty(speedProperty, 0)
            if (!speed.isFinite() || kotlin.math.abs(speed) > 85f) null
            else kotlin.math.abs(speed.toDouble())
        } catch (_: SecurityException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    /** A status line for the ACCEL diagnostic Logcat, not an inferred success claim. */
    fun demoSpeedStatus(): String = synchronized(lock) {
        val manager = cpm ?: return@synchronized "CAR_SERVICE_UNAVAILABLE"
        if (!granted(PERM_SPEED)) return@synchronized "CAR_SPEED_PERMISSION_DENIED"
        return@synchronized try {
            val actual = manager.getCarPropertyConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED)
            val display = manager.getCarPropertyConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED_DISPLAY)
            when {
                actual != null -> "PERF_VEHICLE_SPEED_AVAILABLE"
                display != null -> "PERF_VEHICLE_SPEED_DISPLAY_AVAILABLE"
                else -> "SPEED_PROPERTY_UNSUPPORTED"
            }
        } catch (e: SecurityException) { "SPEED_SECURITY_EXCEPTION" }
        catch (t: Throwable) { "SPEED_CHECK_ERROR:${t.javaClass.simpleName}" }
    }

    // ───────────────────────── 주행거리 ─────────────────────────

    /** 누적 주행거리(km). Pleos → AAOS PERF_ODOMETER 순. 검증을 통과한 값만 돌려준다. 못 읽으면 null. */
    suspend fun readOdometerKm(): Double? {
        odometer?.let { r ->
            val v = runCatching { r.readKm() }.getOrNull()
            updateDiag { it.copy(pleosOdometer = r.status) }
            if (v != null) validate(v, OdoSource.PLEOS)?.let { return it }
        }
        val v = readAaosOdometer() ?: return null
        return validate(v, OdoSource.AAOS)
    }

    /** 주행거리를 못 읽은 이유 한 줄 (Logcat·lastError용) */
    fun odometerFailureReason(): String {
        val d = _diag.value
        fun k(s: OdoStatus) = when (s) {
            OdoStatus.SDK_MISSING -> "SDK 미탑재"
            OdoStatus.NO_PERMISSION -> "권한 부족"
            OdoStatus.UNSUPPORTED -> "속성 미지원"
            OdoStatus.INIT_FAILED -> "SDK 초기화 실패"
            OdoStatus.TIMEOUT -> "응답 없음"
            OdoStatus.READ_FAILED -> "조회 실패"
            OdoStatus.INVALID_VALUE -> "비정상 값"
            OdoStatus.OK -> "정상"
            OdoStatus.NOT_CHECKED -> "미시도"
            OdoStatus.METHOD_UNKNOWN -> "메서드 미확인"
        }
        return "Pleos=${k(d.pleosOdometer)}(${d.pleosOdometer}), AAOS=${k(d.aaosOdometer)}(${d.aaosOdometer})"
    }

    private fun readAaosOdometer(): Double? {
        val m = synchronized(lock) { cpm }
        val st: OdoStatus
        var value: Double? = null
        if (m == null) st = OdoStatus.UNSUPPORTED
        else if (!granted(PERM_MILEAGE)) st = OdoStatus.NO_PERMISSION
        else {
            st = try {
                if (m.getCarPropertyConfig(VehiclePropertyIds.PERF_ODOMETER) == null) OdoStatus.UNSUPPORTED
                else { value = m.getFloatProperty(VehiclePropertyIds.PERF_ODOMETER, 0).toDouble(); OdoStatus.OK }
            } catch (e: SecurityException) { OdoStatus.NO_PERMISSION } catch (t: Throwable) { OdoStatus.READ_FAILED }
        }
        if (st != aaosOdoStatusLogged) {
            Log.i(TAG, "AAOS PERF_ODOMETER: $st" + if (st == OdoStatus.NO_PERMISSION) " ($PERM_MILEAGE 미허용 — 일반 앱에는 안 주어질 수 있음)" else "")
            aaosOdoStatusLogged = st
        }
        updateDiag { it.copy(aaosOdometer = st) }
        return value
    }

    private fun validate(v: Double, src: OdoSource): Double? {
        val last = synchronized(lock) { lastGoodOdo }
        val bad = when {
            !v.isFinite() -> "숫자가 아님($v)"
            v < 0 -> "음수($v)"
            v > ODO_MAX_KM -> "범위 초과($v km)"
            last != null && v < last - ODO_DECREASE_TOLERANCE_KM -> "계기판 감소($last → $v km)"
            else -> null
        }
        if (bad != null) {
            Log.e(TAG, "주행거리 값 거부[$src]: $bad")
            updateDiag {
                if (src == OdoSource.PLEOS) it.copy(pleosOdometer = OdoStatus.INVALID_VALUE)
                else it.copy(aaosOdometer = OdoStatus.INVALID_VALUE)
            }
            return null
        }
        synchronized(lock) { lastGoodOdo = v }
        updateDiag { it.copy(odometerSource = src, lastOdometerKm = v) }
        Log.i(TAG, "주행거리 = $v km (출처: $src)")
        return v
    }

    // ───────────────────────── 주기 처리 ─────────────────────────

    /** 0.25초마다 호출. 대체 감지의 '연속 N초' 판정과 속도 값 끊김 확인. */
    fun onTick() {
        synchronized(lock) {
            if (mode != DetectMode.FALLBACK) return
            val now = clock()
            if (!speedFresh(now) && now - lastPollAt >= SPEED_POLL_MS) {
                lastPollAt = now
                pollSpeed(now)
            }
            evalFallback(now)
        }
    }

    // ───────────────────────── 내부: 모드 선택 ─────────────────────────

    private fun chooseMode() {
        // 1) 기어
        if (!gearBroken) {
            val g = trySubscribe(VehiclePropertyIds.GEAR_SELECTION, PERM_POWERTRAIN, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            updateDiag { it.copy(gear = g) }
            if (g == SignalStatus.OK) {
                unsubscribe(VehiclePropertyIds.PARKING_BRAKE_ON)
                unsubscribe(VehiclePropertyIds.PERF_VEHICLE_SPEED)
                updateDiag { it.copy(parkingBrake = SignalStatus.NOT_CHECKED, speed = SignalStatus.NOT_CHECKED) }
                setMode(DetectMode.GEAR, "기어로 감지")
                return
            }
        }
        // 2) 대체: 브레이크 + 속도 둘 다 정상이어야 함
        val b = trySubscribe(VehiclePropertyIds.PARKING_BRAKE_ON, PERM_POWERTRAIN, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        val s = trySubscribe(VehiclePropertyIds.PERF_VEHICLE_SPEED, PERM_SPEED, SPEED_RATE_HZ)
        updateDiag { it.copy(parkingBrake = b, speed = s) }
        if (b == SignalStatus.OK && s == SignalStatus.OK) {
            setMode(DetectMode.FALLBACK, "기어 대신 주차브레이크+속도로 감지")
            return
        }
        // 3) 보류
        unsubscribe(VehiclePropertyIds.PARKING_BRAKE_ON)
        unsubscribe(VehiclePropertyIds.PERF_VEHICLE_SPEED)
        setMode(DetectMode.NONE, "자동 기록 보류: 기어=${label(_diag.value.gear)}, 브레이크=${label(b)}, 속도=${label(s)}")
        setUnknown()
    }

    private fun setMode(m: DetectMode, note: String) {
        if (_diag.value.mode != m || _diag.value.note != note) Log.i(TAG, "감지 모드 = $m ($note)")
        updateDiag { it.copy(mode = m, note = note) }
        departSince = null
        arriveSince = null
    }

    private fun trySubscribe(prop: Int, perm: String, rate: Float): SignalStatus {
        if (prop in subscribed) return SignalStatus.OK
        val m = cpm ?: return SignalStatus.NO_CAR
        val n = name(prop)
        if (!granted(perm)) {
            Log.w(TAG, "$n 권한 없음 ($perm)")
            return SignalStatus.NO_PERMISSION
        }
        val config = try { m.getCarPropertyConfig(prop) } catch (e: SecurityException) {
            Log.w(TAG, "$n 권한 없음 (SecurityException: ${e.message})"); return SignalStatus.NO_PERMISSION
        } catch (t: Throwable) {
            Log.w(TAG, "$n 구독 실패 (설정 조회 예외: $t)"); return SignalStatus.SUBSCRIBE_FAILED
        }
        if (config == null) {
            Log.w(TAG, "$n 미지원 (getCarPropertyConfig == null)")
            return SignalStatus.UNSUPPORTED
        }
        val ok = try { m.registerCallback(callback, prop, rate) } catch (e: SecurityException) {
            Log.w(TAG, "$n 권한 없음 (registerCallback SecurityException: ${e.message})"); return SignalStatus.NO_PERMISSION
        } catch (t: Throwable) {
            Log.w(TAG, "$n 구독 실패 (예외: $t)"); return SignalStatus.SUBSCRIBE_FAILED
        }
        if (!ok) {
            Log.w(TAG, "$n 구독 실패 (registerCallback=false)")
            return SignalStatus.SUBSCRIBE_FAILED
        }
        subscribed += prop
        Log.i(TAG, "$n 구독 성공")
        return SignalStatus.OK
    }

    private fun unsubscribe(prop: Int) {
        if (prop !in subscribed) return
        runCatching { cpm?.unregisterCallback(callback, prop) }
        subscribed -= prop
        when (prop) {
            VehiclePropertyIds.PARKING_BRAKE_ON -> brakeOn = null
            VehiclePropertyIds.PERF_VEHICLE_SPEED -> { speedMps = null; speedAt = null }
        }
        Log.i(TAG, "${name(prop)} 구독 해제")
    }

    private fun granted(perm: String) = context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    // ───────────────────────── 내부: 신호 처리 ─────────────────────────

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(v: CarPropertyValue<*>) = synchronized(lock) { onSignal(v) }
        override fun onErrorEvent(propId: Int, zone: Int) = synchronized(lock) { onSignalError(propId) }
    }

    private fun onSignal(v: CarPropertyValue<*>) {
        val now = clock()
        val prop = v.propertyId
        if (prop !in subscribed) return // 해제 직후 늦게 도착한 값
        if (v.status != CarPropertyValue.STATUS_AVAILABLE) {
            Log.w(TAG, "${name(prop)} 값 사용 불가(status=${v.status})")
            onSignalError(prop)
            return
        }
        when (prop) {
            VehiclePropertyIds.GEAR_SELECTION -> {
                updateDiag { it.copy(gear = SignalStatus.OK) }
                val g = (v.value as? Int) ?: return
                onGear(g, now)
            }
            VehiclePropertyIds.PARKING_BRAKE_ON -> {
                brakeOn = v.value as? Boolean
                updateDiag { it.copy(parkingBrake = SignalStatus.OK) }
                evalFallback(now)
            }
            VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                val s = (v.value as? Float) ?: return
                if (!s.isFinite()) return
                speedMps = abs(s) // 후진은 음수로 올 수 있음
                speedAt = now
                evalFallback(now)
            }
        }
    }

    private fun onSignalError(prop: Int) {
        Log.w(TAG, "${name(prop)} 오류 이벤트")
        when (prop) {
            VehiclePropertyIds.GEAR_SELECTION -> {
                updateDiag { it.copy(gear = SignalStatus.ERROR) }
                if (mode == DetectMode.GEAR) {
                    unsubscribe(prop)
                    gearBroken = true
                    setUnknown()
                    chooseMode() // 대체 감지 시도 (안 되면 NONE)
                }
            }
            VehiclePropertyIds.PARKING_BRAKE_ON, VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                if (prop == VehiclePropertyIds.PARKING_BRAKE_ON) updateDiag { it.copy(parkingBrake = SignalStatus.ERROR) }
                else updateDiag { it.copy(speed = SignalStatus.ERROR) }
                if (mode == DetectMode.FALLBACK) {
                    unsubscribe(VehiclePropertyIds.PARKING_BRAKE_ON)
                    unsubscribe(VehiclePropertyIds.PERF_VEHICLE_SPEED)
                    setMode(DetectMode.NONE, "대체 감지 신호 오류(${name(prop)}) → 자동 기록 보류")
                    setUnknown()
                }
            }
        }
    }

    private fun onGear(g: Int, now: Long) {
        if (mode != DetectMode.GEAR) return
        val next = when {
            g == GEAR_PARK -> DriveState.PARKED
            g == GEAR_DRIVE || g >= GEAR_1 -> DriveState.DRIVING
            g == GEAR_REVERSE || g == GEAR_NEUTRAL -> return // 상태 유지
            else -> { Log.w(TAG, "알 수 없는 기어 값 $g → 무시"); return }
        }
        move(next, Source.GEAR, now)
    }

    private fun speedFresh(now: Long) = speedAt?.let { now - it <= SPEED_STALE_MS } == true

    private fun pollSpeed(now: Long) {
        val m = cpm ?: return
        try {
            val s = m.getFloatProperty(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)
            if (s.isFinite()) { speedMps = abs(s); speedAt = now }
        } catch (t: Throwable) {
            Log.w(TAG, "SPEED 직접 읽기 실패: $t")
        }
    }

    private fun evalFallback(now: Long) {
        if (mode != DetectMode.FALLBACK) return
        val fresh = speedFresh(now)
        val wanted = if (fresh) SignalStatus.OK else SignalStatus.STALE
        if (_diag.value.speed != wanted) {
            if (!fresh) Log.w(TAG, "SPEED 값 끊김(${SPEED_STALE_MS}ms 이상) → 판단 보류")
            updateDiag { it.copy(speed = wanted) }
        }
        val b = brakeOn
        val s = speedMps
        if (b == null || s == null || !fresh) {
            departSince = null
            arriveSince = null
            return
        }
        departSince = if (!b && s > MOVING_MPS) departSince ?: now else null
        arriveSince = if (b && s <= MOVING_MPS) arriveSince ?: now else null
        departSince?.let { if (now - it >= DEPART_CONFIRM_MS) move(DriveState.DRIVING, Source.FALLBACK, it) }
        arriveSince?.let { if (now - it >= ARRIVE_CONFIRM_MS) move(DriveState.PARKED, Source.FALLBACK, it) }
    }

    /** at = 상태가 실제로 바뀐 시각(대체 감지는 조건이 처음 성립한 시각) */
    private fun move(next: DriveState, source: Source, at: Long) {
        val prev = lastKnown
        _state.value = next
        when {
            prev == null -> {
                lastKnown = next
                _initial.complete(next)
                Log.i(TAG, "첫 상태 = $next ($source)")
            }
            prev != next -> {
                lastKnown = next
                channel.trySend(Transition(next, source, at))
                Log.i(TAG, "$prev → $next ($source)")
            }
            // 같은 상태 반복 수신 → 무시
        }
    }

    /** 신호를 잃음. 마지막 상태는 기억해 두고, 신호가 돌아오면 그 상태와 비교해 전환을 낸다. */
    private fun setUnknown() {
        if (_state.value != DriveState.UNKNOWN) Log.w(TAG, "상태 UNKNOWN (마지막 확정 상태 = $lastKnown)")
        _state.value = DriveState.UNKNOWN
        departSince = null
        arriveSince = null
    }

    private fun setAll(s: SignalStatus, note: String) =
        updateDiag { it.copy(mode = DetectMode.NONE, gear = s, parkingBrake = s, speed = s, note = note) }

    private inline fun updateDiag(f: (Diagnostics) -> Diagnostics) {
        synchronized(_diag) { _diag.value = f(_diag.value) }
    }
}
