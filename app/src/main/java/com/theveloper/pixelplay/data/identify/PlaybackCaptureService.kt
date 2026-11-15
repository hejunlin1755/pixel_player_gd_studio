package com.theveloper.pixelplay.data.identify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import com.theveloper.pixelplay.R
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackCaptureService : android.app.Service() {
    @Inject lateinit var bus: SongIdentifyBus

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private val runId = AtomicInteger(0)
    private var foregroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    private var notice = "正在听歌识曲"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                runId.incrementAndGet()
                bus.cancel()
                releaseProjection()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STATUS -> {
                notice = intent.getStringExtra(EXTRA_TEXT)?.ifBlank { null } ?: notice
                startAsForeground(notice, foregroundType)
                return START_NOT_STICKY
            }
            ACTION_MIC -> holdAndRecord(intent, playback = false)
            ACTION_PLAYBACK -> holdAndRecord(intent, playback = true)
            else -> {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun holdAndRecord(intent: Intent, playback: Boolean) {
        val session = intent.getStringExtra(EXTRA_SESSION).orEmpty()
        val id = runId.incrementAndGet()
        notice = if (playback) "正在听设备里正在放的声音" else "正在用麦克风听周围的声音"
        val type = if (playback) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            startAsForeground(notice, type)
        } catch (error: Exception) {
            bus.complete(session, Result.failure(IllegalStateException(error.message ?: "前台通知没有发出去")))
            stopSelf()
            return
        }
        if (session.isBlank()) {
            bus.complete(session, Result.failure(IllegalStateException("没有开始听歌识曲")))
            return
        }
        Thread {
            val outcome = runCatching {
                if (playback) capturePlayback(intent, id) else captureMicrophone(session)
            }
            if (runId.get() == id) bus.complete(session, outcome)
        }.start()
    }

    private fun captureMicrophone(session: String): File {
        val file = File(cacheDir, "identify/$session.wav")
        if (!PcmClip.recordMicrophone(file, bus)) {
            file.delete()
            throw IllegalStateException("录音被取消")
        }
        return file
    }

    private fun capturePlayback(intent: Intent, id: Int): File {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val consent = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        } ?: throw IllegalStateException("没有拿到录屏许可")
        val mediaProjection = try {
            (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                .getMediaProjection(resultCode, consent)
        } catch (error: Exception) {
            throw IllegalStateException(error.message ?: "没有拿到录屏许可")
        } ?: throw IllegalStateException("没有拿到录屏许可")
        projection = mediaProjection
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                bus.cancel()
            }
        }
        projectionCallback = callback
        mediaProjection.registerCallback(callback, Handler(Looper.getMainLooper()))
        return try {
            recordPlayback(mediaProjection, intent.getStringExtra(EXTRA_SESSION).orEmpty())
        } finally {
            runCatching { releaseProjection() }
            if (runId.get() == id) {
                runCatching { startAsForeground(notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) }
            }
        }
    }

    private fun recordPlayback(mediaProjection: MediaProjection, session: String): File {
        val attempts = listOf(
            48_000 to AudioFormat.CHANNEL_IN_STEREO,
            44_100 to AudioFormat.CHANNEL_IN_STEREO,
            44_100 to AudioFormat.CHANNEL_IN_MONO
        )
        var last: Exception? = null
        val file = File(cacheDir, "identify/$session.wav")
        for ((rate, mask) in attempts) {
            try {
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(mask)
                    .build()
                val capture = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val channels = if (mask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
                val recorded = PcmClip.record(
                    destination = file,
                    bus = bus,
                    sampleRate = rate,
                    channels = channels,
                    silentMessage = "没有录到设备里正在放的声音。这个播放器自己放的歌抓不到，请用麦克风，或先在别的应用里播放",
                    rejectSilence = true
                ) {
                    AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(capture)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(PcmClip.bufferSize(rate, channels))
                        .build()
                }
                if (!recorded) {
                    file.delete()
                    throw IllegalStateException("录音被取消")
                }
                return file
            } catch (error: Exception) {
                val message = error.message.orEmpty()
                if (message == "录音被取消" || message.startsWith("没有录到")) throw error
                last = error
            }
        }
        throw last ?: IllegalStateException("录音设备没有准备好")
    }

    private fun startAsForeground(text: String, type: Int) {
        foregroundType = type
        notice = text
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, "听歌识曲", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
            description = "识别过程中保持通知，避免被系统关掉"
        }
        manager.createNotificationChannel(channel)
        startForeground(NOTIFICATION_ID, buildNotification(text), type)
    }

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, PlaybackCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.monochrome_player)
            .setContentTitle("正在听歌识曲")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(0, "停止", stop)
            .build()
    }

    private fun releaseProjection() {
        val current = projection
        val callback = projectionCallback
        projection = null
        projectionCallback = null
        if (current != null && callback != null) {
            runCatching { current.unregisterCallback(callback) }
        }
        runCatching { current?.stop() }
    }

    override fun onDestroy() {
        releaseProjection()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SESSION = "session"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "consent"
        const val EXTRA_TEXT = "text"
        const val ACTION_MIC = "com.theveloper.pixelplay.identify.MIC"
        const val ACTION_PLAYBACK = "com.theveloper.pixelplay.identify.PLAYBACK"
        const val ACTION_STATUS = "com.theveloper.pixelplay.identify.STATUS"
        const val ACTION_STOP = "com.theveloper.pixelplay.identify.STOP"
        private const val CHANNEL = "song_identify_hold"
        private const val NOTIFICATION_ID = 47
    }
}
