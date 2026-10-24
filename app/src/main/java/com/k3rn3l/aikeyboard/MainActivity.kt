package com.k3rn3l.aikeyboard

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.k3rn3l.aikeyboard.ime.AiKeyboardService

class MainActivity : Activity() {

    private lateinit var micStep: StepRow
    private lateinit var keyStep: StepRow
    private lateinit var enabledStep: StepRow
    private lateinit var selectedStep: StepRow
    private lateinit var contextStep: StepRow

    private val prefs by lazy { Prefs(this) }
    private var tryField: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF07080C.toInt()
        window.navigationBarColor = 0xFF07080C.toInt()
        setContentView(buildScreen())
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REQUEST_MIC, false) == true) {
            intent.removeExtra(EXTRA_REQUEST_MIC)
            if (!hasMic()) requestMic()
        }
        if (intent?.getBooleanExtra(EXTRA_FOCUS_TEST, false) == true) {
            intent.removeExtra(EXTRA_FOCUS_TEST)
            tryField?.post {
                tryField?.requestFocus()
                val manager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                manager?.showSoftInput(tryField, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun buildScreen(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF07080C.toInt())
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(40))
        }
        scroll.addView(content)

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            background = Ui.rounded(0xFF12141C.toInt(), 18f, this@MainActivity, 0xFF2A2D3B.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        brand.addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)))
        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        brandText.addView(Ui.text(this, "AI Keyboard", 22f, 0xFFF5F6FA.toInt(), bold = true))
        brandText.addView(Ui.text(this, "Gemini Live voice, in every app.", 12f, 0xFF8A8F9C.toInt()))
        brand.addView(brandText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(brand)
        content.addView(Ui.spacer(this, 24))

        val badge = Ui.text(this, "●  GEMINI LIVE  ·  STREAMING TRANSCRIPTION", 9.5f, 0xFF8BE9FF.toInt(), bold = true, mono = true)
        badge.letterSpacing = 0.1f
        badge.background = Ui.rounded(0xFF0E2230.toInt(), 10f, this, 0xFF1C4256.toInt())
        badge.setPadding(dp(12), dp(7), dp(12), dp(7))
        content.addView(badge, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        content.addView(Ui.spacer(this, 14))

        val heading = Ui.text(this, "Talk naturally.\nIt types as you pause.", 34f, 0xFFF5F6FA.toInt(), bold = true)
        heading.setLineSpacing(0f, 0.98f)
        content.addView(heading)
        content.addView(Ui.spacer(this, 8))
        content.addView(
            Ui.text(
                this,
                "The AI pill sits on top of a normal Gboard-style keyboard. Tap it once and the keyboard becomes a live Gemini listening surface — no chatter, no assistant replies, just your words in the field.",
                13.5f,
                0xFF9A9FAD.toInt(),
            ).apply { setLineSpacing(0f, 1.18f) }
        )
        content.addView(Ui.spacer(this, 22))

        val setup = card()
        setup.addView(sectionTitle("SETUP", "Five checks before the first session"))
        micStep = addStep(setup, "Microphone", "Required for live transcription", "Grant") {
            requestMic()
        }
        keyStep = addStep(setup, "Gemini API key", "Needed to open the Live session", "Add key") {
            openSettings()
        }
        enabledStep = addStep(setup, "Keyboard enabled", "Turn on AI Keyboard in system settings", "Enable") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        selectedStep = addStep(setup, "Keyboard selected", "Switch to it from the keyboard picker", "Choose") {
            showKeyboardPicker()
        }
        contextStep = addStep(
            setup,
            "Screen context (optional)",
            "Lets Fix read on-screen names and terms. No password fields.",
            "Allow",
        ) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        content.addView(setup)
        content.addView(Ui.spacer(this, 16))

        val tryCard = card()
        tryCard.addView(sectionTitle("TRY IT", "Focus the field, then tap the AI pill"))
        val field = EditText(this).apply {
            hint = "Tap here, then hit AI on the keyboard and say something…"
            setHintTextColor(0xFF6E7382.toInt())
            setTextColor(0xFFF5F6FA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            gravity = Gravity.TOP
            background = Ui.rounded(0xFF0B0C12.toInt(), 14f, this@MainActivity, 0xFF2A2D3B.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        tryField = field
        tryCard.addView(field)
        content.addView(tryCard)
        content.addView(Ui.spacer(this, 16))

        val how = card()
        how.addView(sectionTitle("HOW IT WORKS", "Rust core · thin Android shell"))
        val body = Ui.text(
            this,
            "The keyboard streams 16 kHz PCM to Gemini Live (models/gemini-3.5-transcribe-live) over a rustls WebSocket. A local VAD ends each utterance early for fast finalization. Interim words stay on the keyboard; finalized text is committed into the editor.",
            12.5f,
            0xFF9A9FAD.toInt(),
        )
        body.setLineSpacing(0f, 1.2f)
        how.addView(body)
        how.addView(Ui.spacer(this, 8))
        val privacy = Ui.text(
            this,
            "Microphone audio is processed by Google Gemini. Your API key is stored only on this device. Nothing is recorded to disk.",
            11.5f,
            0xFF6E7382.toInt(),
        )
        privacy.setLineSpacing(0f, 1.15f)
        how.addView(privacy)
        content.addView(how)

        content.addView(Ui.spacer(this, 22))
        val version = try {
            "core ${com.k3rn3l.aikeyboard.core.NativeCore.version()}"
        } catch (_: Throwable) {
            "core unavailable"
        }
        val foot = Ui.text(this, "AI Keyboard 0.1.0  ·  $version", 10.5f, 0xFF565B69.toInt(), mono = true)
        foot.gravity = Gravity.CENTER
        content.addView(foot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        return scroll
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = Ui.rounded(0xFF0F1118.toInt(), 22f, this@MainActivity, 0xFF222533.toInt())
    }

    private fun sectionTitle(label: String, subtitle: String): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(Ui.text(this, label, 9.5f, 0xFF8E7BFF.toInt(), bold = true, mono = true).apply {
            letterSpacing = 0.14f
        })
        column.addView(Ui.spacer(this, 2))
        column.addView(Ui.text(this, subtitle, 12f, 0xFF8A8F9C.toInt()))
        column.setPadding(0, 0, 0, dp(6))
        return column
    }

    private inner class StepRow(
        val root: LinearLayout,
        val mark: TextView,
        val title: TextView,
        val detail: TextView,
        val action: TextView,
    )

    private fun addStep(
        card: LinearLayout,
        titleText: String,
        detailText: String,
        actionText: String,
        onClick: () -> Unit,
    ): StepRow {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val mark = Ui.text(this, "!", 15f, 0xFFFFC66D.toInt(), bold = true).apply {
            gravity = Gravity.CENTER
        }
        row.addView(mark, LinearLayout.LayoutParams(dp(22), LinearLayout.LayoutParams.WRAP_CONTENT))

        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(8), 0)
        }
        val title = Ui.text(this, titleText, 13.5f, 0xFFE8EAF0.toInt(), bold = true)
        val detail = Ui.text(this, detailText, 11f, 0xFF7E8392.toInt())
        labels.addView(title)
        labels.addView(detail)
        row.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val action = Ui.button(this, actionText, primary = false, small = true)
        action.setOnClickListener { onClick() }
        row.addView(action)

        val step = StepRow(row, mark, title, detail, action)
        card.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return step
    }

    private fun refreshState() {
        setStep(micStep, hasMic(), "Grant", "Granted")
        setStep(keyStep, prefs.isConfigured(), "Add key", "Edit")
        setStep(enabledStep, isImeEnabled(), "Enable", "Enabled")
        setStep(selectedStep, isImeSelected(), "Choose", "Selected")
        val accessibility = isAccessibilityEnabled()
        setStep(contextStep, accessibility, "Allow", "On")
        if (!accessibility && hasDeviceAdmin()) {
            contextStep.detail.text = "Controlled by device admin — allow AI Keyboard in the MDM policy"
            contextStep.action.text = "Blocked"
            contextStep.action.isClickable = true
            contextStep.action.isFocusable = true
            contextStep.action.alpha = 1f
        }
    }

    private fun setStep(step: StepRow, done: Boolean, actionLabel: String, doneLabel: String) {
        step.mark.text = if (done) "✓" else "!"
        step.mark.setTextColor(if (done) 0xFF4ADE80.toInt() else 0xFFFFC66D.toInt())
        step.action.text = if (done) doneLabel else actionLabel
        step.action.isClickable = !done
        step.action.isFocusable = !done
        step.action.alpha = if (done) 0.6f else 1f
        step.action.background = if (done) {
            Ui.rounded(0xFF14161D.toInt(), 14f, this, 0xFF232633.toInt())
        } else {
            Ui.rounded(0xFF1B1D26.toInt(), 14f, this, 0xFF2B2E3B.toInt())
        }
        if (done) {
            step.detail.setTextColor(0xFF7E8392.toInt())
        }
    }

    private fun hasMic(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestMic() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) {
            refreshState()
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Microphone access is required for voice typing", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun showKeyboardPicker() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
    }

    private fun isImeEnabled(): Boolean {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        val expected = imeComponent()
        return manager.enabledInputMethodList.any { info ->
            info.packageName == expected.packageName && info.serviceName == expected.className
        }
    }

    private fun isImeSelected(): Boolean = try {
        val expected = imeComponent().flattenToString()
        val expectedShort = imeComponent().flattenToShortString()
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        selected.equals(expected, true) || selected.equals(expectedShort, true)
    } catch (_: SecurityException) {
        false
    }

    private fun imeComponent() = ComponentName(this, AiKeyboardService::class.java)

    private fun hasDeviceAdmin(): Boolean {
        val manager = getSystemService(DevicePolicyManager::class.java) ?: return false
        return !manager.activeAdmins.isNullOrEmpty()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val service = info.resolveInfo.serviceInfo
                service.packageName == packageName &&
                    service.name == com.k3rn3l.aikeyboard.context.ScreenTextService::class.java.name
            }
    }

    private fun dp(value: Int): Int = Ui.dp(this, value)

    companion object {
        const val EXTRA_REQUEST_MIC = "request_mic"
        const val EXTRA_FOCUS_TEST = "focus_test"
        private const val REQUEST_MIC = 10
    }
}
