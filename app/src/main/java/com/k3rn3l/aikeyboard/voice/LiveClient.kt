package com.k3rn3l.aikeyboard.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.k3rn3l.aikeyboard.Prefs
import com.k3rn3l.aikeyboard.core.NativeCore
import org.json.JSONObject

/**
 * Owns one streaming transcription session. The heavy lifting is in Rust; this
 * class only moves PCM into the core and JSON events back out to the UI.
 */
class LiveClient(context: Context, private val listener: Listener) {

    enum class State { IDLE, CONNECTING, LISTENING, RECONNECTING, STOPPING, ERROR }

    interface Listener {
        fun onState(state: State, detail: String?)
        fun onInterim(text: String)
        fun onFinal(text: String)
        fun onLevel(level: Float)
        fun onSpeech(active: Boolean)
        fun onNotice(message: String)
        fun onClosed()
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var handle: Long = 0L
    private var capture: AudioCapture? = null
    private var polling = false
    private var stopping = false

    private val poller = object : Runnable {
        override fun run() {
            if (!polling) return
            val current = handle
            if (current != 0L) {
                try {
                    NativeCore.nativePoll(current)?.lineSequence()?.forEach { line ->
                        if (line.isNotBlank()) parse(line)
                    }
                } catch (error: Throwable) {
                    fail("Native core error: ${error.message}")
                    return
                }
            }
            main.postDelayed(this, 50)
        }
    }

    fun start() {
        if (handle != 0L) return

        val config = Prefs(appContext).coreConfigJson()
        val created = try {
            NativeCore.nativeCreate(config)
        } catch (error: Throwable) {
            listener.onState(State.ERROR, "Rust core failed to load: ${error.message}")
            return
        }
        if (created == 0L) {
            listener.onState(State.ERROR, "Could not create the transcription session.")
            return
        }

        handle = created
        stopping = false
        try {
            NativeCore.nativeStart(created)
        } catch (error: Throwable) {
            fail("Could not start the transcription core: ${error.message}")
            return
        }

        val mic = AudioCapture { samples ->
            val current = handle
            if (current != 0L) {
                try {
                    NativeCore.nativePushAudio(current, samples)
                } catch (_: Throwable) {
                }
            }
        }

        if (!mic.start()) {
            fail("Microphone unavailable. Check the RECORD_AUDIO permission.")
            return
        }
        capture = mic

        polling = true
        listener.onState(State.CONNECTING, null)
        main.post(poller)
    }

    /** Stops capture immediately, but keeps draining finals briefly before teardown. */
    fun stop() {
        if (stopping) return
        stopping = true

        capture?.stop()
        capture = null

        val current = handle
        if (current == 0L) {
            finish()
            return
        }

        listener.onState(State.STOPPING, null)
        try {
            NativeCore.nativeStop(current)
        } catch (_: Throwable) {
        }
        main.postDelayed({ finish() }, 1_800)
    }

    /** Immediate teardown, e.g. when the keyboard window disappears for good. */
    fun cancel() {
        stopping = true
        capture?.stop()
        capture = null
        finish()
    }

    private fun finish() {
        polling = false
        main.removeCallbacks(poller)
        val current = handle
        handle = 0L
        if (current != 0L) {
            try {
                NativeCore.nativeDestroy(current)
            } catch (_: Throwable) {
            }
        }
        listener.onState(State.IDLE, null)
        listener.onClosed()
    }

    private fun fail(message: String) {
        if (stopping) return
        stopping = true
        capture?.stop()
        capture = null
        polling = false
        main.removeCallbacks(poller)

        val current = handle
        handle = 0L
        if (current != 0L) {
            try {
                NativeCore.nativeDestroy(current)
            } catch (_: Throwable) {
            }
        }
        listener.onState(State.ERROR, message)
        listener.onClosed()
    }

    private fun parse(line: String) {
        val event = try {
            JSONObject(line)
        } catch (_: Exception) {
            return
        }

        when (event.optString("type")) {
            "connecting" -> listener.onState(State.CONNECTING, null)
            "ready" -> listener.onState(State.LISTENING, null)
            "reconnecting" -> listener.onState(State.RECONNECTING, event.optString("reason"))
            "waiting" -> listener.onState(
                State.RECONNECTING,
                "Retrying in ${(event.optLong("ms", 0) / 1000).coerceAtLeast(1)}s",
            )
            "error" -> listener.onNotice(event.optString("message"))
            "fatal" -> fail(
                event.optString("message").ifBlank { "Transcription stopped." }
            )
            "interim" -> listener.onInterim(event.optString("text"))
            "final" -> listener.onFinal(event.optString("text"))
            "level" -> listener.onLevel(event.optDouble("value", 0.0).toFloat())
            "speech" -> listener.onSpeech(event.optBoolean("active"))
            "stopped" -> if (stopping) listener.onState(State.STOPPING, null)
        }
    }
}
