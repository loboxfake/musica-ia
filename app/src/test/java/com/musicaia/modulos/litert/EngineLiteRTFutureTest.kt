package com.musicaia.modulos.litert

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineLiteRTFutureTest {
    @Test fun unwrapsOutOfMemoryErrorWithoutChangingItsIdentity() {
        val error = OutOfMemoryError("simulated native allocation failure")
        val future = CompletableFuture<String>().also { it.completeExceptionally(error) }

        val actual = runCatching { awaitFuture(future) }.exceptionOrNull()

        assertSame(error, actual)
    }

    @Test fun unwrapsNativeLinkageErrorsForProviderFallback() {
        val error = UnsatisfiedLinkError("simulated missing JNI library")
        val future = CompletableFuture<String>().also { it.completeExceptionally(error) }

        val actual = runCatching { awaitFuture(future) }.exceptionOrNull()

        assertSame(error, actual)
    }

    @Test fun preservesCancellationAsCancellation() {
        val error = CancellationException("user cancelled")
        val future = CompletableFuture<String>().also { it.completeExceptionally(error) }

        val actual = runCatching { awaitFuture(future) }.exceptionOrNull()

        assertSame(error, actual)
    }

    @Test fun nativeCleanupAttemptsEveryHandleAndPreservesFailures() {
        val first = IllegalStateException("conversation close failed")
        val second = UnsatisfiedLinkError("engine close failed")
        val closed = mutableListOf<String>()

        val actual = runCatching {
            closeResourcesOrThrow(
                { closed += "conversation"; throw first },
                { closed += "engine"; throw second },
                { closed += "third" }
            )
        }.exceptionOrNull()

        assertEquals(listOf("conversation", "engine", "third"), closed)
        assertSame(first, actual)
        assertEquals(1, first.suppressed.size)
        assertSame(second, first.suppressed.single())
    }

    @Test fun successfulNativeCleanupRunsAllHandles() {
        val closed = mutableListOf<String>()

        closeResourcesOrThrow(
            { closed += "conversation" },
            { closed += "engine" }
        )

        assertEquals(listOf("conversation", "engine"), closed)
    }

    @Test fun wrapsCheckedFailuresWithTheirOriginalCause() {
        val error = java.io.IOException("model file became unavailable")
        val future = CompletableFuture<String>().also { it.completeExceptionally(error) }

        val actual = runCatching { awaitFuture(future) }.exceptionOrNull()

        assertTrue(actual is IllegalStateException)
        assertSame(error, actual?.cause)
        assertEquals("La operación LiteRT falló.", actual?.message)
    }

    @Test fun cancellationAndOutOfMemoryDoNotTriggerGpuToCpuRetry() {
        assertTrue(!LiteRtFallbackPolicy.shouldRetryCpu(CancellationException("user cancelled")))
        assertTrue(!LiteRtFallbackPolicy.shouldRetryCpu(OutOfMemoryError("not enough RAM")))
    }

    @Test fun ordinaryAndNativeLinkageFailuresCanRetryCpu() {
        assertTrue(LiteRtFallbackPolicy.shouldRetryCpu(IllegalStateException("GPU unavailable")))
        assertTrue(LiteRtFallbackPolicy.shouldRetryCpu(UnsatisfiedLinkError("GPU driver unavailable")))
    }

    @Test fun interruptedWaitBecomesCancellationAndRestoresInterruptFlag() {
        val future = object : java.util.concurrent.Future<String> {
            override fun cancel(mayInterruptIfRunning: Boolean) = false
            override fun isCancelled() = false
            override fun isDone() = false
            override fun get(): String = throw InterruptedException("waiting thread interrupted")
            override fun get(timeout: Long, unit: java.util.concurrent.TimeUnit): String =
                throw InterruptedException("waiting thread interrupted")
        }
        Thread.interrupted()
        try {
            val actual = runCatching { awaitFuture(future) }.exceptionOrNull()
            assertTrue(actual is CancellationException)
            assertTrue(actual?.cause is InterruptedException)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
