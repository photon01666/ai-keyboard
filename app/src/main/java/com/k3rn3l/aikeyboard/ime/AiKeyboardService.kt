package com.k3rn3l.aikeyboard.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.inputmethodservice.InputMethodService
import android.widget.Toast
import com.k3rn3l.aikeyboard.MainActivity
import com.k3rn3l.aikeyboard.SettingsActivity
import com.k3rn3l.aikeyboard.context.ScreenTextService
import com.k3rn3l.aikeyboard.voice.TextFixer

class AiKeyboardService : InputMethodService() {

    private var root: AiKeyboardView? = null
    private val sessionText = StringBuilder()
    private var fixGeneration = 0

    override fun onCreateInputView(): View {
        val view = AiKeyboardView(this)
        root = view
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        root?.refresh()
        updateShift()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        root?.onKeyboardHidden()
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        root?.onKeyboardHidden()
        super.onWindowHidden()
    }

    override fun onDestroy() {
        root?.onKeyboardHidden()
        super.onDestroy()
    }

    fun commitText(text: String) {
        if (text.isEmpty()) return
        currentInputConnection?.commitText(text, 1)
    }

    /** Final transcripts get natural spacing relative to whatever is already in the field. */
    fun commitFinal(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val connection = currentInputConnection ?: return

        val before = connection.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val startsWithPunctuation = text.first() in ".,!?;:)]}%…’\""
        val needsSpace = before.isNotEmpty() &&
            !before.last().isWhitespace() &&
            !startsWithPunctuation &&
            !text.startsWith("'")

        val committed = if (needsSpace) " $text" else text
        connection.commitText(committed, 1)
        sessionText.append(committed)
        updateShift()
    }

    fun beginSession() {
        sessionText.setLength(0)
    }

    /** The current editor contents, used to make the next AI run start clean. */
    fun currentFieldText(): String {
        val connection = currentInputConnection ?: return ""
        val extracted = try {
            connection.getExtractedText(ExtractedTextRequest(), 0)
        } catch (_: Exception) {
            null
        }
        return extracted?.text?.toString().orEmpty()
    }

    /** Selects the whole field and replaces it with nothing. */
    fun clearField(): Boolean {
        val connection = currentInputConnection ?: return false
        val extracted = try {
            connection.getExtractedText(ExtractedTextRequest(), 0)
        } catch (_: Exception) {
            null
        } ?: return false

        val text = extracted.text?.toString().orEmpty()
        if (text.isEmpty()) return true

        val start = extracted.startOffset
        val end = start + text.length
        return try {
            connection.setSelection(start, end) && connection.commitText("", 1)
        } catch (_: Exception) {
            false
        }
    }

    /** Repair the whole field using screen/field context. */
    fun fixWholeText(onDone: ((Boolean) -> Unit)? = null) = fixText(false, quiet = false, onDone)

    /** Repair only the text inserted since the last repair. Used after pauses and on stop. */
    fun fixSessionText(onDone: ((Boolean) -> Unit)? = null) = fixText(true, quiet = true, onDone)

