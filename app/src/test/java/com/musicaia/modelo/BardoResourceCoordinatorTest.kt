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
}
