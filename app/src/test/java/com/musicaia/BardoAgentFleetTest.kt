package com.musicaia

import com.musicaia.modelos.*
import com.musicaia.modelo.BardoResourceCoordinator
import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BardoAgentFleetTest {
    private class FakeInvoker(
        override val descriptor: BardoModelDescriptor,
        private val responseText: String = "respuesta",
        private val fail: Boolean = false
    ) : BardoAgentInvoker {
        val calls = AtomicInteger()
        override fun complete(prompt: String): BardoModelResponse {
            calls.incrementAndGet()
            check(prompt.contains("subordinado al núcleo BARDO"))
            if (fail) error("fallo simulado")
            return BardoModelResponse(responseText, descriptor.id, descriptor.version, 1)
        }
    }

    private fun descriptor(
        id: String,
        location: BardoModelLocation = BardoModelLocation.LOCAL
    ) = BardoModelDescriptor(
        id = id,
        version = "v1",
        capabilities = setOf("code_analysis"),
        location = location,
        license = "Apache-2.0",
        modelSha256 = null,
        modelSizeBytes = null
    )

    private fun profile(id: String, providerId: String) = BardoAgentProfile(
        id = id,
        name = id,
        providerId = providerId,
        role = BardoAgentRole.SOFTWARE_ENGINEER,
        capabilities = setOf("code_analysis")
    )

    private fun task(allowRemote: Boolean = false) = BardoAgentTask(
        taskId = "task-1",
        capability = "code_analysis",
        goal = "Revisar una función",
        allowRemoteModels = allowRemote
    )

    @Test fun consultsDifferentLocalProvidersAndKeepsProvenance() {
        val gemma = FakeInvoker(descriptor("gemma-local"), "hallazgo uno")
        val second = FakeInvoker(descriptor("second-local"), "hallazgo dos")
        val fleet = BardoAgentFleet(
            listOf(gemma, second),
            listOf(profile("engineer", "gemma-local"), profile("reviewer", "second-local"))
        )

        val report = fleet.consult(task())

        assertEquals(2, report.attemptedAgents)
        assertEquals(2, report.successfulAgents)
        assertEquals(setOf("gemma-local", "second-local"),
            report.successfulContributions.map { it.providerId }.toSet())
        assertTrue(report.successfulContributions.all { it.content.isNotBlank() })
    }

    @Test fun profilesForSameProviderAreNotCountedAsIndependentModels() {
        val gemma = FakeInvoker(descriptor("gemma-local"))
        val fleet = BardoAgentFleet(
            listOf(gemma),
            listOf(profile("planner", "gemma-local"), profile("reviewer", "gemma-local"))
        )

        val report = fleet.consult(task())

        assertEquals(1, report.attemptedAgents)
        assertEquals(1, gemma.calls.get())
    }

    @Test fun remoteProviderIsDeniedEvenWhenTaskRequestsItWithoutGateApproval() {
        val remote = FakeInvoker(descriptor("remote-model", BardoModelLocation.REMOTE))
        val fleet = BardoAgentFleet(
            listOf(remote),
            listOf(profile("remote-specialist", "remote-model"))
        )

        val report = fleet.consult(task(allowRemote = true))

        assertEquals(0, report.attemptedAgents)
        assertEquals(0, remote.calls.get())
    }

    @Test fun remoteProviderRequiresTaskFlagAndIndependentGate() {
        val remote = FakeInvoker(descriptor("remote-model", BardoModelLocation.REMOTE))
        val fleet = BardoAgentFleet(
            listOf(remote),
            listOf(profile("remote-specialist", "remote-model")),
            remoteGate = BardoRemoteAgentGate { _, _ -> true }
        )

        assertEquals(0, fleet.consult(task(allowRemote = false)).attemptedAgents)
        assertEquals(1, fleet.consult(task(allowRemote = true)).successfulAgents)
    }

    @Test fun failedAgentIsRecordedWithoutStoppingOtherModels() {
        val failed = FakeInvoker(descriptor("failed-model"), fail = true)
        val working = FakeInvoker(descriptor("working-model"), "resultado")
        val fleet = BardoAgentFleet(
            listOf(failed, working),
            listOf(profile("first", "failed-model"), profile("second", "working-model"))
        )

        val report = fleet.consult(task())

        assertEquals(2, report.attemptedAgents)
        assertEquals(1, report.successfulAgents)
        assertFalse(report.contributions.first().succeeded)
        assertTrue(report.contributions.last().succeeded)
    }

    @Test fun providerAgentInvocationRunsInsideTextResourceLease() {
        val modelDescriptor = descriptor("resource-aware")
        val provider = object : BardoModelProvider {
            override val descriptor = modelDescriptor
            override fun inspect(context: Context) =
                BardoModelAvailability(BardoModelState.PRESENT_UNVERIFIED, "present")
            override fun load(context: Context) = true
            override fun generate(context: Context, request: BardoModelRequest): BardoModelResponse {
                assertEquals(
                    BardoResourceCoordinator.Resource.TEXT_MODEL,
                    BardoResourceCoordinator.currentResourceForCurrentThread()
                )
                return BardoModelResponse("respuesta protegida", descriptor.id, descriptor.version, 1L)
            }
            override fun unload() = Unit
        }
        val context = ContextWrapper(null)
        val invoker = BardoProviderAgentInvoker(context, provider)
        val response = invoker.complete("analiza este cambio")
        assertEquals("respuesta protegida", response.text)
        assertEquals(null, BardoResourceCoordinator.currentResourceForCurrentThread())
    }

    @Test fun rejectsProfileWithUnknownProvider() {
        assertThrows(IllegalArgumentException::class.java) {
            BardoAgentFleet(emptyList(), listOf(profile("orphan", "missing")))
        }
    }
}
