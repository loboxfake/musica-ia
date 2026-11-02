package com.musicaia.modulos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.TipoModulo
import java.io.File

class ModuloPortada(context: Context) : ModuloIA {
    override val id = "portada"
    override val tipo = TipoModulo.PORTADA
    private val ctx = context.applicationContext

    override fun cargar(): Boolean = true

    override fun ejecutar(entrada: Dato): Dato {
        val ruta = File(ctx.getExternalFilesDir(null), "portada_generada.png").absolutePath
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val base = Paint().apply { color = Color.parseColor("#2A0F3D") }
        canvas.drawRect(0f, 0f, 512f, 512f, base)
        val txt = Paint().apply { color = Color.parseColor("#F0A500"); textSize = 48f; isAntiAlias = true }
        val tema = if (entrada is Dato.Texto) entrada.texto else "Portada"
        canvas.drawText(tema, 40f, 256f, txt)
        FileOutputStream(ruta).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 95, out) }
        return Dato.Imagen(rutaPng = ruta)
    }

    override fun descargar() {}
}
