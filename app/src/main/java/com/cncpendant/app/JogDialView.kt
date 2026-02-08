package com.cncpendant.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.os.Handler
import android.os.Looper
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class JogDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Outer ring (black with scale)
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1a1a1a")
        style = Paint.Style.FILL
    }

    // Inner metallic dial
    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4a4a4a")
        style = Paint.Style.FILL
    }

    // Tick marks
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.BUTT
    }

    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.BUTT
    }

    // Numbers on dial
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    // Center screw
    private val screwPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2a2a2a")
        style = Paint.Style.FILL
    }

    private val screwSlotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1a1a1a")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    // Indicator mark on the metallic center
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#e74c3c")
        style = Paint.Style.FILL
    }

    private var centerX = 0f
    private var centerY = 0f
    private var outerRadius = 0f
    private var innerRadius = 0f

    private var isDragging = false
    private var lastAngle: Float? = null
    private var accumulatedAngle = 0f
    private var dialRotation = 0f

    // Configurable tick count (50 or 100 points)
    private var numTicks = 50
    private var degreesPerTick = 360f / numTicks

    // Debounce: accumulate clicks over a short window, then fire once
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var pendingClicks = 0
    private val debounceDelayMs = 150L
    private val MIN_CLICKS_TO_JOG = 1

    // Continuous jog: triggers after rapid spinning (10+ ticks without pausing)
    private val CONTINUOUS_JOG_THRESHOLD = 10
    private val TICK_RESET_DELAY_MS = 300L
    private var totalTicksInDrag = 0
    private var isContinuousJogging = false
    private var continuousDirection = 0

    private val resetTickCountRunnable = Runnable {
        totalTicksInDrag = 0
        if (isContinuousJogging) {
            isContinuousJogging = false
            continuousJogStopListener?.invoke()
        }
    }

    private val flushPendingRunnable = Runnable {
        val absClicks = kotlin.math.abs(pendingClicks)
        if (absClicks >= MIN_CLICKS_TO_JOG && !isContinuousJogging) {
            val direction = if (pendingClicks > 0) 1 else -1
            jogListener?.invoke(absClicks, direction)
        }
        pendingClicks = 0
    }

    private var jogListener: ((clicks: Int, direction: Int) -> Unit)? = null
    private var continuousJogStartListener: ((direction: Int) -> Unit)? = null
    private var continuousJogStopListener: (() -> Unit)? = null

    fun setOnJogListener(listener: (clicks: Int, direction: Int) -> Unit) {
        this.jogListener = listener
    }

    fun setOnContinuousJogStartListener(listener: (direction: Int) -> Unit) {
        this.continuousJogStartListener = listener
    }

    fun setOnContinuousJogStopListener(listener: () -> Unit) {
        this.continuousJogStopListener = listener
    }

    fun setNumTicks(ticks: Int) {
        numTicks = ticks
        degreesPerTick = 360f / numTicks
        // Snap dial rotation to nearest tick position
        dialRotation = (dialRotation / degreesPerTick).toInt() * degreesPerTick
        invalidate()
    }

    /**
     * Programmatically rotate the dial by a number of ticks.
     * Positive = clockwise, negative = counter-clockwise.
     * Used to sync the visual dial with an external encoder.
     */
    fun rotateByTicks(ticks: Int) {
        dialRotation += ticks * degreesPerTick
        // Keep rotation within 0-360 range
        dialRotation = ((dialRotation % 360f) + 360f) % 360f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        outerRadius = minOf(w, h) / 2f - 40f  // Leave room for outside numbers
        if (outerRadius <= 0f) return
        innerRadius = outerRadius * 0.80f  // Center size

        // Update text size based on dial size
        numberPaint.textSize = outerRadius * 0.09f

        // Create gradient for metallic center
        dialPaint.shader = RadialGradient(
            centerX, centerY - innerRadius * 0.3f,
            innerRadius * 1.5f,
            intArrayOf(
                Color.parseColor("#6a6a6a"),
                Color.parseColor("#4a4a4a"),
                Color.parseColor("#3a3a3a"),
                Color.parseColor("#5a5a5a")
            ),
            floatArrayOf(0f, 0.4f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Major tick interval: every 5 for 50 ticks, every 10 for 100 ticks
        val majorInterval = numTicks / 10

        // Draw numbers on the outside first (before the dial)
        for (i in 0 until numTicks) {
            val angle = Math.toRadians((i * 360.0 / numTicks) - 90)
            val isMajor = i % majorInterval == 0

            // Draw numbers at major ticks on the outside
            if (isMajor) {
                val numberRadius = outerRadius + numberPaint.textSize * 0.8f
                val numX = centerX + cos(angle).toFloat() * numberRadius
                val numY = centerY + sin(angle).toFloat() * numberRadius + numberPaint.textSize * 0.35f
                canvas.drawText(i.toString(), numX, numY, numberPaint)
            }
        }

        // Draw outer black ring
        canvas.drawCircle(centerX, centerY, outerRadius, outerRingPaint)

        // Draw tick marks on outer ring (these stay fixed)
        for (i in 0 until numTicks) {
            val angle = Math.toRadians((i * 360.0 / numTicks) - 90)

            val isMajor = i % majorInterval == 0

            val outerTickRadius = outerRadius - 4f
            val innerTickRadius = if (isMajor) outerRadius * 0.82f else outerRadius * 0.90f

            val x1 = centerX + cos(angle).toFloat() * innerTickRadius
            val y1 = centerY + sin(angle).toFloat() * innerTickRadius
            val x2 = centerX + cos(angle).toFloat() * outerTickRadius
            val y2 = centerY + sin(angle).toFloat() * outerTickRadius

            val paint = if (isMajor) majorTickPaint else tickPaint
            canvas.drawLine(x1, y1, x2, y2, paint)
        }

        // Draw inner metallic dial (this rotates)
        canvas.save()
        canvas.rotate(dialRotation, centerX, centerY)
        
        // Metallic center circle
        canvas.drawCircle(centerX, centerY, innerRadius, dialPaint)
        
        // Draw subtle radial lines on metallic surface for brushed metal effect
        val brushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#55ffffff")
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        for (i in 0 until 60) {
            val angle = Math.toRadians(i * 6.0)
            val x1 = centerX + cos(angle).toFloat() * (innerRadius * 0.15f)
            val y1 = centerY + sin(angle).toFloat() * (innerRadius * 0.15f)
            val x2 = centerX + cos(angle).toFloat() * (innerRadius * 0.95f)
            val y2 = centerY + sin(angle).toFloat() * (innerRadius * 0.95f)
            canvas.drawLine(x1, y1, x2, y2, brushPaint)
        }
        
        // Draw indicator notch extending from inner dial to just before outer tick marks
        val indicatorWidth = innerRadius * 0.08f
        indicatorPaint.strokeWidth = indicatorWidth
        indicatorPaint.style = Paint.Style.STROKE
        indicatorPaint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(
            centerX,
            centerY - innerRadius * 0.85f,  // Start inside the dial
            centerX,
            centerY - outerRadius * 0.88f,  // End just before the short tick marks
            indicatorPaint
        )
        
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                lastAngle = getAngle(event.x, event.y)
                accumulatedAngle = 0f
                totalTicksInDrag = 0
                isContinuousJogging = false
                continuousDirection = 0
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val currentAngle = getAngle(event.x, event.y)
                    lastAngle?.let { last ->
                        var diff = currentAngle - last
                        
                        // Handle wraparound
                        while (diff > 180) diff -= 360
                        while (diff < -180) diff += 360
                        
                        accumulatedAngle += diff
                        
                        // Check if we've crossed a tick threshold
                        val clicks = (kotlin.math.abs(accumulatedAngle) / degreesPerTick).toInt()
                        if (clicks > 0) {
                            val direction = if (accumulatedAngle > 0) 1 else -1

                            // Snap rotation to exact tick positions
                            dialRotation += direction * clicks * degreesPerTick
                            invalidate()

                            totalTicksInDrag += clicks

                            // Reset tick count if no new ticks within 300ms
                            debounceHandler.removeCallbacks(resetTickCountRunnable)
                            debounceHandler.postDelayed(resetTickCountRunnable, TICK_RESET_DELAY_MS)

                            if (!isContinuousJogging && totalTicksInDrag >= CONTINUOUS_JOG_THRESHOLD) {
                                // Switch to continuous jog mode
                                isContinuousJogging = true
                                continuousDirection = direction
                                // Cancel any pending step jog
                                debounceHandler.removeCallbacks(flushPendingRunnable)
                                pendingClicks = 0
                                continuousJogStartListener?.invoke(direction)
                            } else if (!isContinuousJogging) {
                                // Normal debounced step jog
                                pendingClicks += direction * clicks
                                debounceHandler.removeCallbacks(flushPendingRunnable)
                                debounceHandler.postDelayed(flushPendingRunnable, debounceDelayMs)
                            }
                            // While continuous jogging, just let the dial spin visually

                            // Reset accumulated angle (keep remainder)
                            accumulatedAngle = accumulatedAngle % degreesPerTick
                        }
                    }
                    lastAngle = currentAngle
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                lastAngle = null
                accumulatedAngle = 0f
                debounceHandler.removeCallbacks(resetTickCountRunnable)
                if (isContinuousJogging) {
                    isContinuousJogging = false
                    continuousJogStopListener?.invoke()
                } else {
                    // Flush any pending debounced clicks immediately
                    debounceHandler.removeCallbacks(flushPendingRunnable)
                    flushPendingRunnable.run()
                }
                totalTicksInDrag = 0
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun getAngle(x: Float, y: Float): Float {
        val dx = x - centerX
        val dy = y - centerY
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
        if (angle < 0) angle += 360f
        return angle
    }
}
