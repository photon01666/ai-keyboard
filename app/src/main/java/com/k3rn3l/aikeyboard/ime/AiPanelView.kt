package com.k3rn3l.aikeyboard.ime

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.k3rn3l.aikeyboard.voice.LiveClient
import kotlin.math.abs
import kotlin.math.sin

/**
 * Full-keyboard AI mode. Loud about one thing: whether Gemini is listening,
 * with a single unmissable stop control.
 */
class AiPanelView(context: Context) : LinearLayout(context) {

    var onStop: (() -> Unit)? = null

    private val dot = PulseDotView(context)
    private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
    private val title = TextView(context)
    private val timer = TextView(context)
    private val subtitle = TextView(context)
    private val interim = TextView(context)
    private val language = TextView(context)
    private val wave = WaveView(context)
    private val stop = TextView(context)

    private var mode = LiveClient.State.IDLE
    private var smart = true
    private var languageLabel = "AUTO"
    private var polishing = false

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(10))
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF07080C.toInt(), 0xFF141021.toInt(), 0xFF0A0B12.toInt()),
        )

        val accent = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF7C5CFF.toInt(), 0xFF35E0FF.toInt()),
            )
        }
        addView(accent, LayoutParams(LayoutParams.MATCH_PARENT, dp(2)))

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dot.setActive(true)
        header.addView(dot, LayoutParams(dp(18), dp(18)))
        spinner.isIndeterminate = true
        spinner.indeterminateTintList = ColorStateList.valueOf(0xFF35E0FF.toInt())
        spinner.visibility = GONE
        header.addView(spinner, LayoutParams(dp(18), dp(18)))

        title.apply {
            text = "AI IS LISTENING"
            setTextColor(0xFFF5F6FA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.1f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(6), 0, dp(6), 0)
        }
        header.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        timer.apply {
            text = "00:00"
            setTextColor(0xFF8A8F9C.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(10), 0, 0, 0)
        }
        header.addView(timer, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(26)).apply { topMargin = dp(6) })

        subtitle.apply {
            setTextColor(0xFF9A9FAD.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            text = "Gemini Live · speak naturally · text lands as you pause"
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(subtitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(1)
        })

        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(9))
            minimumHeight = dp(96)
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xFF12141C.toInt())
                setStroke(dp(1), 0xFF252838.toInt())
            }
        }

        val cardHeader = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(context).apply {
            text = "LIVE TRANSCRIPT"
            setTextColor(0xFF8E7BFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.16f
        }
        cardHeader.addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        language.apply {
            text = languageLabel
            setTextColor(0xFF9A9FAD.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.08f
        }
        cardHeader.addView(language, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        card.addView(cardHeader, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        interim.apply {
            text = "Start speaking…"
            setTextColor(0xFF6E7382.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create("sans-serif", Typeface.ITALIC)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            minimumHeight = dp(54)
            gravity = Gravity.TOP
            setLineSpacing(0f, 1.06f)
        }
        card.addView(interim, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })

        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })

        wave.setActive(true)
        addView(wave, LayoutParams(LayoutParams.MATCH_PARENT, dp(32)).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
        })

        stop.apply {
            text = "STOP LISTENING"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.1f
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xFFFF4D5E.toInt())
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onStop?.invoke()
            }
        }
        addView(stop, LayoutParams(LayoutParams.MATCH_PARENT, dp(50)))
    }

    fun configure(smartMode: Boolean, language: String) {
        smart = smartMode
        languageLabel = language.ifBlank { "AUTO" }
        this.language.text = languageLabel
        if (mode == LiveClient.State.LISTENING) updateSubtitle()
    }

    fun setState(state: LiveClient.State, detail: String? = null) {
        mode = state
        if (polishing) return
        stop.animate().cancel()
        stop.scaleX = 1f
        stop.scaleY = 1f
        when (state) {
            LiveClient.State.CONNECTING -> {
                title.text = "CONNECTING TO GEMINI"
                title.setTextColor(0xFFFFC66D.toInt())
                dot.setColor(0xFFFFC66D.toInt())
                subtitle.text = detail ?: "Opening a live transcription session…"
                stop.text = "CANCEL"
                setStopColor(0xFF3A2B36.toInt())
                wave.setActive(false)
            }
            LiveClient.State.LISTENING -> {
                title.text = "AI IS LISTENING"
                title.setTextColor(0xFFF5F6FA.toInt())
                dot.setColor(0xFFFF4D5E.toInt())
                updateSubtitle()
                stop.text = "STOP LISTENING"
                setStopColor(0xFFFF4D5E.toInt())
                wave.setActive(true)
            }
            LiveClient.State.RECONNECTING -> {
                title.text = "RECONNECTING"
                title.setTextColor(0xFFFFC66D.toInt())
                dot.setColor(0xFFFFC66D.toInt())
                subtitle.text = detail ?: "Keeping the session alive…"
                stop.text = "STOP LISTENING"
                setStopColor(0xFFFF4D5E.toInt())
                wave.setActive(false)
            }
            LiveClient.State.STOPPING -> {
                title.text = "FINISHING"
                title.setTextColor(0xFF8BE9FF.toInt())
                dot.setColor(0xFF35E0FF.toInt())
                subtitle.text = "Saving the last words…"
                stop.text = "STOPPING…"
                setStopColor(0xFF2A2C38.toInt())
                wave.setActive(false)
            }
            LiveClient.State.ERROR -> {
                title.text = "VOICE PAUSED"
                title.setTextColor(0xFFFF7B88.toInt())
                dot.setColor(0xFFFF4D5E.toInt())
                subtitle.text = detail ?: "Something interrupted the session."
                stop.text = "TRY AGAIN"
                setStopColor(0xFF7C5CFF.toInt())
                wave.setActive(false)
                interim.setTextColor(0xFFFFA2AB.toInt())
                if (interim.text == "Start speaking…" || interim.text.startsWith("✓")) {
                    interim.text = detail ?: "Transcription stopped."
                }
            }
            LiveClient.State.IDLE -> {
                dot.setColor(0xFF8A8F9C.toInt())
            }
        }
    }

    /** Transport status only; never changes Done/Stop state. */
    fun setTransportStatus(text: String) {
        if (polishing) return
        subtitle.text = text
    }

    fun clearTransportStatus() {
        if (polishing) return
        updateSubtitle()
    }

    fun beginSpeech() {
        if (interim.text.isNullOrBlank() ||
            interim.text == "Start speaking…" ||
            interim.text == "Listening…" ||
            interim.text.startsWith("✓")
        ) {
            interim.text = "Listening…"
            interim.setTextColor(0xFF6E7382.toInt())
            interim.typeface = Typeface.create("sans-serif", Typeface.ITALIC)
        }
    }

    fun setInterim(text: String) {
        if (text.isBlank()) return
        interim.text = text
        interim.setTextColor(0xFFF5F6FA.toInt())
        interim.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    fun setFinal(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        interim.text = "✓  $trimmed"
        interim.setTextColor(0xFF9A9FAD.toInt())
        interim.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    /**
     * Distinct, non-cancelable state while the repair pass runs. The only button
     * is disabled on purpose: the session ends by itself when this finishes.
     */
    fun setPolishing(active: Boolean) {
        polishing = active
        if (active) {
                stop.animate().cancel()
            stop.scaleX = 1f
            stop.scaleY = 1f
            dot.visibility = GONE
            spinner.visibility = VISIBLE
            title.text = "POLISHING TEXT"
            title.setTextColor(0xFF8BE9FF.toInt())
            subtitle.text = "Fixing grammar, names and punctuation — please wait"
            stop.text = "POLISHING…"
            stop.isClickable = false
            stop.isFocusable = false
            stop.isEnabled = false
            stop.alpha = 0.55f
            setStopColor(0xFF2A2C38.toInt())
            wave.setActive(false)
        } else {
            spinner.visibility = GONE
            dot.visibility = VISIBLE
            stop.alpha = 1f
            stop.isEnabled = true
            stop.isClickable = true
            stop.isFocusable = true
            setState(mode)
        }
    }

    fun setLevel(value: Float) = wave.push(value)

    fun setElapsed(seconds: Int) {
        timer.text = "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    fun reset() {
        polishing = false
        stop.animate().cancel()
        stop.scaleX = 1f
        stop.scaleY = 1f
        stop.setTextColor(Color.WHITE)
        spinner.visibility = GONE
        dot.visibility = VISIBLE
        stop.alpha = 1f
        stop.isEnabled = true
        stop.isClickable = true
        stop.isFocusable = true
        timer.text = "00:00"
        interim.text = "Start speaking…"
        interim.setTextColor(0xFF6E7382.toInt())
        interim.typeface = Typeface.create("sans-serif", Typeface.ITALIC)
        wave.reset()
    }

    private fun updateSubtitle() {
        subtitle.text = if (smart) {
            "Gemini Live · SMART cleanup · speak naturally"
        } else {
            "Gemini Live · verbatim · speak naturally"
        }
    }

    private fun setStopColor(color: Int) {
        stop.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(color)
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}

/** Pulsing "we are live" dot. */
class PulseDotView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var color = 0xFFFF4D5E.toInt()
    private var active = true
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    fun setColor(value: Int) {
        color = value
        invalidate()
    }

    fun setActive(value: Boolean) {
        active = value
        if (value) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
            phase = 0f
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active && !animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val core = width * 0.22f
        paint.color = color
        paint.alpha = (70 + 90 * phase).toInt()
        canvas.drawCircle(cx, cy, core + width * 0.18f * phase, paint)
        paint.alpha = 255
        canvas.drawCircle(cx, cy, core, paint)
    }
}

/** Mic level bars. One short, smooth, obviously reactive element. */
class WaveView(context: Context) : View(context) {

    private val bars = FloatArray(34)
    private val targets = FloatArray(34)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var active = true
    private var level = 0f
    private var phase = 0f

    init {
        paint.style = Paint.Style.FILL
    }

    fun setActive(value: Boolean) {
        active = value
        if (!value) targets.fill(0f)
        invalidate()
    }

    fun push(value: Float) {
        level = value.coerceIn(0f, 1f)
        for (i in targets.indices) {
            val centerBias = 1f - abs(i - (targets.size - 1) / 2f) / (targets.size / 2f)
            val flutter = 0.55f + 0.45f * abs(sin(phase * 1.7f + i * 0.55f))
            targets[i] = (0.12f + level * 1.35f * (0.45f + centerBias * 0.75f) * flutter)
                .coerceIn(0.06f, 1f)
        }
        invalidate()
    }

    fun reset() {
        level = 0f
        bars.fill(0f)
        targets.fill(0f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        paint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(0xFF35E0FF.toInt(), 0xFF7C5CFF.toInt(), 0xFFFF5C93.toInt()),
            null,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        phase += 0.06f
        val count = bars.size
        val gap = width * 0.006f
        val barWidth = (width - gap * (count - 1)) / count
        val mid = height / 2f
        val maxBar = height * 0.86f

        for (i in 0 until count) {
            val idle = if (active) 0.10f + 0.055f * abs(sin(phase + i * 0.35f)) else 0f
            targets[i] = maxOf(idle, targets[i] * 0.93f)
            bars[i] = bars[i] + (targets[i] - bars[i]) * 0.35f
            val h = (maxBar * bars[i]).coerceAtLeast(3f)
            val x = i * (barWidth + gap)
            rect.set(x, mid - h / 2f, x + barWidth, mid + h / 2f)
            paint.alpha = if (active) 220 else 90
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint)
        }

        if (active) postInvalidateOnAnimation()
    }
}
