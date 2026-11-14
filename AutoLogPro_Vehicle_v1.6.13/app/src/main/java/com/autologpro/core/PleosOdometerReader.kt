package com.autologpro.core

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Odometer
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Pleos Vehicle SDK Odometer → 누적 주행거리(km). (v4: 리플렉션 제거, SDK 타입 직접 호출)
 *
 * ■ 근거
 *   - 공식 문서 Odometer API: getCurrentDistanceDriven / checkCurrentDistanceDrivenCapabilities / registerDistanceDriven
 *   - 공식 샘플 앱(github.com/Pleos-Playground/pleos-sample-vehicle-app, SDK 2.3.1)의 사용 방식:
 *       Vehicle(context.applicationContext) → initialize()(동기, 실패 시 예외) → getOdometer() → get…(onComplete, onFailed) → release()
 *
 * ■ Android Studio에서 "Go to Declaration"으로 확인할 2가지 (샘플에 Odometer 호출 예가 없어 A가 확인 못 함)
 *   ① getCurrentDistanceDriven의 onComplete 값 타입(Float? / Int? 등) → 아래 코드는 어떤 숫자 타입이든 받도록 작성
 *   ② 값의 단위(km인지) → Logcat "raw=" 값을 Virtual Console 계기판과 비교. 다르면 UNIT_TO_KM 수정
 *
 * ■ 이 파일은 Pleos SDK 의존성이 **필수**다. 의존성이 없으면 컴파일이 실패한다(v3처럼 조용히 실패하지 않도록 일부러).
 * ■ SDK 호출은 샘플처럼 메인 스레드에서 한다. 결과는 콜백을 기다렸다가 돌려준다.
 */
