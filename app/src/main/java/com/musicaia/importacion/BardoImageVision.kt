package com.musicaia.importacion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Etiquetado visual local probabilístico; no ofrece descripción libre ni identifica personas. */
object BardoImageVision {
    data class Label(val text: String, val confidence: Float)

    private const val MAX_IMAGE_BYTES = 40L * 1024L * 1024L
    private const val MAX_DIMENSION = 1024
    private const val MIN_CONFIDENCE = 0.30f
    private const val MAX_LABELS = 12

    fun analyze(file: File): List<Label> {
        require(file.isFile && file.length() in 1..MAX_IMAGE_BYTES) {
            "La imagen debe existir y ocupar como máximo 40 MB."
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Dimensiones de imagen no válidas." }
        var sample = 1
        while (bounds.outWidth / sample > MAX_DIMENSION || bounds.outHeight / sample > MAX_DIMENSION) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw IllegalArgumentException("Android no pudo decodificar la imagen.")
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        return try {
            val labels = Tasks.await(labeler.process(InputImage.fromBitmap(bitmap, 0)), 45, TimeUnit.SECONDS)
                .map { Label(it.text, it.confidence) }
            filterLabels(labels)
        } finally {
            labeler.close()
            bitmap.recycle()
        }
    }

    fun filterLabels(labels: List<Label>): List<Label> = labels.asSequence()
        .filter { it.text.isNotBlank() && it.confidence.isFinite() && it.confidence >= MIN_CONFIDENCE }
        .sortedByDescending { it.confidence }
        .distinctBy { it.text.trim().lowercase(Locale.ROOT) }
        .take(MAX_LABELS)
        .toList()

    fun format(labels: List<Label>): String = filterLabels(labels).joinToString("\n") {
        "- " + it.text + " (" + String.format(Locale.ROOT, "%.0f", it.confidence * 100f) + "%)"
    }
}
