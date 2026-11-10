package com.musicaia.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class BardoExportReceipt(
    val relativePath: String,
    val bytes: Long,
    val sha256: String
)

data class BardoMobileFileDeletionRequest(
    val id: String,
    val relativePath: String,
    val permanent: Boolean,
    val reason: String,
    val requestedAtMillis: Long,
    val documentId: String? = null,
    val name: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val lastModifiedMillis: Long? = null
) {
    fun matchesCurrentIdentity(documentId: String, current: BardoMobileFileEntry): Boolean =
        !this.documentId.isNullOrBlank() &&
            this.documentId == documentId &&
            this.name == current.name &&
            this.mimeType == current.mimeType &&
            this.sizeBytes == current.sizeBytes &&
            this.lastModifiedMillis == current.lastModifiedMillis
}

data class BardoMobileFileEntry(
    val relativePath: String,
    val name: String,
    val isDirectory: Boolean,
    val mimeType: String,
    val sizeBytes: Long?,
    val lastModifiedMillis: Long?
)

/**
 * Acceso limitado al árbol BARDO elegido y autorizado por el usuario mediante SAF.
 * No recorre otras carpetas del dispositivo y no elimina archivos de origen
 * durante operaciones de copia.
 */
object BardoSharedStorage {
    private data class Location(val tree: Uri, val root: Uri)

