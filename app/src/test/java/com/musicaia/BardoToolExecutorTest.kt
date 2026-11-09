package com.musicaia

import com.musicaia.herramientas.BardoToolDescriptor
import com.musicaia.herramientas.BardoToolExecutor
import com.musicaia.herramientas.BardoToolHandler
import com.musicaia.herramientas.BardoToolRequest
import com.musicaia.herramientas.BardoToolOutput
import com.musicaia.seguridad.BardoPermissionScope
import com.musicaia.seguridad.BardoPrincipal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoToolExecutorTest {
    private class EchoTool(
        id: String = "test.echo",
        scope: BardoPermissionScope = BardoPermissionScope.READ_OWN_PROJECT,
        maxArguments: Int = 2
    ) : BardoToolHandler {
        override val descriptor = BardoToolDescriptor(
            id, "Test tool", scope, maxArguments, 32
        )
        override fun execute(arguments: Map<String, String>) =
            BardoToolOutput(true, arguments.values.joinToString("|"))
    }

    @Test fun registeredSafeToolCanRunForBardo() {
        val executor = BardoToolExecutor(listOf(EchoTool()))
        val result = executor.execute(
            BardoToolRequest(
                "test.echo",
                mapOf("value" to "hello"),
                BardoPrincipal.BARDO
            ),
            100L
        )
        assertTrue(result.error.orEmpty(), result.succeeded)
        assertEquals("hello", result.content)
    }

    @Test fun modelsAndAgentsCannotUseUserConfirmationAsAuthority() {
        val executor = BardoToolExecutor(listOf(EchoTool()))
        val result = executor.execute(
            BardoToolRequest(
                "test.echo",
                mapOf("value" to "hello"),
                BardoPrincipal.MODEL,
                explicitUserConfirmation = true
            ),
            100L
        )
        assertFalse(result.succeeded)
    }

    @Test fun toolRequestCannotSpoofDirectUserConfirmation() {
        val executor = BardoToolExecutor(listOf(EchoTool()))
        val result = executor.execute(
            BardoToolRequest(
                "test.echo",
                mapOf("value" to "must-not-run"),
                BardoPrincipal.USER,
                explicitUserConfirmation = true
            ),
            100L
        )
        assertFalse(result.succeeded)
        assertTrue(result.error.orEmpty().contains("interfaz"))
    }

    @Test fun bardoCannotRunPrivilegedGithubAction() {
        val executor = BardoToolExecutor(listOf(
            EchoTool(scope = BardoPermissionScope.CREATE_PULL_REQUEST)
        ))
        val result = executor.execute(
            BardoToolRequest("test.echo", emptyMap(), BardoPrincipal.BARDO),
            100L
        )
        assertFalse(result.succeeded)
    }

    @Test fun rejectsUnknownToolsOversizedArgumentsAndDuplicateHandlers() {
        val executor = BardoToolExecutor(listOf(EchoTool()))
        assertFalse(executor.execute(
            BardoToolRequest("unknown.tool", emptyMap(), BardoPrincipal.BARDO), 100L
        ).succeeded)
        assertFalse(executor.execute(
            BardoToolRequest("test.echo", mapOf("x" to "a".repeat(33)), BardoPrincipal.BARDO), 100L
        ).succeeded)
        assertTrue(runCatching { BardoToolExecutor(listOf(EchoTool(), EchoTool())) }.isFailure)
    }
}
