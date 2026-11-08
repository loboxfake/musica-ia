package com.musicaia.modulos.litert

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRTLMModuleTest {
    @Test
    fun expectedSha256HasExactly64LowercaseHexCharacters() {
        assertTrue(Regex("^[0-9a-f]{64}$").matches(LiteRTLMModule.EXPECTED_SHA256))
    }

    @Test
    fun officialGemmaSizeAndSha256PassIdentityVerification() {
        assertTrue(
            LiteRTLMModule.verificarIdentidadModelo(
                2_588_147_712L,
                "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
            )
        )
    }

    @Test
    fun rejectsWrongSizeOrMalformedSha256() {
        assertFalse(
            LiteRTLMModule.verificarIdentidadModelo(
                2_588_147_711L,
                LiteRTLMModule.EXPECTED_SHA256
            )
        )
        assertFalse(
            LiteRTLMModule.verificarIdentidadModelo(
                LiteRTLMModule.EXPECTED_SHA256.length.toLong(),
                LiteRTLMModule.EXPECTED_SHA256 + "1"
            )
        )
    }
}
