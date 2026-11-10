package com.musicaia

import android.content.Context
import com.musicaia.modelos.BardoModelAvailability
import com.musicaia.modelos.BardoModelDescriptor
import com.musicaia.modelos.BardoModelLocation
import com.musicaia.modelos.BardoModelProvider
import com.musicaia.modelos.BardoModelRegistry
import com.musicaia.modelos.BardoModelRequest
import com.musicaia.modelos.BardoModelResponse
import com.musicaia.modelos.BardoModelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoModelRegistryTest {
    private class StubProvider(override val descriptor: BardoModelDescriptor) : BardoModelProvider {
        override fun inspect(context: Context) =
            BardoModelAvailability(BardoModelState.PRESENT_UNVERIFIED, "stub")
        override fun load(context: Context) = true
        override fun generate(context: Context, request: BardoModelRequest) =
            BardoModelResponse("ok", descriptor.id, descriptor.version, 1L)
        override fun unload() = Unit
    }

    @Test fun localProviderIsPreferredOverRemote() {
        val remote = StubProvider(BardoModelDescriptor(
            "remote", "1", setOf("chat"), BardoModelLocation.REMOTE,
            "test", null, null, 1000
        ))
        val local = StubProvider(BardoModelDescriptor(
            "local", "1", setOf("chat"), BardoModelLocation.LOCAL,
            "test", null, null, 1
        ))
        val registry = BardoModelRegistry(listOf(remote, local))
        assertEquals(listOf("local"), registry.orderedCandidates("chat", false).map { it.descriptor.id })
        assertEquals(listOf("local", "remote"), registry.orderedCandidates("chat", true).map { it.descriptor.id })
    }

    @Test fun unrelatedProvidersAreNotSelectedAndPriorityBreaksLocalTies() {
        val providers = listOf(
            StubProvider(BardoModelDescriptor("p1", "1", setOf("chat"), BardoModelLocation.LOCAL, "x", null, null, 1)),
            StubProvider(BardoModelDescriptor("p2", "1", setOf("chat"), BardoModelLocation.LOCAL, "x", null, null, 5)),
            StubProvider(BardoModelDescriptor("vision", "1", setOf("vision"), BardoModelLocation.LOCAL, "x", null, null, 100))
        )
        val registry = BardoModelRegistry(providers)
        assertEquals(listOf("p2", "p1"), registry.orderedCandidates("chat", false).map { it.descriptor.id })
        assertTrue(registry.orderedCandidates("audio", true).isEmpty())
    }

    @Test fun generateReturnsTheSelectedProvidersResponse() {
        val provider = StubProvider(BardoModelDescriptor(
            "local", "1", setOf("chat"), BardoModelLocation.LOCAL, "test", null, null, 1
        ))
        val registry = BardoModelRegistry(listOf(provider))
        val response = registry.generate(
            android.content.ContextWrapper(null),
            "chat",
            BardoModelRequest("responde")
        )
        assertEquals("ok", response.text)
        assertEquals("local", response.providerId)
    }

    @Test fun refusesDuplicateProviderIds() {
        val one = StubProvider(BardoModelDescriptor("same", "1", setOf("chat"), BardoModelLocation.LOCAL, "x", null, null))
        val two = StubProvider(BardoModelDescriptor("same", "2", setOf("chat"), BardoModelLocation.LOCAL, "x", null, null))
        assertTrue(runCatching { BardoModelRegistry(listOf(one, two)) }.isFailure)
    }
}
