package com.k3rn3l.aikeyboard.ime

import android.content.Context
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import com.k3rn3l.aikeyboard.Prefs
import com.k3rn3l.aikeyboard.core.FlowClient
import com.k3rn3l.aikeyboard.voice.LiveClient
import com.k3rn3l.aikeyboard.voice.SwipeClient

/**
 * Kotlin is the executor and renderer here. The listen -> polish -> done rules
 * live in Rust (`rust/src/flow.rs`); this view only forwards Android/session
 * events and carries out the effects Rust returns.
 */
class AiKeyboardView(context: Context) : FrameLayout(context) {

    private val service = context as AiKeyboardService
    private val prefs = Prefs(context)

    private val gboard = GboardView(context)
    private val panel = AiPanelView(context)
    private val autofillStrip = HorizontalScrollView(context)
    private val autofillRow = LinearLayout(context)
    private val handler = Handler(Looper.getMainLooper())
    private var stripHeight = 0
    private var keyboardHeight = 0

    private var client: LiveClient? = null
    private var aiVisible = false
    private var seconds = 0
    private var polishingMirror = false
    private var errorMode = false
    private var swipeGeneration = 0

    private val flow = FlowClient { action, text -> handleEffect(action, text) }

    private val sessionTimeout = Runnable {
        flow.timeout()
    }

    private val ticker = object : Runnable {
        override fun run() {
            seconds++
            panel.setElapsed(seconds)
            handler.postDelayed(this, 1_000)
        }
    }

