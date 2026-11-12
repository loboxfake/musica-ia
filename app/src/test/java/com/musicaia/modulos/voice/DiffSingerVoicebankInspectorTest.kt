package com.musicaia.modulos.voice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiffSingerVoicebankInspectorTest {
    @get:Rule val temp = TemporaryFolder()

    private fun voicebank(): File {
        val root = temp.newFolder("voice")
        File(root, "acoustic.onnx").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "phonemes.txt").writeText("SP\na\ne\ni\no\nu\n")
        File(root, "dsconfig.yaml").writeText(
            "acoustic: acoustic.onnx\nphonemes: phonemes.txt\nvocoder: test-vocoder\nsample_rate: 44100\nhop_size: 512\n"
        )
        val vocoder = File(root, "dsvocoder").apply { mkdirs() }
        File(vocoder, "vocoder.yaml").writeText("model: vocoder.onnx\n")
        File(vocoder, "vocoder.onnx").writeBytes(byteArrayOf(4, 5, 6))
        return root
    }

    @Test fun acceptsCompletePackageForRuntimeProbeOnly() {
        val result = DiffSingerVoicebankInspector.inspect(voicebank())
        assertEquals(DiffSingerVoicebankInspector.Status.READY_FOR_RUNTIME_PROBE, result.status)
        assertTrue(result.readyForRuntimeProbe)
        assertEquals(44100, result.sampleRate)
        assertEquals(512, result.hopSize)
    }

    @Test fun rejectsMissingAcousticGraph() {
        val root = voicebank()
        File(root, "acoustic.onnx").delete()
        assertEquals(DiffSingerVoicebankInspector.Status.MISSING_ACOUSTIC_MODEL,
            DiffSingerVoicebankInspector.inspect(root).status)
    }

    @Test fun rejectsMissingVocoderInsteadOfClaimingVoiceIsReady() {
        val root = voicebank()
        File(root, "dsvocoder/vocoder.yaml").delete()
        assertEquals(DiffSingerVoicebankInspector.Status.MISSING_VOCODER,
            DiffSingerVoicebankInspector.inspect(root).status)
    }

    @Test fun blocksPathTraversalInModelAndPhonemePaths() {
        val root = voicebank()
        File(root, "dsconfig.yaml").writeText(
            "acoustic: ../outside.onnx\nphonemes: phonemes.txt\nvocoder: test-vocoder\n"
        )
        val result = DiffSingerVoicebankInspector.inspect(root)
        assertEquals(DiffSingerVoicebankInspector.Status.MISSING_ACOUSTIC_MODEL, result.status)
        assertFalse(result.readyForRuntimeProbe)
    }

    @Test fun requiresLanguageTableWhenLanguageIdsAreEnabled() {
        val root = voicebank()
        File(root, "dsconfig.yaml").appendText("use_lang_id: true\n")
        assertEquals(DiffSingerVoicebankInspector.Status.MISSING_LANGUAGE_TABLE,
            DiffSingerVoicebankInspector.inspect(root).status)
    }

    @Test fun rejectsUnsafeVocoderPackageNames() {
        val root = voicebank()
        File(root, "dsconfig.yaml").writeText(
            "acoustic: acoustic.onnx\nphonemes: phonemes.txt\nvocoder: ../outside\n"
        )
        assertEquals(DiffSingerVoicebankInspector.Status.INVALID_CONFIG,
            DiffSingerVoicebankInspector.inspect(root).status)
    }
}
