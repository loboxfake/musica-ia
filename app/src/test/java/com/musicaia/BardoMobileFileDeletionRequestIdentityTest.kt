package com.musicaia

import com.musicaia.storage.BardoMobileFileDeletionRequest
import com.musicaia.storage.BardoMobileFileEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoMobileFileDeletionRequestIdentityTest {
    private fun request(
        documentId: String? = "doc-17",
        name: String? = "obsolete.bskill",
        mimeType: String? = "application/zip",
        sizeBytes: Long? = 1024L,
        lastModifiedMillis: Long? = 123456L
    ) = BardoMobileFileDeletionRequest(
        id = "request-1",
        relativePath = "Skills/obsolete.bskill",
        permanent = false,
        reason = "Skill replaced",
        requestedAtMillis = 123456L,
        documentId = documentId,
        name = name,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        lastModifiedMillis = lastModifiedMillis
    )

    private fun entry(
        name: String = "obsolete.bskill",
        mimeType: String = "application/zip",
        sizeBytes: Long? = 1024L,
        lastModifiedMillis: Long? = 123456L
    ) = BardoMobileFileEntry(
        relativePath = "Skills/obsolete.bskill",
        name = name,
        isDirectory = false,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        lastModifiedMillis = lastModifiedMillis
    )

    @Test fun unchangedDocumentWithExactIdAndMetadataMatches() {
        assertTrue(request().matchesCurrentIdentity("doc-17", entry()))
    }

    @Test fun replacingFileAtSamePathIsRejectedByDocumentId() {
        assertFalse(request().matchesCurrentIdentity("new-doc-18", entry()))
    }

    @Test fun changedNameMimeSizeOrTimestampIsRejected() {
        val identity = request()
        assertFalse(identity.matchesCurrentIdentity("doc-17", entry(name = "different.bskill")))
        assertFalse(identity.matchesCurrentIdentity("doc-17", entry(mimeType = "text/plain")))
        assertFalse(identity.matchesCurrentIdentity("doc-17", entry(sizeBytes = 2048L)))
        assertFalse(identity.matchesCurrentIdentity("doc-17", entry(lastModifiedMillis = 123457L)))
    }

    @Test fun legacyRequestWithoutDocumentIdCannotBeApproved() {
        assertFalse(request(documentId = null).matchesCurrentIdentity("doc-17", entry()))
    }

    @Test fun missingMetadataDoesNotBecomeAnIdentityMatch() {
        assertFalse(request(sizeBytes = null).matchesCurrentIdentity("doc-17", entry()))
        assertFalse(request(lastModifiedMillis = null).matchesCurrentIdentity("doc-17", entry()))
    }
}
