package com.k3rn3l.aikeyboard.core

/**
 * Thin JNI surface over the Rust core. Everything interesting (TLS WebSocket,
 * Gemini Live protocol, audio chunking, VAD, reconnects) lives in Rust.
 */
object NativeCore {

    init {
        System.loadLibrary("ai_keyboard_core")
    }

    external fun nativeCreate(configJson: String): Long
    external fun nativeStart(handle: Long)
    external fun nativeStop(handle: Long)
    external fun nativePushAudio(handle: Long, pcm: ShortArray)
    external fun nativePoll(handle: Long): String?
    external fun nativeDestroy(handle: Long)
    external fun nativeCorrect(configJson: String, text: String, context: String): String?
    external fun nativeVersion(): String

    fun version(): String = runCatching { nativeVersion() }.getOrDefault("unknown")
}
