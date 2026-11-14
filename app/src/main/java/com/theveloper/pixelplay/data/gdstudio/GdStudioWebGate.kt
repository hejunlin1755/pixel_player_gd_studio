package com.theveloper.pixelplay.data.gdstudio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GdStudioWebGate @Inject constructor() {
    data class Prompt(val url: String, val id: Long)

    private val lock = Mutex()
    private val _prompt = MutableStateFlow<Prompt?>(null)
    val prompt: StateFlow<Prompt?> = _prompt.asStateFlow()
    private var result: CompletableDeferred<String>? = null

    suspend fun open(url: String): String = lock.withLock {
        val deferred = CompletableDeferred<String>()
        result = deferred
        _prompt.value = Prompt(url, System.nanoTime())
        try {
            withTimeout(180_000) { deferred.await() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            throw GdStudioException("人机验证没有完成")
        } finally {
            result = null
            _prompt.value = null
        }
    }

    fun submit(body: String) {
        val waiter = result ?: return
        if (!waiter.isCompleted && GdStudioApi.looksLikePayload(body)) {
            waiter.complete(body)
        }
    }

    fun cancel() {
        val waiter = result ?: return
        if (!waiter.isCompleted) {
            waiter.completeExceptionally(GdStudioException("人机验证已取消"))
        }
    }
}
