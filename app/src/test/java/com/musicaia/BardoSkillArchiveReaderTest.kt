package com.musicaia

import com.musicaia.skills.BardoSkillArchiveReader
import com.musicaia.skills.BardoSkillWorkflowPackageValidator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BardoSkillArchiveReaderTest {
    private val actions = setOf("knowledge.search")

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun workflow(query: String = "{{input:term}}"): ByteArray =
        JSONObject()
            .put("schema_version", 1)
            .put("steps", JSONArray().put(
                JSONObject()
                    .put("action_id", "knowledge.search")
                    .put("arguments", JSONObject().put("query", query))
            ))
            .toString()
            .toByteArray(Charsets.UTF_8)

    private fun archive(
        workflow: ByteArray = workflow(),
        extraEntry: Pair<String, ByteArray>? = null,
        declaredHash: String = sha256(workflow),
        declaredSize: Int = workflow.size,
        manifestEntryName: String = "manifest.json",
        workflowEntryName: String = "workflow.json",
        packageKind: String = "DECLARATIVE_TOOLCHAIN",
        includeManifest: Boolean = true,
        includeWorkflow: Boolean = true
    ): ByteArray {
        val manifest = JSONObject()
            .put("id", "skill.example")
            .put("name", "Skill example")
            .put("version", "1.0.0")
            .put("api_minimum", 1)
            .put("api_maximum", 1)
            .put("description", "A small declarative test skill.")
            .put("source", "local://unit-test")
            .put("license", "Apache-2.0")
            .put("package_sha256", declaredHash)
            .put("package_size_bytes", declaredSize)
            .put("kind", packageKind)
            .put("actions", JSONArray().put("knowledge.search"))
            .put("permissions", JSONArray().put("READ_BARDO_DATA"))
        val entries = buildList {
            if (includeManifest) {
                add(manifestEntryName to manifest.toString().toByteArray(Charsets.UTF_8))
            }
            if (includeWorkflow) add(workflowEntryName to workflow)
            extraEntry?.let { add(it) }
        }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    @Test fun readsAndVerifiesValidDeclarativeArchive() {
        val result = BardoSkillArchiveReader.read(
            ByteArrayInputStream(archive()),
            actions
        )
        assertEquals("skill.example", result.manifest.id)
        assertFalse(result.manifest.licenseAcceptedByUser)
        assertEquals(actions, result.manifest.actions)
        assertEquals(1, result.workflowValidation.stepCount)
        assertEquals(setOf("term"), result.workflowValidation.requiredInputs)
        assertEquals(sha256(result.workflowBytes), result.manifest.packageSha256)
    }

    @Test fun rejectsHashMismatch() {
        val bytes = archive(declaredHash = "0".repeat(64))
        val result = runCatching {
            BardoSkillArchiveReader.read(ByteArrayInputStream(bytes), actions)
        }
        assertTrue(result.isFailure)
    }

    @Test fun rejectsUnexpectedFilesInArchive() {
        val bytes = archive(extraEntry = "payload.sh" to "echo unsafe".toByteArray())
        val result = runCatching {
            BardoSkillArchiveReader.read(ByteArrayInputStream(bytes), actions)
        }
        assertTrue(result.isFailure)
    }

    @Test fun rejectsMalformedWorkflowInputMarkersBeforeActivation() {
        val bytes = archive(workflow(query = "prefix {{input:term}} suffix"))
        val result = runCatching {
            BardoSkillArchiveReader.read(ByteArrayInputStream(bytes), actions)
        }
        assertTrue(result.isFailure)
    }

    @Test fun rejectsPathTraversalEntryNames() {
        val bytes = archive(manifestEntryName = "../manifest.json")
        val result = runCatching {
            BardoSkillArchiveReader.read(ByteArrayInputStream(bytes), actions)
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Entrada no permitida"))
    }

    @Test fun rejectsDirectoryEntriesInsteadOfTreatingThemAsPackageFiles() {
        val bytes = archive(workflowEntryName = "workflow.json/")
        val result = runCatching {
            BardoSkillArchiveReader.read(ByteArrayInputStream(bytes), actions)
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("directorios"))
    }

    @Test fun rejectsArchivesMissingEitherRequiredEntry() {
        val withoutManifest = archive(includeManifest = false)
        val withoutWorkflow = archive(includeWorkflow = false)

        val manifestResult = runCatching {
            BardoSkillArchiveReader.read(ByteArrayInputStream(withoutManifest), actions)
        }
        val workflowResult = runCatching {
            BardoSkillArchiveReader.read(ByteArrayInputStream(withoutWorkflow), actions)
        }

        assertTrue(manifestResult.isFailure)
        assertTrue(manifestResult.exceptionOrNull()?.message.orEmpty().contains("Falta manifest.json"))
        assertTrue(workflowResult.isFailure)
        assertTrue(workflowResult.exceptionOrNull()?.message.orEmpty().contains("Falta workflow.json"))
    }

    @Test fun rejectsDeclaredWorkflowSizeMismatchAndExecutablePackageKind() {
        val wrongSize = archive(declaredSize = workflow().size + 1)
        val executable = archive(packageKind = "EXECUTABLE_CODE")

        val sizeResult = runCatching {
            BardoSkillArchiveReader.read(ByteArrayInputStream(wrongSize), actions)
        }
        val executableResult = runCatching {
            BardoSkillArchiveReader.read(ByteArrayInputStream(executable), actions)
        }

        assertTrue(sizeResult.isFailure)
        assertTrue(sizeResult.exceptionOrNull()?.message.orEmpty().contains("package_size_bytes"))
        assertTrue(executableResult.isFailure)
        assertTrue(executableResult.exceptionOrNull()?.message.orEmpty().contains("código ejecutable"))
    }

    @Test fun validatorRejectsActionsNotDeclaredAndUnregistered() {
        val valid = workflow()
        val accepted = BardoSkillWorkflowPackageValidator.validate(
            ByteArrayInputStream(valid),
            declaredActions = actions,
            registeredActions = emptySet()
        )
        assertFalse(accepted.accepted)
        assertTrue(accepted.errors.any { it.contains("no registrada") })
    }
}
