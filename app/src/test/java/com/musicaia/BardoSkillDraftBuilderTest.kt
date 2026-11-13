package com.musicaia

import com.musicaia.skills.BardoSkillArchiveReader
import com.musicaia.skills.BardoSkillDraftBuilder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class BardoSkillDraftBuilderTest {
    private val registered = setOf("knowledge.search")

    private fun workflow(): String = JSONObject()
        .put("schema_version", 1)
        .put("steps", JSONArray().put(
            JSONObject()
                .put("action_id", "knowledge.search")
                .put("arguments", JSONObject().put("query", "{{input:term}}"))
        ))
        .toString()

    @Test fun createsPrivateDeclarativeDraftThatCanBeReadByArchiveValidator() {
        val root = createTempDir(prefix = "bardo-skill-drafts")
        try {
            val builder = BardoSkillDraftBuilder(root) { registered }
            val result = builder.createDraft(
                id = "skill.draft.example",
                name = "Example draft",
                version = "1.0.0",
                description = "A local declarative draft.",
                license = "Apache-2.0",
                workflowJson = workflow()
            )
            assertTrue(result.message, result.succeeded)
            val file = result.draftFile
            assertNotNull(file)
            assertTrue(file!!.isFile)
            assertEquals(root.canonicalFile, file.canonicalFile.parentFile)
            assertEquals(setOf("knowledge.search"), result.actions)
            assertFalse(result.requiredPermissions.isEmpty())

            val archive = BardoSkillArchiveReader.read(file.inputStream(), registered)
            assertEquals("skill.draft.example", archive.manifest.id)
            assertFalse(archive.manifest.licenseAcceptedByUser)
            assertEquals(1, archive.workflowValidation.stepCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun refusesUnknownActionsWithoutCreatingDraft() {
        val root = createTempDir(prefix = "bardo-skill-drafts-unknown")
        try {
            val invalidWorkflow = JSONObject()
                .put("schema_version", 1)
                .put("steps", JSONArray().put(
                    JSONObject()
                        .put("action_id", "system.shell")
                        .put("arguments", JSONObject())
                )).toString()
            val result = BardoSkillDraftBuilder(root) { registered }.createDraft(
                id = "skill.draft.invalid",
                name = "Invalid",
                version = "1.0.0",
                description = "Should be rejected.",
                license = "Apache-2.0",
                workflowJson = invalidWorkflow
            )
            assertFalse(result.succeeded)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun refusesUnknownLicenseAndDuplicateDraftWithoutReplacingOriginal() {
        val root = createTempDir(prefix = "bardo-skill-drafts-duplicates")
        try {
            val builder = BardoSkillDraftBuilder(root) { registered }
            val args = arrayOf(
                "skill.draft.example", "Example", "1.0.0",
                "A draft.", "Apache-2.0", workflow()
            )
            val first = builder.createDraft(
                args[0], args[1], args[2], args[3], args[4], args[5]
            )
            assertTrue(first.succeeded)
            val bytes = first.draftFile!!.readBytes()
            val duplicate = builder.createDraft(
                args[0], args[1], args[2], args[3], args[4], args[5]
            )
            assertFalse(duplicate.succeeded)
            assertArrayEquals(bytes, first.draftFile.readBytes())

            val unknownLicense = builder.createDraft(
                "skill.draft.unknown", "Unknown", "1.0.0",
                "No license.", "unknown", workflow()
            )
            assertFalse(unknownLicense.succeeded)
            assertEquals(1, root.listFiles().orEmpty().size)
        } finally {
            root.deleteRecursively()
        }
    }
}
