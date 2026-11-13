package com.theveloper.pixelplay.data.identify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackCaptureService : android.app.Service() {
    @Inject lateinit var bus: SongIdentifyBus

    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = intent?.getStringExtra(EXTRA_SESSION).orEmpty()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val consent = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        startAsForeground()
        if (session.isBlank() || consent == null) {
            bus.complete(session, Result.failure(IllegalStateException("没有拿到录屏许可")))
            stopSelf()
            return START_NOT_STICKY
        }
        val mediaProjection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .getMediaProjection(resultCode, consent)
        projection = mediaProjection
        mediaProjection.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))
        Thread {
            val outcome = runCatching { capture(mediaProjection) }
            bus.complete(session, outcome)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.start()
        return START_NOT_STICKY
    }

    private fun capture(mediaProjection: MediaProjection): File {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(PcmClip.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val capture = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val file = File(cacheDir, "identify/playback.wav")
        try {
            if (!PcmClip.record(file, bus) {
                AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(capture)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(PcmClip.bufferSize())
                    .build()
            }) {
                throw IllegalStateException("录音被取消")
            }
        } finally {
            runCatching { mediaProjection.stop() }
            projection = null
        }
        return file
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, "听歌识曲", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("正在听屏幕里的声音")
            .setContentText("只取声音，不保存画面")
            .setOngoing(true)
            .build()
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    override fun onDestroy() {
        runCatching { projection?.stop() }
        projection = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SESSION = "session"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "consent"
        private const val CHANNEL = "song_identify"
        private const val NOTIFICATION_ID = 47
    }
}
