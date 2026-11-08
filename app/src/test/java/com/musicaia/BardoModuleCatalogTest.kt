package com.musicaia

import com.musicaia.modelo.BardoModuleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BardoModuleCatalogTest {
    @Test
    fun selectsOnlyInstrumentalForInstrumentalIntent() {
        assertEquals(
            listOf("instrumental"),
            BardoModuleCatalog.selectMusicModuleIds(
                instrumentalOnly = true,
                manualLyrics = false,
                gemmaReady = false,
                audioReady = true
            )
        )
    }

    @Test
    fun selectsLyricsThenInstrumentalWhenGemmaIsReady() {
        assertEquals(
            listOf("lyrics", "instrumental"),
            BardoModuleCatalog.selectMusicModuleIds(
                instrumentalOnly = false,
                manualLyrics = false,
                gemmaReady = true,
                audioReady = true
            )
        )
    }

    @Test
    fun allowsManualLyricsWithoutLoadingGemma() {
        assertEquals(
            listOf("lyrics", "instrumental"),
            BardoModuleCatalog.selectMusicModuleIds(
                instrumentalOnly = false,
                manualLyrics = true,
                gemmaReady = false,
                audioReady = true
            )
        )
    }

    @Test
    fun refusesToPlanWhenAudioEngineIsMissing() {
        assertThrows(IllegalArgumentException::class.java) {
            BardoModuleCatalog.selectMusicModuleIds(
                instrumentalOnly = true,
                manualLyrics = false,
                gemmaReady = false,
                audioReady = false
            )
        }
    }

    @Test
    fun refusesNewLyricsWithoutGemma() {
        assertThrows(IllegalArgumentException::class.java) {
            BardoModuleCatalog.selectMusicModuleIds(
                instrumentalOnly = false,
                manualLyrics = false,
                gemmaReady = false,
                audioReady = true
            )
        }
    }

    @Test
    fun gemmaManifestContainsPinnedShaAndAttribution() {
        val gemma = BardoModuleCatalog.descriptors().first { it.id == "lyrics" }
        assertEquals("Apache-2.0", gemma.license)
        assertEquals("181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c1", gemma.modelSha256)
        assertEquals(true, gemma.attribution.contains("Google"))
    }

    @Test
    fun userVoiceMixIsCataloguedSeparatelyFromGeneratedSinging() {
        val modules = BardoModuleCatalog.descriptors().associateBy { it.id }
        assertEquals(true, modules.getValue("voice_mix_user").implemented)
        assertEquals("voice_mix_user", modules.getValue("voice_mix_user").capabilityId)
        assertEquals(false, modules.getValue("voice_synthesis").implemented)
        assertEquals(false, modules.getValue("stem_separation").implemented)
    }

    @Test
    fun voiceSynthesisIsExplicitlyNotImplementedAndNeverSelected() {
        val voice = BardoModuleCatalog.descriptors().first { it.id == "voice_synthesis" }
        assertEquals(false, voice.implemented)
        assertEquals("voice_synthesis", voice.capabilityId)
    }
}
