package com.musicaia.storage

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.security.MessageDigest
import java.util.UUID

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
        val rootId = prefs.getString("root_document_id", null)
            ?: DocumentsContract.getTreeDocumentId(tree)
        val permission = app.contentResolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        }
        if (!permission) return null
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        return Location(tree, root)
    }

    fun isConfigured(context: Context): Boolean = location(context) != null

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
                if (cursor.getString(nameColumn).equals(name, ignoreCase = true) &&
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

    /** Lee solo texto acotado; nunca carga WAV, MP3, modelos ni binarios en memoria. */
    fun readText(context: Context, relativePath: String): String {
        val decision = BardoMobileFilePolicy.authorize(BardoMobileFileOperation.READ, relativePath)
        require(decision.allowed) { decision.reason }
        val loc = location(context) ?: throw IllegalStateException("No existe permiso SAF para BARDO.")
        val (uri, _) = resolveEntry(context, loc, decision.normalizedPath!!)
        val info = metadata(context, uri) ?: throw IllegalStateException("No se pudo consultar el archivo.")
        require(!info.isDirectory) { "La ruta apunta a una carpeta, no a un archivo." }
        require(info.sizeBytes == null || info.sizeBytes in 0..BardoMobileFilePolicy.MAX_TEXT_READ_BYTES) {
            "El archivo supera el máximo de 1 MiB para lectura de texto."
        }
        require(isTextMime(info.mimeType, info.name)) { "BARDO solo lee texto UTF-8 desde esta herramienta." }
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= BardoMobileFilePolicy.MAX_TEXT_READ_BYTES) {
                    "El archivo supera el máximo de 1 MiB para lectura de texto."
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IllegalStateException("No se pudo abrir el documento SAF.")
        return bytes.toString(Charsets.UTF_8)
    }

    fun createDirectory(context: Context, relativePath: String): String {
        val decision = BardoMobileFilePolicy.authorize(BardoMobileFileOperation.CREATE_DIRECTORY, relativePath)
        require(decision.allowed) { decision.reason }
        val loc = location(context) ?: throw IllegalStateException("No existe permiso SAF para BARDO.")
        val normalized = decision.normalizedPath!!
        val segments = normalized.split('/')
        val parentPath = segments.dropLast(1).joinToString("/")
        val name = validateName(segments.last(), allowExtension = true)
        val parent = resolveDirectory(context, loc, parentPath)
        require(findChild(context, loc, parent, name, null) == null) { "Ya existe BARDO/$normalized." }
        DocumentsContract.createDocument(
            context.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name
        ) ?: throw IllegalStateException("El proveedor SAF no pudo crear la carpeta.")
        return normalized
    }

    fun createTextFile(context: Context, relativePath: String, content: String): String {
        require(content.length <= MAX_WRITE_TEXT_CHARS) { "El contenido supera 256 KiB." }
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
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
            } ?: throw IllegalStateException("No se pudo abrir el archivo creado.")
            val saved = readText(context, normalized)
            check(saved == content) { "La verificación de lectura tras la escritura no coincide." }
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
    fun moveToTrash(
        context: Context,
        relativePath: String,
        explicitUserConfirmation: Boolean
    ): String {
        require(explicitUserConfirmation) { "La operación requiere confirmación directa del usuario." }
        val approval = consumeApproval(context, BardoMobileFileOperation.MOVE_TO_TRASH, relativePath)
        require(approval.allowed) { approval.reason }
        val loc = location(context) ?: throw IllegalStateException("No existe permiso SAF para BARDO.")
        val (source, sourceParent) = resolveEntry(context, loc, approval.normalizedPath!!)
        val trash = findChild(context, loc, loc.root, TRASH_DIRECTORY, true)
            ?: DocumentsContract.createDocument(
                context.contentResolver, loc.root,
                DocumentsContract.Document.MIME_TYPE_DIR, TRASH_DIRECTORY
            ) ?: throw IllegalStateException("No se pudo crear la papelera BARDO.")
        val moved = DocumentsContract.moveDocument(context.contentResolver, source, sourceParent, trash)
            ?: throw IllegalStateException("El proveedor SAF no admite mover elementos; no se borró el original.")
        val name = metadata(context, moved)?.name ?: approval.normalizedPath.substringAfterLast('/')
        return "$TRASH_DIRECTORY/$name"
    }

    /** Eliminación permanente limitada a archivos o carpetas vacías y confirmación de una sola operación. */
    fun deletePermanently(
        context: Context,
        relativePath: String,
        explicitUserConfirmation: Boolean
    ): Boolean {
        require(explicitUserConfirmation) { "La eliminación requiere confirmación directa del usuario." }
        val approval = consumeApproval(context, BardoMobileFileOperation.PERMANENT_DELETE, relativePath)
        require(approval.allowed) { approval.reason }
        val loc = location(context) ?: throw IllegalStateException("No existe permiso SAF para BARDO.")
        val (target, _) = resolveEntry(context, loc, approval.normalizedPath!!)
        val info = metadata(context, target) ?: throw IllegalStateException("No se pudo consultar el elemento.")
        if (info.isDirectory) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                loc.tree, DocumentsContract.getDocumentId(target)
            )
            val count = context.contentResolver.query(
                children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null
            )?.use { it.count } ?: throw IllegalStateException("No se pudo verificar si la carpeta está vacía.")
            require(count == 0) { "Por seguridad solo se eliminan carpetas vacías." }
        }
        return DocumentsContract.deleteDocument(context.contentResolver, target)
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
        val prefs = context.applicationContext.getSharedPreferences("bardo_file_approvals", Context.MODE_PRIVATE)
        val used = prefs.getStringSet("used_nonces", emptySet()).orEmpty().toMutableSet()
        require(nonce !in used) { "La autorización ya fue consumida." }
        used += nonce
        check(prefs.edit().putStringSet("used_nonces", used).commit()) {
            "No se pudo registrar el uso único de la autorización."
        }
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
        require(source.isFile) { "El archivo de BARDO no existe: " + source.name }
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
            extension in setOf("txt", "md", "json", "xml", "csv", "log")
    }

    private fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "json" -> "application/json"
        "xml" -> "application/xml"
        "csv" -> "text/csv"
        "md", "txt", "log" -> "text/plain"
        else -> "application/octet-stream"
    }

    companion object {
        const val MAX_LIST_ENTRIES = 1000
        const val MAX_WRITE_TEXT_CHARS = 256 * 1024
        const val TRASH_DIRECTORY = "Papelera"
    }
}
