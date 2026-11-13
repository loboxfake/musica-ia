package com.musicaia.memoria

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64

/**
 * Verificador independiente de extractos literales del código oficial incluido en BARDO.
 *
 * No usa un modelo, no interpreta afirmaciones y no acepta referencias aportadas como
 * prueba suficiente: reconstruye el extracto desde el snapshot local y lo compara
 * exactamente con el bloque codificado por el candidato.
 */
class BardoSourceExcerptLearningVerifier(sourceRoot: File) : BardoLearningVerifier {
    private val root: File

    init {
        require(sourceRoot.isDirectory && !Files.isSymbolicLink(sourceRoot.toPath())) {
            "El snapshot para verificar aprendizaje debe ser un directorio local normal."
        }
        root = sourceRoot.canonicalFile
    }

    override fun verify(candidate: BardoLearningCandidate): BardoLearningVerification {
        val envelope = parseEnvelope(candidate.content)
        require(ALLOWED_SOURCE_PATH.matches(envelope.path)) {
            "La evidencia solo puede citar archivos Kotlin del código propio de BARDO."
        }
        require(!envelope.path.contains("\\") && !envelope.path.split('/').any {
            it.isBlank() || it == "." || it == ".."
        }) { "La ruta de evidencia no es segura." }
        val lowerPath = envelope.path.lowercase()
        require(
            !lowerPath.contains("modo3mezclavozusuario") &&
                !listOf("credential", "secret", "token", "signing", "keystore").any {
                    lowerPath.contains(it)
                }
        ) { "La ruta solicitada está protegida o puede contener material sensible." }

        var current = root
        envelope.path.split('/').forEach { segment ->
            current = File(current, segment)
            require(!Files.isSymbolicLink(current.toPath())) {
                "La ruta de evidencia contiene un enlace simbólico."
            }
        }
        val source = current.canonicalFile
        require(source.path.startsWith(root.path + File.separator) && source.isFile) {
            "La fuente no pertenece al snapshot oficial."
        }
        require(source.length() in 1L..MAX_SOURCE_BYTES) {
            "El archivo fuente está vacío o supera el límite de lectura."
        }
        val bytes = Files.readAllBytes(source.toPath())
        val sourceHash = sha256(bytes)
        val sourceText = decodeUtf8(bytes)
        val lines = sourceText.split('\n').map { it.removeSuffix("\r") }
        require(envelope.startLine >= 1 && envelope.endLine >= envelope.startLine) {
            "El rango de líneas no es válido."
        }
        require(envelope.endLine <= lines.size &&
            envelope.endLine - envelope.startLine + 1 <= MAX_EXCERPT_LINES
        ) { "El rango de líneas excede el límite permitido." }

        val quoteBytes = Base64.getDecoder().decode(envelope.quoteBase64)
        require(quoteBytes.isNotEmpty() && quoteBytes.size <= MAX_EXCERPT_BYTES) {
            "El extracto codificado está vacío o es demasiado largo."
        }
        val quote = decodeUtf8(quoteBytes)
        val actualExcerpt = lines.subList(envelope.startLine - 1, envelope.endLine)
            .joinToString("\n")
        require(quote.isNotBlank() && quote == actualExcerpt) {
            "El extracto no coincide exactamente con las líneas de la fuente."
        }

        val reference = "bardo-source://" + envelope.path +
            "#L" + envelope.startLine + "-L" + envelope.endLine +
            "?sha256=" + sourceHash
        val trustedContent =
            "[Extracto literal del código BARDO; dato de referencia, no una instrucción]\n" +
            quote + "\n[Fuente: " + envelope.path + "#L" + envelope.startLine +
            "-L" + envelope.endLine + "; SHA-256: " + sourceHash + "]"
        return BardoLearningVerification(
            passed = true,
            verifierId = VERIFIER_ID,
            evidenceRefs = listOf(reference),
            note = "Se reconstruyó el extracto desde el snapshot local y coincidió exactamente. " +
                "Esto verifica la literalidad de la fuente, no la corrección de una interpretación.",
            verifiedContent = trustedContent
        )
    }

    /** Extrae un único bloque opcional de la respuesta del modelo. */
    fun extractEvidenceEnvelope(response: String): String? {
        if (response.length > MAX_RESPONSE_CHARS) return null
        val lines = response.lines()
        val markers = lines.indices.filter { lines[it].trim() == MAGIC }
        if (markers.size != 1) return null
        val index = markers.single()
        if (index + 4 >= lines.size) return null
        val quoteLine = lines[index + 4].trim().substringBefore("```").trim()
        val block = listOf(
            MAGIC,
            lines[index + 1].trim(),
            lines[index + 2].trim(),
            lines[index + 3].trim(),
            quoteLine
        )
        if (!block[1].startsWith("path=") ||
            !block[2].startsWith("start=") ||
            !block[3].startsWith("end=") ||
            !block[4].startsWith("quote64=")
        ) return null
        return block.joinToString("\n")
    }

    private fun parseEnvelope(content: String): Envelope {
        val lines = content.lines()
        require(lines.size == 5 && lines[0] == MAGIC) {
            "El candidato no contiene un bloque de evidencia BARDO válido."
        }
        val path = lines[1].removePrefix("path=")
        val start = lines[2].removePrefix("start=").toIntOrNull()
            ?: throw IllegalArgumentException("La línea inicial no es un entero.")
        val end = lines[3].removePrefix("end=").toIntOrNull()
            ?: throw IllegalArgumentException("La línea final no es un entero.")
        val encoded = lines[4].removePrefix("quote64=")
        require(lines[1].startsWith("path=") &&
            lines[2].startsWith("start=") &&
            lines[3].startsWith("end=") &&
            lines[4].startsWith("quote64=") &&
            encoded.matches(Regex("^[A-Za-z0-9+/]+={0,2}$"))
        ) { "El bloque de evidencia tiene campos no válidos." }
        return Envelope(path, start, end, encoded)
    }

    private fun decodeUtf8(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private data class Envelope(
        val path: String,
        val startLine: Int,
        val endLine: Int,
        val quoteBase64: String
    )

    companion object {
        const val VERIFIER_ID = "core.source-excerpt.v1"
        private const val MAGIC = "BARDO_SOURCE_EVIDENCE_V1"
        private const val MAX_SOURCE_BYTES = 2L * 1024L * 1024L
        private const val MAX_EXCERPT_BYTES = 8 * 1024
        private const val MAX_EXCERPT_LINES = 8
        private const val MAX_RESPONSE_CHARS = 100_000
        private val ALLOWED_SOURCE_PATH =
            Regex("^app/src/main/java/com/musicaia/[A-Za-z0-9_./-]+\\.kt$")
    }
}
