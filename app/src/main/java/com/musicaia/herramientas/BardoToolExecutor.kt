package com.musicaia.herramientas

import com.musicaia.seguridad.BardoAuthorityGrant
import com.musicaia.seguridad.BardoAuthorityPolicy
import com.musicaia.seguridad.BardoGrantRevocationStore
import com.musicaia.seguridad.BardoNonceLedger
import com.musicaia.seguridad.BardoPermissionScope
import com.musicaia.seguridad.BardoPrincipal
import com.musicaia.seguridad.BardoSignedAuthorityRequest
import java.util.Base64

data class BardoToolDescriptor(
    val id: String,
    val description: String,
    val requiredScope: BardoPermissionScope,
    val maxArguments: Int = 24,
    val maxValueChars: Int = 8_000
)

data class BardoToolOutput(
    val succeeded: Boolean,
    val content: String,
    val error: String? = null
)

data class BardoJarvisToolEvidence(
    val grant: BardoAuthorityGrant,
    val request: BardoSignedAuthorityRequest
)

interface BardoToolHandler {
    val descriptor: BardoToolDescriptor
    fun execute(arguments: Map<String, String>): BardoToolOutput
}

/**
 * Implementación opcional, escrita y revisada junto al handler, para comprobar
 * su comportamiento con datos locales seguros. Una skill descargada nunca puede
 * implementar esta interfaz porque los paquetes no se cargan como código.
 */
interface BardoToolSmokeTestHandler {
    fun smokeTest(): BardoToolOutput
}

/**
 * La keyring se implementa con el almacenamiento emparejado privado de BARDO.
 * Si no hay una keyring emparejada, las llamadas de JARVIS se rechazan.
 */
interface BardoJarvisKeyring {
    fun pairedUserPublicKeyX509(): ByteArray?
    fun pairedJarvisPublicKeyX509(): ByteArray?
    fun pairedUserFingerprint(): String?
    fun pairedJarvisFingerprint(): String?
    fun nonceLedger(): BardoNonceLedger?
    fun revocationStore(): BardoGrantRevocationStore?
}

data class BardoToolRequest(
    val toolId: String,
    val arguments: Map<String, String>,
    val principal: BardoPrincipal,
    val explicitUserConfirmation: Boolean = false,
    val jarvisEvidence: BardoJarvisToolEvidence? = null
)

/**
 * Ejecutor de herramientas con catálogo cerrado, permisos obligatorios,
 * argumentos acotados y autorización comprobada antes de invocar handlers.
 * No existe una herramienta genérica de shell/comando.
 */
