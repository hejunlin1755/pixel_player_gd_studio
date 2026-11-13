package com.theveloper.pixelplay.presentation.identify

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.identify.IdentifySource
import com.theveloper.pixelplay.data.identify.PcmClip
import com.theveloper.pixelplay.data.identify.PlaybackCaptureService
import com.theveloper.pixelplay.data.identify.RecognizedSong
import com.theveloper.pixelplay.data.identify.SongIdentifyApi
import com.theveloper.pixelplay.data.identify.SongIdentifyBus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val bus: SongIdentifyBus
) : ViewModel() {
    private val _phase = MutableStateFlow<IdentifyPhase>(IdentifyPhase.Ready)
    val phase: StateFlow<IdentifyPhase> = _phase.asStateFlow()
    val level = bus.level

    private var job: Job? = null

    fun listenMicrophone() {
        job?.cancel()
        bus.cancel()
        _phase.value = IdentifyPhase.Listening(IdentifySource.Microphone)
        job = viewModelScope.launch {
            val file = File(context.cacheDir, "identify/mic.wav")
            try {
                val recorded = withContext(Dispatchers.IO) { PcmClip.recordMicrophone(file, bus) }
                if (!recorded) {
                    _phase.value = IdentifyPhase.Ready
                    return@launch
                }
                match(file)
            } catch (_: CancellationException) {
                _phase.value = IdentifyPhase.Ready
            } catch (error: Exception) {
                _phase.value = IdentifyPhase.Failed(error.message ?: "录音失败")
            } finally {
                file.delete()
            }
        }
    }

    fun listenPlayback(resultCode: Int, consent: Intent) {
        job?.cancel()
        bus.cancel()
        val session = UUID.randomUUID().toString()
        val deferred = bus.open(session)
        _phase.value = IdentifyPhase.Listening(IdentifySource.Playback)
        val launch = Intent(context, PlaybackCaptureService::class.java).apply {
            putExtra(PlaybackCaptureService.EXTRA_SESSION, session)
            putExtra(PlaybackCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(PlaybackCaptureService.EXTRA_DATA, consent)
        }
        job = viewModelScope.launch {
            try {
                ContextCompat.startForegroundService(context, launch)
                val file = withTimeoutOrNull(30_000) { deferred.await().getOrThrow() }
                    ?: throw IllegalStateException("没有录到屏幕声音")
                match(file)
                file.delete()
            } catch (_: CancellationException) {
                context.stopService(Intent(context, PlaybackCaptureService::class.java))
                _phase.value = IdentifyPhase.Ready
            } catch (error: Exception) {
                _phase.value = IdentifyPhase.Failed(error.message ?: "录制屏幕声音失败")
            }
        }
    }

    fun fail(message: String) {
        job?.cancel()
        _phase.value = IdentifyPhase.Failed(message)
    }

    fun stop() {
        job?.cancel()
        bus.cancel()
        context.stopService(Intent(context, PlaybackCaptureService::class.java))
        _phase.value = IdentifyPhase.Ready
    }

    private suspend fun match(file: File) {
        _phase.value = IdentifyPhase.Matching
        val song = api.recognize(file)
        _phase.value = if (song == null) {
            IdentifyPhase.Failed("没有认出这首歌，靠近一点或换屏幕声音再试")
        } else {
            IdentifyPhase.Found(song)
        }
    }
}
