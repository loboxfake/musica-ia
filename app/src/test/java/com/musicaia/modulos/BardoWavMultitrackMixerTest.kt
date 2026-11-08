package com.musicaia.modulos

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class BardoWavMultitrackMixerTest {
    private fun createWav(file: File, rate: Int, samples: ShortArray) {
        val size=samples.size*2
        val bb=ByteBuffer.allocate(44+size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36+size)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII)).put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate*2).putShort(2).putShort(16)
        bb.put("data".toByteArray(Charsets.US_ASCII)).putInt(size); samples.forEach { sample -> bb.putShort(sample) }
        file.writeBytes(bb.array())
    }

    @Test fun createsVerifiedStereoOutputAndKeepsOriginals() {
        val dir=createTempDir(prefix="bardo-mix-"); val a=File(dir,"a.wav"); val b=File(dir,"b.wav"); val out=File(dir,"out.wav")
        createWav(a,16000,shortArrayOf(10000,10000,10000,10000)); createWav(b,16000,shortArrayOf(-10000,-10000,-10000,-10000))
        val ah=a.readBytes().contentHashCode(); val bh=b.readBytes().contentHashCode()
        val result=BardoWavMultitrackMixer.mix(listOf(BardoWavMultitrackMixer.Track(a,pan=-1.0),BardoWavMultitrackMixer.Track(b,pan=1.0)),out)
        assertTrue(out.length()>44); assertEquals(16000,result.sampleRate); assertEquals(4L,result.frames); assertEquals(2,result.trackCount)
        assertEquals(ah,a.readBytes().contentHashCode()); assertEquals(bh,b.readBytes().contentHashCode())
        val bytes=out.readBytes(); assertEquals("RIFF",String(bytes,0,4,Charsets.US_ASCII)); assertEquals("WAVE",String(bytes,8,4,Charsets.US_ASCII))
        val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2, pcm.getShort(22).toInt())
        assertTrue("La pista panoramizada a la izquierda debe llegar al canal izquierdo.", pcm.getShort(44) > 0)
        assertTrue("La pista panoramizada a la derecha debe llegar al canal derecho.", pcm.getShort(46) < 0)
        assertTrue(result.peakAfterDbfs<=-0.9)
    }

    @Test fun rejectsDifferentRatesAndInvalidPan() {
        val dir=createTempDir(prefix="bardo-mix-invalid-"); val a=File(dir,"a.wav"); val b=File(dir,"b.wav")
        createWav(a,16000,shortArrayOf(1000,1000)); createWav(b,44100,shortArrayOf(1000,1000))
        assertThrows(IllegalArgumentException::class.java) { BardoWavMultitrackMixer.mix(listOf(BardoWavMultitrackMixer.Track(a),BardoWavMultitrackMixer.Track(b)),File(dir,"rate.wav")) }
        createWav(b,16000,shortArrayOf(1000,1000))
        assertThrows(IllegalArgumentException::class.java) { BardoWavMultitrackMixer.mix(listOf(BardoWavMultitrackMixer.Track(a,pan=-2.0),BardoWavMultitrackMixer.Track(b)),File(dir,"pan.wav")) }
    }

    @Test fun rejectsCorruptOrUnsupportedWavHeadersWithoutPublishingOutput() {
        val dir=createTempDir(prefix="bardo-mix-corrupt-")
        val good=File(dir,"good.wav")
        val bad=File(dir,"bad.wav")
        val output=File(dir,"output.wav")
        createWav(good,16000,shortArrayOf(1000,1000))
        bad.writeBytes(ByteArray(64) { 0x41.toByte() })
        assertThrows(IllegalArgumentException::class.java) {
            BardoWavMultitrackMixer.mix(
                listOf(BardoWavMultitrackMixer.Track(good),BardoWavMultitrackMixer.Track(bad)),
                output
            )
        }
        assertTrue(!output.exists())
        assertTrue(!File(dir,"output.wav.tmp").exists())
    }

    @Test fun rejectsSingleTrackAndSilence() {
        val dir=createTempDir(prefix="bardo-mix-silent-"); val a=File(dir,"a.wav"); val b=File(dir,"b.wav")
        createWav(a,16000,shortArrayOf(0,0,0,0)); createWav(b,16000,shortArrayOf(0,0,0,0))
        assertThrows(IllegalArgumentException::class.java) { BardoWavMultitrackMixer.mix(listOf(BardoWavMultitrackMixer.Track(a)),File(dir,"one.wav")) }
        assertThrows(IllegalArgumentException::class.java) { BardoWavMultitrackMixer.mix(listOf(BardoWavMultitrackMixer.Track(a),BardoWavMultitrackMixer.Track(b)),File(dir,"zero.wav")) }
    }
}