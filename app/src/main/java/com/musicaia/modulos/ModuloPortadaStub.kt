package com.musicaia.modulos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.musicaia.modelo.*
import java.io.FileOutputStream

class ModuloPortadaStub(private val context: Context) : ModuloIA {
    override val id = "portada-stub"
    override val tipo = TipoModulo.PORTADA
    private var cargado = false

    override fun cargar(): Boolean {
        Thread.sleep(500)
        cargado = true
        return true
    }

    override fun ejecutar(entrada: Salida): Entrada {
        if (!cargado) throw IllegalStateException("Modelo no cargado")
        // Generar PNG de color liso (azul pastel)
        val ruta = generarColorLisoPng(context, Color.parseColor("#87CEFA"))
        return Entrada.Imagen(ruta)
    }

    override fun descargar() {
        cargado = false
    }

    private fun generarColorLisoPng(context: Context, color: Int): String {
        val ancho = 512
        val alto = 512
        val bitmap = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = color
        canvas.drawRect(0f, 0f.toFloat(), ancho.toFloat(), alto.toFloat(), paint)
        
        val archivo = java.io.File(context.cacheDir, "portada_ia.png")
        try {
            val fos = FileOutputStream(archivo)
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos)
            fos.close()
        } catch (e: Exception) {
            Log.e("ModuloPortadaStub", "Error generando PNG", e)
        }
        return archivo.absolutePath
    }
}