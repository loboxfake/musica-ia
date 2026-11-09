package com.musicaia

import com.musicaia.storage.BardoMobileFileApproval
import com.musicaia.storage.BardoMobileFileOperation
import com.musicaia.storage.BardoMobileFilePolicy
import org.junit.Assert.*
import org.junit.Test

class BardoMobileFilePolicyTest {
    @Test fun rejectsTraversalAndAbsolutePaths() {
        assertFalse(BardoMobileFilePolicy.authorize(
            BardoMobileFileOperation.READ, "../otra-app/secret.txt"
        ).allowed)
        assertFalse(BardoMobileFilePolicy.authorize(
            BardoMobileFileOperation.READ, "/storage/emulated/0/Download/secret.txt"
        ).allowed)
        assertFalse(BardoMobileFilePolicy.authorize(
            BardoMobileFileOperation.READ, "BARDO\\..\\otra-app"
        ).allowed)
    }

    @Test fun permitsReadAndCreateInsideGrantedTreeWithoutDeleteApproval() {
        assertTrue(BardoMobileFilePolicy.authorize(
            BardoMobileFileOperation.READ, "Proyectos/notas.txt", nowMillis = 10_000
        ).allowed)
        assertTrue(BardoMobileFilePolicy.authorize(
            BardoMobileFileOperation.CREATE_DIRECTORY, "Skills/nueva", nowMillis = 10_000
        ).allowed)
    }

    @Test fun destructiveOperationsRequirePathAndOperationBoundApproval() {
        val noApproval = BardoMobileFilePolicy.authorize(
            BardoMobileFileOperation.MOVE_TO_TRASH, "Skills/antigua.bskill", nowMillis = 10_000
        )
        assertFalse(noApproval.allowed)
        assertTrue(noApproval.requiresUserApproval)

        val approval = BardoMobileFileApproval(
            operation = BardoMobileFileOperation.MOVE_TO_TRASH,
            relativePath = "Skills/antigua.bskill",
            issuedAtMillis = 9_000,
            expiresAtMillis = 11_000,
            nonce = "abcdefghijklmnop"
        )
        assertTrue(BardoMobileFilePolicy.authorize(
            BardoMobileFileOperation.MOVE_TO_TRASH,
            "Skills/antigua.bskill",
            approval,
            nowMillis = 10_000
        ).allowed)
        assertFalse(BardoMobileFilePolicy.authorize(
            BardoMobileFileOperation.PERMANENT_DELETE,
            "Skills/antigua.bskill",
            approval,
            nowMillis = 10_000
        ).allowed)
        assertFalse(BardoMobileFilePolicy.authorize(
            BardoMobileFileOperation.MOVE_TO_TRASH,
            "Skills/otra.bskill",
            approval,
            nowMillis = 10_000
        ).allowed)
    }

    @Test fun expiredApprovalIsRejected() {
        val approval = BardoMobileFileApproval(
            BardoMobileFileOperation.PERMANENT_DELETE,
            "Temp/old.txt",
            1_000,
            2_000,
            "abcdefghijklmnop"
        )
        val decision = BardoMobileFilePolicy.authorize(
            BardoMobileFileOperation.PERMANENT_DELETE,
            "Temp/old.txt",
            approval,
            nowMillis = 2_001
        )
        assertFalse(decision.allowed)
        assertTrue(decision.requiresUserApproval)
    }

    @Test fun originalGemmaModelCannotBeDeletedByMobileFileManager() {
        val decision = BardoMobileFilePolicy.authorize(
            BardoMobileFileOperation.MOVE_TO_TRASH,
            "Modelos/gemma-4-E2B-it.litertlm",
            nowMillis = 10_000
        )
        assertFalse(decision.allowed)
        assertFalse(decision.requiresUserApproval)
    }
}
