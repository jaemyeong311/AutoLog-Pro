package com.autologpro.app

import android.app.Dialog
import android.app.DialogFragment
import android.app.FragmentManager
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Minimal parked-trip classification modal; underlying record is saved before this opens. */
@Suppress("DEPRECATION")
class ContextModalFragment : DialogFragment() {
    companion object {
        private const val AUTO_DISMISS_MS = 60_000L
        private const val COUNTDOWN_DEADLINE_KEY = "countdown_deadline_elapsed_ms"

        fun show(manager: FragmentManager, tripId: Long) {
            ContextModalFragment().apply {
                arguments = Bundle().apply { putLong("trip_id", tripId) }
            }.show(manager, "trip_context")
        }
    }

    // The classification popup is native Android UI, not HTML. It must follow
    // B v1.4's chosen light/dark WebView theme without a separate green palette.
    private data class Palette(
        val surface: Int, val selected: Int, val unselected: Int,
        val divider: Int, val text: Int, val muted: Int,
        val accent: Int, val selectedText: Int, val unselectedText: Int
    )

    private val lightInk = Palette(
        surface = Color.rgb(255, 255, 255),
        selected = Color.rgb(38, 57, 74),
        unselected = Color.rgb(246, 248, 250),
        divider = Color.rgb(222, 228, 234),
        text = Color.rgb(24, 30, 38),
        muted = Color.rgb(100, 116, 132),
        accent = Color.rgb(52, 106, 155),
        selectedText = Color.WHITE,
        unselectedText = Color.rgb(35, 49, 62)
    )
    private val darkInk = Palette(
        surface = Color.rgb(24, 28, 34),
        selected = Color.rgb(147, 181, 212),
        unselected = Color.rgb(34, 41, 50),
        divider = Color.rgb(52, 62, 74),
        text = Color.rgb(240, 243, 246),
        muted = Color.rgb(159, 172, 186),
        accent = Color.rgb(147, 181, 212),
        selectedText = Color.rgb(18, 25, 34),
        unselectedText = Color.rgb(231, 235, 240)
    )
    private var ink: Palette = lightInk
    private var timer: CountDownTimer? = null
    private var countdownDeadlineElapsedMs: Long = 0L
    private var countdownLabel: TextView? = null
    private var countdownProgress: ProgressBar? = null
    private var actionsClosed = false
    internal fun isShowingTrip(id: Long): Boolean = !actionsClosed && isAdded && dialog?.isShowing == true &&
        arguments?.getLong("trip_id") == id && SystemClock.elapsedRealtime() < countdownDeadlineElapsedMs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        countdownDeadlineElapsedMs = savedInstanceState?.getLong(COUNTDOWN_DEADLINE_KEY, 0L) ?: 0L
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(COUNTDOWN_DEADLINE_KEY, countdownDeadlineElapsedMs)
        super.onSaveInstanceState(outState)
    }

    /** Countdown survives temporary backgrounding without resetting to one minute. */
    private fun renderCountdown(remainingMs: Long) {
        val remaining = remainingMs.coerceIn(0L, AUTO_DISMISS_MS)
        countdownProgress?.progress = remaining.toInt()
        countdownLabel?.text = "${(remaining + 999L) / 1000L}초 후 자동 닫힘"
    }
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun background(fill: Int, border: Int = Color.TRANSPARENT, radius: Int = 9): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (border != Color.TRANSPARENT) setStroke(dp(1), border)
        }
    private fun text(value: String, size: Float, color: Int = ink.text, bold: Boolean = false): TextView =
        TextView(activity).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }
    private fun selectStyle(view: TextView, selected: Boolean) {
        view.setTextColor(if (selected) ink.selectedText else ink.unselectedText)
        view.background = background(
            if (selected) ink.selected else ink.unselected,
            if (selected) Color.TRANSPARENT else ink.divider,
            6
        )
    }
    private fun button(value: String, selected: Boolean, click: () -> Unit): TextView =
        text(value, 14f, ink.text, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(40)
            setPadding(dp(7), dp(6), dp(7), dp(6))
            selectStyle(this, selected)
            isFocusable = true
            setOnClickListener { click() }
        }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val host = activity as? InCarDashboardActivity ?: return super.onCreateDialog(savedInstanceState)
        ink = if (host.currentAppearance() == "dark") darkInk else lightInk
        val repo = host.tripRepository()
        val id = arguments?.getLong("trip_id") ?: -1L
        val recent = repo.allTrips()
        val trip = recent.firstOrNull { it.id == id } ?: return super.onCreateDialog(savedInstanceState)
        actionsClosed = false
        fun stopVoice() {
            actionsClosed = true
            host.cancelVoiceFor(id)
        }
        val body = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(18))
            background = background(ink.surface, ink.divider, 10)
        }
        fun put(view: View) { body.addView(view, LinearLayout.LayoutParams(-1, -2)) }
        fun space(size: Int) { body.addView(View(host), LinearLayout.LayoutParams(1, dp(size))) }
        fun divider() { body.addView(View(host).apply { setBackgroundColor(ink.divider) }, LinearLayout.LayoutParams(-1, dp(1))) }
        val top = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val headline = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        headline.addView(text("주행 완료", 23f, ink.text, true))
        headline.addView(text("이번 주행이 종료되었습니다.", 12f, ink.muted))
        top.addView(headline, LinearLayout.LayoutParams(0, -2, 1f))
        countdownLabel = text("60초 후 자동 닫힘", 12f, ink.accent, true)
        top.addView(countdownLabel)
        put(top)
        space(16); divider(); space(13)
        val distances = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val distance = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        val accelEstimate = trip.source.contains("ACCEL")
        distance.addView(text(if (accelEstimate) "시연용 추정 거리" else "이번 주행거리", 12f, ink.muted))
        distance.addView(text(String.format(Locale.KOREA, if (accelEstimate) "%.3f km" else "%.1f km", trip.distanceKm), 29f, ink.text, true))
        distances.addView(distance, LinearLayout.LayoutParams(0, -2, 1f))
        val toll = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        toll.addView(text("통행료", 12f, ink.muted))
        toll.addView(text(String.format(Locale.KOREA, "%,d원", trip.tollWon), 21f, ink.text, true))
        distances.addView(toll, LinearLayout.LayoutParams(0, -2, .62f))
        put(distances)
        space(9)
        put(text("운행시간  ${TripPresentation.duration(trip.startAt, trip.endAt)}", 12f, ink.muted))
        if (accelEstimate) {
            space(6)
            put(text("Virtual Console 속도 누적 추정치 · 실제 계기판 기록이나 세무 증빙이 아닙니다.", 11f, ink.accent))
        }
        if (!accelEstimate && trip.endAt != null && trip.distanceKm < 0.05) {
            space(6)
            put(text("계기판 누적거리 변화 없음 · Virtual Console의 Odometer 값 확인", 11f, ink.accent))
        }
        space(9)
        put(text(if (accelEstimate) "시연 기준점  ${String.format(Locale.KOREA, "%,.3f", trip.startOdoKm)} → ${String.format(Locale.KOREA, "%,.3f", trip.endOdoKm ?: trip.startOdoKm)} km"
            else "누적거리  ${String.format(Locale.KOREA, "%,.1f", trip.startOdoKm)} → ${String.format(Locale.KOREA, "%,.1f", trip.endOdoKm ?: trip.startOdoKm)} km", 11f, ink.muted))
        space(14); divider(); space(12)
        put(text("운전자 · " + trip.driverDepartment.ifBlank { "부서 미입력" }, 12f, ink.muted, true))
        space(7)
        val profile = host.driverForNewTrips()
        put(text(trip.driver, 15f, ink.text, true))
        if(host.cloudConnected()) {
            put(button("서버 운전자 선택", false) { stopVoice(); host.chooseCloudDriver(id) })
        } else if (profile.driver.isNotBlank() && trip.driver != profile.driver) {
            put(button("설정의 운전자로 변경", false) {
                stopVoice()
                repo.setDriver(id, profile.driver)
                dismissAllowingStateLoss()
            })
        }
        space(12)
        put(text("운행 목적", 12f, ink.muted, true))
        space(7)
        val purposeRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(Purpose.COMMUTE, Purpose.BUSINESS).forEachIndexed { index, purpose ->
            val b = button(purpose.label, trip.purpose == purpose) {
                stopVoice()
                host.classifyFromCar(id, purpose)
                Toast.makeText(host, "${purpose.label} 분류 저장", Toast.LENGTH_SHORT).show()
                dismissAllowingStateLoss()
            }
            purposeRow.addView(b, LinearLayout.LayoutParams(0, dp(42), 1f).apply { if (index > 0) leftMargin = dp(7) })
        }
        put(purposeRow)
        host.purposeTip(trip, recent)?.let { tip ->
            space(6)
            put(button("추천 · ${tip.purpose.label}  (${tip.reason})", false) {
                stopVoice()
                host.classifyFromCar(id, tip.purpose)
                Toast.makeText(host, "${tip.purpose.label} 분류 저장", Toast.LENGTH_SHORT).show()
                dismissAllowingStateLoss()
            })
        }
        if (host.voiceEnabled() && trip.purpose == Purpose.UNCLASSIFIED) {
            space(6)
            put(text("안내가 끝나면 “거래처 다녀왔어요”처럼 말하거나 화면에서 선택하세요.", 11f, ink.muted))
        }
        space(10)
        val footer = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        footer.addView(
            text(
                if (trip.purpose == Purpose.UNCLASSIFIED) "선택하지 않으면 미분류로 남습니다."
                else "건너뛰면 기존 분류를 유지합니다.",
                11f, ink.muted
            ),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        val skipButton = button("건너뛰기", false) {
            stopVoice()
            // The trip has already been persisted. Skipping preserves its current classification.
            // The dialog's onDismiss marks this completion notification as handled.
            dismissAllowingStateLoss()
        }.apply {
            minWidth = dp(100)
            minHeight = dp(44)
            contentDescription = "운행 분류 건너뛰기. 미분류 기록은 운행기록에서 나중에 분류할 수 있습니다."
        }
        if (trip.purpose != Purpose.PERSONAL) {
            val exclude = button("업무기록 제외", false) {
                stopVoice()
                host.classifyFromCar(id, Purpose.PERSONAL)
                Toast.makeText(host, "목록에서 제외 · 총주행거리에는 반영", Toast.LENGTH_SHORT).show()
                dismissAllowingStateLoss()
            }
            footer.addView(exclude, LinearLayout.LayoutParams(-2, dp(44)))
        }
        footer.addView(skipButton, LinearLayout.LayoutParams(-2, dp(44)).apply { leftMargin = dp(10) })
        put(footer)
        space(9)
        countdownProgress = ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = AUTO_DISMISS_MS.toInt()
            progress = AUTO_DISMISS_MS.toInt()
            progressTintList = ColorStateList.valueOf(ink.accent)
            progressBackgroundTintList = ColorStateList.valueOf(ink.divider)
        }
        body.addView(countdownProgress, LinearLayout.LayoutParams(-1, dp(3)))
        return Dialog(host).apply {
            setContentView(body)
            setCanceledOnTouchOutside(false)
        }
    }
    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(minOf(resources.displayMetrics.widthPixels - dp(40), dp(570)), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(if (ink === lightInk) .40f else .58f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        if (countdownDeadlineElapsedMs == 0L) {
            countdownDeadlineElapsedMs = SystemClock.elapsedRealtime() + AUTO_DISMISS_MS
        }
        val remainingMs = (countdownDeadlineElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        renderCountdown(remainingMs)
        timer?.cancel()
        if (remainingMs == 0L) {
            // The timeout can expire while this parked-only dialog is backgrounded.
            dismissAllowingStateLoss()
            return
        }
        val id = arguments?.getLong("trip_id") ?: return
        dialog?.window?.decorView?.post {
            if (isShowingTrip(id)) (activity as? InCarDashboardActivity)?.startVoiceFor(this, id)
        }
        timer = object : CountDownTimer(remainingMs, 250L) {
            override fun onTick(millisUntilFinished: Long) {
                renderCountdown(countdownDeadlineElapsedMs - SystemClock.elapsedRealtime())
            }
            override fun onFinish() {
                renderCountdown(0L)
                actionsClosed = true
                (activity as? InCarDashboardActivity)?.cancelVoiceFor(id)
                if (isAdded) dismissAllowingStateLoss()
            }
        }.start()
    }
    override fun onStop() {
        timer?.cancel()
        super.onStop()
    }
    override fun onDismiss(dialog: DialogInterface) {
        actionsClosed = true
        timer?.cancel()
        super.onDismiss(dialog)
        (activity as? InCarDashboardActivity)?.onTripContextCompleted(arguments?.getLong("trip_id") ?: -1L)
    }
}
