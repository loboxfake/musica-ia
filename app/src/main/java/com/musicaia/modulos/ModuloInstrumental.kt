package com.musicaia.modulos

import android.content.Context
import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.TipoModulo
import java.io.File
import java.io.FileOutputStream

/**
 * Genera segmentos instrumentales reales con Stable Audio Open Small + ExecuTorch.
 * El modelo se carga desde assets privados; no hay inferencia ni llamadas de red.
 */
class ModuloInstrumental(
    context: Context,
    private val songContext: String
) : ModuloIA {
    override val id = "instrumental"
    override val tipo = TipoModulo.INSTRUMENTAL
    private val ctx = context.applicationContext

    private val modelFiles = listOf(
        "conditioners_model.pte",
        "dit_model.pte",
        "autoencoder_model.pte",
        "spiece.model"
    )

    override fun cargar(): Boolean {
        if (!AudioGenNative.available) return false
        val modelDir = File(ctx.filesDir, "stable_audio_model")
        if (!modelDir.exists() && !modelDir.mkdirs()) return false

        val requiredSpace = modelFiles.sumOf { name ->
            runCatching { ctx.assets.openFd("audio_model/$name").length }.getOrDefault(0L)
        } + 256L * 1024 * 1024
        if (requiredSpace > 256L * 1024 * 1024 && modelDir.freeSpace < requiredSpace) return false

        return try {
            for (name in modelFiles) {
                val assetName = "audio_model/$name"
                val destination = File(modelDir, name)
                val temporary = File(modelDir, "$name.part")
                if (destination.isFile && destination.length() > 0L) continue

                ctx.assets.open(assetName).buffered().use { input ->
                    FileOutputStream(temporary).buffered().use { output ->
                        input.copyTo(output)
                        output.flush()
                    }
                }
                if (!temporary.isFile || temporary.length() == 0L) return false
                if (destination.exists() && !destination.delete()) return false
                if (!temporary.renameTo(destination)) return false
            }
            modelFiles.all { File(modelDir, it).isFile && File(modelDir, it).length() > 0L }
        } catch (_: Exception) {
            false
        }
    }

    override fun ejecutar(entrada: Dato): Dato {
        if (!AudioGenNative.available) {
            throw IllegalStateException("El motor local de audio no está incluido en esta compilación.")
        }
        val modelDir = File(ctx.filesDir, "stable_audio_model")
        if (modelFiles.any { !File(modelDir, it).isFile }) {
            throw IllegalStateException("Faltan archivos del modelo Stable Audio Open Small.")
        }

        val lyrics = (entrada as? Dato.Texto)?.texto.orEmpty()
        val workDir = File(ctx.cacheDir, "audio_segments")
        if (workDir.exists()) workDir.deleteRecursively()
        if (!workDir.mkdirs()) throw IllegalStateException("No se pudo crear el directorio temporal de audio.")

        // Stable Audio Open Small generates at most 11 seconds per pass.
        // Eighteen sections with 400 ms crossfades yield more than three minutes.
        val sectionNames = listOf(
            "intro: sparse opening with a clear musical motif",
            "verse one A: steady groove and restrained instrumentation",
            "verse one B: continue the groove with a melodic variation",
            "pre-chorus one: gradual harmonic and rhythmic build",
            "chorus one A: full energetic arrangement with a memorable hook",
            "chorus one B: repeat the hook with fuller instrumentation",
            "verse two A: return to the groove with a subtle variation",
            "verse two B: develop the verse melody and bass movement",
            "pre-chorus two: rising energy and layered percussion",
            "chorus two A: full wide arrangement and strong rhythm",
            "chorus two B: repeat the chorus with added harmonic layers",
            "bridge A: contrasting breakdown with a fresh harmonic color",
            "bridge B: rebuild tension toward the final chorus",
            "instrumental break: expressive lead motif over the rhythm section",
            "final chorus A: biggest and most uplifting arrangement",
            "final chorus B: maintain energy with a melodic variation",
            "final chorus C: emphatic final hook and resolving harmony",
            "outro: resolved ending with a natural musical tail"
        )

        val segments = mutableListOf<File>()
        try {
            sectionNames.forEachIndexed { index, section ->
                val prompt = buildPrompt(section, lyrics)
                val segment = File(workDir, "segment_%02d.wav".format(index + 1))
                val generated = AudioGenNative.generateNative(
                    modelDir.absolutePath,
                    prompt,
                    segment.absolutePath,
                    (System.currentTimeMillis().toInt() + index * 37)
                )
                if (generated.isNullOrBlank() || !segment.isFile || segment.length() <= 44L) {
                    throw IllegalStateException("El modelo de audio no generó el segmento ${index + 1}/${sectionNames.size}.")
                }
                segments.add(segment)
            }

            val output = File(ctx.filesDir, "instrumental_ai.wav")
            AudioAssembler.assemble(
                inputFiles = segments,
                outputFile = output,
                crossfadeMs = 400,
                fadeOutMs = 1200
            )
            if (!output.isFile || output.length() <= 44L) {
                throw IllegalStateException("El WAV instrumental final está vacío.")
            }
            val durationSeconds = (output.length() - 44L).toDouble() / (44100.0 * 2.0 * 2.0)
            if (durationSeconds < 180.0) {
                throw IllegalStateException("El instrumental generado dura menos de 3 minutos.")
            }
            return Dato.Audio(output.absolutePath, 44100)
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun buildPrompt(section: String, lyrics: String): String {
        val genre = field("Estilo")
        val mood = field("Ambiente")
        val tempo = field("Ritmo")
        val theme = field("Tema")

        val genreEnglish = when {
            genre.contains("pop", true) -> "modern melodic pop"
            genre.contains("rock", true) -> "energetic alternative rock"
            genre.contains("rap", true) || genre.contains("hip", true) -> "modern hip hop"
            genre.contains("electr", true) -> "electronic dance music"
            genre.contains("balada", true) -> "emotional piano pop ballad"
            genre.contains("jazz", true) -> "modern jazz fusion"
            genre.contains("lofi", true) || genre.contains("lo-fi", true) -> "lo-fi chillhop"
            genre.contains("reggaeton", true) -> "Latin urban pop"
            genre.contains("flamenco", true) -> "modern flamenco guitar"
            genre.contains("orquest", true) -> "cinematic orchestral music"
            genre.isBlank() -> "modern melodic pop"
            else -> genre.take(60)
        }

        val moodEnglish = when {
            mood.contains("alegr", true) || mood.contains("feliz", true) -> "uplifting, bright and joyful"
            mood.contains("trist", true) || mood.contains("melanc", true) -> "melancholic and emotional"
            mood.contains("rom", true) -> "warm, intimate and romantic"
            mood.contains("ép", true) || mood.contains("epic", true) -> "epic, dramatic and cinematic"
            mood.contains("relaj", true) || mood.contains("calm", true) -> "calm, mellow and soothing"
            mood.contains("oscur", true) -> "dark and atmospheric"
            mood.isBlank() -> "warm and expressive"
            else -> mood.take(60)
        }

        val tempoEnglish = when {
            tempo.contains("lent", true) -> "slow tempo around 75 BPM"
            tempo.contains("rápid", true) || tempo.contains("rapid", true) -> "up-tempo around 125 BPM"
            tempo.isBlank() -> "mid-tempo around 105 BPM"
            else -> "mid-tempo around 105 BPM, requested feel: ${tempo.take(40)}"
        }

        val instruments = when {
            genre.contains("rock", true) -> "electric guitars, bass guitar and live drums"
            genre.contains("rap", true) || genre.contains("hip", true) -> "deep 808 bass, punchy kick, crisp snare and atmospheric keys"
            genre.contains("electr", true) -> "synth arpeggios, electronic drums, bass and wide pads"
            genre.contains("balada", true) -> "expressive piano, warm strings and subtle percussion"
            genre.contains("jazz", true) -> "piano, upright bass, brushed drums and subtle brass"
            genre.contains("flamenco", true) -> "nylon guitar, hand claps and organic percussion"
            genre.contains("orquest", true) -> "strings, brass, piano and cinematic percussion"
            else -> "piano, warm synths, bass guitar and polished pop drums"
        }

        val themeEnglish = when {
            theme.contains("amor", true) -> "love and human connection"
            theme.contains("verano", true) -> "summer, freedom and warm nights"
            theme.contains("amistad", true) -> "friendship and shared memories"
            theme.contains("super", true) -> "resilience and overcoming obstacles"
            theme.contains("nostalg", true) -> "nostalgia and memories"
            theme.isBlank() -> "a coherent emotional musical journey"
            else -> theme.take(80)
        }

        val lyricHint = lyrics.replace(Regex("\\s+"), " ").take(180)
        val base = "Instrumental music only, absolutely no vocals and no speech. " +
            "$genreEnglish, $moodEnglish, $tempoEnglish. " +
            "Instrumentation: $instruments. Theme: $themeEnglish. " +
            "Professional studio production, coherent harmony, clean mix, natural dynamics. " +
            "Song section: $section. "
        return base + if (lyricHint.isNotBlank()) {
            "Emotional inspiration from these lyrics: $lyricHint."
        } else ""
    }

    private fun field(name: String): String =
        Regex("(?i)(?:^|[.\\n])\\s*$name\\s*:\\s*([^.]*)")
            .find(songContext)?.groupValues?.getOrNull(1)?.trim().orEmpty()

    override fun descargar() {}
}
