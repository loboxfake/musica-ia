package com.musicaia.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoLocalStorageDestinationPolicyTest {
    @Test
    fun acceptsOnlyThePrimaryInternalDownloadsOrBardoTree() {
        assertTrue(
            BardoLocalStorageDestinationPolicy.isLocalDownloadsTree(
                "content",
                "com.android.externalstorage.documents",
                "primary:Download"
            )
        )
        assertFalse(
            BardoLocalStorageDestinationPolicy.isLocalDownloadsTree(
                "content",
                "com.google.android.apps.docs.storage",
                "primary:Download"
            )
        )
        assertFalse(
            BardoLocalStorageDestinationPolicy.isLocalDownloadsTree(
                "content",
                "com.android.externalstorage.documents",
                "primary:Documents"
            )
        )
        assertFalse(
            BardoLocalStorageDestinationPolicy.isLocalDownloadsTree(
                "https",
                "com.android.externalstorage.documents",
                "primary:Download"
            )
        )
        assertTrue(
            BardoLocalStorageDestinationPolicy.isLocalDownloadsTree(
                "content",
                "com.android.externalstorage.documents",
                "primary:Download/BARDO"
            )
        )
        assertFalse(
            BardoLocalStorageDestinationPolicy.isLocalDownloadsTree(
                "content",
                "com.android.externalstorage.documents",
                "primary:Download/Other"
            )
        )
    }

    @Test
    fun acceptsOnlyDescendantsOfTheExactBardoRoot() {
        val authority = BardoLocalStorageDestinationPolicy.EXTERNAL_STORAGE_AUTHORITY
        val tree = BardoLocalStorageDestinationPolicy.PRIMARY_DOWNLOADS_TREE_ID
        val root = BardoLocalStorageDestinationPolicy.BARDO_ROOT_DOCUMENT_ID

        assertTrue(
            BardoLocalStorageDestinationPolicy.isBardoRoot(
                "content", authority, tree, root
            )
        )
        assertFalse(
            BardoLocalStorageDestinationPolicy.isBardoRoot(
                "content", authority, tree, "primary:Download/DriveMirror/BARDO"
            )
        )
        // SAF también permite que el usuario seleccione directamente Descargas/BARDO.
        assertTrue(
            BardoLocalStorageDestinationPolicy.isBardoRoot(
                "content", authority, root, root
            )
        )
        assertTrue(
            BardoLocalStorageDestinationPolicy.isLocalBardoDestination(
                "content", authority, root, root,
                "primary:Download/BARDO/Canciones/song.wav"
            )
        )
        assertTrue(
            BardoLocalStorageDestinationPolicy.isLocalBardoDestination(
                "content", authority, tree, root,
                "primary:Download/BARDO/Canciones/song.wav"
            )
        )
        assertFalse(
            BardoLocalStorageDestinationPolicy.isLocalBardoDestination(
                "content", authority, tree, root,
                "primary:Download/song.wav"
            )
        )
        assertFalse(
            BardoLocalStorageDestinationPolicy.isLocalBardoDestination(
                "content", authority, tree, root,
                "primary:Download/BARDO-OTHER/song.wav"
            )
        )
        assertFalse(
            BardoLocalStorageDestinationPolicy.isLocalBardoDestination(
                "content", authority, tree, root,
                "primary:Download/BARDO/../song.wav"
            )
        )
        assertFalse(
            BardoLocalStorageDestinationPolicy.isLocalBardoDestination(
                "content", authority, tree, root,
                "primary:Download/BARDO/Canciones//song.wav"
            )
        )
    }
}
