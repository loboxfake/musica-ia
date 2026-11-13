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

    /**
     * Acepta el árbol Descargas o directamente su subcarpeta BARDO.
     * Android puede devolver cualquiera de los dos según la selección del usuario.
     */
    fun isLocalDownloadsTree(
        scheme: String?,
        authority: String?,
        treeDocumentId: String?
    ): Boolean =
        scheme.equals("content", ignoreCase = true) &&
            authority == EXTERNAL_STORAGE_AUTHORITY &&
            treeDocumentId in setOf(PRIMARY_DOWNLOADS_TREE_ID, BARDO_ROOT_DOCUMENT_ID)

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
        if (!destination.startsWith(rootDocumentId + "/")) return false
        val suffix = destination.removePrefix(rootDocumentId + "/")
        val segments = suffix.split('/')
        return segments.isNotEmpty() && segments.all {
            it.isNotBlank() && it != "." && it != ".." &&
                it.none { character -> character == '\\' || character == '\u0000' }
        }
    }
}
