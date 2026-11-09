package com.musicaia.skills

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

enum class BardoSkillLifecycle {
    QUARANTINED,
    INSTALLED_DISABLED,
    ENABLED,
    DISABLED,
    REJECTED
}

data class BardoSkillRecord(
    val manifest: BardoSkillManifest,
    val lifecycle: BardoSkillLifecycle,
    val installedPath: String?,
    val lastTestPassed: Boolean,
    val lastUpdatedAtMillis: Long,
    val lastTestVerifierId: String? = null,
    val lastTestEvidenceRefs: List<String> = emptyList(),
    val lastTestNote: String? = null
)

data class BardoSkillSmokeTestReport(
    val passed: Boolean,
    val verifierId: String,
    val evidenceRefs: List<String>,
    val note: String
)

fun interface BardoSkillSmokeTestRunner {
    /** Ejecuta pruebas reales sobre acciones tipadas registradas; nunca sobre código descargado. */
    fun run(manifest: BardoSkillManifest, requiredPermissions: Set<BardoSkillPermission>): BardoSkillSmokeTestReport
}

data class BardoSkillOperationResult(
    val succeeded: Boolean,
    val record: BardoSkillRecord?,
    val message: String
)

/**
 * Gestión segura de paquetes declarativos. Este gestor nunca carga clases,
 * scripts, dex, bibliotecas nativas ni ejecutables descargados.
 *
 * La entrada puede proceder de un documento SAF o de una descarga HTTPS
 * autorizada por la capa que invoque al gestor. Esta clase no abre red.
 */
