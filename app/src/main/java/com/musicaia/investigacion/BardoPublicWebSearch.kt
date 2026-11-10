package com.musicaia.investigacion

import android.content.Context
import android.text.Html
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class BardoPublicWebResult(
    val title: String,
    val url: String,
    val snippet: String
)

/**
 * Boundary for public-web queries. Only a short, plain-language keyword query
 * is ever sent. Paths, source code, user identifiers, device metrics, local
 * filenames and credential-like values are rejected before network access.
 */
object BardoPublicWebSearchPolicy {
    const val PREFS_NAME = "bardo_web_research"
    const val ENABLED_KEY = "public_keyword_search_enabled"
    const val MAX_QUERY_CHARS = 180

    private val allowedCharacters = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N} .,:_+()\\-]{1,179}$")
    private val privateContent = listOf(
        Regex("(?i)(?:/storage/|/sdcard/|/data/|/home/|/mnt/|/users/|\\\\users\\\\|content://|file://)"),
        Regex("(?i)\\b(?:github_pat_|gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9_-]{16,}|bearer\\s+[A-Za-z0-9._-]{16,}|api[_ -]?key)\\b"),
        Regex("(?i)\\b(?:imei|imsi|serial(?: number)?|ssid|bssid|private project|my project|mi proyecto|archivo local|local file|ruta local|file path|device stats|estadísticas del dispositivo|estadisticas del dispositivo|ram libre|available ram|battery percentage|porcentaje de bateria|thermal status)\\b"),
        Regex("(?i)\\b[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}\\b"),
        Regex("\\b\\d{7,}\\b"),
        Regex("(?i)\\b[0-9a-f]{32,}\\b"),
        Regex("(?i)\\b[^\\s]+\\.(?:wav|mp3|flac|zip|7z|pdf|docx?|xlsx?|pptx?|kt|java|json|txt|db)\\b")
    )

    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(ENABLED_KEY, false)

    fun validateQuery(raw: String): String {
        val query = raw.trim().replace(Regex("\\s+"), " ")
        require(query.length in 2..MAX_QUERY_CHARS) {
            "La búsqueda debe tener entre 2 y $MAX_QUERY_CHARS caracteres."
        }
        require(allowedCharacters.matches(query)) {
            "Solo se permiten palabras clave públicas. No incluyas URLs, código, rutas ni texto de archivos."
        }
        require(privateContent.none { it.containsMatchIn(query) }) {
            "Consulta bloqueada por privacidad: parece contener rutas, archivos, credenciales, identificadores o estadísticas locales."
        }
        require(!query.contains("..")) { "La consulta contiene una secuencia de ruta no permitida." }
        return query
    }
}

/**
 * DuckDuckGo HTML search. The request is an anonymous GET containing only the
 * validated query: no cookies, authorization header, referrer, file upload,
 * local path, project archive, log, telemetry or device inventory is attached.
 * Search snippets are untrusted text and are never executed.
 */
object BardoPublicWebSearch {
    private const val SEARCH_ENDPOINT = "https://html.duckduckgo.com/html/"
    private const val MAX_RESPONSE_BYTES = 1_000_000
    private const val MAX_RESULTS = 8

    fun search(rawQuery: String): List<BardoPublicWebResult> {
        val query = BardoPublicWebSearchPolicy.validateQuery(rawQuery)
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        var current = URL(SEARCH_ENDPOINT + "?q=" + encoded)

        repeat(3) { attempt ->
            require(current.protocol.equals("https", true) &&
                (current.host.equals("html.duckduckgo.com", true) ||
                    current.host.equals("duckduckgo.com", true))) {
                "La búsqueda intentó salir del dominio del proveedor permitido."
            }
            val connection = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", "BARDO-PublicResearch/1.0")
                setRequestProperty("Accept", "text/html")
                setRequestProperty("Accept-Language", "es,en;q=0.7")
                setRequestProperty("Connection", "close")
            }
            try {
                when (val status = connection.responseCode) {
                    in 300..399 -> {
                        require(attempt < 2) { "La búsqueda excedió el límite de redirecciones." }
                        val location = connection.getHeaderField("Location")
                            ?: throw IllegalStateException("El buscador devolvió una redirección sin destino.")
                        current = URL(current, location)
                    }
                    HttpURLConnection.HTTP_OK -> {
                        val html = connection.inputStream.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (output.size() < MAX_RESPONSE_BYTES) {
                                val count = input.read(
                                    buffer,
                                    0,
                                    minOf(buffer.size, MAX_RESPONSE_BYTES - output.size())
                                )
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                            output.toString(StandardCharsets.UTF_8.name())
                        }
                        return parseResults(html)
                    }
                    else -> throw IllegalStateException("El buscador respondió HTTP $status.")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("No se pudo completar la búsqueda web.")
    }

    private fun parseResults(html: String): List<BardoPublicWebResult> {
        val anchor = Regex(
            "(?is)<a[^>]*class=[\"'][^\"']*result__a[^\"']*[\"'][^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>"
        )
        val snippets = Regex(
            "(?is)<(?:a|div)[^>]*class=[\"'][^\"']*result__snippet[^\"']*[\"'][^>]*>(.*?)</(?:a|div)>"
        ).findAll(html).map { cleanHtml(it.groupValues[1]) }.toList()
        val results = anchor.findAll(html).mapIndexedNotNull { index, match ->
            val rawHref = decodeHtml(match.groupValues[1])
            val resolved = resolveResultUrl(rawHref) ?: return@mapNotNullIndexed null
            val title = cleanHtml(match.groupValues[2]).take(240)
            if (title.isBlank()) return@mapNotNullIndexed null
            BardoPublicWebResult(
                title = title,
                url = resolved,
                snippet = snippets.getOrNull(index).orEmpty().take(600)
            )
        }.distinctBy { it.url }.take(MAX_RESULTS).toList()
        return results
    }

    private fun resolveResultUrl(raw: String): String? = runCatching {
        val parsed = URL(raw)
        val resolved = if (parsed.host.endsWith("duckduckgo.com", true) &&
            parsed.path.contains("/l/")
        ) {
            val encodedTarget = parsed.query.orEmpty().split('&')
                .firstOrNull { it.startsWith("uddg=") }
                ?.substringAfter('=')
                ?: return@runCatching null
            URLDecoder.decode(encodedTarget, StandardCharsets.UTF_8.name())
        } else raw
        val target = URL(resolved)
        if (!target.protocol.equals("https", true) || target.host.isBlank() ||
            target.host.endsWith("duckduckgo.com", true)
        ) return@runCatching null
        target.toExternalForm()
    }.getOrNull()

    private fun cleanHtml(value: String): String =
        Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun decodeHtml(value: String): String =
        value.replace("&amp;", "&").replace("&#x2F;", "/").replace("&quot;", "\"")
}
