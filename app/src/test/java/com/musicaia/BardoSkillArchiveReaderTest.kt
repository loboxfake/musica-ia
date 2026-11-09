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
        declaredHash: String = sha256(workflow)
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
            .put("package_size_bytes", workflow.size)
            .put("kind", "DECLARATIVE_TOOLCHAIN")
            .put("actions", JSONArray().put("knowledge.search"))
            .put("permissions", JSONArray().put("READ_BARDO_DATA"))
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("workflow.json"))
            zip.write(workflow)
            zip.closeEntry()
            extraEntry?.let { (name, bytes) ->
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
