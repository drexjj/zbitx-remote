package com.sbitx.remote.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Plays the radio's RX audio: int16 little-endian PCM, 16 kHz mono.
 *
 * Latency strategy: low-latency AudioTrack with a modest (2x min) buffer,
 * plus backlog supervision - if queued-but-unplayed audio exceeds
 * MAX_BACKLOG_MS (network burst after a stall), the oldest excess is
 * dropped so playback snaps back to near-live instead of staying
 * permanently behind. Occasional small skips on a bad link are the
 * accepted trade-off for staying live.
 */
class RxAudioPlayer {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_BACKLOG_MS = 300          // drop threshold
        const val TARGET_BACKLOG_MS = 120       // trim down to this
        const val BYTES_PER_FRAME = 2           // 16-bit mono
    }

    private var track: AudioTrack? = null
    private var framesWritten = 0L

    fun start() {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        framesWritten = 0
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
            .also { it.play() }
    }

    /** Feed a binary frame straight from the WebSocket. */
    fun write(pcm: ByteArray) {
        val t = track ?: return
        var data = pcm

        // Backlog supervision: frames queued = written - played
        val played = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val backlogFrames = framesWritten - played
        val backlogMs = backlogFrames * 1000 / SAMPLE_RATE
        if (backlogMs > MAX_BACKLOG_MS) {
            // Drop from the FRONT of this chunk so we jump toward live.
            val excessFrames = (backlogMs - TARGET_BACKLOG_MS) * SAMPLE_RATE / 1000
            val dropBytes = (excessFrames * BYTES_PER_FRAME)
                .coerceAtMost((pcm.size - BYTES_PER_FRAME).toLong()).toInt()
            if (dropBytes >= BYTES_PER_FRAME) {
                data = pcm.copyOfRange(dropBytes - (dropBytes % BYTES_PER_FRAME), pcm.size)
            }
        }

        t.write(data, 0, data.size)
        framesWritten += data.size / BYTES_PER_FRAME
    }

    fun stop() {
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
        framesWritten = 0
    }
}