    init {
        autofillStrip.isHorizontalScrollBarEnabled = false
        autofillStrip.setBackgroundColor(0xFF0B0C11.toInt())
        autofillStrip.visibility = View.GONE
        autofillRow.orientation = LinearLayout.HORIZONTAL
        autofillRow.gravity = Gravity.CENTER_VERTICAL
        autofillRow.setPadding(dp(8f).toInt(), dp(4f).toInt(), dp(8f).toInt(), dp(4f).toInt())
        autofillStrip.addView(
            autofillRow,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        addView(gboard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(autofillStrip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        panel.visibility = View.GONE

        gboard.listener = object : GboardView.Listener {
            override fun onText(text: String) = service.commitText(text)
            override fun onBackspace() = service.backspace()
            override fun onEnter() = service.enter()
            override fun onAi() = startAi()
            override fun onFix() = service.fixWholeText()
            override fun onSettings() = service.openSettings()

            override fun onSwipe(
                points: List<PointF>,
                keys: List<GboardView.KeyCenter>,
                capitalize: Boolean,
            ) {
                if (!prefs.swipeTyping || aiVisible) return
                val generation = ++swipeGeneration
                val swipeKeys = keys.map { SwipeClient.SwipeKey(it.letter, it.x, it.y) }
                SwipeClient.decode(points, swipeKeys, prefs.vocabularyList()) { word, score ->
                    if (generation != swipeGeneration || word.isNullOrBlank()) return@decode
                    val text = if (capitalize) {
                        word.replaceFirstChar { it.uppercase() }
                    } else {
                        word
                    }
                    gboard.consumeSwipeShift()
                    service.commitSwipeWord(text)
                    Log.d(TAG, "swipe committed '$text' (score $score)")
                }
            }
        }

        panel.onStop = {
            stopSessionTimer()
            if (errorMode) startAi() else flow.stop()
        }
        panel.onDone = {
            // Done wins over the 60s cap at the boundary.
            stopSessionTimer()
            flow.done()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val base = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            296f,
            resources.displayMetrics,
        ).toInt()
        val wantedStrip = if (autofillStrip.visibility == View.VISIBLE) {
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 56f, resources.displayMetrics).toInt()
        } else {
            0
        }
        val desired = base + wantedStrip
        val height = resolveSize(desired, heightMeasureSpec)
        stripHeight = wantedStrip.coerceAtMost(height)
        keyboardHeight = (height - stripHeight).coerceAtLeast(base - wantedStrip)
        setMeasuredDimension(width, height)

        val exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val stripSpec = MeasureSpec.makeMeasureSpec(stripHeight, MeasureSpec.EXACTLY)
        val keyboardSpec = MeasureSpec.makeMeasureSpec(keyboardHeight, MeasureSpec.EXACTLY)

        autofillStrip.measure(exactWidth, stripSpec)
        gboard.measure(exactWidth, keyboardSpec)
        panel.measure(exactWidth, keyboardSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        autofillStrip.layout(0, 0, width, stripHeight)
        gboard.layout(0, stripHeight, width, stripHeight + keyboardHeight)
        panel.layout(0, stripHeight, width, stripHeight + keyboardHeight)
    }

    fun showInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        autofillRow.removeAllViews()

        val suggestions = response.inlineSuggestions
        if (suggestions.isNullOrEmpty()) {
            setAutofillStripVisible(false)
            return false
        }

        val size = Size(dp(360f).toInt(), dp(56f).toInt())
        for (suggestion in suggestions.take(3)) {
            try {
                suggestion.inflate(context, size, context.mainExecutor) { view ->
                    view.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                    ).apply { marginEnd = dp(6f).toInt() }
                    autofillRow.addView(view)
                    setAutofillStripVisible(true)
                    Log.d(TAG, "inline suggestion added: ${autofillRow.childCount}")
                }
            } catch (error: Throwable) {
                Log.w(TAG, "could not inflate inline suggestion: ${error.message}")
            }
        }
        return true
    }

    fun clearInlineSuggestions() {
        autofillRow.removeAllViews()
        setAutofillStripVisible(false)
    }

    private fun setAutofillStripVisible(visible: Boolean) {
        val target = visible && autofillRow.childCount > 0 && !aiVisible
        val newVisibility = if (target) View.VISIBLE else View.GONE
        if (autofillStrip.visibility == newVisibility) return
        autofillStrip.visibility = newVisibility
        requestLayout()
    }

    override fun onDetachedFromWindow() {
        client?.cancel()
        client = null
        flow.destroy()
        stopTicker()
        stopSessionTimer()
        super.onDetachedFromWindow()
    }

    fun refresh() {
        gboard.refresh()
        if (!aiVisible) gboard.setHint("Tap AI to talk")
    }

    fun autoShift(caps: Boolean) {
        if (caps) gboard.setShift(true)
    }

    fun isPolishing(): Boolean = polishingMirror

    fun startAi() {
        if (!prefs.isConfigured()) {
            Toast.makeText(context, "Add your Gemini API key first", Toast.LENGTH_SHORT).show()
            service.openSettings()
            return
        }
        if (!service.hasMicPermission()) {
            Toast.makeText(context, "Microphone access is needed", Toast.LENGTH_SHORT).show()
            service.openMicPermission()
            return
        }
        flow.start(prefs.autoFix)
    }

    fun onKeyboardHidden() {
        swipeGeneration++
        stopTicker()
        stopSessionTimer()
        flow.hidden()
    }

    private fun handleEffect(action: String, text: String?) {
        when (action) {
            "clear_field" -> {
                if (!service.clearField()) {
                    Toast.makeText(context, "Could not clear this text field", Toast.LENGTH_SHORT).show()
                }
            }

            "show_panel" -> {
                errorMode = false
                polishingMirror = false
                panel.configure(
                    prefs.smart,
                    prefs.languageList().joinToString(", ").ifBlank { "AUTO" },
                )
                panel.reset()
                panel.setState(LiveClient.State.CONNECTING)
                showPanel()
                startTicker()
            }

            "start_session" -> {
                if (client != null) {
                    Log.e(TAG, "start_session while a client already exists")
                    return
                }
                val session = LiveClient(context, listener)
                client = session
                session.start()
            }

            "hide_panel" -> {
                stopTicker()
                stopSessionTimer()
                polishingMirror = false
                errorMode = false
                hidePanel()
            }

            "show_listening" -> {
                errorMode = false
                polishingMirror = false
                panel.setPolishing(false)
                panel.setState(LiveClient.State.LISTENING)
                startSessionTimer()
            }

            "show_done" -> panel.setDoneAvailable(true)

            "show_stop" -> panel.setDoneAvailable(false)

            "show_polishing" -> {
                stopSessionTimer()
                polishingMirror = true
                panel.setPolishing(true)
            }

            "polish" -> {
                service.fixSessionText { ok -> flow.polishFinished(ok) }
            }

            "stop_session" -> client?.stop()

            "cancel_session" -> {
                client?.cancel()
                client = null
                stopTicker()
            }

            "hint" -> gboard.setHint(text ?: "Tap AI to talk")

            "toast" -> if (!text.isNullOrBlank()) {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }

            "show_error" -> {
                stopSessionTimer()
                errorMode = true
                polishingMirror = false
                panel.setPolishing(false)
                panel.setState(
                    LiveClient.State.ERROR,
                    text ?: "Transcription stopped.",
                )
                stopTicker()
            }

            else -> Log.w(TAG, "unknown flow effect: $action")
        }
    }

    private fun startSessionTimer() {
        handler.removeCallbacks(sessionTimeout)
        handler.postDelayed(sessionTimeout, 60_000)
    }

    private fun stopSessionTimer() {
        handler.removeCallbacks(sessionTimeout)
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        seconds = 0
        panel.setElapsed(0)
        handler.postDelayed(ticker, 1_000)
    }

    private fun stopTicker() {
        handler.removeCallbacks(ticker)
    }

    private fun showPanel() {
        aiVisible = true
        setAutofillStripVisible(false)
        panel.animate().cancel()
        panel.visibility = View.VISIBLE
        panel.alpha = 0f
        panel.translationY = dp(20f)
        panel.animate().alpha(1f).translationY(0f).setDuration(180).start()
        gboard.visibility = View.VISIBLE
    }

    private fun hidePanel(immediate: Boolean = false) {
        aiVisible = false
        panel.animate().cancel()
        if (immediate) {
            panel.visibility = View.GONE
            panel.alpha = 1f
            panel.translationY = 0f
            setAutofillStripVisible(true)
            return
        }
        panel.animate()
            .alpha(0f)
            .translationY(dp(16f))
            .setDuration(150)
            .withEndAction {
                panel.visibility = View.GONE
                panel.alpha = 1f
                panel.translationY = 0f
                setAutofillStripVisible(true)
            }
            .start()
    }

    private val listener = object : LiveClient.Listener {
        override fun onState(state: LiveClient.State, detail: String?) {
            when (state) {
                LiveClient.State.LISTENING -> {
                    flow.ready()
                    if (!polishingMirror) panel.clearTransportStatus()
                }
                LiveClient.State.RECONNECTING -> {
                    if (!polishingMirror) {
                        panel.setTransportStatus(detail ?: "Reconnecting…")
                    }
                }
                LiveClient.State.ERROR -> {
                    flow.error(detail ?: "Transcription stopped.")
                }
                LiveClient.State.CONNECTING,
                LiveClient.State.STOPPING,
                LiveClient.State.IDLE,
                -> Unit
            }
        }

        override fun onInterim(text: String) {
            flow.interim()
            if (aiVisible) panel.setInterim(text)
        }

        override fun onFinal(text: String) {
            service.commitFinal(text)
            if (aiVisible) panel.setFinal(text)
            flow.finalizeUtterance()
        }

        override fun onLevel(level: Float) {
            if (aiVisible) panel.setLevel(level)
        }

        override fun onSpeech(active: Boolean) {
            if (active && aiVisible) panel.beginSpeech()
            flow.speech(active)
        }

        override fun onNotice(message: String) {
            if (message.isNotBlank()) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        override fun onClosed() {
            client = null
            stopTicker()
            flow.closed()
        }
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    companion object {
        private const val TAG = "AiKeyboardAutofill"
    }
}
