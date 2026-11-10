package com.musicaia

import com.musicaia.seguridad.BardoFileAuthorityLedger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class BardoFileAuthorityLedgerTest {
    @Test fun nonceReplayProtectionSurvivesNewLedgerInstance() {
        val base = createTempDir(prefix = "bardo-authority-nonce")
        try {
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val first = BardoFileAuthorityLedger(base)
            assertTrue(first.consumeOnce(id, now + 60_000, now))
            val second = BardoFileAuthorityLedger(base)
            assertFalse(second.consumeOnce(id, now + 60_000, now))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test fun grantRevocationSurvivesNewLedgerInstance() {
        val base = createTempDir(prefix = "bardo-authority-revoke")
        try {
            val id = UUID.randomUUID().toString().replace("-", "")
            val first = BardoFileAuthorityLedger(base)
            assertFalse(first.isRevoked(id))
            first.revokeGrant(id)
            val second = BardoFileAuthorityLedger(base)
            assertTrue(second.isRevoked(id))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test fun rejectsInvalidIdsAndDoesNotCreateFilesOutsideLedger() {
        val base = createTempDir(prefix = "bardo-authority-path")
        try {
            val ledger = BardoFileAuthorityLedger(File(base, "ledger").apply { mkdirs() })
            val now = System.currentTimeMillis()
            assertTrue(runCatching { ledger.consumeOnce("../bad", now + 1000, now) }.isFailure)
            assertTrue(runCatching { ledger.revokeGrant("../bad") }.isFailure)
            assertTrue(!File(base, "bad").exists())
        } finally {
            base.deleteRecursively()
        }
    }
}
