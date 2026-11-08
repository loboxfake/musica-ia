package com.musicaia.modulos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sin
import kotlin.random.Random

/** Renderizador gráfico procedural offline; no es un modelo generativo de imágenes. */
object BardoCoverArtGenerator {
    const val SIZE = 1024
    val STYLES = listOf("Cosmos", "Geometría", "Aurora", "Noir", "Orgánico")

    fun stableSeed(title: String, artist: String, concept: String, style: String): Int =
        listOf(title.trim(), artist.trim(), concept.trim(), style.trim()).joinToString("|").hashCode()

    fun wrapTitle(title: String, maxCharacters: Int = 18): List<String> {
        val words = title.trim().take(140).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return listOf("SIN TÍTULO")
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (candidate.length <= maxCharacters || current.isEmpty()) current = candidate
            else {
                lines.add(current)
                current = word
            }
            if (lines.size == 2) break
        }
        if (current.isNotEmpty() && lines.size < 3) lines.add(current)
        return lines.take(3).map { it.take(28) }
    }

    fun generate(output: File, title: String, artist: String, concept: String, style: String): File {
        require(title.isNotBlank()) { "Escribe el título de la canción o disco." }
        require(title.length <= 140 && artist.length <= 100 && concept.length <= 500) {
            "El título, artista o concepto supera el límite permitido."
        }
        val selectedStyle = style.takeIf { it in STYLES } ?: STYLES.first()
        val random = Random(stableSeed(title, artist, concept, selectedStyle))
        val colors = palette(selectedStyle)
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, SIZE.toFloat(), SIZE.toFloat(),
                    intArrayOf(colors[0], colors[1], colors[2]), null, Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), background)
            when (selectedStyle) {
                "Cosmos" -> drawCosmos(canvas, random, colors)
                "Geometría" -> drawGeometry(canvas, random, colors)
                "Aurora" -> drawAurora(canvas, random, colors)
                "Noir" -> drawNoir(canvas, random, colors)
                else -> drawOrganic(canvas, random, colors)
            }
            drawTypography(canvas, title.trim(), artist.trim(), concept.trim())
            output.parentFile?.let { check(it.exists() || it.mkdirs()) { "No se pudo crear la carpeta de portadas." } }
            FileOutputStream(output).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Falló la codificación PNG." }
                stream.flush()
                stream.fd.sync()
            }
            check(output.isFile && output.length() > 4096L) { "El PNG resultante está vacío o incompleto." }
            return output
        } finally {
            bitmap.recycle()
        }
    }

    private fun palette(style: String): IntArray = when (style) {
        "Geometría" -> intArrayOf(0xff10172a.toInt(), 0xff275b8a.toInt(), 0xfff0a44b.toInt())
        "Aurora" -> intArrayOf(0xff061b2a.toInt(), 0xff176b6b.toInt(), 0xffb46ad5.toInt())
        "Noir" -> intArrayOf(0xff08090d.toInt(), 0xff31343b.toInt(), 0xffb68a52.toInt())
        "Orgánico" -> intArrayOf(0xff172b22.toInt(), 0xff5d7750.toInt(), 0xffd4a56a.toInt())
        else -> intArrayOf(0xff080f2b.toInt(), 0xff38356f.toInt(), 0xffb05283.toInt())
    }

    private fun drawCosmos(canvas: Canvas, random: Random, c: IntArray) {
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(580f, 420f, 420f, intArrayOf(c[2], c[1], 0x00000000),
                null, Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(580f, 420f, 420f, glow)
        val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xccffffff.toInt() }
        repeat(170) {
            canvas.drawCircle(random.nextFloat() * SIZE, random.nextFloat() * SIZE,
                random.nextFloat() * 3.7f + 0.5f, star)
        }
        val orbit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66ffffff
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        repeat(4) { i ->
            canvas.drawOval(RectF(110f + i * 28f, 145f + i * 40f,
                900f - i * 20f, 840f - i * 32f), orbit)
        }
    }

    private fun drawGeometry(canvas: Canvas, random: Random, c: IntArray) {
        repeat(18) { i ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (i % 3 == 0) c[2] else 0x66ffffff
                style = if (i % 2 == 0) Paint.Style.STROKE else Paint.Style.FILL
                strokeWidth = random.nextFloat() * 15f + 2f
            }
            val inset = random.nextFloat() * 250f
            canvas.drawRoundRect(RectF(inset, inset * 0.4f, SIZE - inset * 0.5f,
                SIZE - inset), random.nextFloat() * 140f, random.nextFloat() * 140f, paint)
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88ffffff.toInt(); strokeWidth = 2f }
        repeat(24) {
            val x = random.nextFloat() * SIZE
            canvas.drawLine(x, 0f, SIZE - x, SIZE.toFloat(), line)
        }
    }

    private fun drawAurora(canvas: Canvas, random: Random, c: IntArray) {
        repeat(9) { i ->
            val path = Path()
            val yBase = 200f + i * 82f
            path.moveTo(-80f, yBase)
            for (step in 0..12) {
                val x = step * 100f
                val y = yBase + sin((step + i) * 0.75).toFloat() *
                    (90f + i * 4f) + random.nextFloat() * 25f
                path.lineTo(x, y)
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (i % 2 == 0) c[2] else c[1]
                alpha = 18 + i * 5
                style = Paint.Style.STROKE
                strokeWidth = 35f + i * 3f
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawPath(path, paint)
        }
    }

    private fun drawNoir(canvas: Canvas, random: Random, c: IntArray) {
        val buildings = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x887b8492.toInt() }
        repeat(16) { i ->
            val width = random.nextFloat() * 80f + 35f
            val height = random.nextFloat() * 500f + 180f
            val left = i * 68f - 20f
            canvas.drawRect(left, SIZE - height, left + width, SIZE.toFloat(), buildings)
        }
        canvas.drawCircle(740f, 300f, 145f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c[2] })
        canvas.drawCircle(800f, 255f, 145f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c[0] })
    }

    private fun drawOrganic(canvas: Canvas, random: Random, c: IntArray) {
        repeat(40) { i ->
            val radius = random.nextFloat() * 210f + 25f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (i % 2 == 0) c[2] else c[1]
                alpha = random.nextInt(10, 48)
            }
            canvas.drawCircle(random.nextFloat() * SIZE, random.nextFloat() * SIZE, radius, paint)
        }
        val leaf = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x77d6e4ad
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        repeat(22) {
            val cx = random.nextFloat() * SIZE
            val cy = random.nextFloat() * SIZE
            val radius = random.nextFloat() * 170f + 40f
            canvas.save()
            canvas.rotate(random.nextFloat() * 180f, cx, cy)
            canvas.drawOval(RectF(cx - radius * 0.65f, cy - radius,
                cx + radius * 0.65f, cy + radius), leaf)
            canvas.restore()
        }
    }

    private fun drawTypography(canvas: Canvas, title: String, artist: String, concept: String) {
        val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 560f, 0f, SIZE.toFloat(),
                intArrayOf(0x00000000, 0xdd000000.toInt()), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 520f, SIZE.toFloat(), SIZE.toFloat(), shade)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xffe9edf5.toInt()
            textSize = 25f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        canvas.drawText("BARDO / ORIGINAL COVER", 72f, 78f, label)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xffffffff.toInt()
            textSize = when {
                title.length > 42 -> 54f
                title.length > 24 -> 68f
                else -> 84f
            }
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setShadowLayer(8f, 0f, 3f, 0xaa000000.toInt())
        }
        var y = 760f
        wrapTitle(title).take(2).forEach { line ->
            while (titlePaint.measureText(line) > 870f && titlePaint.textSize > 42f) titlePaint.textSize -= 3f
            canvas.drawText(line, 72f, y, titlePaint)
            y += titlePaint.textSize * 1.08f
        }
        val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xffe9edf5.toInt()
            textSize = 34f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        canvas.drawText(artist.ifBlank { "ARTISTA" }.take(48), 74f, 960f, artistPaint)
        if (concept.isNotBlank()) {
            artistPaint.textSize = 22f
            artistPaint.alpha = 210
            canvas.drawText(concept.replace(Regex("\\s+"), " ").take(64), 74f, 995f, artistPaint)
        }
    }
}
