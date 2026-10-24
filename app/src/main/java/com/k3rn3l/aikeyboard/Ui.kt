package com.k3rn3l.aikeyboard

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

object Ui {

    fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    fun dp(context: Context, value: Int): Int = dp(context, value.toFloat())

    fun rounded(color: Int, radiusDp: Float, context: Context, stroke: Int? = null, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(context, radiusDp).toFloat()
            if (stroke != null) setStroke(dp(context, strokeDp), stroke)
        }

    fun gradient(colors: IntArray, radiusDp: Float, context: Context): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
            cornerRadius = dp(context, radiusDp).toFloat()
        }

    fun text(
        context: Context,
        value: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
        mono: Boolean = false,
    ): TextView = TextView(context).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = Typeface.create(
            if (mono) "monospace" else "sans-serif",
            if (bold) Typeface.BOLD else Typeface.NORMAL,
        )
        includeFontPadding = false
    }

    fun button(
        context: Context,
        value: String,
        primary: Boolean = false,
        small: Boolean = false,
    ): TextView = TextView(context).apply {
        text = value
        gravity = Gravity.CENTER
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else 0xFFE3E5EC.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 12f else 13f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        isClickable = true
        isFocusable = true
        setPadding(
            dp(context, if (small) 14 else 18),
            dp(context, if (small) 8 else 12),
            dp(context, if (small) 14 else 18),
            dp(context, if (small) 8 else 12),
        )
        background = if (primary) {
            gradient(intArrayOf(0xFF7C5CFF.toInt(), 0xFF35C8FF.toInt()), 14f, context)
        } else {
            rounded(0xFF1B1D26.toInt(), 14f, context, 0xFF2B2E3B.toInt())
        }
    }

    fun spacer(context: Context, heightDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(context, heightDp))
    }

    fun editText(
        context: Context,
        hint: String,
        value: String,
        multiline: Boolean = false,
    ): android.widget.EditText = android.widget.EditText(context).apply {
        this.hint = hint
        setText(value)
        setTextColor(0xFFF5F6FA.toInt())
        setHintTextColor(0xFF6E7382.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        background = rounded(0xFF12141C.toInt(), 12f, context, 0xFF262936.toInt())
        setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
        if (multiline) {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP
        } else {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
    }
}
