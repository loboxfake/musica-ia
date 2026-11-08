package com.musicaia.modulos.imagen

import android.content.Context
import android.graphics.Bitmap
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

        outputFile.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            val pixels = IntArray(512 * 512)
            for (i in pixels.indices) {
                val base = i * 3
                pixels[i] = Color.rgb(
                    result.rgb[base].toInt() and 0xff,
                    result.rgb[base + 1].toInt() and 0xff,
                    result.rgb[base + 2].toInt() and 0xff
                )
            }
            bitmap.setPixels(pixels, 0, 512, 0, 0, 512, 512)
            overlayTypography(bitmap, title, artist)
            FileOutputStream(outputFile).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Android no pudo codificar la portada PNG."
                }
                stream.fd.sync()
            }
        } finally {
            bitmap.recycle()
        }
        check(outputFile.isFile && outputFile.length() > 100_000L) {
            "La portada generada no superó la validación de archivo."
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
