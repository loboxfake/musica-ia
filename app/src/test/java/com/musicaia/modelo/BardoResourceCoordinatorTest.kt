package com.musicaia.modelo

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
