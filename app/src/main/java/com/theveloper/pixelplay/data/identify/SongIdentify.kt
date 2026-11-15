package com.theveloper.pixelplay.data.identify

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

enum class IdentifySource {
    Microphone,
    Playback
}

data class RecognizedSong(
    val title: String,
    val artist: String,
    val album: String?,
    val artworkUrl: String?
)

@Singleton
class SongIdentifyBus @Inject constructor() {
    val level = MutableStateFlow(0f)
    val generation = AtomicInteger(0)

    private val sessions = HashMap<String, CompletableDeferred<Result<File>>>()

    @Synchronized
    fun open(id: String): CompletableDeferred<Result<File>> {
        level.value = 0f
        val deferred = CompletableDeferred<Result<File>>()
        sessions[id] = deferred
        return deferred
    }

    @Synchronized
    fun complete(id: String, result: Result<File>) {
        sessions.remove(id)?.complete(result)
    }

    fun cancel() {
        generation.incrementAndGet()
        level.value = 0f
        synchronized(this) {
            val pending = sessions.values.toList()
            sessions.clear()
            pending.forEach { it.cancel() }
        }
    }
}

internal object PcmClip {
    const val SAMPLE_RATE = 44100
    const val SECONDS = 12

    fun recordMicrophone(destination: File, bus: SongIdentifyBus): Boolean {
        return record(
            destination = destination,
            bus = bus,
            createRecorder = {
                val min = bufferSize()
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    min
                )
            }
        )
    }

    fun bufferSize(sampleRate: Int = SAMPLE_RATE, channels: Int = 1): Int {
        val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val min = AudioRecord.getMinBufferSize(
            sampleRate,
            mask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        return if (min > 0) min * 2 else 8192
    }

    fun record(
        destination: File,
        bus: SongIdentifyBus,
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = 1,
        silentMessage: String = "没有录到声音",
        rejectSilence: Boolean = false,
        createRecorder: () -> AudioRecord
    ): Boolean {
        synchronized(lock) {
        val token = bus.generation.get()
        val recorder = createRecorder()
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("录音设备没有准备好")
        }
        val buffer = ByteArray(bufferSize(sampleRate, channels))
        val pcm = java.io.ByteArrayOutputStream()
        var energy = 0.0
        var reads = 0
        val deadline = android.os.SystemClock.elapsedRealtime() + SECONDS * 1000L
        try {
            recorder.startRecording()
            while (bus.generation.get() == token && android.os.SystemClock.elapsedRealtime() < deadline) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val mono = if (channels == 1) buffer.copyOf(read) else downmix(buffer, read)
                    pcm.write(mono)
                    val level = levelOf(mono, mono.size)
                    energy += level
                    reads++
                    bus.level.value = level
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            bus.level.value = 0f
        }
        if (bus.generation.get() != token) return false
        val bytes = pcm.toByteArray()
        if (bytes.size < sampleRate || (rejectSilence && (reads == 0 || energy / reads < 0.01))) {
            throw IllegalStateException(silentMessage)
        }
        writeWav(destination, bytes, sampleRate)
        return true
        }
    }

    private fun downmix(buffer: ByteArray, length: Int): ByteArray {
        val frames = length / 4
        val mono = ByteArray(frames * 2)
        var input = 0
        var output = 0
        repeat(frames) {
            val left = sampleAt(buffer, input)
            val right = sampleAt(buffer, input + 2)
            val mixed = ((left + right) / 2).toInt().toShort()
            mono[output] = (mixed.toInt() and 0xff).toByte()
            mono[output + 1] = ((mixed.toInt() shr 8) and 0xff).toByte()
            input += 4
            output += 2
        }
        return mono
    }

    private fun sampleAt(buffer: ByteArray, index: Int): Int {
        val sample = (buffer[index].toInt() and 0xff) or (buffer[index + 1].toInt() shl 8)
        return sample.toShort().toInt()
    }

    private fun levelOf(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < length) {
            val sample = (buffer[index].toInt() and 0xff) or (buffer[index + 1].toInt() shl 8)
            val signed = sample.toShort().toInt()
            sum += signed * signed.toDouble()
            count++
            index += 2
        }
        if (count == 0) return 0f
        val rms = sqrt(sum / count) / 32768.0
        return (rms * 4.0).toFloat().coerceIn(0f, 1f)
    }

    fun writeWav(destination: File, pcm: ByteArray, sampleRate: Int) {
        destination.parentFile?.mkdirs()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".encodeToByteArray())
        header.putInt(36 + pcm.size)
        header.put("WAVE".encodeToByteArray())
        header.put("fmt ".encodeToByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".encodeToByteArray())
        header.putInt(pcm.size)
        FileOutputStream(destination).use { output ->
            output.write(header.array())
            output.write(pcm)
        }
    }

    private val lock = Any()
}
