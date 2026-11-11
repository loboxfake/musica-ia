package com.musicaia.desarrollo

/**
 * Redacts credentials and signed URLs before CI output is displayed or stored in local memory.
 * The result is diagnostic context, not proof that a candidate passed.
 */
object BardoCiLogSanitizer {
    const val DEFAULT_MAX_EXCERPT_CHARS = 3200

    private val failurePattern = Regex(
        """(?i)(FAILURE:|BUILD FAILED|FAILED|Exception|Caused by:|error:|Execution failed|Task .* FAILED|fatal:)"""
    )
    private val primaryFailurePattern = Regex(
        """(?i)(FAILURE:|BUILD FAILED|Execution failed|Task .* FAILED|BUILD FAILURE)"""
    )

    private val secretPatterns = listOf(
        Regex("""(?i)\bgithub_pat_[A-Za-z0-9_]+\b"""),
        Regex("""(?i)\bgh[pousr]_[A-Za-z0-9_]{20,}\b"""),
        Regex("""(?i)(authorization\s*:\s*(?:bearer|token)\s+)[^\s]+"""),
        Regex("""(?i)((?:token|password|secret|client_secret|authorization)\s*[:=]\s*)[^\s,;]+""")
    )

    fun selectRelevantExcerpt(raw: String, maxChars: Int = DEFAULT_MAX_EXCERPT_CHARS): String {
        require(maxChars in 200..12000)
        if (raw.isBlank()) return "GitHub devolvió un log vacío."
        var safe = raw
        secretPatterns.forEach { pattern ->
            safe = pattern.replace(safe) { match ->
                val prefix = match.groups[1]?.value.orEmpty()
                prefix + "<REDACTED>"
            }
        }
        safe = Regex("""(?i)https://[^\s?]+\?[^\s]+""").replace(safe) {
            it.value.substringBefore("?") + "?<REDACTED>"
        }

        val lines = safe.lines()
        val hits = lines.indices.filter { failurePattern.containsMatchIn(lines[it]) }
        val primaryHits = lines.indices.filter { primaryFailurePattern.containsMatchIn(lines[it]) }.takeLast(4)
        val indices = if (hits.isEmpty()) {
            lines.indices.takeLast(45)
        } else {
            val selected = linkedSetOf<Int>()
            (primaryHits + hits.takeLast(6)).distinct().forEach { hit ->
                for (index in (hit - 6).coerceAtLeast(0)..(hit + 12).coerceAtMost(lines.lastIndex)) {
                    selected += index
                }
            }
            selected.toList().sorted()
        }
        val excerpt = indices.joinToString("\n") { lines[it] }
        if (excerpt.length <= maxChars) return excerpt
        val failureAnchor = primaryHits.lastOrNull()?.let { lines[it].take(240) }.orEmpty()
        val reserved = if (failureAnchor.isBlank()) 8 else failureAnchor.length + 14
        val tail = excerpt.takeLast((maxChars - reserved).coerceAtLeast(100))
        return if (failureAnchor.isBlank()) "[...]\n" + tail else {
            "[...]\n" + failureAnchor + "\n...\n" + tail
        }
    }
}
