package com.k3rn3l.aikeyboard

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var languages: EditText
    private lateinit var vocabulary: EditText
    private lateinit var silence: EditText
    private lateinit var fixModel: EditText
    private lateinit var smart: Switch
    private lateinit var hybridVad: Switch
    private lateinit var autoFix: Switch
    private lateinit var swipeTyping: Switch
    private lateinit var keyboardSize: EditText
    private lateinit var keyboardHeight: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF07080C.toInt()
        window.navigationBarColor = 0xFF07080C.toInt()
        setContentView(buildScreen())
    }

    private fun buildScreen(): View {
        val prefs = Prefs(this)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF07080C.toInt())
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(40))
        }
        scroll.addView(content)

        val back = Ui.text(this, "‹  Back", 13f, 0xFF9A9FAD.toInt(), bold = true).apply {
            setPadding(0, dp(8), 0, dp(8))
            isClickable = true
            setOnClickListener { finish() }
        }
        content.addView(back)
        content.addView(Ui.spacer(this, 8))
        content.addView(Ui.text(this, "Settings", 30f, 0xFFF5F6FA.toInt(), bold = true))
        content.addView(Ui.spacer(this, 4))
        content.addView(
            Ui.text(
                this,
                "Everything here stays on your device. The key is only used to open a direct WebSocket to Google's Gemini Live API.",
                12.5f,
                0xFF8A8F9C.toInt(),
            ).apply { setLineSpacing(0f, 1.15f) }
        )
        content.addView(Ui.spacer(this, 24))

        content.addView(fieldLabel("GEMINI API KEY"))
        apiKey = Ui.editText(this, "AIza…", prefs.apiKey).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            typeface = android.graphics.Typeface.MONOSPACE
        }
        content.addView(apiKey)
        val keyLink = Ui.text(this, "Get a key at aistudio.google.com/apikey  ↗", 11.5f, 0xFF8E7BFF.toInt(), bold = true).apply {
            setPadding(0, dp(7), 0, dp(4))
            isClickable = true
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
            }
        }
        content.addView(keyLink)
        content.addView(Ui.spacer(this, 16))

        content.addView(fieldLabel("LIVE MODEL"))
        model = Ui.editText(this, Prefs.DEFAULT_MODEL, prefs.model)
        content.addView(model)
        content.addView(fieldHelp("Default: gemini-3.5-transcribe-live. This dedicated Live model streams text only, so the keyboard never talks back."))

        content.addView(Ui.spacer(this, 16))
        content.addView(fieldLabel("LANGUAGES"))
        languages = Ui.editText(this, "auto-detect (leave empty)", prefs.languages)
        content.addView(languages)
        content.addView(fieldHelp("Comma separated BCP-47 hints, e.g. en-US, es-ES. Empty = automatic multilingual detection."))

        content.addView(Ui.spacer(this, 20))
        content.addView(sectionLabel("TEXT REPAIR"))
        autoFix = switchRow(
            content,
            "Polish on Done",
            "Done runs a Gemini pass for grammar, names and punctuation, then closes the session. Every AI run starts with an empty field.",
            prefs.autoFix,
        )
        content.addView(Ui.spacer(this, 8))
        content.addView(fieldLabel("REPAIR MODEL"))
        fixModel = Ui.editText(this, Prefs.DEFAULT_FIX_MODEL, prefs.fixModel)
        content.addView(fixModel)
        val accessibilityLink = Ui.text(
            this,
            "Screen context is optional — allow AI Keyboard in Accessibility settings  ↗",
            11.5f,
            0xFF8E7BFF.toInt(),
            bold = true,
        ).apply {
            setPadding(0, dp(7), 0, 0)
            isClickable = true
            setOnClickListener {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        content.addView(accessibilityLink)
        content.addView(fieldHelp("Password fields and hidden content are skipped. Screen text is read only when Fix runs and is never stored."))

        content.addView(Ui.spacer(this, 20))
        content.addView(sectionLabel("TRANSCRIPTION"))
        smart = switchRow(
            content,
            "Smart transcription",
            "Removes filler words, fixes false starts and formats numbers, dates and lists.",
            prefs.smart,
        )

        content.addView(Ui.spacer(this, 8))
        hybridVad = switchRow(
            content,
            "Fast local finalization",
            "The keyboard's own voice detector ends utterances early for lower-latency text.",
            prefs.hybridVad,
        )

        content.addView(Ui.spacer(this, 16))
        content.addView(fieldLabel("END-OF-SPEECH SILENCE (MS)"))
        silence = Ui.editText(this, "800", prefs.silenceMs.toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        content.addView(silence)
        content.addView(fieldHelp("How long you pause before the current phrase is committed. 300–3000 ms."))

        content.addView(Ui.spacer(this, 16))
        content.addView(fieldLabel("CUSTOM VOCABULARY"))
        vocabulary = Ui.editText(
            this,
            "Names, products, acronyms…\nOne per line or comma separated",
            prefs.vocabulary,
            multiline = true,
        )
        content.addView(vocabulary)
        content.addView(fieldHelp("Biases recognition toward words it should never misspell. Up to ~100 terms works best."))

        content.addView(Ui.spacer(this, 20))
        content.addView(sectionLabel("KEYBOARD"))
        swipeTyping = switchRow(
            content,
            "Swipe to type (English)",
            "Glide across letters. Decoded by the Rust core with a built-in 10k word list.",
            prefs.swipeTyping,
        )

        content.addView(Ui.spacer(this, 8))
        content.addView(fieldLabel("KEYBOARD SIZE (%)"))
        keyboardSize = Ui.editText(this, "100", prefs.keyboardSize.toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        content.addView(keyboardSize)
        content.addView(fieldHelp("Scale of the keyboard and letter size. 80-150%. Restart keyboard to apply."))

        content.addView(Ui.spacer(this, 8))
        content.addView(fieldLabel("KEYBOARD HEIGHT (%)"))
        keyboardHeight = Ui.editText(this, "100", prefs.keyboardHeight.toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        content.addView(keyboardHeight)
        content.addView(fieldHelp("Overall height of the keyboard. 80-150%."))

        content.addView(Ui.spacer(this, 28))

        val save = Ui.button(this, "Save settings", primary = true).apply {
            setPadding(0, dp(16), 0, dp(16))
            setOnClickListener { save() }
        }
        content.addView(save, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        content.addView(Ui.spacer(this, 12))
        val note = Ui.text(
            this,
            "The microphone permission is granted from the AI Keyboard app, not from this screen.",
            11f,
            0xFF6E7382.toInt(),
        )
        note.gravity = Gravity.CENTER
        content.addView(note, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        return scroll
    }

    private fun fieldLabel(text: String): TextView = Ui.text(this, text, 9.5f, 0xFF8E7BFF.toInt(), bold = true, mono = true).apply {
        letterSpacing = 0.12f
        setPadding(0, 0, 0, dp(7))
    }

    private fun sectionLabel(text: String): TextView = Ui.text(this, text, 10f, 0xFF35E0FF.toInt(), bold = true, mono = true).apply {
        letterSpacing = 0.16f
        setPadding(0, 0, 0, dp(4))
    }

    private fun fieldHelp(text: String): TextView = Ui.text(this, text, 11f, 0xFF6E7382.toInt()).apply {
        setPadding(0, dp(6), 0, 0)
        setLineSpacing(0f, 1.15f)
    }

    private fun switchRow(
        parent: LinearLayout,
        title: String,
        description: String,
        checked: Boolean,
    ): Switch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(12), 0)
        }
        labels.addView(Ui.text(this, title, 13.5f, 0xFFE8EAF0.toInt(), bold = true))
        labels.addView(Ui.text(this, description, 11f, 0xFF7E8392.toInt()).apply {
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val toggle = Switch(this).apply {
            isChecked = checked
            setTextColor(0xFFF5F6FA.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFFB39DFF.toInt())
            trackTintList = android.content.res.ColorStateList.valueOf(0xFF3A2F66.toInt())
        }
        row.addView(toggle)
        parent.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return toggle
    }

    private fun save() {
        val prefs = Prefs(this)
        prefs.apiKey = apiKey.text.toString()
        prefs.model = model.text.toString()
        prefs.languages = languages.text.toString()
        prefs.smart = smart.isChecked
        prefs.hybridVad = hybridVad.isChecked
        prefs.silenceMs = silence.text.toString().toIntOrNull() ?: 800
        prefs.vocabulary = vocabulary.text.toString()
        prefs.fixModel = fixModel.text.toString()
        prefs.autoFix = autoFix.isChecked
        prefs.swipeTyping = swipeTyping.isChecked
        prefs.keyboardSize = keyboardSize.text.toString().toIntOrNull() ?: 100
        prefs.keyboardHeight = keyboardHeight.text.toString().toIntOrNull() ?: 100

        val message = if (prefs.apiKey.isBlank()) {
            "Saved — add a Gemini API key to start voice mode"
        } else {
            "Settings saved"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
