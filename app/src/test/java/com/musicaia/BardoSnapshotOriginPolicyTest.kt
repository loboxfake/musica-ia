package com.musicaia

import com.musicaia.desarrollo.BardoSnapshotOriginPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoSnapshotOriginPolicyTest {
    @Test
    fun onlyBardoOwnedSnapshotsMayReachPrivateRepositoryCi() {
        assertTrue(BardoSnapshotOriginPolicy.allowsPrivateRepositoryCi(BardoSnapshotOriginPolicy.BUNDLED_BARDO))
        assertTrue(BardoSnapshotOriginPolicy.allowsPrivateRepositoryCi(BardoSnapshotOriginPolicy.PRIVATE_GITHUB_MAIN))
        assertFalse(BardoSnapshotOriginPolicy.allowsPrivateRepositoryCi(BardoSnapshotOriginPolicy.USER_IMPORTED_PROJECT))
        assertFalse(BardoSnapshotOriginPolicy.allowsPrivateRepositoryCi(null))
        assertFalse(BardoSnapshotOriginPolicy.allowsPrivateRepositoryCi(""))
        assertFalse(BardoSnapshotOriginPolicy.allowsPrivateRepositoryCi("unknown"))
    }
}
