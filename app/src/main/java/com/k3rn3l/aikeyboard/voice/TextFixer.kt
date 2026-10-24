package com.k3rn3l.aikeyboard.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k3rn3l.aikeyboard.Prefs
import com.k3rn3l.aikeyboard.core.NativeCore
import org.json.JSONObject

/** Runs the Gemini repair pass off the UI thread. */
object TextFixer {

    private const val TAG = "AiKeyboardFix"
    private val main = Handler(Looper.getMainLooper())

    fun fix(
        context: Context,
        text: String,
        screenContext: String,
        onResult: (corrected: String?, error: String?) -> Unit,
    ) {
        val appContext = context.applicationContext
        Thread({
            var corrected: String? = null
            var error: String? = null
            try {
                val config = Prefs(appContext).coreConfigJson()
                val raw = NativeCore.nativeCorrect(config, text, screenContext)
                if (raw == null) {
                    error = "The Rust core did not answer."
                } else {
                    val json = JSONObject(raw)
                    if (json.optBoolean("ok")) {
                        corrected = json.optString("text").takeIf { it.isNotBlank() }
                        if (corrected == null) error = "Gemini returned an empty repair."
                    } else {
                        error = json.optString("error").ifBlank { "Gemini could not repair this text." }
                    }
                }
            } catch (failure: Throwable) {
                error = failure.message ?: "Text repair failed."
            }
            Log.d(TAG, "repair result: ${if (corrected != null) "ok" else "error: $error"}")
            main.post { onResult(corrected, error) }
        }, "aikeyboard-fix").start()
    }
}
