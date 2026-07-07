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

/**
 * Captures the phone mic as int16 LE PCM @ 8 kHz mono and hands off ~50 ms
 * chunks (400 samples / 800 bytes), matching the pacing of the sBitx web UI.
 * The firmware's browser_mic_input() jitter-buffers these and upsamples
 * 8 kHz -> 96 kHz (12x) into the SSB TX chain.
 *
 * Keep streaming for the whole time PTT is held: the firmware marks the
 * remote mic inactive after a short timeout without frames.
 */
class MicStreamer(private val onChunk: (ByteArray) -> Unit) {

    companion object {
        const val SAMPLE_RATE = 8_000
        const val CHUNK_SAMPLES = 400            // 50 ms at 8 kHz
        const val CHUNK_BYTES = CHUNK_SAMPLES * 2
    }

    private var record: AudioRecord? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    val isRunning get() = job?.isActive == true

    /** Requires RECORD_AUDIO permission to already be granted. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(CHUNK_BYTES * 4)

        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, // echo/noise-suppressed source
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); return
        }
        record = rec
        rec.startRecording()

        job = scope.launch {
            val buf = ByteArray(CHUNK_BYTES)
            while (isActive) {
                var off = 0
                while (off < CHUNK_BYTES && isActive) {
                    val n = rec.read(buf, off, CHUNK_BYTES - off)
                    if (n <= 0) break
                    off += n
                }
                if (off == CHUNK_BYTES) onChunk(buf.copyOf())
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }
}
