package dev.mobilecode.ide.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BuildDeadlineTest {
    @Test fun sequentialCompilersShareOneBudget() = runTest {
        var cleaned=false; var completed=false
        val failure=runCatching { withBuildDeadline(100) {
            delay(60)
            try { delay(60); completed=true } finally { cleaned=true }
        } }.exceptionOrNull()
        assertTrue(failure.toString(),failure is BuildTimeoutException)
        assertEquals(100L,currentTime)
        assertTrue(cleaned); assertFalse(completed)
    }

    @Test fun waitingForOutputAfterProcessExitStillConsumesTheBudget() = runTest {
        var readerClosed=false
        val failure=runCatching { withBuildDeadline(100) {
            val reader=launch { try { awaitCancellation() } finally { readerClosed=true } }
            delay(10) // The compiler exited, but an output pipe has not reached EOF.
            reader.join()
        } }.exceptionOrNull()
        assertTrue(failure.toString(),failure is BuildTimeoutException)
        assertTrue(readerClosed)
        assertEquals(100L,currentTime)
    }

    @Test fun userStopIsCancellationRatherThanBuildTimeout() = runTest {
        var failure: Throwable?=null
        val task=launch { try { withBuildDeadline(1000) { awaitCancellation() } } catch(error: Throwable) { failure=error } }
        runCurrent(); task.cancelAndJoin()
        assertTrue(failure.toString(),failure is CancellationException)
        assertFalse(failure is BuildTimeoutException)
    }

    @Test fun successfulStagesReturnTheirActualResult() = runTest {
        assertEquals(17,withBuildDeadline(100) { delay(40); delay(40); 17 })
        assertEquals(80L,currentTime)
    }
}