class BardoSkillManager(
    private val rootDirectory: File,
    private val registeredActionIds: () -> Set<String>,
    private val smokeTestRunner: BardoSkillSmokeTestRunner? = null
) {
    private val lock = Any()
    private val quarantine: File
    private val installed: File
    private val metadata: File
    private val records = linkedMapOf<String, BardoSkillRecord>()

    init {
        if (!rootDirectory.exists()) check(rootDirectory.mkdirs()) {
            "No se pudo crear el almacén de skills."
        }
        require(rootDirectory.isDirectory && !Files.isSymbolicLink(rootDirectory.toPath())) {
            "El almacén de skills debe ser una carpeta normal."
        }
        quarantine = childDirectory("quarantine")
        installed = childDirectory("installed")
        metadata = File(rootDirectory.canonicalFile, "registry.json")
        loadRegistry()
    }

    /** Importa el paquete a cuarentena. No instala ni habilita la skill. */
    fun importToQuarantine(
        manifest: BardoSkillManifest,
        input: InputStream,
        userAcceptedLicense: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): BardoSkillOperationResult = synchronized(lock) {
        val candidate = manifest.copy(licenseAcceptedByUser = userAcceptedLicense)
        val validation = BardoSkillManifestValidator.validate(
            candidate,
            registeredActionIds()
        )
        if (!validation.accepted) {
            return@synchronized BardoSkillOperationResult(
                false, null, "Manifiesto rechazado: " + validation.errors.joinToString(" ")
            )
        }
        val key = recordKey(candidate.id, candidate.version)
        if (records.containsKey(key)) {
            return@synchronized BardoSkillOperationResult(false, records[key], "Esta versión ya está registrada.")
        }

        val temporary = File(quarantine, key + "." + UUID.randomUUID() + ".part")
        val finalFile = File(quarantine, "$key.bskill")
        try {
            var total = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(temporary, false).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= BardoSkillManifestValidator.MAX_PACKAGE_BYTES) {
                        "El paquete supera el límite de 10 MiB."
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                output.flush()
                output.fd.sync()
            }
            require(total == candidate.packageSizeBytes) {
                "El tamaño real del paquete no coincide con el manifiesto."
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualHash == candidate.packageSha256) {
                "SHA-256 incorrecto; el paquete permanece rechazado."
            }
            require(!finalFile.exists()) { "El fichero de cuarentena ya existe." }
            moveAtomically(temporary, finalFile)
            val record = BardoSkillRecord(
                candidate,
                BardoSkillLifecycle.QUARANTINED,
                finalFile.absolutePath,
                false,
                nowMillis
            )
            records[key] = record
            saveRegistry()
            BardoSkillOperationResult(true, record, "Paquete verificado y aislado en cuarentena; aún no está instalado.")
        } catch (error: Exception) {
            temporary.delete()
            BardoSkillOperationResult(false, null, error.message ?: error.javaClass.simpleName)
        }
    }

    /** Instala únicamente bytes que ya han superado manifiesto, tamaño y SHA-256. */
    fun install(
        skillId: String,
        version: String,
        explicitUserApproval: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): BardoSkillOperationResult = synchronized(lock) {
        val key = recordKey(skillId, version)
        val record = records[key]
            ?: return@synchronized BardoSkillOperationResult(false, null, "La versión no está en cuarentena.")
        if (record.lifecycle != BardoSkillLifecycle.QUARANTINED) {
            return@synchronized BardoSkillOperationResult(false, record, "La skill no está en estado de cuarentena.")
        }
        if (!explicitUserApproval) {
            return@synchronized BardoSkillOperationResult(false, record, "La instalación requiere aprobación explícita.")
        }
        val source = safePackageFile(record.installedPath)
        if (!verifyPackage(source, record.manifest)) {
            records[key] = record.copy(
                lifecycle = BardoSkillLifecycle.REJECTED,
                lastTestPassed = false,
                lastUpdatedAtMillis = nowMillis,
                lastTestVerifierId = null,
                lastTestEvidenceRefs = emptyList(),
                lastTestNote = null
            )
            saveRegistry()
            return@synchronized BardoSkillOperationResult(false, records[key], "La integridad de la skill ha cambiado.")
        }
        val workflowValidation = BardoSkillWorkflowPackageValidator.validate(
            source, record.manifest.actions, registeredActionIds()
        )
        if (!workflowValidation.accepted) {
            return@synchronized BardoSkillOperationResult(
                false, record,
                "Workflow rechazado antes de instalar: " + workflowValidation.errors.joinToString(" ")
            )
        }

        val destination = File(installed, "$key.bskill")
        if (destination.exists()) {
            return@synchronized BardoSkillOperationResult(false, record, "Ya existe un paquete instalado con esa versión.")
        }
        val temporary = File(installed, "$key." + UUID.randomUUID() + ".part")
        try {
            copyAndSync(source, temporary)
            require(verifyPackage(temporary, record.manifest)) {
                "La copia instalada no supera la verificación SHA-256."
            }
            moveAtomically(temporary, destination)
            val updated = record.copy(
                lifecycle = BardoSkillLifecycle.INSTALLED_DISABLED,
                installedPath = destination.absolutePath,
                lastTestPassed = false,
                lastUpdatedAtMillis = nowMillis
            )
            records[key] = updated
            saveRegistry()
            BardoSkillOperationResult(true, updated, "Skill instalada pero deshabilitada. Aún no se ha ejecutado.")
        } catch (error: Exception) {
            temporary.delete()
            BardoSkillOperationResult(false, record, error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Habilita la receta declarativa solo tras aceptación y un smoke-test
     * explícito del catálogo de herramientas. No ejecuta el paquete descargado.
     */
    fun enable(
        skillId: String,
        version: String,
        explicitlyApproved: Boolean,
        approvedPermissions: Set<BardoSkillPermission>,
        nowMillis: Long = System.currentTimeMillis()
    ): BardoSkillOperationResult = synchronized(lock) {
        val key = recordKey(skillId, version)
        val record = records[key]
            ?: return@synchronized BardoSkillOperationResult(false, null, "Skill desconocida.")
        if (record.lifecycle !in setOf(BardoSkillLifecycle.INSTALLED_DISABLED, BardoSkillLifecycle.DISABLED)) {
            return@synchronized BardoSkillOperationResult(false, record, "Solo se puede habilitar una skill instalada y deshabilitada.")
        }
        if (!explicitlyApproved) {
            return@synchronized BardoSkillOperationResult(false, record, "La activación requiere aprobación explícita.")
        }
        val validation = BardoSkillManifestValidator.validate(record.manifest, registeredActionIds())
        if (!validation.accepted) {
            return@synchronized BardoSkillOperationResult(false, record, validation.errors.joinToString(" "))
        }
        if (!approvedPermissions.containsAll(validation.requiredPermissions)) {
            return@synchronized BardoSkillOperationResult(false, record, "Faltan permisos aprobados por el usuario.")
        }
        val file = safePackageFile(record.installedPath)
        if (!verifyPackage(file, record.manifest)) {
            return@synchronized BardoSkillOperationResult(false, record, "La skill instalada no supera la verificación de integridad.")
        }
        val workflowValidation = BardoSkillWorkflowPackageValidator.validate(
            file, record.manifest.actions, registeredActionIds()
        )
        if (!workflowValidation.accepted) {
            return@synchronized BardoSkillOperationResult(
                false, record,
                "La activación está bloqueada: workflow no válido. " +
                    workflowValidation.errors.joinToString(" ")
            )
        }
        val runner = smokeTestRunner
            ?: return@synchronized BardoSkillOperationResult(
                false, record,
                "No hay un ejecutor de pruebas independiente conectado; la activación queda bloqueada."
            )
        val testReport = try {
            runner.run(record.manifest, validation.requiredPermissions)
        } catch (error: Exception) {
            return@synchronized BardoSkillOperationResult(
                false, record,
                "El smoke-test de confianza falló: " + (error.message ?: error.javaClass.simpleName)
            )
        }
        val validTestEvidence = testReport.verifierId.matches(Regex("^[A-Za-z0-9._:-]{3,100}$")) &&
            testReport.evidenceRefs.isNotEmpty() &&
            testReport.evidenceRefs.size <= MAX_TEST_EVIDENCE_REFS &&
            testReport.evidenceRefs.distinct().size == testReport.evidenceRefs.size &&
            testReport.evidenceRefs.all { it.isNotBlank() && it.length <= 1000 } &&
            testReport.note.isNotBlank() && testReport.note.length <= 2000
        if (!testReport.passed || !validTestEvidence) {
            return@synchronized BardoSkillOperationResult(
                false, record,
                "El smoke-test no aportó una prueba independiente válida; la skill permanece deshabilitada."
            )
        }
        val updated = record.copy(
            lifecycle = BardoSkillLifecycle.ENABLED,
            lastTestPassed = true,
            lastUpdatedAtMillis = nowMillis,
            lastTestVerifierId = testReport.verifierId,
            lastTestEvidenceRefs = testReport.evidenceRefs,
            lastTestNote = testReport.note.take(2000)
        )
        records[key] = updated
        saveRegistry()
        BardoSkillOperationResult(true, updated, "Skill declarativa habilitada; las acciones siguen pasando por ToolExecutor.")
    }

    fun disable(skillId: String, version: String): BardoSkillOperationResult = synchronized(lock) {
        val key = recordKey(skillId, version)
        val record = records[key]
            ?: return@synchronized BardoSkillOperationResult(false, null, "Skill desconocida.")
        if (record.lifecycle != BardoSkillLifecycle.ENABLED) {
            return@synchronized BardoSkillOperationResult(false, record, "La skill no estaba habilitada.")
        }
        val updated = record.copy(
            lifecycle = BardoSkillLifecycle.DISABLED,
            lastUpdatedAtMillis = System.currentTimeMillis()
        )
        records[key] = updated
        saveRegistry()
        BardoSkillOperationResult(true, updated, "Skill deshabilitada.")
    }

    /** Reversión: deshabilita la versión nueva y vuelve a habilitar una anterior probada. */
    fun rollback(
        skillId: String,
        fromVersion: String,
        toVersion: String,
        approvedPermissions: Set<BardoSkillPermission>
    ): BardoSkillOperationResult = synchronized(lock) {
        val fromKey = recordKey(skillId, fromVersion)
        val toKey = recordKey(skillId, toVersion)
        val current = records[fromKey]
            ?: return@synchronized BardoSkillOperationResult(false, null, "No se encuentra la versión actual.")
        val target = records[toKey]
            ?: return@synchronized BardoSkillOperationResult(false, current, "No se encuentra la versión anterior.")
        if (target.lifecycle !in setOf(BardoSkillLifecycle.DISABLED, BardoSkillLifecycle.INSTALLED_DISABLED) ||
            !target.lastTestPassed || target.lastTestVerifierId.isNullOrBlank() ||
            target.lastTestEvidenceRefs.isEmpty()
        ) {
            return@synchronized BardoSkillOperationResult(false, current, "La versión destino no está deshabilitada y probada.")
        }
        val validation = BardoSkillManifestValidator.validate(target.manifest, registeredActionIds())
        if (!validation.accepted || !approvedPermissions.containsAll(validation.requiredPermissions) ||
            !verifyPackage(safePackageFile(target.installedPath), target.manifest)
        ) {
            return@synchronized BardoSkillOperationResult(false, current, "La versión anterior no supera manifiesto, permisos o integridad.")
        }
        if (current.lifecycle == BardoSkillLifecycle.ENABLED) {
            records[fromKey] = current.copy(
                lifecycle = BardoSkillLifecycle.DISABLED,
                lastUpdatedAtMillis = System.currentTimeMillis()
            )
        }
        val restored = target.copy(
            lifecycle = BardoSkillLifecycle.ENABLED,
            lastUpdatedAtMillis = System.currentTimeMillis()
        )
        records[toKey] = restored
        saveRegistry()
        BardoSkillOperationResult(true, restored, "Reversión completada a la versión $toVersion.")
    }

    fun remove(
        skillId: String,
        version: String,
        explicitUserApproval: Boolean
    ): BardoSkillOperationResult = synchronized(lock) {
        val key = recordKey(skillId, version)
        val record = records[key]
            ?: return@synchronized BardoSkillOperationResult(false, null, "Skill desconocida.")
        if (!explicitUserApproval) {
            return@synchronized BardoSkillOperationResult(false, record, "Eliminar una skill requiere aprobación explícita.")
        }
        val file = safePackageFile(record.installedPath)
        if (file.exists() && !file.delete()) {
            return@synchronized BardoSkillOperationResult(false, record, "No se pudo eliminar el paquete de skill.")
        }
        records.remove(key)
        saveRegistry()
        BardoSkillOperationResult(true, null, "Skill eliminada del almacén de BARDO; no se borró su archivo de origen.")
    }

    fun list(): List<BardoSkillRecord> = synchronized(lock) {
        records.values.sortedWith(compareBy<BardoSkillRecord> { it.manifest.id }.thenBy { it.manifest.version })
    }

    /**
     * Recupera una skill habilitada solo si conserva la evidencia de prueba y
     * el archivo instalado sigue coincidiendo con su tamaño y SHA-256.
     * Si el paquete cambió, se revoca su estado habilitado y se marca REJECTED.
     */
    fun findEnabled(skillId: String, version: String): BardoSkillRecord? = synchronized(lock) {
        val key = recordKey(skillId, version)
        val record = records[key] ?: return@synchronized null
        if (record.lifecycle != BardoSkillLifecycle.ENABLED ||
            !record.lastTestPassed ||
            record.lastTestVerifierId.isNullOrBlank() ||
            record.lastTestEvidenceRefs.isEmpty()
        ) return@synchronized null

        val intact = runCatching {
            verifyPackage(safePackageFile(record.installedPath), record.manifest)
        }.getOrDefault(false)
        if (!intact) {
            records[key] = record.copy(
                lifecycle = BardoSkillLifecycle.REJECTED,
                lastTestPassed = false,
                lastUpdatedAtMillis = System.currentTimeMillis(),
                lastTestVerifierId = null,
                lastTestEvidenceRefs = emptyList(),
                lastTestNote = "Paquete alterado o inaccesible tras la activación."
            )
            saveRegistry()
            return@synchronized null
        }
        record
    }

    /** Verifica hashes de todos los paquetes antes de devolverlos como habilitados. */
    fun enabled(): List<BardoSkillRecord> = list()
        .mapNotNull { findEnabled(it.manifest.id, it.manifest.version) }

    private fun verifyPackage(file: File, manifest: BardoSkillManifest): Boolean {
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) ||
            file.length() != manifest.packageSizeBytes ||
            file.length() !in 1..BardoSkillManifestValidator.MAX_PACKAGE_BYTES
        ) return false
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return hash == manifest.packageSha256
    }

    private fun safePackageFile(path: String?): File {
        require(!path.isNullOrBlank()) { "Falta ruta de paquete." }
        val file = File(path).canonicalFile
        val allowedParents = setOf(quarantine.canonicalFile, installed.canonicalFile)
        require(file.parentFile in allowedParents &&
            file.extension == "bskill" &&
            !Files.isSymbolicLink(file.toPath())
        ) {
            "La skill intenta salir de las carpetas de paquetes del almacén privado."
        }
        return file
    }

    private fun childDirectory(name: String): File {
        require(name.matches(Regex("^[a-z-]+$")))
        val child = File(rootDirectory.canonicalFile, name)
        require(child.canonicalFile.parentFile == rootDirectory.canonicalFile)
        if (!child.exists()) check(child.mkdirs()) { "No se pudo crear $name." }
        require(child.isDirectory && !Files.isSymbolicLink(child.toPath()))
        return child
    }

    private fun recordKey(id: String, version: String): String {
        require(id.matches(Regex("^[a-z][a-z0-9._-]{2,63}$")))
        require(version.matches(Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-[0-9A-Za-z.-]+)?$")))
        return id.replace('.', '_') + "-" + version.replace('.', '_')
            .replace('-', '_').replace('+', '_')
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private fun copyAndSync(source: File, destination: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination, false).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.flush()
                output.fd.sync()
            }
        }
    }

    private fun loadRegistry() = synchronized(lock) {
        records.clear()
        if (!metadata.isFile || Files.isSymbolicLink(metadata.toPath())) return@synchronized
        val root = JSONObject(metadata.readText(Charsets.UTF_8))
        val array = root.optJSONArray("records") ?: JSONArray()
        for (i in 0 until array.length()) {
            val record = array.optJSONObject(i) ?: continue
            val manifest = record.optJSONObject("manifest")?.let(::parseManifest) ?: continue
            val lifecycle = runCatching {
                BardoSkillLifecycle.valueOf(record.optString("lifecycle"))
            }.getOrNull() ?: continue
            val key = recordKey(manifest.id, manifest.version)
            records[key] = BardoSkillRecord(
                manifest,
                lifecycle,
                record.optString("installedPath").takeIf { it.isNotBlank() && it != "null" },
                record.optBoolean("lastTestPassed", false) &&
                    record.optJSONArray("lastTestEvidenceRefs")?.length()?.let { it > 0 } == true &&
                    record.optString("lastTestVerifierId").isNotBlank(),
                record.optLong("lastUpdatedAtMillis", 0L),
                record.optString("lastTestVerifierId").takeIf { it.isNotBlank() && it != "null" },
                record.optJSONArray("lastTestEvidenceRefs")?.let { refs ->
                    (0 until refs.length()).map { refs.getString(it) }
                }.orEmpty(),
                record.optString("lastTestNote").takeIf { it.isNotBlank() && it != "null" }
            )
        }
    }

    private fun saveRegistry() {
        val root = JSONObject().put("records", JSONArray().also { array ->
            records.values.forEach { record ->
                array.put(JSONObject()
                    .put("manifest", manifestJson(record.manifest))
                    .put("lifecycle", record.lifecycle.name)
                    .put("installedPath", record.installedPath)
                    .put("lastTestPassed", record.lastTestPassed)
                    .put("lastUpdatedAtMillis", record.lastUpdatedAtMillis)
                    .put("lastTestVerifierId", record.lastTestVerifierId)
                    .put("lastTestEvidenceRefs", JSONArray(record.lastTestEvidenceRefs))
                    .put("lastTestNote", record.lastTestNote))
            }
        })
        val temporary = File(rootDirectory, "registry." + UUID.randomUUID() + ".tmp")
        try {
            FileOutputStream(temporary, false).use {
                it.write(root.toString().toByteArray(Charsets.UTF_8))
                it.flush()
                it.fd.sync()
            }
            moveAtomically(temporary, metadata)
        } finally {
            temporary.delete()
        }
    }

    private fun manifestJson(m: BardoSkillManifest) = JSONObject()
        .put("id", m.id).put("name", m.name).put("version", m.version)
        .put("apiMinimum", m.apiMinimum).put("apiMaximum", m.apiMaximum)
        .put("description", m.description).put("source", m.source).put("license", m.license)
        .put("packageSha256", m.packageSha256).put("packageSizeBytes", m.packageSizeBytes)
        .put("kind", m.kind.name).put("actions", JSONArray(m.actions.sorted()))
        .put("permissions", JSONArray(m.permissions.map { it.name }.sorted()))
        .put("dependencies", JSONArray().also { array ->
            m.dependencies.forEach { dep -> array.put(JSONObject().put("skillId", dep.skillId).put("minimumVersion", dep.minimumVersion)) }
        })
        .put("licenseAcceptedByUser", m.licenseAcceptedByUser)

    private fun parseManifest(json: JSONObject): BardoSkillManifest? = runCatching {
        BardoSkillManifest(
            id = json.getString("id"),
            name = json.getString("name"),
            version = json.getString("version"),
            apiMinimum = json.getInt("apiMinimum"),
            apiMaximum = json.getInt("apiMaximum"),
            description = json.getString("description"),
            source = json.getString("source"),
            license = json.getString("license"),
            packageSha256 = json.getString("packageSha256"),
            packageSizeBytes = json.getLong("packageSizeBytes"),
            kind = BardoSkillPackageKind.valueOf(json.getString("kind")),
            actions = json.getJSONArray("actions").let { a ->
                (0 until a.length()).map { a.getString(it) }.toSet()
            },
            permissions = json.getJSONArray("permissions").let { a ->
                (0 until a.length()).map { BardoSkillPermission.valueOf(a.getString(it)) }.toSet()
            },
            dependencies = json.optJSONArray("dependencies")?.let { a ->
                (0 until a.length()).map { i ->
                    val d = a.getJSONObject(i)
                    BardoSkillDependency(d.getString("skillId"), d.getString("minimumVersion"))
                }
            }.orEmpty(),
            licenseAcceptedByUser = json.optBoolean("licenseAcceptedByUser", false)
        )
    }.getOrNull()

    companion object {
        private const val MAX_TEST_EVIDENCE_REFS = 20
    }
}
