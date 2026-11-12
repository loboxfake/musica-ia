package com.musicaia.modulos.imagen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.LinearGradient
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

/** Generación local real de portadas con Bonsai Image 4B + rotulación nítida. */
object BardoBonsaiImageGenerator {
    data class Output(val file: File, val seconds: Double, val stepSeconds: List<Double>)

    fun generate(
        context: Context,
        prompt: String,
        title: String,
        artist: String,
        outputFile: File,
        cancelled: AtomicBoolean,
        status: (String) -> Unit,
        progress: (String, Double) -> Unit
    ): Output {
        check(BardoBonsaiModelInstaller.isInstalled(context)) {
            "El modelo Bonsai no está instalado o no superó la verificación."
        }
        val dir = BardoBonsaiModelInstaller.modelDir(context)
        val meta = JSONObject(File(dir, "pipeline_meta.json").readText())
        val vocabBytes = File(dir, "tokenizer/vocab.json").readBytes()
        val mergesBytes = File(dir, "tokenizer/merges.txt").readBytes()
        val tokenizer = BardoQwenTokenizer(vocabBytes.inputStream(), mergesBytes.inputStream())
        val pipeline = BardoBonsaiPipeline(dir, meta)
        val result = pipeline.generate(
            tokenizer,
            prompt,
            System.nanoTime(),
            4,
            status
        ) { label, fraction ->
            if (cancelled.get()) pipeline.cancelled = true
            progress(label, fraction)
        }
        check(!cancelled.get()) { "Generación de imagen cancelada por el usuario." }
        require(result.rgb.size == 512 * 512 * 3) {
            "El modelo no devolvió una imagen RGB completa de 512×512."
        }

        val outputDirectory = outputFile.absoluteFile.parentFile ?: File(".")
        check(outputDirectory.isDirectory || outputDirectory.mkdirs()) {
            "No se pudo crear el directorio de la imagen."
        }
        val temporary = File.createTempFile("bardo_bonsai_", ".png.tmp", outputDirectory)
        try {
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            try {
                val pixels = IntArray(512 * 512)
                for (i in pixels.indices) {
                    if ((i and 0x3fff) == 0) {
                        check(!cancelled.get()) { "Generación de imagen cancelada por el usuario." }
                    }
                    val base = i * 3
                    pixels[i] = Color.rgb(
                        result.rgb[base].toInt() and 0xff,
                        result.rgb[base + 1].toInt() and 0xff,
                        result.rgb[base + 2].toInt() and 0xff
                    )
                }
                bitmap.setPixels(pixels, 0, 512, 0, 0, 512, 512)
                check(!cancelled.get()) { "Generación de imagen cancelada por el usuario." }
                overlayTypography(bitmap, title, artist)
                FileOutputStream(temporary).use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        "Android no pudo codificar la portada PNG."
                    }
                    stream.fd.sync()
                }
            } finally {
                bitmap.recycle()
            }
            check(temporary.isFile && temporary.length() > 1_024L) {
                "La portada temporal no contiene un PNG válido."
            }
            val pngBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.absolutePath, pngBounds)
            check(pngBounds.outWidth == 512 && pngBounds.outHeight == 512) {
                "La portada PNG no tiene las dimensiones verificadas de 512×512."
            }
            check(!cancelled.get()) { "Generación de imagen cancelada por el usuario." }
            val size = temporary.length()
            try {
                Files.move(
                    temporary.toPath(), outputFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(), outputFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            check(outputFile.isFile && outputFile.length() == size) {
                "La imagen PNG publicada no coincide con el archivo temporal verificado."
            }
        } finally {
            temporary.delete()
        }
        return Output(outputFile, result.seconds, result.stepSeconds)
    }

    private fun overlayTypography(bitmap: Bitmap, title: String, artist: String) {
        val canvas = Canvas(bitmap)
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, height * 0.60f, 0f, height,
                intArrayOf(Color.TRANSPARENT, 0xE6000000.toInt()),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, height * 0.60f, width, height, scrim)
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 44f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setShadowLayer(4f, 0f, 2f, Color.BLACK)
        }
        val layout = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, 448)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xffeeeeee.toInt()
            textSize = 19f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
        }
        val titleTop = height - layout.height - if (artist.isBlank()) 32f else 66f
        canvas.save()
        canvas.translate(32f, titleTop)
        layout.draw(canvas)
        canvas.restore()
        if (artist.isNotBlank()) canvas.drawText(artist.take(80), width / 2f, height - 28f, artistPaint)
    }
}