class PleosOdometerReader(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : OdometerReader {

    companion object {
        private const val TAG = "PleosOdo"

        /** SDK 값 → km 배율. Virtual Console 계기판(km)과 Logcat raw 값이 같으면 1.0 */
        @Volatile var UNIT_TO_KM: Double = 1.0
        /** 단위를 직접 확인했으면 true로. false면 처음 한 번 '단위 미확인' 경고를 찍는다 */
        @Volatile var UNIT_VERIFIED: Boolean = false

        const val CALL_TIMEOUT_MS = 1_500L
        const val INIT_RETRY_MS = 3_000L
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mutex = Mutex()

    private var vehicle: Vehicle? = null
    private var odometer: Odometer? = null
    private var lastInitAttempt: Long? = null
    private var capabilityChecked = false
    private var unitWarned = false

    @Volatile override var status: OdoStatus = OdoStatus.NOT_CHECKED
        private set

    override suspend fun readKm(): Double? = mutex.withLock {
        val odo = ensureReady() ?: return@withLock null
        if (!capabilityChecked) checkCapability(odo)
        if (status == OdoStatus.UNSUPPORTED) return@withLock null

        val result = withTimeoutOrNull(CALL_TIMEOUT_MS) {
            onMain<Result<Any?>> { done ->
                odo.getCurrentDistanceDriven(
                    onComplete = { value -> done(Result.success(value)) },
                    onFailed = { e -> done(Result.failure(e)) },
                )
            }
        }
        if (result == null) {
            set(OdoStatus.TIMEOUT, "getCurrentDistanceDriven 응답 없음(${CALL_TIMEOUT_MS}ms)")
            return@withLock null
        }
        val raw = result.getOrElse { e ->
            set(if (isPermission(e)) OdoStatus.NO_PERMISSION else OdoStatus.READ_FAILED, "getCurrentDistanceDriven 실패: ${desc(e)}")
            return@withLock null
        }
        val num = (raw as? Number)?.toDouble()
        if (num == null) {
            set(OdoStatus.READ_FAILED, "getCurrentDistanceDriven 값이 숫자가 아님: ${raw?.javaClass?.name} = $raw")
            return@withLock null
        }
        val km = num * UNIT_TO_KM
        if (!UNIT_VERIFIED && !unitWarned) {
            Log.w(TAG, "단위 미확인: raw=$num × $UNIT_TO_KM = $km km → Virtual Console 계기판과 같은지 확인 후 UNIT_VERIFIED=true")
            unitWarned = true
        }
        status = OdoStatus.OK
        Log.i(TAG, "주행거리 읽음: raw=$num → $km km")
        km
    }

    override fun release() {
        val v = vehicle ?: return
        main.post { runCatching { v.release() }.onFailure { Log.w(TAG, "release 실패: ${desc(it)}") } }
        vehicle = null
        odometer = null
    }

    // ───────────────────────── 초기화 ─────────────────────────

    /** 준비되면 Odometer, 아니면 null. 실패하면 INIT_RETRY_MS 동안 다시 시도하지 않는다(초기화 지연 대비 재시도). */
    private suspend fun ensureReady(): Odometer? {
        odometer?.let { return it }
        val now = clock()
        lastInitAttempt?.let { if (now - it < INIT_RETRY_MS) return null }
        lastInitAttempt = now

        val r = onMain<Result<Odometer>> { done ->
            try {
                val v = vehicle ?: Vehicle(appContext).also { vehicle = it }
                v.initialize()
                done(Result.success(v.getOdometer()))
            } catch (e: Throwable) {
                done(Result.failure(e))
            }
        }
        return r.fold(
            onSuccess = { o ->
                Log.i(TAG, "Pleos Vehicle 초기화 성공, Odometer 준비됨")
                odometer = o
                o
            },
            onFailure = { e ->
                vehicle = null
                set(if (isPermission(e)) OdoStatus.NO_PERMISSION else OdoStatus.INIT_FAILED,
                    "Pleos Vehicle 초기화 실패(${INIT_RETRY_MS}ms 뒤 재시도): ${desc(e)}")
                null
            },
        )
    }

    /**
     * 지원 여부 확인(한 번). 콜백 인자 개수가 문서에 없어 리플렉션으로 호출하고, 결과는 로그·상태에만 쓴다.
     * 확인이 실패해도 값 읽기는 막지 않는다(accessType=NONE일 때만 미지원 처리).
     */
    private suspend fun checkCapability(odo: Odometer) {
        capabilityChecked = true
        val m = odo.javaClass.methods.firstOrNull { it.name == "checkCurrentDistanceDrivenCapabilities" } ?: run {
            Log.w(TAG, "checkCurrentDistanceDrivenCapabilities 없음 → 지원 확인 생략"); return
        }
        val result = withTimeoutOrNull(CALL_TIMEOUT_MS) {
            onMain<Result<List<Any?>>> { done ->
                val args = m.parameterTypes.mapIndexed { i, t ->
                    java.lang.reflect.Proxy.newProxyInstance(t.classLoader ?: javaClass.classLoader, arrayOf(t)) { proxy, method, a ->
                        when (method.name) {
                            "equals" -> proxy === a?.getOrNull(0)
                            "hashCode" -> System.identityHashCode(proxy)
                            "toString" -> "CapabilityCallback#$i"
                            else -> {
                                val fail = i > 0 // 문서 규칙: (onComplete, onFailed)
                                done(if (fail) Result.failure(a?.getOrNull(0) as? Throwable ?: RuntimeException("onFailed")) else Result.success(a?.toList() ?: emptyList()))
                                if (method.returnType.name == "kotlin.Unit") Unit else null
                            }
                        }
                    }
                }
                try { m.invoke(odo, *args.toTypedArray()) } catch (e: Throwable) { done(Result.failure(e)) }
            }
        }
        when {
            result == null -> Log.w(TAG, "지원 확인 응답 없음 → 값 읽기는 계속 시도")
            result.isFailure -> Log.w(TAG, "지원 확인 실패: ${desc(result.exceptionOrNull()!!)} → 값 읽기는 계속 시도")
            else -> {
                val values = result.getOrThrow()
                val access = values.firstNotNullOfOrNull { v ->
                    v?.javaClass?.methods?.firstOrNull { it.name == "getAccessType" && it.parameterCount == 0 }?.let { g -> runCatching { g.invoke(v) }.getOrNull() }
                }
                Log.i(TAG, "Odometer 지원 확인: accessType=$access (${values.joinToString()})")
                if (access?.toString() == "NONE") set(OdoStatus.UNSUPPORTED, "이 차량(에뮬레이터)은 getCurrentDistanceDriven 미지원(accessType=NONE)")
            }
        }
    }

    // ───────────────────────── 보조 ─────────────────────────

    /** 메인 스레드에서 block을 실행하고, block이 done(값)을 부를 때까지 기다린다. */
    private suspend fun <T> onMain(block: (done: (T) -> Unit) -> Unit): T = suspendCancellableCoroutine { cont ->
        val once = CompletableDeferred<Unit>()
        val done: (T) -> Unit = { v -> if (once.complete(Unit) && cont.isActive) cont.resume(v) }
        main.post { block(done) }
    }

    private fun set(s: OdoStatus, msg: String) {
        if (s != status) Log.w(TAG, "[$s] $msg") else Log.d(TAG, "[$s] $msg")
        status = s
    }

    private fun unwrap(t: Throwable): Throwable = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
    private fun desc(t: Throwable) = unwrap(t).let { "${it.javaClass.simpleName}: ${it.message}" }
    private fun isPermission(t: Throwable): Boolean {
        var e: Throwable? = unwrap(t)
        while (e != null) {
            if (e is SecurityException || e.message?.contains("permission", ignoreCase = true) == true) return true
            e = e.cause
        }
        return false
    }
}
