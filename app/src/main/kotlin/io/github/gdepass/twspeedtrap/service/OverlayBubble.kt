package io.github.gdepass.twspeedtrap.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Draggable status bubble drawn over other apps (Google Maps in front):
 * green while nothing is alerted, red with a live metre countdown from the
 * moment an alert fires until the camera is behind. Requires the
 * display-over-other-apps permission; [DetectionService] owns its lifecycle.
 */
@SuppressLint("ViewConstructor")
class OverlayBubble(
    context: Context,
    initialX: Int,
    initialY: Int,
    private val onPositionChanged: (x: Int, y: Int) -> Unit,
) : View(context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val sizePx = (BUBBLE_SIZE_DP * resources.displayMetrics.density).roundToInt()

    private val params =
        WindowManager
            .LayoutParams(
                sizePx,
                sizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = if (initialX >= 0) initialX else resources.displayMetrics.widthPixels - sizePx * 5 / 4
                y = if (initialY >= 0) initialY else resources.displayMetrics.heightPixels / 4
            }

    private var distanceM: Int? = null
    private var attached = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.04f
            color = Color.WHITE
        }
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            textSize = sizePx * 0.28f
        }

    fun attach() {
        if (attached) return
        windowManager.addView(this, params)
        attached = true
    }

    fun detach() {
        if (!attached) return
        windowManager.removeView(this)
        attached = false
    }

    /** null = all clear (green); metres to the alerted camera otherwise (red). */
    fun setDistance(meters: Int?) {
        if (meters == distanceM) return
        distanceM = meters
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val center = sizePx / 2f
        val distance = distanceM
        fillPaint.color = if (distance != null) COLOR_ALERT else COLOR_CLEAR
        canvas.drawCircle(center, center, center - strokePaint.strokeWidth, fillPaint)
        canvas.drawCircle(center, center, center - strokePaint.strokeWidth, strokePaint)
        if (distance != null) {
            val baseline = center - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText("$distance m", center, baseline, textPaint)
        }
    }

    private var downRawX = 0f
    private var downRawY = 0f
    private var dragStartX = 0
    private var dragStartY = 0

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                dragStartX = params.x
                dragStartY = params.y
            }
            MotionEvent.ACTION_MOVE -> {
                params.x = dragStartX + (event.rawX - downRawX).roundToInt()
                params.y = dragStartY + (event.rawY - downRawY).roundToInt()
                if (attached) windowManager.updateViewLayout(this, params)
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                onPositionChanged(params.x, params.y)
            }
        }
        return true
    }

    companion object {
        private const val BUBBLE_SIZE_DP = 72f
        private val COLOR_CLEAR = Color.rgb(0x2E, 0x7D, 0x32)
        private val COLOR_ALERT = Color.rgb(0xC6, 0x28, 0x28)
    }
}
