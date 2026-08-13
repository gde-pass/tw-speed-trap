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
    private val emojiPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
        }
    private val signTextPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

    init {
        strokePaint.strokeWidth = hPx * 0.045f
        valuePaint.textSize = hPx * 0.26f
        unitPaint.textSize = hPx * 0.13f
        emojiPaint.textSize = hPx * 0.30f
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
                drawCard(canvas, COLOR_ALERT)
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

    /** Type emoji always on show; the limit sign joins it when the limit is known. */
    private fun drawAlert(
        canvas: Canvas,
        alert: BubbleState.Alert,
    ) {
        val glyphCx = if (alert.limitKmh != null) wPx * 0.27f else wPx / 2f
        drawEmoji(canvas, emojiFor(alert.type), glyphCx)
        if (alert.limitKmh != null) drawLimitSign(canvas, alert.limitKmh, wPx * 0.70f)
        drawBottomValue(canvas, "${alert.distanceM}", " m")
    }

    private fun drawSection(
        canvas: Canvas,
        section: BubbleState.Section,
    ) {
        drawEmoji(canvas, SECTION_EMOJI, wPx * 0.27f)
        drawLimitSign(canvas, section.limitKmh, wPx * 0.70f)
        drawBottomValue(canvas, "${section.projectedKmh}", " km/h")
    }

    private fun emojiFor(type: CameraType): String =
        when (type) {
            CameraType.RED_LIGHT -> "🚦"
            CameraType.TECH -> "👀"
            CameraType.MOBILE -> "🚓"
            else -> "📸"
        }

    private fun drawEmoji(
        canvas: Canvas,
        emoji: String,
        cx: Float,
    ) {
        val cy = hPx * TOP_SLOT_CENTER
        val baseline = cy - (emojiPaint.ascent() + emojiPaint.descent()) / 2f
        canvas.drawText(emoji, cx, baseline, emojiPaint)
    }

    /** Taiwan-style speed sign: white halo (so the ring reads on the red
     * card), red ring, white disc, big black number. */
    private fun drawLimitSign(
        canvas: Canvas,
        limitKmh: Int,
        cx: Float,
    ) {
        val cy = hPx * TOP_SLOT_CENTER
        glyphPaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, hPx * 0.205f, glyphPaint)
        glyphPaint.color = COLOR_SIGN_RING
        canvas.drawCircle(cx, cy, hPx * 0.180f, glyphPaint)
        glyphPaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, hPx * 0.132f, glyphPaint)
        signTextPaint.textSize = if (limitKmh >= 100) hPx * 0.15f else hPx * 0.20f
        val baseline = cy - (signTextPaint.ascent() + signTextPaint.descent()) / 2f
        canvas.drawText("$limitKmh", cx, baseline, signTextPaint)
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
        private const val SECTION_EMOJI = "⏱️"
        private val COLOR_IDLE = Color.rgb(0x54, 0x6E, 0x7A)
        private val COLOR_NO_GPS = Color.rgb(0xF9, 0xA8, 0x25)
        private val COLOR_CLEAR = Color.rgb(0x2E, 0x7D, 0x32)
        private val COLOR_ALERT = Color.rgb(0xC6, 0x28, 0x28)

        /** Traffic-sign red, slightly darker than the card so the halo carries the separation. */
        private val COLOR_SIGN_RING = Color.rgb(0xB7, 0x1C, 0x1C)
    }
}
