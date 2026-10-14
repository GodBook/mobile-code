package dev.mobilecode.ide.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

class BuildTimeoutException(timeoutMs: Long): IllegalStateException("BUILD_TIMEOUT：整个构建超过 ${timeoutMs/1000.0} 秒限制")

internal suspend fun <T> withBuildDeadline(timeoutMs: Long, block: suspend CoroutineScope.()->T): T {
    try { return withTimeout(timeoutMs,block) }
    catch(timeout: TimeoutCancellationException) {
        // A caller's cancellation must still mean Stop, not a build failure.
        currentCoroutineContext().ensureActive()
        throw BuildTimeoutException(timeoutMs)
    }
}
