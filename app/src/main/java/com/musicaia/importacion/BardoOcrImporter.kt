package com.musicaia.importacion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * OCR local de texto visible. No identifica objetos ni interpreta semánticamente
 * imágenes; devuelve solo texto que el reconocedor haya podido leer.
 */
object BardoOcrImporter {
    private const val MAX_IMAGE_DIMENSION = 2048
    private const val MAX_VIDEO_DURATION_MS = 10L * 60L * 1000L
    private const val MAX_VIDEO_FRAMES = 8

    fun extractImage(file: File): String {
        require(file.isFile && file.length() in 1..(40L * 1024L * 1024L)) {
            "La imagen debe existir y ocupar como máximo 40 MB."
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "La imagen no tiene dimensiones válidas." }
        var sample = 1
        while (bounds.outWidth / sample > MAX_IMAGE_DIMENSION || bounds.outHeight / sample > MAX_IMAGE_DIMENSION) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw IllegalArgumentException("Android no pudo decodificar la imagen.")
        return try {
            recognizeMultiscript(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun extractVideo(file: File): String {
        require(file.isFile && file.length() in 1..(100L * 1024L * 1024L)) {
            "El vídeo debe existir y ocupar como máximo 100 MB."
        }
        val retriever = MediaMetadataRetriever()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: throw IllegalArgumentException("No se pudo leer la duración del vídeo.")
            require(duration in 1..MAX_VIDEO_DURATION_MS) {
                "Para proteger la memoria, el análisis OCR de vídeo admite hasta 10 minutos."
            }
            val frames = minOf(MAX_VIDEO_FRAMES, (duration / 5000L).toInt().coerceAtLeast(1))
            return buildString {
                for (index in 0 until frames) {
                    val timestampMs = ((index + 1L) * duration) / (frames + 1L)
                    val original = retriever.getFrameAtTime(timestampMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: continue
                    val bitmap = scaled(original)
                    if (bitmap !== original) original.recycle()
                    try {
                        val text = recognize(bitmap, recognizer)
                        if (text.isNotBlank()) append("[Fotograma aprox. ").append(timestampMs / 1000L)
                            .append(" s]\n").append(text).append('\n')
                    } finally {
                        bitmap.recycle()
                    }
                }
            }.trim()
        } finally {
            recognizer.close()
            retriever.release()
        }
    }

    fun extractScannedPdf(document: PDDocument): String {
        require(document.numberOfPages in 1..100) {
            "El OCR de PDF escaneado admite hasta 100 páginas."
        }
        val renderer = PDFRenderer(document)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val pages = minOf(document.numberOfPages, 10)
            return buildString {
                for (page in 0 until pages) {
                    val original = renderer.renderImageWithDPI(page, 110f)
                    val bitmap = scaled(original)
                    if (bitmap !== original) original.recycle()
                    try {
                        val text = recognize(bitmap, recognizer)
                        if (text.isNotBlank()) append("[Página ").append(page + 1).append("]\n")
                            .append(text).append('\n')
                    } finally {
                        bitmap.recycle()
                    }
                }
            }.trim()
        } finally {
            recognizer.close()
        }
    }

    private fun recognizeMultiscript(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizers = buildList<Pair<String, TextRecognizer>> {
            add("latino" to TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS))
            add("chino" to TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()))
            add("devanagari" to TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()))
            add("japones" to TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()))
            add("coreano" to TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()))
        }
        try {
            val outputs = mutableListOf<String>()
            recognizers.forEach { (script, recognizer) ->
                val text = runCatching {
                    Tasks.await(recognizer.process(image), 8, TimeUnit.SECONDS).text.trim()
                }.getOrDefault("")
                if (text.isNotBlank()) outputs.add("[$script]\n$text")
            }
            return outputs.distinctBy {
                it.substringAfter('\n').lowercase(java.util.Locale.ROOT)
                    .replace(Regex("\\s+"), " ").trim()
            }.joinToString("\n\n").take(240000)
        } finally {
            recognizers.forEach { runCatching { it.second.close() } }
        }
    }

    private fun recognize(bitmap: Bitmap): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try { recognize(bitmap, recognizer) } finally { recognizer.close() }
    }

    private fun recognize(bitmap: Bitmap, recognizer: TextRecognizer): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        return Tasks.await(recognizer.process(image), 45, TimeUnit.SECONDS).text
    }

    private fun scaled(bitmap: Bitmap): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= MAX_IMAGE_DIMENSION) return bitmap
        val ratio = MAX_IMAGE_DIMENSION.toFloat() / largest.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }
}
