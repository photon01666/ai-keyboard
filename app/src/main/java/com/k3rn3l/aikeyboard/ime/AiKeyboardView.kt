package com.k3rn3l.aikeyboard.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import com.k3rn3l.aikeyboard.Prefs
import com.k3rn3l.aikeyboard.voice.LiveClient

/**
 * Root IME view: a normal Gboard-like keyboard plus a full-surface AI mode.
 * The user taps Done after an utterance; that runs the polish and then closes
 * the session. There is no timed auto-polish.
 */
class AiKeyboardView(context: Context) : FrameLayout(context) {

    private val service = context as AiKeyboardService
    private val prefs = Prefs(context)

    private val gboard = GboardView(context)
    private val panel = AiPanelView(context)
    private val handler = Handler(Looper.getMainLooper())

    private var client: LiveClient? = null
    private var aiVisible = false
    private var lastState = LiveClient.State.IDLE
    private var seconds = 0

    private var polishing = false
    private var speechDuringPolish = false
    private var stopRequested = false
    private var pendingHide = false
    private var finishing = false
    private var pendingStart = false
    private var finalDuringPolish = false

    private val ticker = object : Runnable {
        override fun run() {
            seconds++
            panel.setElapsed(seconds)
            handler.postDelayed(this, 1_000)
        }
    }

    init {
        addView(gboard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        panel.visibility = View.GONE

        gboard.listener = object : GboardView.Listener {
            override fun onText(text: String) = service.commitText(text)
            override fun onBackspace() = service.backspace()
            override fun onEnter() = service.enter()
            override fun onAi() = startAi()
            override fun onFix() = service.fixWholeText()
            override fun onSettings() = service.openSettings()
        }

        panel.onStop = {
            if (lastState == LiveClient.State.ERROR) {
                client = null
                startAi()
            } else {
                stopAi()
            }
        }
        panel.onDone = { startPolish() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desired = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            296f,
            resources.displayMetrics,
        ).toInt()
        val height = resolveSize(desired, heightMeasureSpec)
        setMeasuredDimension(width, height)

        val exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val exactHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        for (index in 0 until childCount) {
            getChildAt(index).measure(exactWidth, exactHeight)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (index in 0 until childCount) {
            getChildAt(index).layout(0, 0, right - left, bottom - top)
        }
    }

    fun refresh() {
        gboard.refresh()
        if (!aiVisible) gboard.setHint("Tap AI to talk")
    }

    fun autoShift(caps: Boolean) {
        if (caps) gboard.setShift(true)
    }

    fun isPolishing(): Boolean = polishing

    fun startAi() {
        if (client != null) {
            if (finishing) {
                // The old session is flushing its final words. Queue the restart
                // instead of dropping the tap on the floor.
                pendingStart = true
                gboard.setHint("Starting…")
            } else {
                gboard.setHint(if (polishing) "Polishing text…" else "Finishing transcript…")
            }
            return
        }
        pendingStart = false
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

        polishing = false
        speechDuringPolish = false
        stopRequested = false
        pendingHide = false

        // Every AI run starts with a clean field, so a new message never gets
        // appended to the previous one.
        if (!service.clearField()) {
            Toast.makeText(context, "Could not clear this text field", Toast.LENGTH_SHORT).show()
        }

        panel.configure(prefs.smart, prefs.languageList().joinToString(", ").ifBlank { "AUTO" })
        panel.reset()
        panel.setState(LiveClient.State.CONNECTING)
        showPanel()
        service.beginSession()

        lastState = LiveClient.State.CONNECTING
        seconds = 0
        panel.setElapsed(0)
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 1_000)

        val session = LiveClient(context, listener)
        client = session
        session.start()
    }

    /** Abort without polishing. The Done button is the polish path. */
    fun stopAi() {
        if (polishing) {
            stopRequested = true
            return
        }
        finishSession("Tap AI to talk")
    }

    private fun startPolish() {
        val session = client ?: return
        if (polishing || !aiVisible) return
        if (session !== client) return

        if (!prefs.autoFix) {
            finishSession("Tap AI to talk")
            return
        }

        polishing = true
        speechDuringPolish = false
        finalDuringPolish = false
        stopRequested = false
        panel.setPolishing(true)
        service.fixSessionText { ok -> onPolishFinished(ok) }
    }

    private fun finishSession(hint: String) {
        val session = client
        handler.removeCallbacks(ticker)
        finishing = session != null
        session?.stop()
        hidePanel()
        gboard.setHint(hint)
    }

    fun onKeyboardHidden() {
        if (polishing) {
            // Never throw away an in-flight correction. The polish callback owns
            // the final teardown when it finishes.
            pendingHide = true
            return
        }
        handler.removeCallbacks(ticker)
        stopRequested = false
        pendingStart = false
        finishing = false
        client?.cancel()
        client = null
        if (aiVisible) hidePanel(immediate = true)
    }

    private fun onPolishFinished(ok: Boolean) {
        polishing = false
        panel.setPolishing(false)

        if (pendingHide) {
            pendingHide = false
            handler.removeCallbacks(ticker)
            client?.cancel()
            client = null
            hidePanel(immediate = true)
            return
        }

        if (speechDuringPolish && !stopRequested && client != null) {
            // They kept talking while the previous segment was polishing. Keep the
            // session live and, if that next utterance is already finalised, put
            // Done back immediately instead of leaving a bare Stop button.
            panel.setState(LiveClient.State.LISTENING)
            if (finalDuringPolish) {
                panel.setDoneAvailable(prefs.autoFix)
            }
            finalDuringPolish = false
            return
        }

        if (!ok) {
            Toast.makeText(context, "Polish failed — stopped listening", Toast.LENGTH_SHORT).show()
        }

        if (client == null) {
            hidePanel(immediate = true)
            gboard.setHint("Tap AI to talk")
            return
        }

        finishSession(if (ok) "Polished ✓" else "Tap AI to talk")
    }

    private fun showPanel() {
        aiVisible = true
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
            }
            .start()
    }