    private fun location(context: Context): Location? {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("bardo_storage", Context.MODE_PRIVATE)
        val rawTree = prefs.getString("tree_uri", null) ?: return null
        val tree = runCatching { Uri.parse(rawTree) }.getOrNull() ?: return null
        val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
        if (!BardoLocalStorageDestinationPolicy.isLocalDownloadsTree(
                tree.scheme, tree.authority, treeDocumentId
            )
        ) return null
        val rootId = prefs.getString("root_document_id", null)
            ?: treeDocumentId
        if (!BardoLocalStorageDestinationPolicy.isBardoRoot(
                tree.scheme, tree.authority, treeDocumentId, rootId
            )
        ) return null
        val permission = app.contentResolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        }
        if (!permission) return null
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val rootInfo = runCatching { metadata(app, root) }.getOrNull() ?: return null
        if (!rootInfo.isDirectory || !rootInfo.name.equals("BARDO", ignoreCase = true)) {
            return null
        }
        return Location(tree, root)
    }

    fun isConfigured(context: Context): Boolean = location(context) != null

    /** True only for a document inside the user's configured on-device BARDO folder. */
    fun isAuthorizedLocalBardoDestination(context: Context, documentUri: Uri): Boolean = runCatching {
        val loc = location(context) ?: return@runCatching false
        val treeId = DocumentsContract.getTreeDocumentId(loc.tree)
        val rootId = DocumentsContract.getDocumentId(loc.root)
        val documentId = DocumentsContract.getDocumentId(documentUri)
        BardoLocalStorageDestinationPolicy.isLocalBardoDestination(
            documentUri.scheme,
            documentUri.authority,
            treeId,
            rootId,
            documentId
        )
    }.getOrDefault(false)

    private fun findChild(
        context: Context,
        location: Location,
        parent: Uri,
        name: String,
        directory: Boolean?
    ): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            location.tree, DocumentsContract.getDocumentId(parent)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        return context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val typeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val isDirectory = cursor.getString(typeColumn) == DocumentsContract.Document.MIME_TYPE_DIR
                if (cursor.getString(nameColumn) == name &&
                    (directory == null || directory == isDirectory)
                ) {
                    return@use DocumentsContract.buildDocumentUriUsingTree(
                        location.tree, cursor.getString(idColumn)
                    )
                }
            }
            null
        }
    }

    private fun resolveDirectory(
        context: Context,
        loc: Location,
        relativePath: String
    ): Uri {
        val normalized = BardoMobileFilePolicy.normalizeRelativePath(relativePath)
            ?: throw IllegalArgumentException("Ruta relativa no válida.")
        var current = loc.root
        if (normalized.isEmpty()) return current
        for (segment in normalized.split('/')) {
            current = findChild(context, loc, current, segment, true)
                ?: throw IllegalArgumentException("No existe la carpeta BARDO/$normalized.")
        }
        return current
    }

    private fun resolveEntry(
        context: Context,
        loc: Location,
        relativePath: String
    ): Pair<Uri, Uri> {
        val normalized = BardoMobileFilePolicy.normalizeRelativePath(relativePath)
            ?: throw IllegalArgumentException("Ruta relativa no válida.")
        require(normalized.isNotEmpty()) { "La ruta debe identificar un archivo o carpeta." }
        val segments = normalized.split('/')
        val parentPath = segments.dropLast(1).joinToString("/")
        val parent = resolveDirectory(context, loc, parentPath)
        val entry = findChild(context, loc, parent, segments.last(), null)
            ?: throw IllegalArgumentException("No existe BARDO/$normalized.")
        return entry to parent
    }

    private fun metadata(context: Context, uri: Uri): BardoMobileFileEntry? {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
            val mime = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            BardoMobileFileEntry(
                relativePath = name,
                name = name,
                isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                mimeType = mime.orEmpty(),
                sizeBytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null,
                lastModifiedMillis = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex) else null
            )
        }
    }

    /** Lista únicamente hijos directos del directorio relativo indicado. */
    fun listEntries(context: Context, relativeDirectory: String = ""): List<BardoMobileFileEntry> {
        val decision = BardoMobileFilePolicy.authorize(
            BardoMobileFileOperation.LIST, relativeDirectory
        )
        require(decision.allowed) { decision.reason }
        val loc = location(context) ?: throw IllegalStateException(
            "No existe un permiso SAF persistente para Descargas/BARDO."
        )
        val parent = resolveDirectory(context, loc, decision.normalizedPath.orEmpty())
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            loc.tree, DocumentsContract.getDocumentId(parent)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val result = mutableListOf<BardoMobileFileEntry>()
        context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext() && result.size < MAX_LIST_ENTRIES) {
                val name = cursor.getString(nameCol) ?: continue
                val mime = cursor.getString(mimeCol).orEmpty()
                val isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val parentPath = decision.normalizedPath.orEmpty()
                val relative = if (parentPath.isEmpty()) name else "$parentPath/$name"
                BardoMobileFilePolicy.normalizeRelativePath(relative) ?: continue
                result += BardoMobileFileEntry(
                    relativePath = relative,
                    name = name,
                    isDirectory = isDirectory,
                    mimeType = mime,
                    sizeBytes = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else null,
                    lastModifiedMillis = if (modifiedCol >= 0 && !cursor.isNull(modifiedCol)) cursor.getLong(modifiedCol) else null
                )
            }
        } ?: throw IllegalStateException("El proveedor SAF no permitió listar la carpeta.")
        return result.sortedWith(compareBy<BardoMobileFileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    /** Lectura UTF-8 por tramos; los archivos grandes no se cargan completos en RAM. */
    fun readText(
        context: Context,
        relativePath: String
    ): String = readTextRange(context, relativePath, 0, MAX_READ_CHUNK_CHARS)

    fun readTextRange(
        context: Context,
        relativePath: String,
        offsetChars: Int = 0,
        maxChars: Int = MAX_READ_CHUNK_CHARS
    ): String {
        require(offsetChars in 0..MAX_READ_OFFSET_CHARS) { "El desplazamiento supera el límite permitido." }
        require(maxChars in 1..MAX_READ_CHUNK_CHARS) { "El tramo debe contener entre 1 y $MAX_READ_CHUNK_CHARS caracteres." }
        val decision = BardoMobileFilePolicy.authorize(BardoMobileFileOperation.READ, relativePath)
        require(decision.allowed) { decision.reason }
        val loc = location(context) ?: throw IllegalStateException("No existe permiso SAF para BARDO.")
        val (uri, _) = resolveEntry(context, loc, decision.normalizedPath!!)
        val info = metadata(context, uri) ?: throw IllegalStateException("No se pudo consultar el archivo.")
        require(!info.isDirectory) { "La ruta apunta a una carpeta, no a un archivo." }
        require(isTextMime(info.mimeType, info.name)) { "BARDO solo lee texto UTF-8 desde esta herramienta." }
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("No se pudo abrir el documento SAF.")
        return input.use { stream ->
            InputStreamReader(stream, Charsets.UTF_8).buffered().use readerUse@ { reader ->
                var remaining = offsetChars.toLong()
                while (remaining > 0L) {
                    val skipped = reader.skip(remaining)
                    if (skipped > 0L) {
                        remaining -= skipped
                    } else {
                        if (reader.read() < 0) return@readerUse ""
                        remaining--
                    }
                }
                val result = StringBuilder(minOf(maxChars, 4096))
                val buffer = CharArray(minOf(maxChars, 4096))
                var total = 0
                while (total < maxChars) {
                    val count = reader.read(buffer, 0, minOf(buffer.size, maxChars - total))
                    if (count < 0) break
                    if (count == 0) continue
                    result.append(buffer, 0, count)
                    total += count
                }
                result.toString()
            }
        }
    }

    /** Crea toda la ruta anidada; reutiliza solo los directorios ya existentes. */
    fun createDirectory(context: Context, relativePath: String): String {
        val decision = BardoMobileFilePolicy.authorize(BardoMobileFileOperation.CREATE_DIRECTORY, relativePath)
        require(decision.allowed) { decision.reason }
        val loc = location(context) ?: throw IllegalStateException("No existe permiso SAF para BARDO.")
        val normalized = decision.normalizedPath!!
        val segments = normalized.split('/')
        var parent = loc.root
        var traversed = ""
        segments.forEachIndexed { index, rawName ->
            val name = validateName(rawName, allowExtension = true)
            traversed = if (traversed.isEmpty()) name else "$traversed/$name"
            val existing = findChild(context, loc, parent, name, null)
            if (index < segments.lastIndex) {
                if (existing != null) {
                    val info = metadata(context, existing)
                    require(info?.isDirectory == true) {
                        "La ruta BARDO/$traversed ya existe como archivo."
                    }
                    parent = existing
                } else {
                    parent = DocumentsContract.createDocument(
                        context.contentResolver,
                        parent,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        name
                    ) ?: throw IllegalStateException("El proveedor SAF no pudo crear BARDO/$traversed.")
                    recordAudit(context, "CREATE_DIRECTORY", traversed, "SUCCESS")
                }
            } else {
                require(existing == null) { "Ya existe BARDO/$traversed." }
                DocumentsContract.createDocument(
                    context.contentResolver,
                    parent,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    name
                ) ?: throw IllegalStateException("El proveedor SAF no pudo crear BARDO/$traversed.")
                recordAudit(context, "CREATE_DIRECTORY", traversed, "SUCCESS")
            }
        }
        return normalized
    }

    fun createTextFile(context: Context, relativePath: String, content: String): String {
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        require(contentBytes.size <= BardoMobileFilePolicy.MAX_TEXT_READ_BYTES) {
            "El archivo de texto supera el máximo de 48 KiB en UTF-8."
        }
        val decision = BardoMobileFilePolicy.authorize(BardoMobileFileOperation.CREATE_FILE, relativePath)
        require(decision.allowed) { decision.reason }
        val loc = location(context) ?: throw IllegalStateException("No existe permiso SAF para BARDO.")
        val normalized = decision.normalizedPath!!
        val segments = normalized.split('/')
        val parentPath = segments.dropLast(1).joinToString("/")
        val name = validateName(segments.last(), allowExtension = true)
        val parent = resolveDirectory(context, loc, parentPath)
        require(findChild(context, loc, parent, name, null) == null) { "Ya existe BARDO/$normalized." }
        val target = DocumentsContract.createDocument(
            context.contentResolver, parent, mimeForName(name), name
        ) ?: throw IllegalStateException("El proveedor SAF no pudo crear el archivo.")
        try {
            context.contentResolver.openOutputStream(target, "w")?.use { output ->
                output.write(contentBytes)
                output.flush()
            } ?: throw IllegalStateException("No se pudo abrir el archivo creado.")
            val saved = readText(context, normalized)
            check(saved == content) { "La verificación de lectura tras la escritura no coincide." }
            recordAudit(context, "CREATE_TEXT_FILE", normalized, "SUCCESS")
            return normalized
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
            throw error
        }
    }

    /**
     * Mueve un elemento a BARDO/Papelera usando la operación MOVE nativa del proveedor.
     * Si el proveedor no admite movimientos, falla sin borrar ni copiar el original.
     */
    private fun moveToTrash(
        context: Context,
        location: Location,
        source: Uri,
        sourceParent: Uri,
        relativePath: String,
        explicitUserConfirmation: Boolean
    ): String {
        require(explicitUserConfirmation) { "La operación requiere confirmación directa del usuario." }
        val approval = consumeApproval(context, BardoMobileFileOperation.MOVE_TO_TRASH, relativePath)
        require(approval.allowed && approval.normalizedPath == relativePath) { approval.reason }
        val trash = findChild(context, location, location.root, TRASH_DIRECTORY, true)
            ?: DocumentsContract.createDocument(
                context.contentResolver, location.root,
                DocumentsContract.Document.MIME_TYPE_DIR, TRASH_DIRECTORY
            ) ?: throw IllegalStateException("No se pudo crear la papelera BARDO.")
        val moved = DocumentsContract.moveDocument(context.contentResolver, source, sourceParent, trash)
            ?: throw IllegalStateException("El proveedor SAF no admite mover elementos; no se borró el original.")
        val name = metadata(context, moved)?.name ?: approval.normalizedPath.substringAfterLast('/')
        recordAudit(context, "MOVE_TO_TRASH", approval.normalizedPath, "SUCCESS")
        return "$TRASH_DIRECTORY/$name"
    }

    /** Eliminación permanente limitada a archivos o carpetas vacías y confirmación de una sola operación. */
    private fun deletePermanently(
        context: Context,
        location: Location,
        target: Uri,
        relativePath: String,
        explicitUserConfirmation: Boolean
    ): Boolean {
        require(explicitUserConfirmation) { "La eliminación requiere confirmación directa del usuario." }
        val approval = consumeApproval(context, BardoMobileFileOperation.PERMANENT_DELETE, relativePath)
        require(approval.allowed && approval.normalizedPath == relativePath) { approval.reason }
        val info = metadata(context, target) ?: throw IllegalStateException("No se pudo consultar el elemento.")
        if (info.isDirectory) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                location.tree, DocumentsContract.getDocumentId(target)
            )
            val count = context.contentResolver.query(
                children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null
            )?.use { it.count } ?: throw IllegalStateException("No se pudo verificar si la carpeta está vacía.")
            require(count == 0) { "Por seguridad solo se eliminan carpetas vacías." }
        }
        val deleted = DocumentsContract.deleteDocument(context.contentResolver, target)
        recordAudit(
            context,
            "PERMANENT_DELETE",
            approval.normalizedPath!!,
            if (deleted) "SUCCESS" else "PROVIDER_RETURNED_FALSE"
        )
        return deleted
    }

    /**
     * BARDO solicita una eliminación, pero no la ejecuta. La solicitud queda
     * pendiente hasta que el usuario la revise y confirme en la interfaz.
     */
    fun requestDeletion(
        context: Context,
        relativePath: String,
        permanent: Boolean,
        reason: String
    ): BardoMobileFileDeletionRequest {
        val operation = if (permanent) BardoMobileFileOperation.PERMANENT_DELETE
            else BardoMobileFileOperation.MOVE_TO_TRASH
        val decision = BardoMobileFilePolicy.authorize(operation, relativePath)
        require(decision.requiresUserApproval && decision.normalizedPath != null) {
            decision.reason.ifBlank { "La solicitud de eliminación no es válida." }
        }
        val loc = location(context) ?: throw IllegalStateException("No existe permiso SAF para BARDO.")
        val (entryUri, _) = resolveEntry(context, loc, decision.normalizedPath)
        val entryInfo = metadata(context, entryUri)
            ?: throw IllegalStateException("No se pudo registrar la identidad del documento.")
        val documentId = DocumentsContract.getDocumentId(entryUri)
        require(documentId.isNotBlank()) { "El proveedor SAF no facilitó un identificador estable." }
        val prefs = context.applicationContext.getSharedPreferences(DELETE_REQUESTS_PREFS, Context.MODE_PRIVATE)
        val current = readRequests(prefs.getString(DELETE_REQUESTS_KEY, "[]").orEmpty())
        require(current.size < MAX_PENDING_DELETE_REQUESTS) {
            "Hay demasiadas solicitudes pendientes; revisa primero las existentes."
        }
        require(current.none { it.relativePath == decision.normalizedPath && it.permanent == permanent }) {
            "Ya existe una solicitud pendiente para esta operación y ruta."
        }
        val request = BardoMobileFileDeletionRequest(
            id = UUID.randomUUID().toString(),
            relativePath = decision.normalizedPath,
            permanent = permanent,
            reason = reason.trim().take(MAX_DELETE_REASON_CHARS).ifBlank {
                "Mantenimiento solicitado por BARDO."
            },
            requestedAtMillis = System.currentTimeMillis(),
            documentId = documentId,
            name = entryInfo.name,
            mimeType = entryInfo.mimeType,
            sizeBytes = entryInfo.sizeBytes,
            lastModifiedMillis = entryInfo.lastModifiedMillis
        )
        val updated = current + request
        check(prefs.edit().putString(DELETE_REQUESTS_KEY, requestsJson(updated).toString()).commit()) {
            "No se pudo guardar la solicitud; no se ha eliminado nada."
        }
        recordAudit(context, "REQUEST_DELETE", request.relativePath, if (permanent) "PENDING_PERMANENT" else "PENDING_TRASH")
        return request
    }

    fun pendingDeletionRequests(context: Context): List<BardoMobileFileDeletionRequest> {
        val prefs = context.applicationContext.getSharedPreferences(DELETE_REQUESTS_PREFS, Context.MODE_PRIVATE)
        return readRequests(prefs.getString(DELETE_REQUESTS_KEY, "[]").orEmpty())
            .sortedBy { it.requestedAtMillis }
    }

    fun rejectDeletionRequest(context: Context, requestId: String): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(DELETE_REQUESTS_PREFS, Context.MODE_PRIVATE)
        val current = readRequests(prefs.getString(DELETE_REQUESTS_KEY, "[]").orEmpty())
        val updated = current.filterNot { it.id == requestId }
        if (updated.size == current.size) return false
        val request = current.firstOrNull { it.id == requestId }
        val saved = prefs.edit().putString(DELETE_REQUESTS_KEY, requestsJson(updated).toString()).commit()
        if (saved && request != null) recordAudit(context, "REJECT_DELETE_REQUEST", request.relativePath, "REJECTED_BY_USER")
        return saved
    }

    /**
     * Esta función se invoca solo desde la acción positiva de un diálogo UI
     * que muestra la ruta y el tipo de eliminación. Los agentes no reciben
     * una herramienta para aprobar solicitudes.
     */
    fun approveDeletionRequest(context: Context, requestId: String): String {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(DELETE_REQUESTS_PREFS, Context.MODE_PRIVATE)
        val current = readRequests(prefs.getString(DELETE_REQUESTS_KEY, "[]").orEmpty())
        val request = current.firstOrNull { it.id == requestId }
            ?: throw IllegalArgumentException("La solicitud ya no existe o fue rechazada.")
        val loc = location(app) ?: throw IllegalStateException("No existe permiso SAF para BARDO.")
        val (target, parent) = resolveEntry(app, loc, request.relativePath)
        val currentInfo = metadata(app, target)
            ?: throw IllegalStateException("No se pudo comprobar el documento pendiente.")
        val currentId = DocumentsContract.getDocumentId(target)
        require(request.matchesCurrentIdentity(currentId, currentInfo)) {
            "El elemento de Descargas/BARDO cambió desde la solicitud. No se ha movido ni eliminado; rechaza esta solicitud y crea otra si procede."
        }
        val outcome = if (request.permanent) {
            check(deletePermanently(
                app, loc, target, request.relativePath, explicitUserConfirmation = true
            )) {
                "El proveedor SAF no confirmó la eliminación."
            }
            "Eliminado permanentemente: BARDO/" + request.relativePath
        } else {
            "Movido a BARDO/" + moveToTrash(
                app, loc, target, parent, request.relativePath, explicitUserConfirmation = true
            )
        }
        val latest = readRequests(prefs.getString(DELETE_REQUESTS_KEY, "[]").orEmpty())
        val updated = latest.filterNot { it.id == requestId }
        check(prefs.edit().putString(DELETE_REQUESTS_KEY, requestsJson(updated).toString()).commit()) {
            "La operación terminó, pero no se pudo cerrar el registro de la solicitud."
        }
        return outcome
    }

    private fun readRequests(raw: String): List<BardoMobileFileDeletionRequest> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                val id = row.optString("id")
                val path = row.optString("path")
                val reason = row.optString("reason")
                val operation = row.optString("operation")
                val requestedAt = row.optLong("requestedAt", 0L)
                if (id.isNotBlank() && path.isNotBlank() &&
                    operation in setOf("MOVE_TO_TRASH", "PERMANENT_DELETE") &&
                    requestedAt > 0L &&
                    BardoMobileFilePolicy.normalizeRelativePath(path) != null
                ) {
                    add(BardoMobileFileDeletionRequest(
                        id = id,
                        relativePath = path,
                        permanent = operation == "PERMANENT_DELETE",
                        reason = reason.take(MAX_DELETE_REASON_CHARS),
                        requestedAtMillis = requestedAt,
                        documentId = row.optString("documentId").takeIf { it.isNotBlank() },
                        name = row.optString("name").takeIf { it.isNotBlank() },
                        mimeType = row.optString("mimeType").takeIf { it.isNotBlank() },
                        sizeBytes = if (row.has("sizeBytes") && !row.isNull("sizeBytes")) row.optLong("sizeBytes") else null,
                        lastModifiedMillis = if (row.has("lastModifiedMillis") && !row.isNull("lastModifiedMillis")) row.optLong("lastModifiedMillis") else null
                    ))
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun requestsJson(requests: List<BardoMobileFileDeletionRequest>) = JSONArray().also { array ->
        requests.forEach { request ->
            array.put(JSONObject()
                .put("id", request.id)
                .put("path", request.relativePath)
                .put("operation", if (request.permanent) "PERMANENT_DELETE" else "MOVE_TO_TRASH")
                .put("reason", request.reason)
                .put("requestedAt", request.requestedAtMillis)
                .put("documentId", request.documentId)
                .put("name", request.name)
                .put("mimeType", request.mimeType)
                .put("sizeBytes", request.sizeBytes)
                .put("lastModifiedMillis", request.lastModifiedMillis)
            )
        }
    }

    private fun consumeApproval(
        context: Context,
        operation: BardoMobileFileOperation,
        rawPath: String
    ): BardoMobileFileDecision {
        val normalized = BardoMobileFilePolicy.normalizeRelativePath(rawPath)
            ?: throw IllegalArgumentException("Ruta relativa no válida.")
        val now = System.currentTimeMillis()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val approval = BardoMobileFileApproval(
            operation = operation,
            relativePath = normalized,
            issuedAtMillis = now,
            expiresAtMillis = now + 30_000L,
            nonce = nonce
        )
        val decision = BardoMobileFilePolicy.authorize(operation, normalized, approval, now)
        require(decision.allowed) { decision.reason }
        // El token se crea en memoria después de la confirmación visible, no se
        // persiste ni se expone a herramientas; la aprobación es efímera y de un solo uso.
        return decision
    }

    /** Localiza el Gemma original sin copiarlo, renombrarlo ni eliminarlo. */
    fun findFile(context: Context, folderName: String, fileName: String): Uri? {
        val loc = location(context) ?: return null
        val folder = findChild(context, loc, loc.root, folderName, true) ?: return null
        return findChild(context, loc, folder, fileName, false)
    }

    /** Exporta únicamente archivos producidos por BARDO; nunca elimina el archivo fuente. */
    fun copyFile(
        context: Context,
        folderName: String,
        source: File,
        destinationName: String,
        mimeType: String
    ): String {
        val developerWorkspace = File(context.applicationContext.filesDir, "bardo_developer")
        require(BardoExportSourcePolicy.allows(developerWorkspace, source)) {
            "Solo se pueden exportar archivos regulares creados dentro del workspace privado de BARDO."
        }
        require(folderName.matches(Regex("[A-Za-z0-9 _-]{1,64}"))) {
            "Nombre de carpeta no permitido."
        }
        require(destinationName.matches(Regex("[A-Za-z0-9._ -]{1,180}")) &&
            !destinationName.startsWith(".") && !destinationName.contains("..")
        ) { "Nombre de archivo no permitido." }

        val loc = location(context) ?: throw IllegalStateException(
            "No hay permiso SAF para Descargas/BARDO. Configura la carpeta desde BARDO."
        )
        val folder = findChild(context, loc, loc.root, folderName, true)
            ?: DocumentsContract.createDocument(
                context.contentResolver,
                loc.root,
                DocumentsContract.Document.MIME_TYPE_DIR,
                folderName
            ) ?: throw IllegalStateException("No se pudo crear la carpeta " + folderName + ".")

        val target = DocumentsContract.createDocument(
            context.contentResolver, folder, mimeType, destinationName
        ) ?: throw IllegalStateException("No se pudo crear " + destinationName + ".")

        try {
            val output = context.contentResolver.openOutputStream(target, "w")
                ?: throw IllegalStateException("No se pudo abrir el destino de " + destinationName + ".")
            var copied = 0L
            output.use { stream ->
                source.inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        stream.write(buffer, 0, count)
                        copied += count
                    }
                }
            }
            check(copied == source.length()) {
                "La copia de " + destinationName + " no coincide con el tamaño original."
            }
            return folderName + "/" + destinationName
        } catch (error: Exception) {
            // Solo se elimina el destino parcial creado por esta misma operación.
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
            throw error
        }
    }

    /**
     * Exporta un artefacto BARDO desde almacenamiento privado y verifica la copia
     * leyendo de nuevo el documento SAF. Un recuento de bytes escrito no basta.
     */
    fun exportGeneratedFile(
        context: Context,
        relativeDirectory: String,
        source: File,
        destinationName: String,
        mimeType: String
    ): BardoExportReceipt {
        val app = context.applicationContext
        require(source.isFile && source.length() > 0L) {
            "El archivo de origen no existe o está vacío."
        }
        val allowedRoots = listOfNotNull(
            app.filesDir,
            app.cacheDir,
            app.getExternalFilesDir(null),
            app.externalCacheDir
        )
        require(BardoGeneratedExportSourcePolicy.allows(allowedRoots, source)) {
            "Por seguridad, solo se exportan archivos creados o importados dentro del almacenamiento privado de BARDO."
        }
        require(mimeType.isNotBlank()) { "El tipo MIME no puede estar vacío." }
        val directory = BardoMobileFilePolicy.normalizeRelativePath(relativeDirectory)
            ?: throw IllegalArgumentException("La carpeta de destino no es válida.")
        require(directory.isNotEmpty()) { "Indica una carpeta dentro de Descargas/BARDO." }
        val directoryDecision = BardoMobileFilePolicy.authorize(
            BardoMobileFileOperation.CREATE_DIRECTORY, directory
        )
        require(directoryDecision.allowed) { directoryDecision.reason }
        createDirectory(app, directory)

        val name = validateName(destinationName, allowExtension = true)
        val relativePath = "$directory/$name"
        val fileDecision = BardoMobileFilePolicy.authorize(
            BardoMobileFileOperation.CREATE_FILE, relativePath
        )
        require(fileDecision.allowed) { fileDecision.reason }
        val loc = location(app) ?: throw IllegalStateException(
            "No existe permiso SAF persistente y verificable para Descargas/BARDO."
        )
        val parent = resolveDirectory(app, loc, directory)
        val target = DocumentsContract.createDocument(
            app.contentResolver, parent, mimeType, name
        ) ?: throw IllegalStateException("Android no pudo crear BARDO/$relativePath.")

        try {
            check(isAuthorizedLocalBardoDestination(app, target)) {
                "Android devolvió un destino fuera de la carpeta local BARDO autorizada."
            }
            val expectedBytes = source.length()
            val writeDigest = java.security.MessageDigest.getInstance("SHA-256")
            var writtenBytes = 0L
            val output = app.contentResolver.openOutputStream(target, "w")
                ?: throw IllegalStateException("No se pudo abrir el destino SAF para escritura.")
            output.use { destination ->
                source.inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        destination.write(buffer, 0, count)
                        writeDigest.update(buffer, 0, count)
                        writtenBytes += count
                    }
                }
                destination.flush()
            }
            check(writtenBytes == expectedBytes && source.length() == expectedBytes) {
                "La escritura quedó incompleta ($writtenBytes/$expectedBytes bytes)."
            }
            val expectedHash = writeDigest.digest().joinToString("") { "%02x".format(it) }

            val readDigest = java.security.MessageDigest.getInstance("SHA-256")
            var verifiedBytes = 0L
            val readback = app.contentResolver.openInputStream(target)
                ?: throw IllegalStateException("No se pudo reabrir el archivo para verificarlo.")
            readback.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    readDigest.update(buffer, 0, count)
                    verifiedBytes += count
                }
            }
            val verifiedHash = readDigest.digest().joinToString("") { "%02x".format(it) }
            check(verifiedBytes == expectedBytes && verifiedHash == expectedHash) {
                "La verificación de lectura no coincide: $verifiedBytes/$expectedBytes bytes; SHA-256 distinto."
            }
            val info = metadata(app, target)
                ?: throw IllegalStateException("El proveedor SAF no confirma que el archivo exista.")
            check(!info.isDirectory && (info.sizeBytes == null || info.sizeBytes == expectedBytes)) {
                "El proveedor SAF informa un tamaño o tipo distinto al archivo original."
            }
            recordAudit(app, "EXPORT_FILE", relativePath, "VERIFIED bytes=$verifiedBytes sha256=$verifiedHash")
            return BardoExportReceipt(relativePath, verifiedBytes, verifiedHash)
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(app.contentResolver, target) }
            recordAudit(app, "EXPORT_FILE", relativePath, "FAILED \${error.message ?: error.javaClass.simpleName}")
            throw error
        }
    }

    private fun recordAudit(context: Context, operation: String, path: String, outcome: String) {
        val file = File(context.applicationContext.filesDir, "bardo_mobile_file_operations.log")
        val line = System.currentTimeMillis().toString() + "\t" +
            operation.take(40) + "\t" + path.take(BardoMobileFilePolicy.MAX_RELATIVE_PATH_CHARS) + "\t" +
            outcome.take(120) + "\n"
        synchronized(auditLock) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(line, Charsets.UTF_8)
            }
        }
    }

    private fun validateName(name: String, allowExtension: Boolean): String {
        require(name.length in 1..180 && name == name.trim() &&
            name !in setOf(".", "..") && !name.startsWith(".") &&
            !name.contains("..") && !name.contains('/') && !name.contains('\\') &&
            !name.any { it.isISOControl() }
        ) { "Nombre de archivo o carpeta no permitido." }
        require(allowExtension || !name.contains('.')) { "Extensión no permitida." }
        return name
    }

    private fun isTextMime(mime: String, name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return mime.startsWith("text/") || mime in setOf("application/json", "application/xml") ||
            extension in setOf("txt", "md", "json", "xml", "csv", "log", "kt", "java", "kts", "gradle", "yml", "yaml", "properties", "toml", "ini", "py", "sh", "html", "css", "js", "ts", "sql", "diff", "patch")
    }

    private fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "json" -> "application/json"
        "xml" -> "application/xml"
        "csv" -> "text/csv"
        "md", "txt", "log" -> "text/plain"
        else -> "application/octet-stream"
    }

    const val MAX_LIST_ENTRIES = 1000
    const val MAX_WRITE_TEXT_CHARS = 24_000
    const val MAX_READ_CHUNK_CHARS = 24_000
    const val MAX_READ_OFFSET_CHARS = 10_000_000
    const val MAX_PENDING_DELETE_REQUESTS = 100
    const val MAX_DELETE_REASON_CHARS = 500
    const val TRASH_DIRECTORY = "Papelera"
    private val auditLock = Any()
    private const val DELETE_REQUESTS_PREFS = "bardo_mobile_file_requests"
    private const val DELETE_REQUESTS_KEY = "pending"
}

