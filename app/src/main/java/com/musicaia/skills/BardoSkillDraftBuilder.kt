package com.musicaia.skills

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class BardoSkillDraftResult(
    val succeeded: Boolean,
    val draftFile: File?,
    val skillId: String,
    val version: String,
    val sha256: String?,
    val actions: Set<String>,
    val requiredPermissions: Set<BardoSkillPermission>,
    val message: String
)

/**
 * Crea únicamente un paquete .bskill declarativo en un directorio privado
 * de borradores. No lo importa, instala, habilita ni ejecuta.
 */
class BardoSkillDraftBuilder(
    private val rootDirectory: File,
    private val registeredActionIds: () -> Set<String>
) {
    fun createDraft(
        id: String,
        name: String,
        version: String,
        description: String,
        license: String,
        workflowJson: String,
        nowMillis: Long = System.currentTimeMillis()
    ): BardoSkillDraftResult {
        val actionsAndPermissions = try {
            require(workflowJson.isNotBlank() && workflowJson.length <= MAX_WORKFLOW_CHARS) {
                "El workflow está vacío o supera el límite de borrador."
            }
            val workflowBytes = workflowJson.toByteArray(Charsets.UTF_8)
            val json = JSONObject(workflowJson)
            val steps = json.optJSONArray("steps")
                ?: throw IllegalArgumentException("El workflow no contiene steps.")
            val actions = (0 until steps.length()).map { index ->
                steps.getJSONObject(index).getString("action_id")
            }.toSet()
            require(actions.isNotEmpty() && actions.size <= BardoSkillWorkflowPackageValidator.MAX_STEPS) {
                "El borrador debe contener entre 1 y 20 acciones diferentes."
            }
            val allPermissions = BardoSkillPermission.entries.toSet()
            val candidate = BardoSkillManifest(
                id = id,
                name = name,
                version = version,
                apiMinimum = BardoSkillManifestValidator.CURRENT_API,
                apiMaximum = BardoSkillManifestValidator.CURRENT_API,
                description = description,
                source = "local://bardo-draft/" + id + "/" + version,
                license = license,
                packageSha256 = sha256(workflowBytes),
                packageSizeBytes = workflowBytes.size.toLong(),
                kind = BardoSkillPackageKind.DECLARATIVE_TOOLCHAIN,
                actions = actions,
                permissions = allPermissions,
                licenseAcceptedByUser = true
            )
            val manifestValidation = BardoSkillManifestValidator.validate(candidate, registeredActionIds())
            require(manifestValidation.accepted) {
                "El manifiesto del borrador no es válido: " + manifestValidation.errors.joinToString(" ")
            }
            val workflowValidation = BardoSkillWorkflowPackageValidator.validate(
                workflowBytes.inputStream(), actions, registeredActionIds()
            )
            require(workflowValidation.accepted) {
                "El workflow del borrador no es válido: " + workflowValidation.errors.joinToString(" ")
            }
            Triple(workflowBytes, candidate.copy(
                permissions = manifestValidation.requiredPermissions,
                licenseAcceptedByUser = false
            ), manifestValidation.requiredPermissions)
        } catch (error: Exception) {
            return rejected(id, version, error.message ?: error.javaClass.simpleName)
        }

        return try {
            ensureRoot()
            val (workflowBytes, manifest, permissions) = actionsAndPermissions
            val archiveBytes = buildArchive(manifest, workflowBytes)
            require(archiveBytes.size <= MAX_ARCHIVE_BYTES) {
                "El paquete comprimido supera el límite permitido."
            }
            val filename = id + "-" + version.replace('+', '_') + ".bskill"
            val target = File(rootDirectory.canonicalFile, filename)
            require(target.canonicalFile.parentFile == rootDirectory.canonicalFile &&
                !Files.isSymbolicLink(target.toPath())
            ) { "Ruta de borrador no válida." }
            require(!target.exists()) { "Ya existe un borrador con este ID y versión." }
            val temporary = File(rootDirectory, filename + "." + UUID.randomUUID() + ".tmp")
            try {
                FileOutputStream(temporary, false).use { output ->
                    output.write(archiveBytes)
                    output.flush()
                    output.fd.sync()
                }
                moveAtomically(temporary, target)
            } finally {
                temporary.delete()
            }
            BardoSkillDraftResult(
                succeeded = true,
                draftFile = target,
                skillId = id,
                version = version,
                sha256 = sha256(archiveBytes),
                actions = manifest.actions,
                requiredPermissions = permissions,
                message = "Borrador .bskill creado y validado. Permanece en almacenamiento privado, sin instalar ni habilitar."
            )
        } catch (error: Exception) {
            rejected(id, version, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun buildArchive(manifest: BardoSkillManifest, workflowBytes: ByteArray): ByteArray {
        val json = JSONObject()
            .put("id", manifest.id)
            .put("name", manifest.name)
            .put("version", manifest.version)
            .put("api_minimum", manifest.apiMinimum)
            .put("api_maximum", manifest.apiMaximum)
            .put("description", manifest.description)
            .put("source", manifest.source)
            .put("license", manifest.license)
            .put("package_sha256", manifest.packageSha256)
            .put("package_size_bytes", manifest.packageSizeBytes)
            .put("kind", manifest.kind.name)
            .put("actions", JSONArray(manifest.actions.sorted()))
            .put("permissions", JSONArray(manifest.permissions.map { it.name }.sorted()))
            .put("dependencies", JSONArray())
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(BardoSkillArchiveReader.MANIFEST_ENTRY))
            zip.write(json.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BardoSkillArchiveReader.WORKFLOW_ENTRY))
            zip.write(workflowBytes)
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun ensureRoot() {
        if (!rootDirectory.exists()) check(rootDirectory.mkdirs()) {
            "No se pudo crear el directorio privado de borradores."
        }
        require(rootDirectory.isDirectory && !Files.isSymbolicLink(rootDirectory.toPath())) {
            "El directorio de borradores no es una carpeta normal."
        }
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun rejected(id: String, version: String, reason: String) = BardoSkillDraftResult(
        false, null, id, version, null, emptySet(), emptySet(),
        "No se creó el borrador: " + reason.take(1200)
    )

    companion object {
        const val MAX_WORKFLOW_CHARS = 24_000
        const val MAX_ARCHIVE_BYTES = 10 * 1024 * 1024
    }
}
