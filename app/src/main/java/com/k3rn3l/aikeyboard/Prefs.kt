package com.k3rn3l.aikeyboard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty().trim()
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL)!!.ifBlank { DEFAULT_MODEL }
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim().ifBlank { DEFAULT_MODEL }).apply()

    /** Comma separated BCP-47 codes. Empty means automatic language detection. */
    var languages: String
        get() = prefs.getString(KEY_LANGUAGES, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LANGUAGES, value.trim()).apply()

    var smart: Boolean
        get() = prefs.getBoolean(KEY_SMART, true)
        set(value) = prefs.edit().putBoolean(KEY_SMART, value).apply()

    /** Newline separated custom vocabulary. */
    var vocabulary: String
        get() = prefs.getString(KEY_VOCABULARY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_VOCABULARY, value.trim()).apply()

    var hybridVad: Boolean
        get() = prefs.getBoolean(KEY_HYBRID_VAD, true)
        set(value) = prefs.edit().putBoolean(KEY_HYBRID_VAD, value).apply()

    var silenceMs: Int
        get() = prefs.getInt(KEY_SILENCE_MS, 800).coerceIn(300, 3000)
        set(value) = prefs.edit().putInt(KEY_SILENCE_MS, value.coerceIn(300, 3000)).apply()

    var fixModel: String
        get() = prefs.getString(KEY_FIX_MODEL, DEFAULT_FIX_MODEL)!!.ifBlank { DEFAULT_FIX_MODEL }
        set(value) = prefs.edit().putString(KEY_FIX_MODEL, value.trim().ifBlank { DEFAULT_FIX_MODEL }).apply()

    var autoFix: Boolean
        get() = prefs.getBoolean(KEY_AUTO_FIX, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_FIX, value).apply()

    var swipeTyping: Boolean
        get() = prefs.getBoolean(KEY_SWIPE_TYPING, true)
        set(value) = prefs.edit().putBoolean(KEY_SWIPE_TYPING, value).apply()

    /** Keyboard size as a percentage (80-150). 100 = default. */
    var keyboardSize: Int
        get() = prefs.getInt(KEY_KEYBOARD_SIZE, 100).coerceIn(80, 150)
        set(value) = prefs.edit().putInt(KEY_KEYBOARD_SIZE, value.coerceIn(80, 150)).apply()

    /** Keyboard height as a percentage (80-150). 100 = default. */
    var keyboardHeight: Int
        get() = prefs.getInt(KEY_KEYBOARD_HEIGHT, 100).coerceIn(80, 150)
        set(value) = prefs.edit().putInt(KEY_KEYBOARD_HEIGHT, value.coerceIn(80, 150)).apply()

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    fun languageList(): List<String> =
        languages.split(',', ' ', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun vocabularyList(): List<String> =
        vocabulary.split('\n', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun coreConfigJson(): String = JSONObject().apply {
        put("apiKey", apiKey)
        put("model", model)
        put("languageCodes", JSONArray(languageList()))
        put("mode", if (smart) "SMART" else "VERBATIM")
        put("vocabulary", JSONArray(vocabularyList()))
        put("hybridVad", hybridVad)
        put("vadSilenceMs", silenceMs)
        put("vadThreshold", 0.014)
        put("fixModel", fixModel)
    }.toString()

    companion object {
        private const val NAME = "ai_keyboard_settings"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_LANGUAGES = "languages"
        private const val KEY_SMART = "smart"
        private const val KEY_VOCABULARY = "vocabulary"
        private const val KEY_HYBRID_VAD = "hybrid_vad"
        private const val KEY_SILENCE_MS = "silence_ms"
        private const val KEY_FIX_MODEL = "fix_model"
        private const val KEY_AUTO_FIX = "auto_fix"
        private const val KEY_SWIPE_TYPING = "swipe_typing"
        private const val KEY_KEYBOARD_SIZE = "keyboard_size"
        private const val KEY_KEYBOARD_HEIGHT = "keyboard_height"

        const val DEFAULT_MODEL = "gemini-3.5-transcribe-live"
        const val DEFAULT_FIX_MODEL = "gemini-3.6-flash"
    }
}