data class BardoMobileDeletionRequestArguments(
    val relativePath: String,
    val permanent: Boolean,
    val reason: String
)

/**
 * Parser estricto para propuestas de retirada creadas por BARDO.
 * La aprobación y la operación real siguen perteneciendo a la UI del usuario.
 */
object BardoMobileDeletionRequestArgumentsParser {
    fun parse(arguments: Map<String, String>): BardoMobileDeletionRequestArguments {
        val expected = setOf("path", "permanent", "reason")
        val missing = expected - arguments.keys
        val extra = arguments.keys - expected
        require(missing.isEmpty() && extra.isEmpty()) {
            "Argumentos incorrectos. Faltan=" + missing.sorted().joinToString() +
                "; adicionales=" + extra.sorted().joinToString()
        }

        val path = arguments.getValue("path").trim()
        require(path.isNotBlank() && path.length <= 512) {
            "La ruta está vacía o supera 512 caracteres."
        }

        val permanentText = arguments.getValue("permanent").trim().lowercase(Locale.ROOT)
        require(permanentText == "true" || permanentText == "false") {
            "permanent debe ser exactamente true o false."
        }

        val reason = arguments.getValue("reason").trim()
        require(reason.isNotBlank() && reason.length <= 500) {
            "El motivo es obligatorio y no puede superar 500 caracteres."
        }

        return BardoMobileDeletionRequestArguments(
            relativePath = path,
            permanent = permanentText == "true",
            reason = reason
        )
    }
}