    private val listener = object : LiveClient.Listener {
        override fun onState(state: LiveClient.State, detail: String?) {
            lastState = state
            when (state) {
                LiveClient.State.LISTENING -> {
                    if (!polishing) panel.setState(state)
                }
                LiveClient.State.CONNECTING,
                LiveClient.State.RECONNECTING,
                LiveClient.State.STOPPING,
                LiveClient.State.ERROR,
                -> {
                    if (!polishing) panel.setState(state, detail)
                }
                LiveClient.State.IDLE -> {
                    handler.removeCallbacks(ticker)
                    if (!polishing) gboard.setHint("Tap AI to talk")
                }
            }
        }

        override fun onInterim(text: String) {
            if (polishing) {
                speechDuringPolish = true
            }
            // Do NOT hide Done on an interim: a late interim can arrive just after
            // a final and would swallow the button. Speech start hides it instead.
            if (aiVisible) panel.setInterim(text)
        }

        override fun onFinal(text: String) {
            service.commitFinal(text)
            if (!aiVisible) return
            panel.setFinal(text)
            if (polishing) {
                // This is the next utterance completing while the previous one is
                // still being polished.
                finalDuringPolish = true
            } else {
                // Utterance finished: expose the explicit Done -> polish action.
                panel.setDoneAvailable(prefs.autoFix)
            }
            Log.d("AiKeyboardDone", "final polishing=$polishing chars=${text.length}")
        }

        override fun onLevel(level: Float) {
            if (aiVisible) panel.setLevel(level)
        }

        override fun onSpeech(active: Boolean) {
            if (!active) return
            if (polishing) {
                speechDuringPolish = true
            } else {
                panel.setDoneAvailable(false)
            }
            if (aiVisible) panel.beginSpeech()
        }

        override fun onNotice(message: String) {
            if (message.isNotBlank()) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        override fun onClosed() {
            client = null
            finishing = false
            handler.removeCallbacks(ticker)
            if (polishing) return

            if (pendingStart) {
                pendingStart = false
                gboard.setHint("Starting…")
                handler.post { startAi() }
                return
            }

            if (lastState == LiveClient.State.ERROR) return
            if (aiVisible) hidePanel()
            gboard.setHint("Tap AI to talk")
        }
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
