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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the SbitxClient, RX playback and PTT mic
 * streaming so the QSO survives the screen turning off.
 */
class RadioService : Service() {

    companion object {
        const val CHANNEL_ID = "sbitx_remote"
        const val NOTIF_ID = 1
    }

    inner class LocalBinder : Binder() { fun service() = this@RadioService }
    private val binder = LocalBinder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var client: SbitxClient? = null
        private set
    private val player = RxAudioPlayer()
    private var mic: MicStreamer? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    fun connect(host: String, port: Int, useTls: Boolean, pin: String) {
        // Tear down any previous session
        client?.shutdown()

        startForegroundCompat()
        player.start()

        val c = SbitxClient(host, port, useTls)
        client = c
        scope.launch { c.rxAudio.collect { player.write(it) } }
        c.connect(pin)
    }

    /** PTT pressed: switch radio to TX, then stream phone mic continuously. */
    fun pttDown() {
        val c = client ?: return
        c.ptt(true)
        if (mic == null) mic = MicStreamer { chunk -> c.sendMicAudio(chunk) }
        mic?.start()
    }

    /** PTT released: stop mic (short tail lets buffered audio flush), then RX. */
    fun pttUp() {
        val c = client ?: return
        scope.launch {
            delay(150)          // flush tail so last syllable isn't clipped
            mic?.stop()
            c.ptt(false)
        }
    }

    fun disconnect() {
        mic?.stop(); mic = null
        player.stop()
        client?.shutdown(); client = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        mic?.stop()
        player.stop()
        client?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("sBitx Remote")
            .setContentText("Connected to radio")
            .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setOngoing(true)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "sBitx Remote", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(Context.NOTIFICATION_SERVICE)
                .let { it as NotificationManager }
                .createNotificationChannel(ch)
        }
    }
}
