package com.musicaia.importacion

import org.junit.Assert.assertEquals
import org.junit.Test

class BardoMimeTypeResolverTest {
    @Test
    fun usesKnownFileExtensionBeforeMismatchedMime() {
        assertEquals("mp4", BardoMimeTypeResolver.resolveExtension("clip.mp4", "audio/mpeg"))
    }

    @Test
    fun detectsVideoWhenProviderSuppliesNoFilenameExtension() {
        assertEquals("mp4", BardoMimeTypeResolver.resolveExtension("recording", "video/mp4"))
        assertEquals("mkv", BardoMimeTypeResolver.resolveExtension("recording", "video/x-matroska"))
    }

    @Test
    fun detectsDocumentsWithoutExtensionsAndIgnoresMimeParameters() {
        assertEquals("docx", BardoMimeTypeResolver.resolveExtension(
            "document", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        ))
        assertEquals("txt", BardoMimeTypeResolver.resolveExtension("note", "text/plain; charset=utf-8"))
    }

    @Test
    fun keepsSvgOnTheTextPathInsteadOfRasterOcr() {
        assertEquals("svg", BardoMimeTypeResolver.resolveExtension("vector", "image/svg+xml"))
    }

    @Test
    fun returnsEmptyForUnsupportedGenericBinaryTypes() {
        assertEquals("", BardoMimeTypeResolver.resolveExtension("opaque.data", "application/octet-stream"))
        assertEquals("", BardoMimeTypeResolver.resolveExtension("opaque", "application/x-unknown"))
    }
}