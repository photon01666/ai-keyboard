package com.k3rn3l.aikeyboard.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.max

/**
 * 16 kHz mono PCM16 capture, delivered in ~20 ms frames. The Rust core does the
 * 100 ms chunking, metering and voice activity detection.
 */
class AudioCapture(private val onPcm: (ShortArray) -> Unit) {

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    fun start(): Boolean {
        if (running) return true

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false

        val recorder = open(MediaRecorder.AudioSource.VOICE_RECOGNITION, minBuffer)
            ?: open(MediaRecorder.AudioSource.MIC, minBuffer)
            ?: return false

        record = recorder
        running = true
        thread = Thread({ readLoop(recorder) }, "aikeyboard-mic").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun open(source: Int, minBuffer: Int): AudioRecord? {
        return try {
            val recorder = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuffer * 2, FRAME_SAMPLES * 2 * 8))
                .build()

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return null
            }
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                recorder.release()
                return null
            }
            recorder
        } catch (_: Exception) {
            null
        }
    }

    private fun readLoop(recorder: AudioRecord) {
        val buffer = ShortArray(FRAME_SAMPLES)
        try {
            while (running) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    onPcm(buffer.copyOf(read))
                } else if (read < 0) {
                    break
                }
            }
        } catch (_: Exception) {
            // Releasing the recorder concurrently can make read() fail; that's fine.
        }
    }

    fun stop() {
        if (!running && record == null) return
        running = false
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null
        thread?.let { worker ->
            if (worker !== Thread.currentThread()) {
                try {
                    worker.join(500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        thread = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 320 // 20 ms
    }
}
