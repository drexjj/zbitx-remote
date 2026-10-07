package com.sbitx.remote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sbitx.remote.audio.MicStreamer
import com.sbitx.remote.audio.RxAudioPlayer
import com.sbitx.remote.net.SbitxClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the SbitxClient, RX playback and PTT mic
 * streaming so the QSO survives the screen turning off.
 */
class RadioService : Service() {

    companion object {
        const val CHANNEL_ID = "zbitx_remote"
        const val NOTIF_ID = 1
        private const val PREFS = "sbitx"
        private const val KEY_MIC_GAIN = "phoneMicGain"
    }

    inner class LocalBinder : Binder() { fun service() = this@RadioService }
    private val binder = LocalBinder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Observable so the UI recomposes when a new connection is created. */
    val client = MutableStateFlow<SbitxClient?>(null)

    /** Phone-side mic gain (zBitx does not apply its MIC control to remote audio). */
    val phoneMicGain = MutableStateFlow(1.0f)

    /** True when the phone mic could not be opened (permission denied / busy). */
    val micError = MutableStateFlow(false)

    private val player = RxAudioPlayer()
    private var mic: MicStreamer? = null
    private var pttReleaseJob: Job? = null
    private var stateWatchJob: Job? = null
    private var audioJob: Job? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannel()
        phoneMicGain.value = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_MIC_GAIN, 1.0f)
    }

    fun connect(host: String, port: Int, useTls: Boolean, pin: String) {
        // Tear down any previous session
        stateWatchJob?.cancel(); audioJob?.cancel()
        pttReleaseJob?.cancel()
        mic?.stop(); mic = null          // the old streamer is bound to the old client
        player.muted = false
        client.value?.shutdown()

        startForegroundCompat("Connecting to $host")
        player.start()

        val c = SbitxClient(host, port, useTls)
        client.value = c
        audioJob = scope.launch { c.rxAudio.collect { player.write(it) } }
        stateWatchJob = scope.launch {
            c.state.collect { s ->
                val text = when (s) {
                    SbitxClient.ConnState.CONNECTED -> "Connected to $host"
                    SbitxClient.ConnState.RECONNECTING -> "Link lost - reconnecting to $host"
                    SbitxClient.ConnState.CONNECTING, SbitxClient.ConnState.LOGIN_SENT -> "Connecting to $host"
                    else -> "Not connected"
                }
                updateNotification(text)
                // a dropped link mid-over: stop the mic and give the speaker back
                if (s != SbitxClient.ConnState.CONNECTED && mic?.isRunning == true) {
                    mic?.stop(); player.muted = false
                }
            }
        }
        c.connect(pin)
    }

    fun setPhoneMicGain(g: Float) {
        val v = g.coerceIn(0.25f, 6f)
        phoneMicGain.value = v
        mic?.gain = v
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_MIC_GAIN, v).apply()
    }

    /** Current phone mic peak 0..1 (for the on-air meter). */
    fun micPeak(): Float = mic?.peak ?: 0f

    /** PTT pressed: key the radio, mute the speaker, stream the phone mic. */
    fun pttDown() {
        val c = client.value ?: return
        pttReleaseJob?.cancel()
        player.muted = true
        if (mic == null) mic = MicStreamer { chunk -> c.sendMicAudio(chunk) }
        mic?.gain = phoneMicGain.value
        micError.value = mic?.start() == false
        c.ptt(true)
    }

    /** PTT released: short tail so the last syllable isn't clipped, then RX. */
    fun pttUp() {
        val c = client.value ?: return
        pttReleaseJob?.cancel()
        pttReleaseJob = scope.launch {
            delay(150)
            mic?.stop()
            c.ptt(false)
            delay(150)          // let the radio switch before audio resumes
            player.muted = false
        }
    }

    fun disconnect() {
        pttReleaseJob?.cancel()
        stateWatchJob?.cancel(); audioJob?.cancel()
        mic?.stop(); mic = null
        player.muted = false
        player.stop()
        client.value?.shutdown(); client.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        mic?.stop()
        player.stop()
        client.value?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(text: String) {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            android.app.PendingIntent.getActivity(
                this, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("zBitx Remote")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { if (open != null) setContentIntent(open) }
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "zBitx Remote", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(Context.NOTIFICATION_SERVICE)
                .let { it as NotificationManager }
                .createNotificationChannel(ch)
        }
    }
}
