package com.k3rn3l.aikeyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * Gboard-style QWERTY surface. Drawn on a single canvas so the whole keyboard
 * stays one lightweight view. The AI pill in the suggestion strip is the only
 * bridge into voice mode.
 */
class GboardView(context: Context) : View(context) {

    interface Listener {
        fun onText(text: String)
        fun onBackspace()
        fun onEnter()
        fun onAi()
        fun onFix()
        fun onSettings()
    }

    var listener: Listener? = null

    private enum class Kind { CHAR, SHIFT, DELETE, SYMBOLS, SPACE, ENTER }

    private data class Key(
        val label: String,
        val value: String,
        val kind: Kind = Kind.CHAR,
        val weight: Float = 1f,
    )

    private sealed class Hit {
        data class KeyHit(val index: Int, val key: Key) : Hit()
        data object Ai : Hit()
        data object Fix : Hit()
        data object Settings : Hit()
    }

    private val density = resources.displayMetrics.density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B0C11.toInt() }
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF262832.toInt() }
    private val specialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1C1E26.toInt() }
    private val spacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B2D37.toInt() }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3D4A.toInt() }
    private val pressedAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8E74FF.toInt() }
    private val enterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6C5CE7.toInt() }
    private val fixPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF14161F.toInt() }
    private val fixStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3B3355.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val fixTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC9C2FF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B1D25.toInt() }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF2F3F7.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val smallLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9A9FAD.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8B90A0.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val aiTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB7BCC8.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

    private val keyRects = mutableListOf<RectF>()
    private var rows: List<List<Key>> = letters()
    private var shift = false
    private var symbols = false
    private var hint = "Tap AI to talk"

    private val aiRect = RectF()
    private val fixRect = RectF()
    private val gearRect = RectF()
    private var pressed: Hit? = null

    private val repeatHandler = Handler(Looper.getMainLooper())
    private val repeatBackspace = object : Runnable {
        override fun run() {
            val hit = pressed
            if (hit is Hit.KeyHit && hit.key.kind == Kind.DELETE) {
                listener?.onBackspace()
                repeatHandler.postDelayed(this, 55)
            }
        }
    }

    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, dp(80f), 0f,
            intArrayOf(0xFF7C5CFF.toInt(), 0xFF35E0FF.toInt()),
            null,
            Shader.TileMode.CLAMP,
        )
    }

    init {
        isClickable = true
        isFocusable = false
        setBackgroundColor(0xFF0B0C11.toInt())
    }

    fun setShift(on: Boolean) {
        if (shift == on) return
        shift = on
        invalidate()
    }

    fun setHint(text: String) {
        hint = text
        invalidate()
    }

    fun refresh() {
        rows = if (symbols) symbols() else letters()
        invalidate()
    }

    private fun dp(value: Float): Float = value * density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desired = dp(272f).toInt()
        setMeasuredDimension(width, resolveSize(desired, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutKeys()
    }

    private fun layoutKeys() {
        keyRects.clear()
        val padH = dp(4f)
        val gap = dp(5f)
        val toolbar = dp(44f)
        val bottom = dp(4f)
        val rowHeight = (height - toolbar - bottom - gap * 3f) / 4f

        var y = toolbar
        rows.forEach { row ->
            val available = width - padH * 2f - gap * (row.size - 1)
            val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
            var x = padH
            row.forEach { key ->
                val keyWidth = available * key.weight / totalWeight
                keyRects.add(RectF(x, y, x + keyWidth, y + rowHeight))
                x += keyWidth + gap
            }
            y += rowHeight + gap
        }

        // AI lives at the far top-right, with Fix beside it and settings at the far left.
        gearRect.set(padH, dp(5f), padH + dp(38f), dp(39f))
        aiRect.set(width - padH - dp(82f), dp(7f), width - padH, dp(37f))
        fixRect.set(aiRect.left - dp(57f), dp(8f), aiRect.left - dp(7f), dp(36f))

        gradientPaint.shader = LinearGradient(
            aiRect.left, 0f, aiRect.right, 0f,
            intArrayOf(0xFF7C5CFF.toInt(), 0xFF35E0FF.toInt()),
            null,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        drawToolbar(canvas)

        val rowTop = dp(44f)
        canvas.drawRect(0f, rowTop, width.toFloat(), rowTop + dp(1f), dividerPaint)

        var index = 0
        for (row in rows) {
            for (key in row) {
                val rect = keyRects.getOrNull(index) ?: break
                drawKey(canvas, rect, key, index)
                index++
            }
        }
    }

    private fun drawToolbar(canvas: Canvas) {
        val pressedAi = pressed == Hit.Ai
        val pressedFix = pressed == Hit.Fix
        val pressedGear = pressed == Hit.Settings

        gradientPaint.alpha = if (pressedAi) 220 else 255
        canvas.drawRoundRect(aiRect, dp(15f), dp(15f), gradientPaint)

        // Sparkle glyph, drawn so we don't depend on an emoji font.
        val sparkleR = dp(6.5f)
        val textSize = dp(13.5f)
        aiTextPaint.textSize = textSize
        val textWidth = aiTextPaint.measureText("AI")
        val gap = dp(5f)
        val groupWidth = sparkleR * 2f + gap + textWidth
        val startX = aiRect.centerX() - groupWidth / 2f
        drawSparkle(canvas, startX + sparkleR, aiRect.centerY(), sparkleR)

        val baseline = aiRect.centerY() - (aiTextPaint.descent() + aiTextPaint.ascent()) / 2f
        canvas.drawText("AI", startX + sparkleR * 2f + gap, baseline, aiTextPaint)

        canvas.drawRoundRect(fixRect, dp(14f), dp(14f), if (pressedFix) pressedPaint else fixPaint)
        canvas.drawRoundRect(fixRect, dp(14f), dp(14f), fixStrokePaint)
        fixTextPaint.textSize = dp(11.5f)
        val fixBaseline = fixRect.centerY() - (fixTextPaint.descent() + fixTextPaint.ascent()) / 2f
        canvas.drawText("Fix", fixRect.centerX(), fixBaseline, fixTextPaint)

        hintPaint.textSize = dp(11.5f)
        val hintCenter = (gearRect.right + fixRect.left) / 2f
        val hintBaseline = aiRect.centerY() - (hintPaint.descent() + hintPaint.ascent()) / 2f
        canvas.drawText(hint, hintCenter, hintBaseline, hintPaint)

        // Simple sliders icon for settings.
        val cx = gearRect.centerX()
        val cy = gearRect.centerY()
        val half = dp(8f)
        iconPaint.alpha = if (pressedGear) 255 else 200
        for ((offsetY, knobX) in listOf(-dp(3.5f) to -dp(2.5f), dp(3.5f) to dp(3.5f))) {
            canvas.drawLine(cx - half, cy + offsetY, cx + half, cy + offsetY, iconPaint)
            canvas.drawCircle(cx + knobX, cy + offsetY, dp(2.4f), iconPaint)
        }
    }

    private fun drawSparkle(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val path = Path()
        path.moveTo(cx, cy - r)
        path.quadTo(cx + r * 0.18f, cy - r * 0.18f, cx + r, cy)
        path.quadTo(cx + r * 0.18f, cy + r * 0.18f, cx, cy + r)
        path.quadTo(cx - r * 0.18f, cy + r * 0.18f, cx - r, cy)
        path.quadTo(cx - r * 0.18f, cy - r * 0.18f, cx, cy - r)
        path.close()
        canvas.drawPath(path, sparklePaint)
    }

    private fun drawKey(canvas: Canvas, rect: RectF, key: Key, index: Int) {
        val isPressed = (pressed as? Hit.KeyHit)?.index == index
        val radius = dp(9f)

        val fill = when {
            isPressed && key.kind == Kind.ENTER -> pressedAccentPaint
            isPressed -> pressedPaint
            key.kind == Kind.ENTER -> enterPaint
            key.kind == Kind.SPACE -> spacePaint
            key.kind == Kind.CHAR && key.value.firstOrNull()?.isLetter() == false -> specialPaint
            key.kind == Kind.CHAR -> keyPaint
            else -> specialPaint
        }
        canvas.drawRoundRect(rect, radius, radius, fill)

        if (key.kind == Kind.SHIFT && shift) {
            val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x337C5CFF }
            canvas.drawRoundRect(rect, radius, radius, highlight)
            canvas.drawRoundRect(rect, radius, radius, iconPaint.apply {
                color = 0xFFB39DFF.toInt()
                style = Paint.Style.STROKE
            })
            iconPaint.color = 0xFFB7BCC8.toInt()
        }

        val baseline = rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
        when (key.kind) {
            Kind.SPACE -> {
                smallLabelPaint.textSize = dp(11f)
                canvas.drawText("space", rect.centerX(), baseline, smallLabelPaint)
            }
            Kind.SHIFT, Kind.DELETE, Kind.ENTER, Kind.SYMBOLS -> {
                labelPaint.textSize = if (key.kind == Kind.SYMBOLS) dp(12f) else dp(17f)
                labelPaint.color = if (key.kind == Kind.ENTER) 0xFFFFFFFF.toInt() else 0xFFD7DAE3.toInt()
                canvas.drawText(key.label, rect.centerX(), baseline, labelPaint)
                labelPaint.color = 0xFFF2F3F7.toInt()
            }
            Kind.CHAR -> {
                val letter = key.label.length == 1 && key.label[0].isLetter()
                labelPaint.textSize = if (letter) dp(18.5f) else dp(15f)
                labelPaint.color = 0xFFF2F3F7.toInt()
                val shown = if (shift && letter) key.label.uppercase() else key.label
                canvas.drawText(shown, rect.centerX(), baseline, labelPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitTest(event.x, event.y) ?: return false
                pressed = hit
                invalidate()
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (hit is Hit.KeyHit && hit.key.kind == Kind.DELETE) {
                    repeatHandler.postDelayed(repeatBackspace, 400)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val hit = pressed ?: return true
                if (!contains(hit, event.x, event.y)) {
                    pressed = null
                    repeatHandler.removeCallbacks(repeatBackspace)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val hit = pressed
                pressed = null
                repeatHandler.removeCallbacks(repeatBackspace)
                invalidate()
                if (hit != null && contains(hit, event.x, event.y)) {
                    activate(hit)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                repeatHandler.removeCallbacks(repeatBackspace)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun contains(hit: Hit, x: Float, y: Float): Boolean = when (hit) {
        Hit.Ai -> aiRect.contains(x, y)
        Hit.Fix -> fixRect.contains(x, y)
        Hit.Settings -> gearRect.contains(x, y)
        is Hit.KeyHit -> keyRects.getOrNull(hit.index)?.contains(x, y) == true
    }

    private fun hitTest(x: Float, y: Float): Hit? {
        if (aiRect.contains(x, y)) return Hit.Ai
        if (fixRect.contains(x, y)) return Hit.Fix
        if (gearRect.contains(x, y)) return Hit.Settings
        var index = 0
        for (row in rows) {
            for (key in row) {
                if (keyRects.getOrNull(index)?.contains(x, y) == true) {
                    return Hit.KeyHit(index, key)
                }
                index++
            }
        }
        return null
    }

    private fun activate(hit: Hit) {
        when (hit) {
            Hit.Ai -> listener?.onAi()
            Hit.Fix -> listener?.onFix()
            Hit.Settings -> listener?.onSettings()
            is Hit.KeyHit -> activateKey(hit.key)
        }
    }

    private fun activateKey(key: Key) {
        when (key.kind) {
            Kind.SHIFT -> {
                shift = !shift
                invalidate()
            }
            Kind.DELETE -> listener?.onBackspace()
            Kind.SYMBOLS -> {
                symbols = !symbols
                rows = if (symbols) symbols() else letters()
                shift = false
                invalidate()
            }
            Kind.ENTER -> {
                listener?.onEnter()
                shift = true
                invalidate()
            }
            Kind.SPACE -> {
                listener?.onText(" ")
                invalidate()
            }
            Kind.CHAR -> {
                val text = if (shift) key.value.uppercase() else key.value
                listener?.onText(text)
                if (shift) {
                    shift = false
                    invalidate()
                }
                if (key.value == "." || key.value == "!" || key.value == "?") {
                    shift = true
                }
            }
        }
    }

    private fun letters(): List<List<Key>> = listOf(
        "qwertyuiop".map { Key(it.toString(), it.toString()) },
        "asdfghjkl".map { Key(it.toString(), it.toString()) },
        listOf(Key("⇧", "SHIFT", Kind.SHIFT, 1.45f)) +
            "zxcvbnm".map { Key(it.toString(), it.toString()) } +
            Key("⏎", "ENTER", Kind.ENTER, 1.5f),
        listOf(
            Key("?123", "SYM", Kind.SYMBOLS, 1.5f),
            Key(",", ",", Kind.CHAR, 1f),
            Key("space", " ", Kind.SPACE, 4.3f),
            Key(".", ".", Kind.CHAR, 1f),
            Key("⌫", "DEL", Kind.DELETE, 1.5f),
        ),
    )

    private fun symbols(): List<List<Key>> = listOf(
        "1234567890".map { Key(it.toString(), it.toString()) },
        listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/")
            .map { Key(it, it) },
        listOf(Key("ABC", "ABC", Kind.SYMBOLS, 1.5f)) +
            listOf("*", "\"", "'", ":", ";", "!", "?").map { Key(it, it) } +
            Key("⏎", "ENTER", Kind.ENTER, 1.5f),
        listOf(
            Key(",", ",", Kind.CHAR, 1f),
            Key("space", " ", Kind.SPACE, 4.3f),
            Key(".", ".", Kind.CHAR, 1f),
            Key("⌫", "DEL", Kind.DELETE, 1.5f),
        ),
    )
}
