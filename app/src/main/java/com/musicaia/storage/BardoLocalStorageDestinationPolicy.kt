package com.musicaia.storage

/**
 * Cloud-blocking policy for BARDO's shared workspace and all export targets.
 * Only Android's primary internal Downloads tree is accepted; arbitrary SAF
 * providers (Drive, OneDrive, remote DAV, removable cards, etc.) fail closed.
 */
object BardoLocalStorageDestinationPolicy {
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    const val PRIMARY_DOWNLOADS_TREE_ID = "primary:Download"
    const val BARDO_ROOT_DOCUMENT_ID = "primary:Download/BARDO"

    fun isLocalDownloadsTree(
        scheme: String?,
        authority: String?,
        treeDocumentId: String?
    ): Boolean =
        scheme.equals("content", ignoreCase = true) &&
            authority == EXTERNAL_STORAGE_AUTHORITY &&
            treeDocumentId == PRIMARY_DOWNLOADS_TREE_ID

    fun isBardoRoot(
        scheme: String?,
        authority: String?,
        treeDocumentId: String?,
        rootDocumentId: String?
    ): Boolean =
        isLocalDownloadsTree(scheme, authority, treeDocumentId) &&
            rootDocumentId == BARDO_ROOT_DOCUMENT_ID

    fun isLocalBardoDestination(
        scheme: String?,
        authority: String?,
        treeDocumentId: String?,
        rootDocumentId: String?,
        destinationDocumentId: String?
    ): Boolean {
        if (!isBardoRoot(scheme, authority, treeDocumentId, rootDocumentId)) return false
        val destination = destinationDocumentId ?: return false
        return destination.startsWith(rootDocumentId + "/")
    }
}
