package com.musicaia.modulos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioFormat
import android.media.AudioRecord
import com.musicaia.modelo.Catalogo
import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.TipoModulo
import java.io.File
import java.io.FileOutputStream

class ModuloPortada(context: Context) : ModuloIA {
    override val id = "portada"
    override val tipo = TipoModulo.PORTADA
    private val ctx = context.applicationContext
    private val catalogo = Catalogo()
    private val modeloId = "portada-stable-diffusion-small"

    override fun cargar(): Boolean {
        val modelo = catalogo.obtenerModelo(modeloId)
        return modelo != null || catalogo.cargarDesdeUrl("https://raw.githubusercontent.com/loboxfake/musica-ia/main/catalogo/catalogo.json").run { catalogo.obtenerModelo(modeloId) != null }
    }

    override fun ejecutar(entrada: Dato): Dato {
        val ruta = File(ctx.getExternalFilesDir(null), "portada_generada.png").absolutePath
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = Color.parseColor("#2A0F3D") }
        canvas.drawRect(0f, 0f, 512f, 512f, paint)
        val textPaint = Paint().apply { color = Color.parseColor("#F0A500"); textSize = 48f; isAntiAlias = true }
        val tema = if (entrada is Dato.Texto) entrada.texto else "Portada"
        canvas.drawText(tema, 40f, 256f, textPaint)
        FileOutputStream(ruta).use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 95, out) }
        return Dato.Imagen(rutaPng = ruta)
    }

    override fun descargar() {}
}
