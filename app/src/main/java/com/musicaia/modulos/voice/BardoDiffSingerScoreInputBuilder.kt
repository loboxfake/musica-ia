package com.musicaia.modulos.voice

import kotlin.math.pow

/**
 * Convierte una secuencia fonética ya resuelta en los tensores temporales base
 * que espera DiffSinger. No realiza G2P, inferencia ni síntesis por sí mismo.
 */
object BardoDiffSingerScoreInputBuilder {
    data class PhonemeNote(
        val phoneme: String,
        val startMs: Double,
        val durationMs: Double,
        val midiNote: Int?
    )

    data class InputBatch(
        val tokens: LongArray,
        val durations: LongArray,
        val f0Hz: FloatArray,
        val frameMs: Double
    ) {
        val totalFrames: Int get() = f0Hz.size
    }

    fun build(
        spans: List<PhonemeNote>,
        phonemeIds: Map<String, Int>,
        sampleRate: Int,
        hopSize: Int,
        headFrames: Int = 8,
        tailFrames: Int = 8
    ): InputBatch {
        require(sampleRate in 8_000..192_000) { "Frecuencia de muestreo inválida." }
        require(hopSize in 1..8_192) { "Hop size inválido." }
        require(headFrames in 0..256 && tailFrames in 0..256) { "Padding DiffSinger fuera de límites." }
        require(spans.isNotEmpty()) { "La secuencia no contiene fonemas." }
        require(spans.size <= MAX_PHONEMES) { "La secuencia supera el máximo de fonemas." }
        require("SP" in phonemeIds) { "La vozbank no define el fonema SP de silencio." }
        require(phonemeIds.values.all { it >= 0 }) { "El inventario contiene índices fonéticos negativos." }

        val frameMs = hopSize.toDouble() * 1_000.0 / sampleRate
        data class Segment(val phoneme: String, val durationMs: Double, val midiNote: Int?)
        val segments = ArrayList<Segment>(spans.size + 2)
        segments += Segment("SP", headFrames * frameMs, null)

        var previousEndMs = 0.0
        spans.forEachIndexed { index, span ->
            require(span.phoneme.isNotBlank()) { "Fonema vacío en la posición $index." }
            require(span.startMs.isFinite() && span.durationMs.isFinite()) { "Tiempo no finito en el fonema $index." }
            require(span.startMs >= 0.0 && span.durationMs > 0.0) { "Duración o inicio inválido en el fonema $index." }
            require(span.midiNote == null || span.midiNote in 0..127) { "Nota MIDI fuera de 0–127 en el fonema $index." }
            require(phonemeIds.containsKey(span.phoneme)) { "La vozbank no contiene el fonema «${span.phoneme}»." }
            require(span.startMs + 1e-6 >= previousEndMs) { "Los fonemas se solapan en la posición $index." }
            val gapMs = span.startMs - previousEndMs
            if (gapMs > 1e-6) segments += Segment("SP", gapMs, null)
            segments += Segment(span.phoneme, span.durationMs, span.midiNote)
            previousEndMs = span.startMs + span.durationMs
        }
        segments += Segment("SP", tailFrames * frameMs, null)

        val durations = cumulativeDurationsToFrames(segments.map { it.durationMs }, frameMs)
        val totalFrames = durations.sum()
        require(totalFrames in 1..MAX_TOTAL_FRAMES) { "La frase excede el límite de duración procesable." }

        val tokens = LongArray(segments.size) { index ->
            phonemeIds[segments[index].phoneme]!!.toLong()
        }
        val f0 = FloatArray(totalFrames)
        var frameOffset = 0
        segments.forEachIndexed { index, segment ->
            val frameCount = durations[index]
            val midi = segment.midiNote
            if (midi != null && segment.phoneme != "SP") {
                val frequency = (440.0 * 2.0.pow((midi - 69) / 12.0)).toFloat()
                for (frame in frameOffset until frameOffset + frameCount) f0[frame] = frequency
            }
            frameOffset += frameCount
        }
        check(frameOffset == totalFrames && tokens.size == durations.size)
        return InputBatch(tokens, durations.map(Int::toLong).toLongArray(), f0, frameMs)
    }

    /**
     * Replica el redondeo acumulado de DiffSingerUtils.DurationsMsToFrames.
     * Math.rint usa empate al entero par, igual que MidpointRounding.ToEven.
     */
    private fun cumulativeDurationsToFrames(durationsMs: List<Double>, frameMs: Double): IntArray {
        var accumulatedMs = 0.0
        var previousFrame = 0
        return IntArray(durationsMs.size) { index ->
            val duration = durationsMs[index]
            require(duration.isFinite() && duration >= 0.0) { "Duración de segmento inválida." }
            accumulatedMs += duration
            val roundedFrame = Math.rint(accumulatedMs / frameMs + 0.5)
            require(
                roundedFrame.isFinite() && roundedFrame in 0.0..MAX_TOTAL_FRAMES.toDouble()
            ) { "La frase excede el límite de $MAX_TOTAL_FRAMES frames." }
            val frame = roundedFrame.toInt()
            val count = frame - previousFrame
            require(count >= 0) { "El redondeo produjo una duración negativa." }
            previousFrame = frame
            count
        }
    }

    const val MAX_PHONEMES = 100_000
    const val MAX_TOTAL_FRAMES = 300_000
}
