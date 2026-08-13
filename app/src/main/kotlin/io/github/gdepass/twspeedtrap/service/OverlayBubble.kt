package io.github.gdepass.twspeedtrap.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import io.github.gdepass.twspeedtrap.R
import io.github.gdepass.twspeedtrap.detection.CameraType
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the floating bubble is showing. */
sealed interface BubbleState {
    /** Detection is off: tap to start. */
    data object Idle : BubbleState

    /** Detection running, nothing alerted. */
    data object Clear : BubbleState

    /** Detection running but blind: location off, or no GPS fix yet.
     * Deliberately never green — a green bubble is a promise. */
    data object NoGps : BubbleState

    /** A fired camera alert still ahead. */
    data class Alert(
        val type: CameraType,
        val limitKmh: Int?,
        val distanceM: Int,
    ) : BubbleState

    /** Average-speed section in progress. */
    data class Section(
        val limitKmh: Int,
        val projectedKmh: Int,
    ) : BubbleState
}

/**
 * Draggable floating status overlay drawn over other apps (Google Maps in
 * front). Quiet states are a small circle: grey with a play glyph while
 * detection is off (tap toggles detection), green while running and clear.
 * Active states expand into a rounded card: red with the camera-type glyph,
 * its limit pill and a live metre countdown during an alert; purple with the
 * section limit and the projected exit average inside an average-speed
 * section. Requires the display-over-other-apps permission;
 * [BubbleOverlayController] owns its lifecycle.
 */
