package com.autologpro.core

import com.autologpro.data.Source

/** 차량 주행 상태. UNKNOWN = 신호가 없거나 판단을 보류한 상태(자동 기록 안 함). */
enum class DriveState { UNKNOWN, PARKED, DRIVING }

/** 상태 감지 방식. NONE = 쓸 수 있는 신호가 없어 자동 기록을 보류함. */
enum class DetectMode { NONE, GEAR, FALLBACK }

/** 신호 하나(기어·주차브레이크·속도)의 상태. Logcat과 diagnostics에 그대로 나간다. */
enum class SignalStatus {
    NOT_CHECKED,      // 아직 확인 안 함
    OK,               // 구독 중, 값 수신 정상
    NO_CAR,           // 차량(AAOS) 기기가 아니거나 Car 서비스 연결 실패
    UNSUPPORTED,      // 이 차량(에뮬레이터)이 해당 속성을 제공하지 않음 (getCarPropertyConfig == null)
    NO_PERMISSION,    // 권한 없음 (checkSelfPermission 거부 또는 SecurityException)
    SUBSCRIBE_FAILED, // 권한·지원은 있는데 registerCallback 실패
    ERROR,            // 구독 후 오류 이벤트 / 값 사용 불가 상태 수신
    STALE,            // 2초 넘게 새 값이 없음 (판단 보류)
}

/** 주행거리 읽기 상태 */
enum class OdoStatus {
    NOT_CHECKED, OK,
    SDK_MISSING,      // Pleos SDK 클래스가 앱에 없음 (의존성 미추가)
    NO_PERMISSION,    // 권한 없음
    UNSUPPORTED,      // 속성 미지원
    INIT_FAILED,      // SDK 객체 생성/초기화 실패 (재시도함)
    METHOD_UNKNOWN,   // 총 주행거리 메서드를 자동으로 특정하지 못함 → Logcat의 메서드 목록 보고 지정
    TIMEOUT,          // 응답 없음
    READ_FAILED,      // 호출 실패 / 값 해석 실패
    INVALID_VALUE,    // 값이 비정상 (NaN·음수·범위 밖·감소)
}

enum class OdoSource { NONE, PLEOS, AAOS }

/** B 화면·디버그용 진단 정보. VehicleCoreService.diagnostics 로 구독. */
data class Diagnostics(
    val mode: DetectMode = DetectMode.NONE,
    val gear: SignalStatus = SignalStatus.NOT_CHECKED,
    val parkingBrake: SignalStatus = SignalStatus.NOT_CHECKED,
    val speed: SignalStatus = SignalStatus.NOT_CHECKED,
    val pleosOdometer: OdoStatus = OdoStatus.NOT_CHECKED,
    val aaosOdometer: OdoStatus = OdoStatus.NOT_CHECKED,
    val odometerSource: OdoSource = OdoSource.NONE,
    val lastOdometerKm: Double? = null,
    /** 한 줄 요약 (사람이 읽는 용) */
    val note: String = "",
)

/** 상태가 바뀐 순간 1건. at = 감지 시각(ms) */
data class Transition(val to: DriveState, val source: Source, val at: Long)

/** 누적 주행거리(km) 읽기. 못 읽으면 null, 이유는 status. */
interface OdometerReader {
    val status: OdoStatus
    suspend fun readKm(): Double?
    fun release() {}
}

/** simulate / stopSimulation / resetSimulationData 결과 */
enum class SimResult {
    OK,
    NOT_DEBUG_BUILD,     // 릴리스(디버그 불가) 빌드 → 시뮬레이션 금지
    SERVICE_NOT_RUNNING, // VehicleCoreService.start() 전
    ALREADY_IN_STATE,    // 이미 그 상태 (중복 호출)
    ODO_INVALID,         // 계기판 값 이상 (음수·NaN·직전 도착값보다 작음·출발값보다 작음)
    STILL_DRIVING,       // 시뮬 주행 중에는 중지·초기화 불가
    FAILED,              // DB 오류 등 (Logcat VehicleCore 확인)
}
