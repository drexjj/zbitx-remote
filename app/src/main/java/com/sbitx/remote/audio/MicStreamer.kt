package com.sbitx.remote.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Captures the phone mic as int16 LE PCM @ 8 kHz mono for the zBitx browser-mic
 * path (browser_mic_input() in sbitx.c).
 *
 * Chunk size matters on zBitx: the radio falls back to its own microphone if
 * no browser frame has arrived for 100 ms (BROWSER_MIC_TIMEOUT). The zBitx web
 * UI therefore sends 256-sample (32 ms) frames; 50 ms frames left only one
 * frame of slack and dropped out on Wi-Fi/cellular jitter.
 *
 * zBitx applies COMP and TX EQ to this audio but NOT the radio's MIC gain, so
 * [gain] is the only level control for phone audio. A soft clipper (same curve
 * as the web UI) keeps peaks above 0.8 FS from splattering.
 */
class MicStreamer(private val onChunk: (ByteArray) -> Unit) {

    companion object {
        const val SAMPLE_RATE = 8_000
        const val CHUNK_SAMPLES = 256            // 32 ms at 8 kHz, as the zBitx web UI
        const val CHUNK_BYTES = CHUNK_SAMPLES * 2
    }

    /** Linear gain applied before sending (1.0 = unity). Safe to change while running. */
    @Volatile var gain: Float = 1.0f

    /** Peak level of the last chunk, 0..1, for a mic meter. */
    @Volatile var peak: Float = 0f
        private set

    private var record: AudioRecord? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    val isRunning get() = job?.isActive == true

    /** Requires RECORD_AUDIO permission to already be granted. Returns false if the mic can't open. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isRunning) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(CHUNK_BYTES * 8)

        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // echo/noise-suppressed source
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf
            )
        }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); return false
        }
        record = rec
        rec.startRecording()

        job = scope.launch {
            val samples = ShortArray(CHUNK_SAMPLES)
            val out = ByteArray(CHUNK_BYTES)
            while (isActive) {
                var off = 0
                while (off < CHUNK_SAMPLES && isActive) {
                    val n = rec.read(samples, off, CHUNK_SAMPLES - off)
                    if (n <= 0) break
                    off += n
                }
                if (off < CHUNK_SAMPLES) continue
                process(samples, out)
                onChunk(out.copyOf())
            }
        }
        return true
    }

    private fun process(samples: ShortArray, out: ByteArray) {
        val g = gain
        var pk = 0f
        for (i in samples.indices) {
            var s = samples[i] / 32768f * g
            // soft clip above 0.8, as in the zBitx web UI's send loop
            if (s > 0.8f) {
                val x = (s - 0.8f) / 0.2f
                s = 0.8f + (s - 0.8f) / (1 + x * x)
            } else if (s < -0.8f) {
                val x = (-s - 0.8f) / 0.2f
                s = -0.8f - (-s - 0.8f) / (1 + x * x)
            }
            if (abs(s) > pk) pk = abs(s)
            val v = (s * 32767f).roundToInt().coerceIn(-32768, 32767)
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        peak = pk
    }

    fun stop() {
        job?.cancel(); job = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        peak = 0f
    }
}