    private fun fixText(onlySession: Boolean, quiet: Boolean, onDone: ((Boolean) -> Unit)?) {
        if (onlySession && sessionText.isBlank()) {
            onDone?.invoke(false)
            return
        }

        val sessionSnapshot = if (onlySession) sessionText.toString() else null

        val connection = currentInputConnection
        if (connection == null) {
            if (!quiet) toast("Open a text field first")
            onDone?.invoke(false)
            return
        }

        val extracted = try {
            connection.getExtractedText(ExtractedTextRequest(), 0)
        } catch (_: Exception) {
            null
        }
        val full = extracted?.text?.toString().orEmpty()
        if (full.isBlank()) {
            if (!quiet) toast("Nothing to fix")
            onDone?.invoke(false)
            return
        }

        val relStart: Int
        val length: Int
        if (onlySession) {
            val needle = sessionSnapshot.orEmpty()
            val index = full.lastIndexOf(needle)
            if (index < 0) {
                onDone?.invoke(false)
                return
            }
            relStart = index
            length = needle.length
        } else {
            relStart = 0
            length = full.length
        }

        val original = full.substring(relStart, relStart + length)
        if (original.isBlank()) {
            onDone?.invoke(false)
            return
        }

        val absoluteStart = (extracted?.startOffset ?: 0) + relStart

        // Accessibility is optional (and can be blocked by a device admin), so the
        // rest of the field is always supplied as context for grammar and names.
        val fieldContext = buildString {
            if (relStart > 0) {
                append("TEXT BEFORE:\n")
                append(full.substring(0, relStart.coerceAtMost(2_000)))
                append("\n")
            }
            if (relStart + length < full.length) {
                append("TEXT AFTER:\n")
                append(full.substring(relStart + length).take(2_000))
            }
        }
        val screenContext = listOf(ScreenTextService.read(), fieldContext)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        val generation = ++fixGeneration
        if (!quiet) toast("Fixing text…")

        TextFixer.fix(this, original, screenContext) { corrected, error ->
            if (generation != fixGeneration) {
                onDone?.invoke(false)
                return@fix
            }
            if (corrected == null) {
                if (!quiet) toast(error ?: "Could not fix text")
                Log.w("AiKeyboardFix", "repair failed: $error")
                onDone?.invoke(false)
                return@fix
            }
            if (corrected == original) {
                if (!quiet) toast("Text already looks right")
                consumeSession(sessionSnapshot)
                onDone?.invoke(true)
                return@fix
            }

            val live = currentInputConnection
            if (live == null) {
                onDone?.invoke(false)
                return@fix
            }

            val now = try {
                live.getExtractedText(ExtractedTextRequest(), 0)
            } catch (_: Exception) {
                null
            }
            val nowText = now?.text?.toString().orEmpty()
            val nowRelative = absoluteStart - (now?.startOffset ?: 0)
            if (nowRelative < 0 ||
                nowRelative + original.length > nowText.length ||
                nowText.substring(nowRelative, nowRelative + original.length) != original
            ) {
                if (!quiet) toast("Text changed — nothing replaced")
                onDone?.invoke(false)
                return@fix
            }

            val replaced = try {
                live.setSelection(absoluteStart, absoluteStart + original.length) &&
                    live.commitText(corrected, 1)
            } catch (_: Exception) {
                false
            }

            if (!replaced) {
                if (!quiet) toast("This field can't replace text")
                onDone?.invoke(false)
                return@fix
            }

            // Keep the cursor after any text that arrived while polishing.
            val end = (now?.startOffset ?: 0) + nowText.length - original.length + corrected.length
            try {
                live.setSelection(end, end)
            } catch (_: Exception) {
            }

            consumeSession(sessionSnapshot)
            Log.d("AiKeyboardFix", "replaced ${original.length} chars with ${corrected.length}")
            if (!quiet) toast("Text fixed ✓")
            onDone?.invoke(true)
        }
    }

    /**
     * Removes only the text this repair consumed. New finals appended while the
     * request was in flight stay buffered for the next polish.
     */
    private fun consumeSession(snapshot: String?) {
        if (snapshot == null) {
            sessionText.setLength(0)
            return
        }
        if (sessionText.startsWith(snapshot)) {
            sessionText.delete(0, snapshot.length)
        } else {
            sessionText.setLength(0)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && root?.isPolishing() == true) {
            toast("Polishing — one moment")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && root?.isPolishing() == true) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    fun backspace() {
        val connection = currentInputConnection ?: return
        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            connection.commitText("", 1)
        } else {
            connection.deleteSurroundingText(1, 0)
        }
    }

    fun enter() {
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_NONE

        if (action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED &&
            sendDefaultEditorAction(true)
        ) {
            return
        }
        commitText("\n")
        root?.autoShift(true)
    }

    fun openSettings() {
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openMicPermission() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_REQUEST_MIC, true)
        )
    }

    fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun updateShift() {
        val caps = currentInputConnection
            ?.getCursorCapsMode(TextUtils.CAP_MODE_SENTENCES) ?: 0
        root?.autoShift(caps != 0)
    }
}
