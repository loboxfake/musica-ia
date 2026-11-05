package com.musicaia.modelo

import java.text.Normalizer
import java.util.Locale

/** Clasifica la intención antes de activar una skill; la música no secuestra el chat general. */
object BardoIntentRouter {
    enum class Intent {
        GENERAL_CHAT,
        MUSIC_CREATION,
        OWN_LYRICS,
        CONFIRM_CREATION,
        CHANGE_CREATION
    }

    fun classify(message: String, creationInProgress: Boolean): Intent {
        val text = normalize(message)
        if (text.isBlank()) return Intent.GENERAL_CHAT

        if (listOf("mi letra", "texto propio", "pegar mi letra", "tengo mi letra")
                .any { text.contains(it) }) return Intent.OWN_LYRICS

        if (Regex("^(cambiar|cambia|modificar|modifica)\\s+(tema|estilo|ambiente|ritmo|idioma|tipo|voz)\\b")
                .containsMatchIn(text)) return Intent.CHANGE_CREATION

        if (creationInProgress && text in setOf("crear", "crear cancion", "crear musica", "si", "continuar", "adelante"))
            return Intent.CONFIRM_CREATION

        val explicitMusicRequest = listOf(
            "quiero crear una cancion", "quiero hacer una cancion", "quiero componer",
            "hazme una cancion", "crea una cancion", "compon una cancion",
            "necesito una cancion", "vamos a crear una cancion", "vamos a hacer una cancion",
            "una cancion sobre", "cancion sobre", "crear instrumental", "quiero un instrumental",
            "crear musica", "quiero crear musica", "quiero hacer musica"
        )
        if (explicitMusicRequest.any { text.contains(it) }) return Intent.MUSIC_CREATION

        return Intent.GENERAL_CHAT
    }

    private fun normalize(value: String): String = Normalizer
        .normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
