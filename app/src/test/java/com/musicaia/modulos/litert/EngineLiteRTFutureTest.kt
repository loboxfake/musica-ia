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
}
