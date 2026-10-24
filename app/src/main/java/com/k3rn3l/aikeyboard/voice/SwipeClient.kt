package com.k3rn3l.aikeyboard.voice

import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k3rn3l.aikeyboard.core.NativeCore
import org.json.JSONArray
import org.json.JSONObject

/** Runs the Rust swipe decoder off the UI thread. */
object SwipeClient {

    private val main = Handler(Looper.getMainLooper())

    fun decode(
        points: List<PointF>,
        keys: List<SwipeKey>,
        extra: List<String>,
        onResult: (word: String?, score: Float) -> Unit,
    ) {
        val path = JSONArray()
        points.forEach { point ->
            path.put(JSONArray().put(point.x.toDouble()).put(point.y.toDouble()))
        }

        val keyArray = JSONArray()
        keys.forEach { key ->
            keyArray.put(
                JSONObject()
                    .put("c", key.letter.toString())
                    .put("x", key.x.toDouble())
                    .put("y", key.y.toDouble())
            )
        }
        val extraArray = JSONArray()
        extra.forEach { word -> extraArray.put(word) }

        Thread({
            var word: String? = null
            var score = 0f
            try {
                val raw = NativeCore.nativeSwipeDecode(path.toString(), keyArray.toString(), extraArray.toString())
                val json = raw?.takeIf { it.isNotBlank() }?.let { JSONObject(it) }
                if (json != null) {
                    word = json.optString("word").takeIf { text -> text.isNotBlank() }
                    score = json.optDouble("score", 0.0).toFloat()
                }
            } catch (error: Throwable) {
                Log.e(TAG, "swipe decode failed: ${error.message}")
            }
            Log.d(TAG, "swipe -> $word (${String.format("%.3f", score)})")
            main.post { onResult(word, score) }
        }, "aikeyboard-swipe").start()
    }

    data class SwipeKey(val letter: Char, val x: Float, val y: Float)

    private const val TAG = "AiKeyboardSwipe"
}
