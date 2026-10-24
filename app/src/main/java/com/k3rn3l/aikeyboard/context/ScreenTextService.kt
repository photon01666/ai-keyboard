package com.k3rn3l.aikeyboard.context

import android.accessibilityservice.AccessibilityService
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Optional, user-granted screen reader. Only used when the user presses "Fix"
 * or when auto-fix after dictation runs. Password fields, hidden nodes and our
 * own windows are skipped. Nothing is stored to disk.
 */
class ScreenTextService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Deliberately quiet: context is read on demand, not continuously.
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun readScreen(): String {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return ""

        val lines = LinkedHashSet<String>()
        walk(root, lines, 0)
        return lines.joinToString("\n").take(MAX_CHARS)
    }

    private fun walk(node: AccessibilityNodeInfo?, out: MutableSet<String>, depth: Int) {
        if (node == null || depth > MAX_DEPTH || out.size >= MAX_NODES) return
        try {
            if (!node.isVisibleToUser) return
            if (node.isPassword) return
            if (node.packageName == packageName) return

            node.text?.toString()?.trim()?.let { add(it, out) }
            node.contentDescription?.toString()?.trim()?.let { add(it, out) }

            for (index in 0 until node.childCount) {
                walk(node.getChild(index), out, depth + 1)
            }
        } catch (_: Exception) {
            // Window content can disappear while we walk it.
        }
    }

    private fun add(value: String, out: MutableSet<String>) {
        if (value.isEmpty() || value.length > 500) return
        if (out.size >= MAX_NODES) return
        out.add(value)
    }

    companion object {
        private const val MAX_CHARS = 4_000
        private const val MAX_NODES = 160
        private const val MAX_DEPTH = 24

        @Volatile
        private var instance: ScreenTextService? = null

        fun isConnected(): Boolean = instance != null

        /** Must be called from the main thread; returns an empty string if unavailable. */
        fun read(): String {
            if (Looper.myLooper() != Looper.getMainLooper()) return ""
            return try {
                instance?.readScreen().orEmpty()
            } catch (_: Exception) {
                ""
            }
        }
    }
}