@SuppressLint("ViewConstructor")
class OverlayBubble(
    context: Context,
    initialX: Int,
    initialY: Int,
    private val onPositionChanged: (x: Int, y: Int) -> Unit,
    private val onTap: () -> Unit,
) : View(context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = resources.displayMetrics.density
    private val wPx = (CARD_WIDTH_DP * density).roundToInt()
    private val hPx = (CARD_HEIGHT_DP * density).roundToInt()
    private val quietPx = (QUIET_SIZE_DP * density).roundToInt()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // The window matches what is drawn — quiet circle or full card — so no
    // invisible margin ever swallows taps meant for the app underneath.
    private val params =
        WindowManager
            .LayoutParams(
                quietPx,
                quietPx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x =
                    if (initialX >= 0) {
                        initialX
                    } else {
                        resources.displayMetrics.widthPixels - quietPx - (12 * density).roundToInt()
                    }
                y = if (initialY >= 0) initialY else resources.displayMetrics.heightPixels / 4
            }

    private var state: BubbleState = BubbleState.Idle
    private var attached = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokeGlyphPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
        }
    private val strokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
        }
    private val valuePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
    private val unitPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            isFakeBoldText = true
        }
    private val pillTextPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

    init {
        strokePaint.strokeWidth = hPx * 0.045f
        strokeGlyphPaint.strokeWidth = hPx * 0.03f
        valuePaint.textSize = hPx * 0.26f
        unitPaint.textSize = hPx * 0.13f
        pillTextPaint.textSize = hPx * 0.19f
        contentDescription = context.getString(R.string.btn_start)
    }

    fun attach() {
        if (attached) return
        clampToScreen()
        windowManager.addView(this, params)
        attached = true
    }

    fun detach() {
        if (!attached) return
        windowManager.removeView(this)
        attached = false
    }

    fun render(newState: BubbleState) {
        if (newState == state) return
        state = newState
        contentDescription =
            context.getString(if (newState is BubbleState.Idle) R.string.btn_start else R.string.btn_stop)
        applyWindowSize(newState)
        invalidate()
    }

    /** Quiet states get a small window, loud states the full card, keeping the
     * visual centre in place. */
    private fun applyWindowSize(newState: BubbleState) {
        val loud = newState is BubbleState.Alert || newState is BubbleState.Section
        val newW = if (loud) wPx else quietPx
        val newH = if (loud) hPx else quietPx
        if (params.width == newW && params.height == newH) return
        params.x += (params.width - newW) / 2
        params.y += (params.height - newH) / 2
        params.width = newW
        params.height = newH
        clampToScreen()
        if (attached) windowManager.updateViewLayout(this, params)
    }

    /** A dragged or restored position must never strand the bubble off-screen. */
    private fun clampToScreen() {
        val metrics = resources.displayMetrics
        params.x = params.x.coerceIn(0, maxOf(0, metrics.widthPixels - params.width))
        params.y = params.y.coerceIn(0, maxOf(0, metrics.heightPixels - params.height))
    }

    override fun onDraw(canvas: Canvas) {
        when (val s = state) {
            is BubbleState.Idle -> {
                drawQuietCircle(canvas, COLOR_IDLE)
                drawPlayGlyph(canvas)
            }
            is BubbleState.Clear -> drawQuietCircle(canvas, COLOR_CLEAR)
            is BubbleState.NoGps -> {
                drawQuietCircle(canvas, COLOR_NO_GPS)
                drawNoGpsGlyph(canvas)
            }
            is BubbleState.Alert -> {
                drawCard(canvas, COLOR_ALERT)
                drawAlert(canvas, s)
            }
            is BubbleState.Section -> {
                drawCard(canvas, COLOR_SECTION)
                drawSection(canvas, s)
            }
        }
    }

    /** Small unobtrusive circle for the quiet states, filling its window. */
    private fun drawQuietCircle(
        canvas: Canvas,
        color: Int,
    ) {
        fillPaint.color = color
        val radius = quietPx / 2f - strokePaint.strokeWidth
        canvas.drawCircle(width / 2f, height / 2f, radius, fillPaint)
        canvas.drawCircle(width / 2f, height / 2f, radius, strokePaint)
    }

    /** Full rounded card for the loud states. */
    private fun drawCard(
        canvas: Canvas,
        color: Int,
    ) {
        fillPaint.color = color
        val inset = strokePaint.strokeWidth
        val rect = RectF(inset, inset, wPx - inset, hPx - inset)
        val corner = hPx * 0.26f
        canvas.drawRoundRect(rect, corner, corner, fillPaint)
        canvas.drawRoundRect(rect, corner, corner, strokePaint)
    }

    /** Type glyph always on show; the limit pill joins it when the limit is known. */
    private fun drawAlert(
        canvas: Canvas,
        alert: BubbleState.Alert,
    ) {
        val hasPill = alert.limitKmh != null
        val glyphCx = if (hasPill) wPx * 0.28f else wPx / 2f
        when (alert.type) {
            CameraType.RED_LIGHT -> drawTrafficLightGlyph(canvas, glyphCx)
            CameraType.MOBILE -> drawMobileGlyph(canvas, glyphCx)
            CameraType.TECH -> drawEyeGlyph(canvas, glyphCx)
            else -> drawCameraGlyph(canvas, glyphCx)
        }
        if (alert.limitKmh != null) drawLimitPill(canvas, alert.limitKmh, wPx * 0.68f)
        drawBottomValue(canvas, "${alert.distanceM}", " m")
    }

    private fun drawSection(
        canvas: Canvas,
        section: BubbleState.Section,
    ) {
        drawLimitPill(canvas, section.limitKmh, wPx / 2f)
        drawBottomValue(canvas, "${section.projectedKmh}", " km/h")
    }

    /** Mini speed-sign: white pill, black limit number. */
    private fun drawLimitPill(
        canvas: Canvas,
        limitKmh: Int,
        cx: Float,
    ) {
        val cy = hPx * TOP_SLOT_CENTER
        val halfH = hPx * 0.13f
        val halfW = maxOf(hPx * 0.19f, pillTextPaint.measureText("$limitKmh") / 2f + hPx * 0.06f)
        glyphPaint.color = Color.WHITE
        val rect = RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        canvas.drawRoundRect(rect, halfH, halfH, glyphPaint)
        val baseline = cy - (pillTextPaint.ascent() + pillTextPaint.descent()) / 2f
        canvas.drawText("$limitKmh", cx, baseline, pillTextPaint)
    }

    /** Three stacked light dots in a dark housing: red-light camera ahead. */
    private fun drawTrafficLightGlyph(
        canvas: Canvas,
        cx: Float,
    ) {
        glyphPaint.color = COLOR_LIGHT_HOUSING
        val halfW = hPx * 0.095f
        val rect = RectF(cx - halfW, hPx * 0.10f, cx + halfW, hPx * 0.50f)
        canvas.drawRoundRect(rect, halfW * 0.6f, halfW * 0.6f, glyphPaint)
        val radius = hPx * 0.047f
        listOf(0.17f to COLOR_LIGHT_RED, 0.30f to COLOR_LIGHT_AMBER, 0.43f to COLOR_LIGHT_GREEN)
            .forEach { (yFraction, color) ->
                glyphPaint.color = color
                canvas.drawCircle(cx, hPx * yFraction, radius, glyphPaint)
            }
    }

    /** White camera body with a punched-out lens: fixed camera. */
    private fun drawCameraGlyph(
        canvas: Canvas,
        cx: Float,
    ) {
        val cy = hPx * TOP_SLOT_CENTER
        glyphPaint.color = Color.WHITE
        val halfW = hPx * 0.155f
        val halfH = hPx * 0.115f
        val body = RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        canvas.drawRoundRect(body, hPx * 0.035f, hPx * 0.035f, glyphPaint)
        glyphPaint.color = fillPaint.color
        canvas.drawCircle(cx, cy, hPx * 0.062f, glyphPaint)
    }

    /** Camera on tripod legs: mobile enforcement. */
    private fun drawMobileGlyph(
        canvas: Canvas,
        cx: Float,
    ) {
        val cy = hPx * 0.26f
        glyphPaint.color = Color.WHITE
        val halfW = hPx * 0.14f
        val halfH = hPx * 0.10f
        val body = RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        canvas.drawRoundRect(body, hPx * 0.03f, hPx * 0.03f, glyphPaint)
        glyphPaint.color = fillPaint.color
        canvas.drawCircle(cx, cy, hPx * 0.055f, glyphPaint)
        canvas.drawLine(cx - hPx * 0.06f, cy + halfH, cx - hPx * 0.13f, hPx * 0.52f, strokeGlyphPaint)
        canvas.drawLine(cx + hPx * 0.06f, cy + halfH, cx + hPx * 0.13f, hPx * 0.52f, strokeGlyphPaint)
    }

    /** Watching eye: tech / behaviour enforcement. */
    private fun drawEyeGlyph(
        canvas: Canvas,
        cx: Float,
    ) {
        val cy = hPx * TOP_SLOT_CENTER
        glyphPaint.color = Color.WHITE
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(1.6f, 1f)
        canvas.drawCircle(0f, 0f, hPx * 0.115f, glyphPaint)
        canvas.restore()
        glyphPaint.color = fillPaint.color
        canvas.drawCircle(cx, cy, hPx * 0.065f, glyphPaint)
    }

    private fun drawPlayGlyph(canvas: Canvas) {
        glyphPaint.color = Color.WHITE
        val cx = width / 2f
        val cy = height / 2f
        val r = quietPx / 2f
        val path =
            Path().apply {
                moveTo(cx - r * 0.26f, cy - r * 0.45f)
                lineTo(cx - r * 0.26f, cy + r * 0.45f)
                lineTo(cx + r * 0.54f, cy)
                close()
            }
        canvas.drawPath(path, glyphPaint)
    }

    /** White exclamation mark: running but blind. */
    private fun drawNoGpsGlyph(canvas: Canvas) {
        glyphPaint.color = Color.WHITE
        val cx = width / 2f
        val cy = height / 2f
        val r = quietPx / 2f
        val barHalfW = r * 0.11f
        canvas.drawRoundRect(
            RectF(cx - barHalfW, cy - r * 0.52f, cx + barHalfW, cy + r * 0.16f),
            barHalfW,
            barHalfW,
            glyphPaint,
        )
        canvas.drawCircle(cx, cy + r * 0.44f, r * 0.13f, glyphPaint)
    }

    /** Big number plus a small unit, centred as one line in the bottom slot. */
    private fun drawBottomValue(
        canvas: Canvas,
        value: String,
        unit: String,
    ) {
        val cy = hPx * BOTTOM_SLOT_CENTER
        val baseline = cy - (valuePaint.ascent() + valuePaint.descent()) / 2f
        val valueWidth = valuePaint.measureText(value)
        val unitWidth = unitPaint.measureText(unit)
        val start = wPx / 2f - (valueWidth + unitWidth) / 2f
        valuePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(value, start, baseline, valuePaint)
        canvas.drawText(unit, start + valueWidth, baseline, unitPaint)
        valuePaint.textAlign = Paint.Align.CENTER
    }

    private var downRawX = 0f
    private var downRawY = 0f
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragging = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                dragStartX = params.x
                dragStartY = params.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                if (dragging) {
                    params.x = dragStartX + dx.roundToInt()
                    params.y = dragStartY + dy.roundToInt()
                    clampToScreen()
                    if (attached) windowManager.updateViewLayout(this, params)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) onPositionChanged(params.x, params.y) else performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) onPositionChanged(params.x, params.y)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap()
        return true
    }

    companion object {
        private const val CARD_WIDTH_DP = 124f
        private const val CARD_HEIGHT_DP = 92f
        private const val QUIET_SIZE_DP = 72f
        private const val TOP_SLOT_CENTER = 0.30f
        private const val BOTTOM_SLOT_CENTER = 0.71f
        private val COLOR_IDLE = Color.rgb(0x54, 0x6E, 0x7A)
        private val COLOR_NO_GPS = Color.rgb(0xF9, 0xA8, 0x25)
        private val COLOR_CLEAR = Color.rgb(0x2E, 0x7D, 0x32)
        private val COLOR_ALERT = Color.rgb(0xC6, 0x28, 0x28)
        private val COLOR_SECTION = Color.rgb(0xAB, 0x47, 0xBC)
        private val COLOR_LIGHT_HOUSING = Color.rgb(0x26, 0x32, 0x38)
        private val COLOR_LIGHT_RED = Color.rgb(0xFF, 0x52, 0x52)
        private val COLOR_LIGHT_AMBER = Color.rgb(0xFF, 0xC1, 0x07)
        private val COLOR_LIGHT_GREEN = Color.rgb(0x66, 0xBB, 0x6A)
    }
}
