package com.musicaia.modulos

import android.content.Context
import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.TipoModulo
import com.musicaia.modelo.BardoResourceBudget
import com.musicaia.modulos.litert.EngineLiteRT
import java.io.File
import java.io.FileOutputStream

/**
 * Genera segmentos instrumentales reales con Stable Audio Open Small + ExecuTorch.
 * El modelo se carga desde assets privados; no hay inferencia ni llamadas de red.
 */
class ModuloInstrumental(
    context: Context,
    private val songContext: String,
    private val quickTest: Boolean = false,
    private val onSegmentProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
    private val isCancellationRequested: () -> Boolean = { false }
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
        // BardoTaskService se ejecuta en el proceso aislado :audio. MainActivity
        // libera Gemma en el proceso principal antes de arrancar el servicio;
        // esta llamada es defensiva para pruebas o ejecuciones en el mismo proceso.
        EngineLiteRT.cerrar()
        if (!AudioGenNative.available) throw IllegalStateException("El motor nativo ExecuTorch no está disponible en esta APK.")
        val minimumAvailableBytes = 1536L * 1024L * 1024L
        val bloqueoRecursos = BardoResourceBudget.blockingReason(ctx, minimumAvailableBytes)
        if (bloqueoRecursos != null) {
            android.util.Log.w("BARDO-AudioGen", "Inferencia aplazada por presupuesto de recursos: $bloqueoRecursos")
            throw IllegalStateException("No se cargará Stable Audio todavía. $bloqueoRecursos")
        }
        val modelDir = File(ctx.filesDir, "stable_audio_model")
        if (!modelDir.exists() && !modelDir.mkdirs()) throw IllegalStateException("No se pudo crear el directorio privado del modelo Stable Audio.")

        val requiredSpace = modelFiles.sumOf { name ->
            val expectedLength = ctx.assets.openFd("audio_model/$name").use { it.length }
            val destination = File(modelDir, name)
            if (destination.isFile && destination.length() == expectedLength) 0L else expectedLength
        } + 256L * 1024 * 1024
        if (requiredSpace > 256L * 1024 * 1024 && modelDir.freeSpace < requiredSpace) throw IllegalStateException("Espacio insuficiente para preparar Stable Audio: libres ${modelDir.freeSpace / (1024L * 1024L)} MiB; necesarios ${requiredSpace / (1024L * 1024L)} MiB.")

        return try {
            for (name in modelFiles) {
                val assetName = "audio_model/$name"
                val destination = File(modelDir, name)
                val temporary = File(modelDir, "$name.part")
                val expectedLength = ctx.assets.openFd(assetName).use { it.length }
                if (destination.isFile && destination.length() == expectedLength) continue

                val copiedBytes = ctx.assets.open(assetName).buffered().use { input ->
                    FileOutputStream(temporary).buffered().use { output ->
                        val copied = input.copyTo(output)
                        output.flush()
                        copied
                    }
                }
                if (!temporary.isFile || copiedBytes != expectedLength || temporary.length() != expectedLength) {
                    throw IllegalStateException("La copia temporal del archivo $name no coincide con el tamaño del asset ($copiedBytes/$expectedLength bytes).")
                }
                if (destination.exists() && !destination.delete()) throw IllegalStateException("No se pudo sustituir el archivo incompleto $name.")
                if (!temporary.renameTo(destination)) throw IllegalStateException("No se pudo activar el archivo preparado $name.")
            }
            val assetsValidos = modelFiles.all { name ->
                val expectedLength = ctx.assets.openFd("audio_model/$name").use { it.length }
                val file = File(modelDir, name)
                file.isFile && file.length() == expectedLength
            }
            if (!assetsValidos) {
                throw IllegalStateException("Falta o está incompleto al menos uno de los cuatro archivos requeridos de Stable Audio.")
            }
            true
        } catch (error: Exception) {
            throw IllegalStateException("No se pudieron preparar los archivos locales de Stable Audio: ${error.message ?: error.javaClass.simpleName}", error)
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
        val workDir = File(ctx.cacheDir, "audio_segments_${System.currentTimeMillis()}")
        if (!workDir.mkdirs()) throw IllegalStateException("No se pudo crear el directorio temporal de audio.")
        val diagnosticos = mutableListOf<String>()
        val informeDiagnostico = File(ctx.filesDir, "audio_generation_diagnostics.txt")
        var generacionCompleta = false

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

        val sectionsToGenerate = if (quickTest) sectionNames.take(1) else sectionNames
        val segments = mutableListOf<File>()
        try {
            sectionsToGenerate.forEachIndexed { index, section ->
                val segmentStartedAt = System.currentTimeMillis()
                if (isCancellationRequested()) throw java.util.concurrent.CancellationException("Cancelación solicitada por el usuario.")
                onSegmentProgress(index + 1, sectionsToGenerate.size, "Preparando ${section.substringBefore(':')}")
                val previousSection = sectionNames.getOrNull(index - 1)
                val prompt = buildPrompt(section, lyrics, previousSection)
                val segment = File(workDir, "segment_%02d.wav".format(index + 1))
                val generated = AudioGenNative.generateNative(
                    modelDir.absolutePath,
                    prompt,
                    segment.absolutePath,
                    (System.currentTimeMillis().toInt() + index * 37)
                )
                if (generated.isNullOrBlank() || !segment.isFile || segment.length() <= 44L) {
                    val causa = "El motor no produjo un WAV utilizable."
                    diagnosticos += "SEGMENT ${index + 1}/${sectionsToGenerate.size} | STATE=FAIL | CAUSE=$causa"
                    throw IllegalStateException("El modelo de audio no generó el segmento ${index + 1}/${sectionsToGenerate.size}: $causa")
                }
                val info = WavSegmentDiagnostics.inspect(segment)
                val duracionMinima = if (quickTest) 1.5 else 5.0
                val estadoSegmento = if (info.durationSeconds >= duracionMinima) "PASS" else "FAIL"
                val tiempoSegmento = ((System.currentTimeMillis() - segmentStartedAt) / 1000L).coerceAtLeast(0L)
                val detalle = "SEGMENT ${index + 1}/${sectionsToGenerate.size} | STATE=$estadoSegmento | EXPECTED=11.0s | ACTUAL=%.3fs | FRAMES=${info.frames} | SAMPLE_RATE=${info.sampleRate}Hz | CHANNELS=${info.channels} | BITS=${info.bitsPerSample} | BYTES=${segment.length()} | ELAPSED=${tiempoSegmento}s".format(java.util.Locale.ROOT, info.durationSeconds)
                diagnosticos += detalle
                if (estadoSegmento != "PASS") {
                    throw IllegalStateException("Segmento ${index + 1}/${sectionsToGenerate.size} demasiado corto (%.3fs; mínimo %.1fs).".format(java.util.Locale.ROOT, info.durationSeconds, duracionMinima))
                }
                segments.add(segment)
                onSegmentProgress(index + 1, sectionsToGenerate.size, "Segmento ${index + 1}/${sectionsToGenerate.size} · PASS · %.2fs · ${info.frames} frames · ${info.sampleRate} Hz · ${tiempoSegmento}s".format(java.util.Locale.ROOT, info.durationSeconds))
                if (isCancellationRequested()) throw java.util.concurrent.CancellationException("Cancelación solicitada por el usuario.")
            }

            onSegmentProgress(sectionsToGenerate.size, sectionsToGenerate.size, "Uniendo y validando los ${sectionsToGenerate.size} segmentos WAV")
            if (isCancellationRequested()) throw java.util.concurrent.CancellationException("Cancelación solicitada por el usuario.")
            val output = File(ctx.filesDir, "instrumental_ai.wav")
            AudioAssembler.assemble(
                inputFiles = segments,
                outputFile = output,
                crossfadeMs = 700,
                fadeOutMs = 1200
            )
            if (!output.isFile || output.length() <= 44L) {
                throw IllegalStateException("El WAV instrumental final está vacío.")
            }
            val finalInfo = WavSegmentDiagnostics.inspect(output)
            val minimumDuration = if (quickTest) 2.0 else 180.0
            if (finalInfo.durationSeconds < minimumDuration) {
                throw IllegalStateException(if (quickTest) "La prueba rápida generó menos de 2 segundos de audio." else "El instrumental generado dura menos de 3 minutos.")
            }
            diagnosticos += "FINAL | STATE=PASS | DURATION=%.3fs | FRAMES=${finalInfo.frames} | SAMPLE_RATE=${finalInfo.sampleRate}Hz | CHANNELS=${finalInfo.channels} | BYTES=${output.length()}".format(java.util.Locale.ROOT, finalInfo.durationSeconds)
            onSegmentProgress(sectionsToGenerate.size, sectionsToGenerate.size, "WAV final validado · %.1fs · ${finalInfo.sampleRate} Hz · ${output.length()} bytes".format(java.util.Locale.ROOT, finalInfo.durationSeconds))
            generacionCompleta = true
            return Dato.Audio(output.absolutePath, finalInfo.sampleRate)
        } catch (error: Throwable) {
            diagnosticos += "RESULT | STATE=FAIL | CAUSE=${error.javaClass.simpleName}: ${error.message ?: "sin detalle"}"
            if (error is java.util.concurrent.CancellationException) throw error
            throw IllegalStateException(
                "${error.message ?: error.javaClass.simpleName} WAV parciales: ${workDir.absolutePath}. Diagnóstico: ${informeDiagnostico.absolutePath}",
                error
            )
        } finally {
            runCatching { AudioGenNative.releaseNative() }
                .onFailure { android.util.Log.e("BARDO-AudioGen", "No se pudieron liberar los modelos nativos de audio", it) }
            informeDiagnostico.writeText(diagnosticos.joinToString("\n") + "\n", Charsets.UTF_8)
            if (generacionCompleta) {
                workDir.deleteRecursively()
            } else {
                android.util.Log.e("BARDO-AudioGen", "Se conservan WAV parciales y diagnóstico en ${workDir.absolutePath}")
            }
        }
    }

    private fun buildPrompt(section: String, lyrics: String, previousSection: String?): String {
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

        val bpmSolicitado = Regex("""(?i)(?:\b(\d{2,3})\s*bpm\b|\bbpm\s*(\d{2,3})\b|\b(\d{2,3})\b)""")
            .find(tempo)?.groupValues?.drop(1)?.firstNotNullOfOrNull { it.toIntOrNull() }
            ?.takeIf { it in 40..220 }
        val tempoEnglish = when {
            bpmSolicitado != null -> "exactly $bpmSolicitado BPM, fixed steady tempo throughout the entire song"
            tempo.contains("lent", true) -> "slow tempo around 75 BPM, fixed steady pulse"
            tempo.contains("rápid", true) || tempo.contains("rapid", true) -> "up-tempo around 128 BPM, fixed steady pulse"
            tempo.isBlank() -> "mid-tempo around 105 BPM, fixed steady pulse"
            else -> "mid-tempo around 105 BPM, fixed steady pulse; requested feel: ${tempo.take(40)}"
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
        val continuity = previousSection?.let {
            "Continue the same key center, tempo, groove, instrumentation palette and melodic identity established in the previous section ($it); make only a natural arrangement transition, do not restart the song or change tempo. "
        }.orEmpty()
        return base + continuity + if (lyricHint.isNotBlank()) {
            "Emotional inspiration from these lyrics: $lyricHint."
        } else ""
    }

    private fun field(name: String): String =
        Regex("(?i)(?:^|[.\\n])\\s*$name\\s*:\\s*([^.]*)")
            .find(songContext)?.groupValues?.getOrNull(1)?.trim().orEmpty()

    override fun descargar() {}
}
