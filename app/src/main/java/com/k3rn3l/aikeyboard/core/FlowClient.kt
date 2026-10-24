package com.k3rn3l.aikeyboard.core

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject

/**
 * Kotlin side of the Rust flow controller. Sends Android/user events into Rust
 * and executes the effects Rust returns. It holds no transition rules itself.
 */
class FlowClient(private val onEffect: (action: String, text: String?) -> Unit) {

    private val handler = Handler(Looper.getMainLooper())
    private var handle: Long = NativeCore.nativeFlowCreate()
    private var polling = true

    private val poller = object : Runnable {
        override fun run() {
            if (!polling) return
            val current = handle
            if (current != 0L) {
                try {
                    NativeCore.nativeFlowPoll(current)
                        ?.lineSequence()
                        ?.forEach { line ->
                            if (line.isNotBlank()) {
                                val effect = JSONObject(line)
                                onEffect(
                                    effect.optString("action"),
                                    effect.optString("text").takeIf { it.isNotEmpty() },
                                )
                            }
                        }
                } catch (error: Throwable) {
                    Log.e(TAG, "flow poll failed: ${error.message}")
                }
            }
            handler.postDelayed(this, 40)
        }
    }

    init {
        if (handle != 0L) {
            handler.post(poller)
        } else {
            Log.e(TAG, "Rust flow controller unavailable")
        }
    }

    fun start(polish: Boolean) {
        send(JSONObject().put("event", "start").put("polish", polish))
    }

    fun ready() = send(JSONObject().put("event", "ready"))
    fun interim() = send(JSONObject().put("event", "interim"))
    fun finalizeUtterance() = send(JSONObject().put("event", "final"))
    fun speech(active: Boolean) = send(JSONObject().put("event", "speech").put("active", active))
    fun done() = send(JSONObject().put("event", "done"))
    fun stop() = send(JSONObject().put("event", "stop"))
    fun polishFinished(ok: Boolean) = send(JSONObject().put("event", "polish_finished").put("ok", ok))
    fun closed() = send(JSONObject().put("event", "closed"))
    fun hidden() = send(JSONObject().put("event", "hidden"))
    fun timeout() = send(JSONObject().put("event", "timeout"))
    fun error(message: String) = send(
        JSONObject().put("event", "error").put("message", message)
    )

    private fun send(event: JSONObject) {
        val current = handle
        if (current == 0L) return
        try {
            NativeCore.nativeFlowEvent(current, event.toString())
        } catch (error: Throwable) {
            Log.e(TAG, "flow event failed: ${error.message}")
        }
    }

    fun destroy() {
        polling = false
        handler.removeCallbacks(poller)
        val current = handle
        handle = 0L
        if (current != 0L) {
            try {
                NativeCore.nativeFlowDestroy(current)
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        private const val TAG = "AiKeyboardFlow"
    }
}
