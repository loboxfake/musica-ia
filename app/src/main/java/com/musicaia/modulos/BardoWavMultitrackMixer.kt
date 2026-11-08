package com.musicaia.modulos

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Mezclador WAV PCM básico, no destructivo y sin funciones DAW profesionales. */
object BardoWavMultitrackMixer {
    const val MAX_TRACK_BYTES = 25L * 1024L * 1024L
    const val MAX_TOTAL_INPUT_BYTES = 100L * 1024L * 1024L
    const val TARGET_PEAK_DBFS = -1.0
    private const val BLOCK_FRAMES = 8192
    data class Track(val file: File, val gainDb: Double = 0.0, val pan: Double = 0.0)
    data class Result(val output: File, val sampleRate: Int, val frames: Long, val trackCount: Int,
        val peakBeforeDbfs: Double, val peakAfterDbfs: Double, val normalizationGainDb: Double)
    private data class Pcm(val source: Track, val bytes: ByteArray, val offset: Int,
        val channels: Int, val rate: Int, val frames: Long)

    fun mix(tracks: List<Track>, output: File): Result {
        require(tracks.size in 2..8) { "La mezcla necesita entre 2 y 8 pistas." }
        require(tracks.map { it.file.canonicalFile }.distinct().size == tracks.size) { "Pista duplicada." }
        require(tracks.all { it.gainDb.isFinite() && it.gainDb in -24.0..6.0 }) { "Ganancia fuera de -24 a +6 dB." }
        require(tracks.all { it.pan.isFinite() && it.pan in -1.0..1.0 }) { "Panorama fuera de -1 a +1." }
        require(tracks.none { it.file.canonicalFile == output.canonicalFile }) { "La salida no puede sobrescribir una pista." }
        var total = 0L
        val pcm = tracks.map { track ->
            require(track.file.isFile && track.file.length() in 44..MAX_TRACK_BYTES) { "WAV ausente o superior a 25 MB: " + track.file.name }
            total += track.file.length()
            require(total <= MAX_TOTAL_INPUT_BYTES) { "Las pistas superan 100 MB." }
            parse(track)
        }
        val rate = pcm.first().rate
        require(pcm.all { it.rate == rate }) { "Todas las pistas deben compartir frecuencia de muestreo; no se remuestrea." }
        val frames = pcm.maxOf { it.frames }
        require(frames in 1..(rate.toLong() * 300L)) { "La sesión supera 5 minutos." }
        val dataSize = frames * 4L
        require(dataSize <= Int.MAX_VALUE - 44L) { "El WAV de salida supera el límite RIFF." }
        var peak = 0.0
        var cursor = 0L
        while (cursor < frames) {
            val count = min(BLOCK_FRAMES.toLong(), frames - cursor).toInt()
            render(pcm, cursor, count).forEach { peak = maxOf(peak, abs(it)) }
            cursor += count
        }
        require(peak > 1e-8) { "La mezcla está en silencio; no se genera un archivo vacío." }
        val target = 10.0.pow(TARGET_PEAK_DBFS / 20.0)
        val normalization = min(target / peak, 10.0.pow(12.0 / 20.0))
        output.parentFile?.let { check(it.exists() || it.mkdirs()) { "No se pudo crear la salida." } }
        val temp = File(output.parentFile ?: File("."), output.name + ".tmp")
        try {
            temp.delete()
            FileOutputStream(temp).use { stream ->
                stream.write(header(rate, 2, dataSize.toInt()))
                cursor = 0L
                while (cursor < frames) {
                    val count = min(BLOCK_FRAMES.toLong(), frames - cursor).toInt()
                    val samples = render(pcm, cursor, count)
                    val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    samples.forEach { sample ->
                        val v = (sample * normalization).coerceIn(-target, target)
                        bytes.putShort((v * 32768.0).toInt().coerceIn(-32768, 32767).toShort())
                    }
                    stream.write(bytes.array(), 0, bytes.position())
                    cursor += count
                }
                stream.fd.sync()
            }
            require(temp.isFile && temp.length() == 44L + dataSize) { "El WAV generado está truncado." }
            val verified = parse(Track(temp))
            require(verified.channels == 2 && verified.rate == rate && verified.frames == frames) {
                "La cabecera/canales/frecuencia del WAV no superan la verificación."
            }
            check(!output.exists() || output.delete()) { "No se pudo sustituir una salida anterior." }
            check(temp.renameTo(output)) { "No se pudo publicar el WAV mezclado." }
            return Result(output, rate, frames, pcm.size, db(peak), db(peak * normalization), db(normalization))
        } finally { temp.delete() }
    }

