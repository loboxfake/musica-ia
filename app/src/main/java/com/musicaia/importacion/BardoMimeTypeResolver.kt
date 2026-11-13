package com.musicaia.importacion

/**
 * Obtiene una extensión conocida desde el nombre o, cuando falta/no está soportada,
 * desde el MIME entregado por el proveedor Android. No inspecciona ni ejecuta binarios.
 */
object BardoMimeTypeResolver {
    private val knownExtensions = setOf(
        "jpg", "jpeg", "png", "webp", "bmp",
        "mp4", "mkv", "mov", "3gp", "webm",
        "wav", "mp3", "m4a", "aac", "ogg", "flac", "opus", "amr", "3ga",
        "epub", "odt", "ods", "odp", "rtf", "html", "htm",
        "pdf", "docx", "pptx", "xlsx",
        "txt", "kt", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp",
        "sql", "yaml", "yml", "toml", "json", "csv", "xml", "md", "markdown",
        "svg", "srt", "vtt"
    )

    private val mimeExtensions = mapOf(
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
        "image/bmp" to "bmp",
        "image/svg+xml" to "svg",
        "video/mp4" to "mp4",
        "video/quicktime" to "mov",
        "video/x-matroska" to "mkv",
        "video/3gpp" to "3gp",
        "video/webm" to "webm",
        "audio/wav" to "wav",
        "audio/x-wav" to "wav",
        "audio/wave" to "wav",
        "audio/mpeg" to "mp3",
        "audio/mp4" to "m4a",
        "audio/x-m4a" to "m4a",
        "audio/aac" to "aac",
        "audio/ogg" to "ogg",
        "audio/flac" to "flac",
        "audio/x-flac" to "flac",
        "audio/opus" to "opus",
        "audio/amr" to "amr",
        "application/pdf" to "pdf",
        "application/epub+zip" to "epub",
        "application/rtf" to "rtf",
        "application/x-rtf" to "rtf",
        "text/rtf" to "rtf",
        "text/html" to "html",
        "application/vnd.oasis.opendocument.text" to "odt",
        "application/vnd.oasis.opendocument.spreadsheet" to "ods",
        "application/vnd.oasis.opendocument.presentation" to "odp",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "text/plain" to "txt",
        "text/csv" to "csv",
        "application/json" to "json",
        "text/xml" to "xml",
        "application/xml" to "xml",
        "text/markdown" to "md",
        "application/x-subrip" to "srt",
        "text/vtt" to "vtt"
    )

    fun resolveExtension(fileName: String, mimeType: String): String {
        val fromName = fileName.substringAfterLast('.', "").lowercase()
        if (fromName in knownExtensions) return fromName
        val normalizedMime = mimeType.substringBefore(';').trim().lowercase()
        return mimeExtensions[normalizedMime].orEmpty()
    }
}