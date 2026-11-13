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
        File(root, "phonemes.txt").writeText("SP\\na\\ne\\ni\\no\\nu\\n")
        File(root, "dsdict-es.yaml").writeText("symbols:\\n  - symbol: a\\n    type: vowel\\nentries:\\n  - grapheme: hola\\n    phonemes: [a]\\n")
        File(root, "dsconfig.yaml").writeText(
            "acoustic: acoustic.onnx\nphonemes: phonemes.txt\nvocoder: test-vocoder\nsample_rate: 44100\nhop_size: 512\n"
        )
        val vocoder = File(root, "dsvocoder").apply { mkdirs() }
        File(vocoder, "vocoder.yaml").writeText("model: vocoder.onnx\n")
        File(vocoder, "vocoder.onnx").writeBytes(byteArrayOf(4, 5, 6))
        return root
    }

    @Test fun acceptsCompleteSpanishPackageForRuntimeProbeOnly() {
        val result = DiffSingerVoicebankInspector.inspect(voicebank())
        assertEquals(DiffSingerVoicebankInspector.Status.READY_FOR_RUNTIME_PROBE, result.status)
        assertTrue(result.readyForRuntimeProbe)
        assertEquals(44100, result.sampleRate)
        assertEquals(512, result.hopSize)
        assertTrue(result.phonemizerDictionary?.name == "dsdict-es.yaml")
        assertEquals("vocoder.onnx", result.vocoderModel?.name)
        assertEquals(mapOf("SP" to 0, "a" to 1, "e" to 2, "i" to 3, "o" to 4, "u" to 5), result.phonemeIds)
    }

    @Test fun rejectsMissingAcousticGraph() {
        val root = voicebank()
        File(root, "acoustic.onnx").delete()
        assertEquals(DiffSingerVoicebankInspector.Status.MISSING_ACOUSTIC_MODEL,
            DiffSingerVoicebankInspector.inspect(root).status)
    }

    @Test fun rejectsInventoryWithoutSilenceTokenBeforeRuntimeProbe() {
        val root = voicebank()
        File(root, "phonemes.txt").writeText("a\ne\ni\no\nu\n")
        val result = DiffSingerVoicebankInspector.inspect(root)
        assertEquals(DiffSingerVoicebankInspector.Status.INVALID_PHONEMES, result.status)
        assertFalse(result.readyForRuntimeProbe)
        assertTrue(result.errors.any { it.contains("SP") })
    }

    @Test fun rejectsMalformedSpanishDictionaryBeforeRuntimeProbe() {
        val root = voicebank()
        File(root, "dsdict-es.yaml").writeText("symbols: []\n")
        val result = DiffSingerVoicebankInspector.inspect(root)
        assertEquals(DiffSingerVoicebankInspector.Status.INVALID_PHONEMIZER_DICTIONARY, result.status)
        assertFalse(result.readyForRuntimeProbe)
    }

    @Test fun rejectsMissingSpanishDictionary() {
        val root = voicebank()
        File(root, "dsdict-es.yaml").delete()
        assertEquals(DiffSingerVoicebankInspector.Status.MISSING_PHONEMIZER_DICTIONARY,
            DiffSingerVoicebankInspector.inspect(root).status)
    }

    @Test fun rejectsMissingVocoderConfigAndModel() {
        val root = voicebank()
        File(root, "dsvocoder/vocoder.yaml").delete()
        assertEquals(DiffSingerVoicebankInspector.Status.MISSING_VOCODER,
            DiffSingerVoicebankInspector.inspect(root).status)
        File(root, "dsvocoder/vocoder.yaml").writeText("model: absent.onnx\n")
        assertEquals(DiffSingerVoicebankInspector.Status.MISSING_VOCODER_MODEL,
            DiffSingerVoicebankInspector.inspect(root).status)
    }

    @Test fun rejectsIncompatibleVocoderParameters() {
        val root = voicebank()
        File(root, "dsvocoder/vocoder.yaml").writeText(
            "model: vocoder.onnx\nsample_rate: 48000\n"
        )
        assertEquals(DiffSingerVoicebankInspector.Status.INCOMPATIBLE_VOCODER,
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

    @Test fun rejectsMissingOptionalAuxiliaryModelWhenDeclared() {
        val root = voicebank()
        File(root, "dsconfig.yaml").appendText("dur: duration.onnx\n")
        assertEquals(DiffSingerVoicebankInspector.Status.MISSING_AUXILIARY_MODEL,
            DiffSingerVoicebankInspector.inspect(root).status)
    }


    @Test fun rejectsDuplicateScalarKeysInVoicebankConfig() {
        val root = voicebank()
        File(root, "dsconfig.yaml").appendText("acoustic: alternate.onnx\n")
        val result = DiffSingerVoicebankInspector.inspect(root)
        assertEquals(DiffSingerVoicebankInspector.Status.INVALID_CONFIG, result.status)
        assertTrue(result.errors.any { it.contains("Clave duplicada 'acoustic'") })
    }

    @Test fun rejectsDuplicateScalarKeysInVocoderConfig() {
        val root = voicebank()
        File(root, "dsvocoder/vocoder.yaml").writeText(
            "model: vocoder.onnx\nmodel: alternate.onnx\n"
        )
        val result = DiffSingerVoicebankInspector.inspect(root)
        assertEquals(DiffSingerVoicebankInspector.Status.INVALID_CONFIG, result.status)
        assertTrue(result.errors.any { it.contains("Clave duplicada 'model'") })
    }

    @Test fun preservesHashInsideQuotedModelPath() {
        val root = voicebank()
        File(root, "acoustic#prod.onnx").writeBytes(byteArrayOf(7, 8, 9))
        File(root, "dsconfig.yaml").writeText(
            "acoustic: \"acoustic#prod.onnx\"\nvocoder: test-vocoder\n"
        )
        val result = DiffSingerVoicebankInspector.inspect(root)
        assertEquals(DiffSingerVoicebankInspector.Status.READY_FOR_RUNTIME_PROBE, result.status)
        assertEquals("acoustic#prod.onnx", result.acousticModel?.name)
    }
}
