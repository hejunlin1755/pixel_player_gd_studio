package com.theveloper.pixelplay.presentation.identify

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.identify.IdentifyHistoryStore
import com.theveloper.pixelplay.data.identify.IdentifySource
import com.theveloper.pixelplay.data.identify.PlaybackCaptureService
import com.theveloper.pixelplay.data.identify.RecognizedSong
import com.theveloper.pixelplay.data.identify.SongIdentifyApi
import com.theveloper.pixelplay.data.identify.SongIdentifyBus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import javax.inject.Inject

sealed interface IdentifyPhase {
    data object Ready : IdentifyPhase
    data class Listening(val source: IdentifySource) : IdentifyPhase
    data object Matching : IdentifyPhase
    data class Found(val song: RecognizedSong) : IdentifyPhase
    data class Failed(val message: String) : IdentifyPhase
}

@HiltViewModel
class SongIdentifyViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: SongIdentifyApi,
    private val bus: SongIdentifyBus,
    private val historyStore: IdentifyHistoryStore
) : ViewModel() {
    private val _phase = MutableStateFlow<IdentifyPhase>(IdentifyPhase.Ready)
    val phase: StateFlow<IdentifyPhase> = _phase.asStateFlow()
    val history = historyStore.records
    val level = bus.level

    private var job: Job? = null
    private var activeSession: String? = null

    fun listenMicrophone() {
        val session = begin(IdentifySource.Microphone)
        val deferred = bus.open(session)
        ContextCompat.startForegroundService(context, Intent(context, PlaybackCaptureService::class.java).apply {
            action = PlaybackCaptureService.ACTION_MIC
            putExtra(PlaybackCaptureService.EXTRA_SESSION, session)
        })
        job = viewModelScope.launch {
            try {
                val file = withTimeoutOrNull(30_000) { deferred.await().getOrThrow() }
                    ?: throw IllegalStateException("没有录到声音")
                match(file)
                file.delete()
            } catch (_: CancellationException) {
                if (activeSession == session) _phase.value = IdentifyPhase.Ready
            } catch (error: Exception) {
                _phase.value = IdentifyPhase.Failed(error.message ?: "录音失败")
            } finally {
                release(session)
            }
        }
    }

    fun listenPlayback(resultCode: Int, consent: Intent) {
        val session = begin(IdentifySource.Playback)
        val deferred = bus.open(session)
        ContextCompat.startForegroundService(context, Intent(context, PlaybackCaptureService::class.java).apply {
            action = PlaybackCaptureService.ACTION_PLAYBACK
            putExtra(PlaybackCaptureService.EXTRA_SESSION, session)
            putExtra(PlaybackCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(PlaybackCaptureService.EXTRA_DATA, consent)
        })
        job = viewModelScope.launch {
            try {
                val file = withTimeoutOrNull(30_000) { deferred.await().getOrThrow() }
                    ?: throw IllegalStateException("没有录到设备里正在放的声音")
                match(file)
                file.delete()
            } catch (_: CancellationException) {
                if (activeSession == session) _phase.value = IdentifyPhase.Ready
            } catch (error: Exception) {
                _phase.value = IdentifyPhase.Failed(error.message ?: "录制设备声音失败")
            } finally {
                release(session)
            }
        }
    }

    fun fail(message: String) {
        stop()
        _phase.value = IdentifyPhase.Failed(message)
    }

    fun stop() {
        activeSession = null
        job?.cancel()
        bus.cancel()
        context.stopService(Intent(context, PlaybackCaptureService::class.java))
        _phase.value = IdentifyPhase.Ready
    }

    private fun begin(source: IdentifySource): String {
        val session = UUID.randomUUID().toString()
        activeSession = session
        job?.cancel()
        bus.cancel()
        _phase.value = IdentifyPhase.Listening(source)
        return session
    }

    private fun release(session: String) {
        if (activeSession == session) {
            activeSession = null
            bus.cancel()
            context.stopService(Intent(context, PlaybackCaptureService::class.java))
        }
    }

    private suspend fun match(file: File) {
        _phase.value = IdentifyPhase.Matching
        ContextCompat.startForegroundService(context, Intent(context, PlaybackCaptureService::class.java).apply {
            action = PlaybackCaptureService.ACTION_STATUS
            putExtra(PlaybackCaptureService.EXTRA_TEXT, "正在发给在线识别")
        })
        val song = api.recognize(file)
        if (song == null) {
            _phase.value = IdentifyPhase.Failed("没有认出这首歌，靠近一点或换屏幕声音再试")
        } else {
            historyStore.add(song)
            _phase.value = IdentifyPhase.Found(song)
        }
    }
}
