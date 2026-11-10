package com.musicaia.investigacion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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
            buildString { append("github"); append("_pat_"); append("A".repeat(30)) },
            "Xiaomi 11T Pro Snapdragon 888",
            "RAM libre 2.4 GB batería 72%",
            "battery 82",
            "RAM libre 512",
            "CPU at 74",
            "my_audio.wav",
            "1234567890123456"
        ).forEach { query ->
            assertThrows(IllegalArgumentException::class.java) {
                BardoPublicWebSearchPolicy.validateQuery(query)
            }
        }
    }

    @Test
    fun exactQueryApprovalIsBoundToOneQueryAndShortExpiry() {
        val approved = "Android Kotlin coroutines official documentation"
        val hash = BardoPublicWebSearchPolicy.querySha256(approved)
        val now = 1_000_000L
        val expiry = now + BardoPublicWebSearchPolicy.AUTHORIZATION_TTL_MILLIS

        assertTrue(
            BardoPublicWebSearchPolicy.authorizationMatches(
                hash, expiry, approved, now
            )
        )
        assertFalse(
            BardoPublicWebSearchPolicy.authorizationMatches(
                hash, expiry, "Android Kotlin security documentation", now
            )
        )
        assertFalse(
            BardoPublicWebSearchPolicy.authorizationMatches(
                hash, now, approved, now
            )
        )
        assertFalse(
            BardoPublicWebSearchPolicy.authorizationMatches(
                hash, now + BardoPublicWebSearchPolicy.AUTHORIZATION_TTL_MILLIS + 1L,
                approved, now
            )
        )
        assertFalse(
            BardoPublicWebSearchPolicy.authorizationMatches(
                null, expiry, approved, now
            )
        )
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
