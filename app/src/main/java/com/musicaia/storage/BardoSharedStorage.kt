package com.musicaia.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Acceso limitado al árbol BARDO elegido y autorizado por el usuario mediante SAF.
 * No recorre otras carpetas del dispositivo y no elimina archivos de origen.
 */
object BardoSharedStorage {
    private data class Location(val tree: Uri, val root: Uri)

    private fun location(context: Context): Location? {
        val prefs = context.applicationContext.getSharedPreferences("bardo_storage", Context.MODE_PRIVATE)
        val rawTree = prefs.getString("tree_uri", null) ?: return null
        val tree = Uri.parse(rawTree)
        val rootId = prefs.getString("root_document_id", null)
            ?: DocumentsContract.getTreeDocumentId(tree)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        return Location(tree, root)
    }

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
}
