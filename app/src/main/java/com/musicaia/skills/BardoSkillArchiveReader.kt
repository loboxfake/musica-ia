package com.musicaia.skills

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class BardoSkillArchiveContent(
    val manifest: BardoSkillManifest,
    val workflowBytes: ByteArray,
    val workflowValidation: BardoSkillWorkflowPackageValidation
)

/**
 * Lector de paquetes .bskill. El ZIP solo puede contener manifest.json y
 * workflow.json; no se extraen rutas al disco ni se aceptan scripts/ejecutables.
 */
object BardoSkillArchiveReader {
    const val MANIFEST_ENTRY = "manifest.json"
    const val WORKFLOW_ENTRY = "workflow.json"
    const val MAX_MANIFEST_BYTES = 32 * 1024
    const val MAX_ARCHIVE_BYTES = BardoSkillManifestValidator.MAX_PACKAGE_BYTES + 512L * 1024L

    fun read(
        input: InputStream,
        registeredActionIds: Set<String>
    ): BardoSkillArchiveContent {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(LimitedInputStream(input, MAX_ARCHIVE_BYTES)).use { zip ->
            var entryCount = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= 2) { "El paquete solo puede contener manifest.json y workflow.json." }
                require(!entry.isDirectory) { "No se admiten directorios dentro del paquete." }
                require(entry.name == MANIFEST_ENTRY || entry.name == WORKFLOW_ENTRY) {
                    "Entrada no permitida en el paquete: " + entry.name
                }
                require(entry.name !in entries) { "El paquete contiene una entrada duplicada." }
                val limit = if (entry.name == MANIFEST_ENTRY) {
                    MAX_MANIFEST_BYTES
                } else {
                    BardoSkillManifestValidator.MAX_PACKAGE_BYTES.toInt()
                }
                val bytes = readEntryBounded(zip, limit)
                require(bytes.isNotEmpty()) { "La entrada " + entry.name + " está vacía." }
                if (entry.size >= 0L) require(entry.size == bytes.size.toLong()) {
                    "El tamaño declarado no coincide con " + entry.name + "."
                }
                entries[entry.name] = bytes
                zip.closeEntry()
            }
        }
        val manifestBytes = entries[MANIFEST_ENTRY]
            ?: throw IllegalArgumentException("Falta manifest.json.")
        val workflowBytes = entries[WORKFLOW_ENTRY]
            ?: throw IllegalArgumentException("Falta workflow.json.")

        val manifestJson = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        val manifest = parseManifest(manifestJson)
        val actualSize = workflowBytes.size.toLong()
        require(manifest.packageSizeBytes == actualSize) {
            "package_size_bytes no coincide con workflow.json."
        }
        val actualHash = sha256(workflowBytes)
        require(manifest.packageSha256 == actualHash) {
            "SHA-256 incorrecto en workflow.json."
        }
        require(manifest.kind == BardoSkillPackageKind.DECLARATIVE_TOOLCHAIN) {
            "Los paquetes .bskill no admiten código ejecutable."
        }
        val manifestValidation = BardoSkillManifestValidator.validate(
            manifest.copy(licenseAcceptedByUser = true),
            registeredActionIds
        )
        require(manifestValidation.accepted) {
            "Manifiesto rechazado: " + manifestValidation.errors.joinToString(" ")
        }
        val workflowValidation = BardoSkillWorkflowPackageValidator.validate(
            ByteArrayInputStream(workflowBytes),
            manifest.actions,
            registeredActionIds
        )
        require(workflowValidation.accepted) {
            "Workflow rechazado: " + workflowValidation.errors.joinToString(" ")
        }
        return BardoSkillArchiveContent(
            manifest = manifest.copy(licenseAcceptedByUser = false),
            workflowBytes = workflowBytes,
            workflowValidation = workflowValidation
        )
    }

    private fun parseManifest(json: JSONObject): BardoSkillManifest {
        val allowed = setOf(
            "id", "name", "version", "api_minimum", "api_maximum", "description",
            "source", "license", "package_sha256", "package_size_bytes", "kind",
            "actions", "permissions", "dependencies"
        )
        val keys = json.keys().asSequence().toSet()
        require(keys == allowed || keys == allowed - "dependencies") {
            "El manifiesto debe contener exactamente los campos de la versión .bskill v1."
        }
        val actions = json.getJSONArray("actions").toStringSet("actions")
        val permissions = json.getJSONArray("permissions").toStringSet("permissions")
            .map { BardoSkillPermission.valueOf(it) }.toSet()
        val dependencies = json.optJSONArray("dependencies")?.let { array ->
            require(array.length() <= 20) { "Demasiadas dependencias." }
            (0 until array.length()).map { index ->
                val dependency = array.getJSONObject(index)
                require(dependency.length() == 2 &&
                    dependency.has("skill_id") && dependency.has("minimum_version")
                ) { "Cada dependencia debe declarar skill_id y minimum_version." }
                BardoSkillDependency(
                    dependency.getString("skill_id"),
                    dependency.getString("minimum_version")
                )
            }
        }.orEmpty()
        return BardoSkillManifest(
            id = json.getString("id"),
            name = json.getString("name"),
            version = json.getString("version"),
            apiMinimum = json.getInt("api_minimum"),
            apiMaximum = json.getInt("api_maximum"),
            description = json.getString("description"),
            source = json.getString("source"),
            license = json.getString("license"),
            packageSha256 = json.getString("package_sha256"),
            packageSizeBytes = json.getLong("package_size_bytes"),
            kind = BardoSkillPackageKind.valueOf(json.getString("kind")),
            actions = actions,
            permissions = permissions,
            dependencies = dependencies,
            licenseAcceptedByUser = false
        )
    }

    private fun JSONArray.toStringSet(label: String): Set<String> {
        val values = (0 until length()).map { index ->
            get(index) as? String
                ?: throw IllegalArgumentException(label + " debe contener solo cadenas.")
        }
        require(values.distinct().size == values.size) { label + " contiene duplicados." }
        return values.toSet()
    }

    private fun readEntryBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "La entrada descomprimida supera su límite permitido." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private class LimitedInputStream(input: InputStream, private val maxBytes: Long) :
        FilterInputStream(input) {
        private var consumed = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) {
                consumed++
                require(consumed <= maxBytes) { "El archivo .bskill supera el máximo comprimido." }
            }
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) {
                consumed += count
                require(consumed <= maxBytes) { "El archivo .bskill supera el máximo comprimido." }
            }
            return count
        }
    }
}