    private fun render(tracks: List<Pcm>, start: Long, count: Int): DoubleArray {
        val out = DoubleArray(count * 2)
        tracks.forEach { t ->
            val gain = 10.0.pow(t.source.gainDb / 20.0)
            val pan = t.source.pan
            val lpan = if (t.channels == 1) cos((pan + 1.0) * Math.PI / 4.0) else if (pan > 0) cos(pan * Math.PI / 2) else 1.0
            val rpan = if (t.channels == 1) sin((pan + 1.0) * Math.PI / 4.0) else if (pan < 0) cos(-pan * Math.PI / 2) else 1.0
            val countAvailable = (t.frames - start).coerceIn(0L, count.toLong()).toInt()
            val frameBytes = t.channels * 2
            repeat(countAvailable) { i ->
                val p = t.offset + ((start + i) * frameBytes).toInt()
                if (t.channels == 1) {
                    val value = sample(t.bytes, p) / 32768.0 * gain
                    out[i * 2] += value * lpan
                    out[i * 2 + 1] += value * rpan
                } else {
                    out[i * 2] += sample(t.bytes, p) / 32768.0 * gain * lpan
                    out[i * 2 + 1] += sample(t.bytes, p + 2) / 32768.0 * gain * rpan
                }
            }
        }
        return out
    }

    private fun parse(track: Track): Pcm {
        val data = track.file.readBytes()
        require(data.size >= 44 && ascii(data,0,4)=="RIFF" && ascii(data,8,4)=="WAVE") { "WAV RIFF no válido: " + track.file.name }
        var format=-1; var channels=-1; var rate=-1; var bits=-1; var align=-1; var dataPos=-1; var dataBytes=-1; var pos=12
        while (pos + 8 <= data.size) {
            val id=ascii(data,pos,4); val size=uint(data,pos+4); val start=pos+8; val end=start.toLong()+size
            require(end <= data.size.toLong()) { "Chunk WAV excede el archivo." }
            when(id) {
                "fmt " -> {
                    require(size >= 16L) { "Chunk fmt incompleto." }
                    val f=ByteBuffer.wrap(data,start,size.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                    format=f.short.toInt() and 65535; channels=f.short.toInt() and 65535
                    rate=f.int; f.int; align=f.short.toInt() and 65535; bits=f.short.toInt() and 65535
                }
                "data" -> if(dataPos<0) { dataPos=start; dataBytes=size.toInt() }
            }
            val next=end+(size and 1L); require(next>=end && next<=data.size.toLong() && next<=Int.MAX_VALUE) { "Chunk RIFF inválido o relleno truncado." }; pos=next.toInt()
        }
        require(format==1 && bits==16) { "Solo se admite WAV PCM16." }
        require(channels in 1..2 && rate in 8000..96000) { "Canales/frecuencia no compatibles." }
        require(align==channels*2 && dataPos>=0 && dataBytes>0 && dataBytes%align==0) { "Datos PCM incompletos." }
        val frames=(dataBytes/align).toLong()
        require(frames in 1..(rate.toLong()*300L)) { "La pista supera 5 minutos." }
        return Pcm(track,data,dataPos,channels,rate,frames)
    }
    private fun sample(b:ByteArray,p:Int):Int=((b[p].toInt() and 255) or ((b[p+1].toInt() and 255) shl 8)).toShort().toInt()
    private fun ascii(b:ByteArray,p:Int,n:Int)=String(b,p,n,Charsets.US_ASCII)
    private fun uint(b:ByteArray,p:Int):Long=(b[p].toLong() and 255) or ((b[p+1].toLong() and 255) shl 8) or ((b[p+2].toLong() and 255) shl 16) or ((b[p+3].toLong() and 255) shl 24)
    private fun header(rate:Int,channels:Int,size:Int):ByteArray {
        val align=channels*2
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36+size)
            put("WAVE".toByteArray(Charsets.US_ASCII)); put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1.toShort()); putShort(channels.toShort()); putInt(rate); putInt(rate*align)
            putShort(align.toShort()); putShort(16.toShort()); put("data".toByteArray(Charsets.US_ASCII)); putInt(size)
        }.array()
    }
    private fun db(v:Double)=if(v<=1e-12) -120.0 else 20.0*ln(v)/ln(10.0)
}