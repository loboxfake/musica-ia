package com.musicaia.modulos

import android.content.Context
import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.TipoModulo
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * MODO 3 — mezcla PCM real de una grabación del usuario con el instrumental.
 *
 * Lee los WAV originales sin modificarlos, delega la decodificación, el
 * remuestreo y la mezcla en MezclaVoz, valida el WAV generado y solo entonces
 * lo publica como modo3_mezcla.wav. No genera ni clona una voz mediante IA.
 */
class Modo3MezclaVozUsuario(context: Context) : ModuloIA {
    override val id = "modo3_voz_usuario"
    override val tipo = TipoModulo.MEZCLA
    private val ctx = context.applicationContext

    override fun cargar(): Boolean = true

    override fun ejecutar(entrada: Dato): Dato {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val voz = File(dir, "voz_usuario.wav")
        val instrumental = File(dir, "instrumental.wav")
        val mezcla = File(dir, "modo3_mezcla.wav")

        if (!voz.isFile || voz.length() <= 44L) {
            return Dato.Texto(
                "Modo 3 pendiente: falta una grabación de voz válida " +
                    "(voz_usuario.wav, WAV PCM16)."
            )
        }
        if (!instrumental.isFile || instrumental.length() <= 44L) {
            return Dato.Texto(
                "Modo 3 pendiente: falta el instrumental válido " +
                    "(instrumental.wav, WAV PCM16)."
            )
        }

        var temporal: File? = null
        return try {
            check(dir.isDirectory || dir.mkdirs()) {
                "No se pudo crear el directorio de audio."
            }

            // El temporal queda en el mismo directorio para poder renombrarlo
            // de forma atómica sin tocar las grabaciones originales.
            temporal = File.createTempFile("bardo_modo3_", ".wav.tmp", dir)

            val resultado = MezclaVoz.mezclar(
                rutaVoz = voz.absolutePath,
                rutaInstrumental = instrumental.absolutePath,
                rutaSalida = temporal.absolutePath
            )

            check(temporal.isFile && temporal.length() > 44L) {
                "El mezclador no generó un WAV de salida."
            }
            val comprobacion = LectorWav.leer(temporal.absolutePath)
            check(comprobacion.muestras.isNotEmpty()) {
                "El WAV generado no contiene muestras PCM."
            }
            check(comprobacion.frecuencia == resultado.frecuenciaMuestreo) {
                "La frecuencia del WAV generado no coincide con el resultado."
            }

            try {
                Files.move(
                    temporal.toPath(),
                    mezcla.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporal.toPath(),
                    mezcla.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }

            // Verificación final sobre el archivo publicado, no solo sobre
            // el temporal previo al movimiento.
            val salida = LectorWav.leer(mezcla.absolutePath)
            check(mezcla.isFile && mezcla.length() > 44L && salida.muestras.isNotEmpty()) {
                "El WAV publicado no superó la validación final."
            }

            Dato.Audio(
                rutaWav = mezcla.absolutePath,
                frecuenciaMuestreo = salida.frecuencia
            )
        } catch (e: Exception) {
            val motivo = e.message?.take(240) ?: e.javaClass.simpleName
            Dato.Texto("Modo 3 no pudo crear la mezcla PCM: $motivo")
        } finally {
            temporal?.let { if (it.exists()) it.delete() }
        }
    }

    override fun descargar() {}
}
