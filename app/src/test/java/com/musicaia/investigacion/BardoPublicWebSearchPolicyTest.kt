package com.musicaia.investigacion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BardoPublicWebSearchPolicyTest {
    @Test
    fun acceptsOnlyShortGenericPublicKeywords() {
        assertEquals(
            "Android Kotlin coroutines official documentation",
            BardoPublicWebSearchPolicy.validateQuery("  Android   Kotlin coroutines official documentation  ")
        )
    }

    @Test
    fun rejectsLocalPathsFilesCredentialsAndPersonalIdentifiers() {
        listOf(
            "/storage/emulated/0/Download/private.wav",
            "private project email name@example.com",
            "github_pat_ABCDEFGHIJKLMNOPQRSTUVWXYZ123456",
            "Xiaomi 11T Pro Snapdragon 888",
            "RAM libre 2.4 GB batería 72%",
            "my_audio.wav",
            "1234567890123456"
        ).forEach { query ->
            assertThrows(IllegalArgumentException::class.java) {
                BardoPublicWebSearchPolicy.validateQuery(query)
            }
        }
    }

    @Test
    fun rejectsCodeUrlsAndOversizedQueries() {
        listOf(
            "https://example.com/private?token=abc",
            "fun main() { println(\"secret\") }",
            "x".repeat(BardoPublicWebSearchPolicy.MAX_QUERY_CHARS + 1)
        ).forEach { query ->
            assertThrows(IllegalArgumentException::class.java) {
                BardoPublicWebSearchPolicy.validateQuery(query)
            }
        }
    }
}