class BardoToolExecutor(
    handlers: List<BardoToolHandler>,
    private val jarvisKeyring: BardoJarvisKeyring? = null
) {
    private val handlersById: Map<String, BardoToolHandler>

    init {
        require(handlers.map { it.descriptor.id }.distinct().size == handlers.size) {
            "Hay identificadores de herramienta duplicados."
        }
        handlers.forEach { handler ->
            require(handler.descriptor.id.matches(Regex("^[a-z][a-z0-9._-]{2,63}$"))) {
                "ID de herramienta no válido."
            }
            require(handler.descriptor.maxArguments in 0..64 &&
                handler.descriptor.maxValueChars in 1..32_000) {
                "Límites de argumentos no válidos para " + handler.descriptor.id
            }
        }
        handlersById = handlers.associateBy { it.descriptor.id }
    }

    fun execute(request: BardoToolRequest, nowMillis: Long): BardoToolOutput {
        if (request.principal == BardoPrincipal.USER) {
            return blocked(
                "Una confirmación directa del usuario debe originarse en la interfaz; " +
                    "no se acepta como identidad dentro de una solicitud de herramienta."
            )
        }
        val handler = handlersById[request.toolId]
            ?: return blocked("La herramienta no está registrada: " + request.toolId)
        val descriptor = handler.descriptor
        val arguments = validateArguments(request.arguments, descriptor)
            ?: return blocked("Los argumentos superan el esquema o los límites de la herramienta.")

        val payload = canonicalPayload(descriptor.id, arguments)
        val evidence = request.jarvisEvidence
        val keyring = if (request.principal == BardoPrincipal.JARVIS) jarvisKeyring else null
        val decision = BardoAuthorityPolicy.authorize(
            principal = request.principal,
            scope = descriptor.requiredScope,
            payload = payload,
            nowMillis = nowMillis,
            explicitUserConfirmation = request.explicitUserConfirmation,
            grant = evidence?.grant,
            request = evidence?.request,
            pairedUserPublicKeyX509 = keyring?.pairedUserPublicKeyX509(),
            pairedJarvisPublicKeyX509 = keyring?.pairedJarvisPublicKeyX509(),
            pairedUserFingerprint = keyring?.pairedUserFingerprint(),
            pairedJarvisFingerprint = keyring?.pairedJarvisFingerprint(),
            nonceLedger = keyring?.nonceLedger(),
            revocationStore = keyring?.revocationStore()
        )
        if (!decision.allowed) return blocked(decision.reason)

        return try {
            val result = handler.execute(arguments)
            if (result.content.length > MAX_OUTPUT_CHARS) {
                blocked("La herramienta excedió el tamaño máximo de salida.")
            } else {
                result
            }
        } catch (error: OutOfMemoryError) {
            throw error
        } catch (error: Exception) {
            BardoToolOutput(
                succeeded = false,
                content = "",
                error = error.message ?: error.javaClass.simpleName
            )
        }
    }

    fun registeredTools(): List<BardoToolDescriptor> =
        handlersById.values.map { it.descriptor }.sortedBy { it.id }

    /**
     * Acciones que pueden incluirse en una skill instalable. Una acción solo
     * es elegible si el APK incorpora un smoke-test escrito junto al handler;
     * no basta con que figure en el catálogo general.
     */
    fun registeredSkillToolIds(): Set<String> =
        handlersById.values
            .filter { it is BardoToolSmokeTestHandler }
            .map { it.descriptor.id }
            .toSortedSet()

    /**
     * Ejecuta únicamente una prueba interna escrita en el código del handler.
     * No acepta argumentos ni código de la skill. Si el handler no declara
     * explícitamente un smoke-test, la acción no puede habilitarse como skill.
     */
    fun runTrustedSmokeTest(toolId: String): BardoToolOutput {
        val handler = handlersById[toolId]
            ?: return blocked("La herramienta no está registrada: $toolId")
        val smoke = handler as? BardoToolSmokeTestHandler
            ?: return blocked("La herramienta no tiene un smoke-test de confianza: $toolId")
        return try {
            val result = smoke.smokeTest()
            if (!result.succeeded) result
            else if (result.content.length > MAX_OUTPUT_CHARS) {
                blocked("El smoke-test excedió el tamaño máximo de salida.")
            } else result
        } catch (error: OutOfMemoryError) {
            throw error
        } catch (error: Exception) {
            BardoToolOutput(
                succeeded = false,
                content = "",
                error = error.message ?: error.javaClass.simpleName
            )
        }
    }

    private fun validateArguments(
        arguments: Map<String, String>,
        descriptor: BardoToolDescriptor
    ): Map<String, String>? {
        if (arguments.size > descriptor.maxArguments) return null
        if (arguments.keys.any { !it.matches(Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$")) }) return null
        if (arguments.values.any { it.length > descriptor.maxValueChars }) return null
        val total = arguments.entries.sumOf { it.key.length.toLong() + it.value.length.toLong() }
        if (total > MAX_INPUT_CHARS) return null
        return arguments.toSortedMap()
    }

    private fun canonicalPayload(toolId: String, arguments: Map<String, String>): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return buildString {
            append("BARDO-TOOL-V1\n")
            append(toolId)
            append('\n')
            arguments.toSortedMap().forEach { (key, value) ->
                append(key)
                append('=')
                append(encoder.encodeToString(value.toByteArray(Charsets.UTF_8)))
                append('\n')
            }
        }
    }

    private fun blocked(message: String) = BardoToolOutput(
        succeeded = false,
        content = "",
        error = message
    )

    companion object {
        const val MAX_INPUT_CHARS = 32_000L
        const val MAX_OUTPUT_CHARS = 64_000
    }
}
