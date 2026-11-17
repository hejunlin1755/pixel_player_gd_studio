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
    @Volatile private var result: CompletableDeferred<Unit>? = null
    @Volatile private var clearedAt = 0L

    suspend fun awaitClearance() {
        if (System.currentTimeMillis() - clearedAt < CLEAR_WINDOW_MS) return
        lock.withLock { showPrompt() }
    }

    private suspend fun showPrompt() {
        if (System.currentTimeMillis() - clearedAt < CLEAR_WINDOW_MS) return
        val deferred = CompletableDeferred<Unit>()
        result = deferred
        _prompt.value = Prompt(GdStudioApi.SITE, System.nanoTime())
        try {
            withTimeout(180_000) { deferred.await() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            throw GdStudioException("人机验证没有完成")
        } finally {
            result = null
            _prompt.value = null
        }
    }

    fun finished() {
        clearedAt = System.currentTimeMillis()
        val waiter = result ?: return
        if (!waiter.isCompleted) waiter.complete(Unit)
    }

    fun cancel() {
        val waiter = result ?: return
        if (!waiter.isCompleted) {
            waiter.completeExceptionally(GdStudioException("人机验证已取消"))
        }
    }

    companion object {
        private const val CLEAR_WINDOW_MS = 15_000L
    }
}
