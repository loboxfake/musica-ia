package com.musicaia.importacion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoOcrLanguageSupportTest {
    @Test
    fun bundledLatinOcrCoversSpanishAndEnglishLocales() {
        assertTrue(BardoOcrLanguageSupport.supportsMinimumLanguage("es"))
        assertTrue(BardoOcrLanguageSupport.supportsMinimumLanguage("es-ES"))
        assertTrue(BardoOcrLanguageSupport.supportsMinimumLanguage("en"))
        assertTrue(BardoOcrLanguageSupport.supportsMinimumLanguage("en-US"))
    }

    @Test
    fun minimumLanguageContractDoesNotClaimEveryLanguage() {
        assertFalse(BardoOcrLanguageSupport.supportsMinimumLanguage("fr"))
        assertFalse(BardoOcrLanguageSupport.supportsMinimumLanguage(""))
    }
}
