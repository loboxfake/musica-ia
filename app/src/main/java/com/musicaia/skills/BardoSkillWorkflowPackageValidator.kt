package com.musicaia.skills

import org.json.JSONObject
import java.io.File
import java.io.InputStream

data class BardoSkillWorkflowPackageValidation(
    val accepted: Boolean,
    val errors: List<String>,
    val stepCount: Int,
    val actionIds: Set<String>,
    val requiredInputs: Set<String>
)

/**
 * Valida el contenido JSON declarativo antes de instalar o activar una skill.
 * No ejecuta herramientas ni evalúa expresiones del paquete.
 */
object BardoSkillWorkflowPackageValidator {
    const val SCHEMA_VERSION = 1
    const val MAX_STEPS = 20
    const val MAX_PACKAGE_BYTES = 10L * 1024L * 1024L
    const val MAX_INPUTS = 16
    const val MAX_ARGUMENT_CHARS = 2_000

    private val actionIdPattern = Regex("^[a-z][a-z0-9._-]{2,63}$")
    private val argumentKeyPattern = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$")
    private val inputTokenPattern = Regex("^\\{\\{input[.:]([A-Za-z][A-Za-z0-9_.-]{0,63})}}$")

    fun validate(
        file: File,
        declaredActions: Set<String>,
        registeredActions: Set<String>
    ): BardoSkillWorkflowPackageValidation {
        if (!file.isFile || file.length() !in 1..MAX_PACKAGE_BYTES) {
            return rejected("El workflow está vacío o supera 10 MiB.")
        }
        return runCatching {
            file.inputStream().use { validate(it, declaredActions, registeredActions) }
        }.getOrElse {
            rejected(it.message ?: "No se pudo leer el paquete declarativo.")
        }
    }

    fun validate(
        input: InputStream,
        declaredActions: Set<String>,
        registeredActions: Set<String>
    ): BardoSkillWorkflowPackageValidation = runCatching {
        validateInternal(input, declaredActions, registeredActions)
    }.getOrElse {
        rejected(it.message ?: "El workflow no supera la validación.")
    }

    private fun validateInternal(
        input: InputStream,
        declaredActions: Set<String>,
        registeredActions: Set<String>
    ): BardoSkillWorkflowPackageValidation {
        val bytes = input.readBytesLimited(MAX_PACKAGE_BYTES.toInt())
        require(bytes.isNotEmpty()) { "El workflow está vacío." }
        val text = bytes.toString(Charsets.UTF_8)
        require(text.length <= MAX_PACKAGE_BYTES.toInt()) { "El workflow supera el límite de tamaño." }
        val root = JSONObject(text)
        require(root.length() == 2 && root.has("schema_version") && root.has("steps")) {
            "El workflow solo admite schema_version y steps."
        }
        require(root.optInt("schema_version", -1) == SCHEMA_VERSION) {
            "Versión de workflow no compatible."
        }
        val array = root.optJSONArray("steps")
            ?: throw IllegalArgumentException("Falta el array steps.")
        require(array.length() in 1..MAX_STEPS) {
            "El workflow debe contener entre 1 y " + MAX_STEPS + " pasos."
        }
        require(declaredActions.isNotEmpty() && declaredActions.size <= MAX_STEPS) {
            "El manifiesto debe declarar entre 1 y 20 acciones."
        }
        require(declaredActions.all { actionIdPattern.matches(it) }) {
            "El manifiesto contiene un identificador de acción no válido."
        }

        val foundActions = linkedSetOf<String>()
        val requiredInputs = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index)
                ?: throw IllegalArgumentException("El paso " + index + " no es un objeto.")
            require(row.length() == 2 && row.has("action_id") && row.has("arguments")) {
                "El paso " + index + " debe contener solo action_id y arguments."
            }
            val actionId = row.optString("action_id")
            require(actionIdPattern.matches(actionId)) {
                "ID de acción no válido en el paso " + index + "."
            }
            require(actionId in declaredActions) {
                "Acción no declarada en el manifiesto: " + actionId
            }
            require(actionId in registeredActions) {
                "Acción no registrada en BARDO: " + actionId
            }
            foundActions += actionId
            val args = row.optJSONObject("arguments")
                ?: throw IllegalArgumentException("Los argumentos del paso " + index + " deben ser un objeto.")
            val keys = args.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                require(argumentKeyPattern.matches(key)) {
                    "Nombre de argumento no válido: " + key
                }
                val value = args.opt(key)
                require(value is String && value.length <= MAX_ARGUMENT_CHARS) {
                    "Los argumentos deben ser cadenas acotadas."
                }
                val markers = inputTokenPattern.findAll(value).toList()
                if (markers.isEmpty()) {
                    require(!value.contains("{{") && !value.contains("}}")) {
                        "Marcador de entrada mal formado."
                    }
                } else {
                    require(markers.size == 1 && markers.single().value == value) {
                        "Solo se permite un marcador de entrada completo por valor."
                    }
                    requiredInputs += markers.single().groupValues[1]
                }
            }
        }
        require(foundActions == declaredActions) {
            "Las acciones del manifiesto y del workflow no coinciden."
        }
        require(requiredInputs.size <= MAX_INPUTS) {
            "El workflow declara más de " + MAX_INPUTS + " entradas."
        }
        return BardoSkillWorkflowPackageValidation(
            accepted = true,
            errors = emptyList(),
            stepCount = array.length(),
            actionIds = foundActions,
            requiredInputs = requiredInputs
        )
    }

    private fun rejected(message: String) = BardoSkillWorkflowPackageValidation(
        accepted = false,
        errors = listOf(message.take(1200)),
        stepCount = 0,
        actionIds = emptySet(),
        requiredInputs = emptySet()
    )

    private fun InputStream.readBytesLimited(maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "El workflow supera el límite de tamaño." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
