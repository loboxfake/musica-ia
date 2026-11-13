package com.musicaia.modelo

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoResourceCoordinatorTest {
    @Test
    fun serializesTextAndAudioModelWork() {
        val entered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)

        fun recordEntry() {
            val now = active.incrementAndGet()
            maximum.updateAndGet { old -> maxOf(old, now) }
        }

        val first = Thread {
            BardoResourceCoordinator.withTextModel {
                recordEntry()
                entered.countDown()
                check(releaseFirst.await(3, TimeUnit.SECONDS))
                active.decrementAndGet()
            }
        }
        first.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        val second = Thread {
            BardoResourceCoordinator.withAudioModel {
                recordEntry()
                active.decrementAndGet()
                secondFinished.countDown()
            }
        }
        second.start()

        Thread.sleep(120)
        assertEquals("heavy model work must not overlap", 1, maximum.get())
        releaseFirst.countDown()
        assertTrue(secondFinished.await(3, TimeUnit.SECONDS))
        first.join(1000)
        second.join(1000)
        assertEquals(1, maximum.get())
    }

    @Test
    fun restoresThreadResourceStateAfterTheTask() {
        assertNull(BardoResourceCoordinator.currentResourceForCurrentThread())
        BardoResourceCoordinator.withTextModel {
            assertEquals(
                BardoResourceCoordinator.Resource.TEXT_MODEL,
                BardoResourceCoordinator.currentResourceForCurrentThread()
            )
        }
        assertNull(BardoResourceCoordinator.currentResourceForCurrentThread())
    }

    @Test
    fun serializesImageInferenceAgainstTextAndAudioModels() {
        val enteredImage = CountDownLatch(1)
        val releaseImage = CountDownLatch(1)
        val textFinished = CountDownLatch(1)
        val audioFinished = CountDownLatch(1)
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)

        fun recordEntry() {
            val now = active.incrementAndGet()
            maximum.updateAndGet { previous -> maxOf(previous, now) }
        }

        val image = Thread {
            BardoResourceCoordinator.withImageModel {
                recordEntry()
                assertEquals(
                    BardoResourceCoordinator.Resource.IMAGE_MODEL,
                    BardoResourceCoordinator.currentResourceForCurrentThread()
                )
                enteredImage.countDown()
                check(releaseImage.await(3, TimeUnit.SECONDS))
                active.decrementAndGet()
            }
        }
        image.start()
        assertTrue(enteredImage.await(2, TimeUnit.SECONDS))

        val text = Thread {
            BardoResourceCoordinator.withTextModel {
                recordEntry()
                active.decrementAndGet()
                textFinished.countDown()
            }
        }
        val audio = Thread {
            BardoResourceCoordinator.withAudioModel {
                recordEntry()
                active.decrementAndGet()
                audioFinished.countDown()
            }
        }
        text.start()
        audio.start()

        Thread.sleep(150)
        assertEquals("Image inference must hold the exclusive heavy-runtime lease.", 1, maximum.get())
        assertTrue(!textFinished.await(100, TimeUnit.MILLISECONDS))
        assertTrue(!audioFinished.await(100, TimeUnit.MILLISECONDS))

        releaseImage.countDown()
        assertTrue(textFinished.await(3, TimeUnit.SECONDS))
        assertTrue(audioFinished.await(3, TimeUnit.SECONDS))
        image.join(1000)
        text.join(1000)
        audio.join(1000)
        assertEquals(1, maximum.get())
    }

    @Test
    fun speechRecognitionExcludesTextAudioAndImageRuntimes() {
        val enteredSpeech = CountDownLatch(1)
        val releaseSpeech = CountDownLatch(1)
        val textFinished = CountDownLatch(1)
        val audioFinished = CountDownLatch(1)
        val imageFinished = CountDownLatch(1)
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)
        val lockDirectory = java.nio.file.Files.createTempDirectory("bardo-speech-lock").toFile()
        val context = object : android.content.ContextWrapper(null) {
            override fun getNoBackupFilesDir() = lockDirectory
        }

        fun recordEntry() {
            val now = active.incrementAndGet()
            maximum.updateAndGet { old -> maxOf(old, now) }
        }

        val speech = Thread {
            BardoResourceCoordinator.withSpeechModel(context) {
                recordEntry()
                assertEquals(
                    BardoResourceCoordinator.Resource.SPEECH_MODEL,
                    BardoResourceCoordinator.currentResourceForCurrentThread()
                )
                assertTrue(java.io.File(lockDirectory, "bardo-heavy-runtime.lock").isFile)
                enteredSpeech.countDown()
                check(releaseSpeech.await(3, TimeUnit.SECONDS))
                active.decrementAndGet()
            }
        }
        speech.start()
        assertTrue(enteredSpeech.await(2, TimeUnit.SECONDS))

        val text = Thread {
            BardoResourceCoordinator.withTextModel(context) {
                recordEntry()
                active.decrementAndGet()
                textFinished.countDown()
            }
        }
        val audio = Thread {
            BardoResourceCoordinator.withAudioModel(context) {
                recordEntry()
                active.decrementAndGet()
                audioFinished.countDown()
            }
        }
        val image = Thread {
            BardoResourceCoordinator.withImageModel(context) {
                recordEntry()
                active.decrementAndGet()
                imageFinished.countDown()
            }
        }
        text.start()
        audio.start()
        image.start()

        Thread.sleep(150)
        assertEquals("Speech recognition must hold the cross-runtime lease.", 1, maximum.get())
        assertTrue(!textFinished.await(100, TimeUnit.MILLISECONDS))
        assertTrue(!audioFinished.await(100, TimeUnit.MILLISECONDS))
        assertTrue(!imageFinished.await(100, TimeUnit.MILLISECONDS))

        releaseSpeech.countDown()
        assertTrue(textFinished.await(3, TimeUnit.SECONDS))
        assertTrue(audioFinished.await(3, TimeUnit.SECONDS))
        assertTrue(imageFinished.await(3, TimeUnit.SECONDS))
        speech.join(1000)
        text.join(1000)
        audio.join(1000)
        image.join(1000)
        assertEquals(1, maximum.get())
        assertNull(BardoResourceCoordinator.currentResourceForCurrentThread())
    }

    @Test
    fun restoresResourceStateAfterImageInference() {
        assertNull(BardoResourceCoordinator.currentResourceForCurrentThread())
        BardoResourceCoordinator.withImageModel {
            assertEquals(
                BardoResourceCoordinator.Resource.IMAGE_MODEL,
                BardoResourceCoordinator.currentResourceForCurrentThread()
            )
        }
        assertNull(BardoResourceCoordinator.currentResourceForCurrentThread())
    }

    @Test
    fun releasesResourceStateAfterAnInferenceFailure() {
        val failure = runCatching {
            BardoResourceCoordinator.withImageModel {
                assertEquals(
                    BardoResourceCoordinator.Resource.IMAGE_MODEL,
                    BardoResourceCoordinator.currentResourceForCurrentThread()
                )
                error("simulated native inference failure")
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertNull(BardoResourceCoordinator.currentResourceForCurrentThread())
        BardoResourceCoordinator.withTextModel {
            assertEquals(
                BardoResourceCoordinator.Resource.TEXT_MODEL,
                BardoResourceCoordinator.currentResourceForCurrentThread()
            )
        }
        assertNull(BardoResourceCoordinator.currentResourceForCurrentThread())
    }

    @Test
    fun nestedSameRuntimeReusesTheOuterLease() {
        BardoResourceCoordinator.withImageModel {
            BardoResourceCoordinator.withImageModel {
                assertEquals(
                    BardoResourceCoordinator.Resource.IMAGE_MODEL,
                    BardoResourceCoordinator.currentResourceForCurrentThread()
                )
            }
            assertEquals(
                BardoResourceCoordinator.Resource.IMAGE_MODEL,
                BardoResourceCoordinator.currentResourceForCurrentThread()
            )
        }
        assertNull(BardoResourceCoordinator.currentResourceForCurrentThread())
    }

    @Test
    fun cleanupFailureAggregationKeepsTheFirstFailureAndPreservesLaterFailures() {
        val primary = IllegalStateException("text runtime close failed")
        val secondary = UnsatisfiedLinkError("audio runtime close failed")

        val merged = BardoResourceCoordinator.mergeCleanupFailure(primary, secondary)

        assertSame(primary, merged)
        assertEquals(1, primary.suppressed.size)
        assertSame(secondary, primary.suppressed.single())
    }

    @Test
    fun cleanupFailureAggregationDoesNotSelfSuppressThePrimaryFailure() {
        val primary = IllegalStateException("same failure instance")

        val merged = BardoResourceCoordinator.mergeCleanupFailure(primary, primary)

        assertSame(primary, merged)
        assertEquals(0, primary.suppressed.size)
    }

    @Test
    fun cleanupFailureAggregationReturnsTheFirstFailureWhenThereIsNoPrimary() {
        val first = IOException("file lock release failed")
        val second = IOException("file channel close failed")

        val merged = BardoResourceCoordinator.mergeCleanupFailure(
            BardoResourceCoordinator.mergeCleanupFailure(null, first),
            second
        )

        assertSame(first, merged)
        assertEquals(1, first.suppressed.size)
        assertSame(second, first.suppressed.single())
    }

    @Test
    fun fileLockAcquisitionFailureIsNotHiddenAndDoesNotRunTheAction() {
        val invalidParent = java.nio.file.Files.createTempFile("bardo-lock-parent", ".tmp").toFile()
        var actionRan = false
        val context = object : android.content.ContextWrapper(null) {
            override fun getNoBackupFilesDir() = invalidParent
        }

        val failure = runCatching {
            BardoResourceCoordinator.withSpeechModel(context) {
                actionRan = true
            }
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertFalse(actionRan)
        assertNull(BardoResourceCoordinator.currentResourceForCurrentThread())
        invalidParent.delete()
    }

    @Test
    fun rejectsUnsafeNestedSwitchWithoutCorruptingOuterResourceState() {
        BardoResourceCoordinator.withTextModel {
            assertEquals(
                BardoResourceCoordinator.Resource.TEXT_MODEL,
                BardoResourceCoordinator.currentResourceForCurrentThread()
            )
            val error = runCatching {
                BardoResourceCoordinator.withImageModel { error("must not execute") }
            }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertTrue(error?.message.orEmpty().contains("No se puede cambiar del runtime"))
            assertEquals(
                BardoResourceCoordinator.Resource.TEXT_MODEL,
                BardoResourceCoordinator.currentResourceForCurrentThread()
            )
        }
        assertNull(BardoResourceCoordinator.currentResourceForCurrentThread())
    }
}
