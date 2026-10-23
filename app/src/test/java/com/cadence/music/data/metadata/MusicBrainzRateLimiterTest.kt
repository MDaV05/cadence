package com.cadence.music.data.metadata

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class MusicBrainzRateLimiterTest {

    @Before
    fun setUp() {
        MusicBrainzRateLimiter.reset()
    }

    @After
    fun tearDown() {
        MusicBrainzRateLimiter.reset()
    }

    @Test
    fun `first call proceeds immediately without sleeping`() {
        var sleepCalls = 0
        var simulatedTime = 1_000_000L

        MusicBrainzRateLimiter.clock = { simulatedTime }
        MusicBrainzRateLimiter.sleeper = { _ -> sleepCalls++ }

        MusicBrainzRateLimiter.acquire()
        assertEquals(0, sleepCalls)
    }

    @Test
    fun `subsequent call before minDelay sleeps the remaining time`() {
        var simulatedTime = 1_000_000L
        val sleepDurations = mutableListOf<Long>()

        MusicBrainzRateLimiter.clock = { simulatedTime }
        MusicBrainzRateLimiter.sleeper = { duration ->
            sleepDurations.add(duration)
            simulatedTime += duration
        }

        // First call at t=1,000,000
        MusicBrainzRateLimiter.acquire(1000L)
        assertEquals(emptyList<Long>(), sleepDurations)

        // Advance simulated time by only 300ms
        simulatedTime += 300L

        // Second call at t=1,000,300 (only 300ms elapsed since first call)
        MusicBrainzRateLimiter.acquire(1000L)
        assertEquals(listOf(700L), sleepDurations)
    }

    @Test
    fun `subsequent call after minDelay has elapsed does not sleep`() {
        var simulatedTime = 1_000_000L
        val sleepDurations = mutableListOf<Long>()

        MusicBrainzRateLimiter.clock = { simulatedTime }
        MusicBrainzRateLimiter.sleeper = { duration ->
            sleepDurations.add(duration)
            simulatedTime += duration
        }

        // First call at t=1,000,000
        MusicBrainzRateLimiter.acquire(1000L)

        // Advance simulated time by 1500ms (> 1000ms)
        simulatedTime += 1500L

        // Second call
        MusicBrainzRateLimiter.acquire(1000L)
        assertEquals(emptyList<Long>(), sleepDurations)
    }

    @Test
    fun `execute runs block and returns value`() {
        val result = MusicBrainzRateLimiter.execute(1000L) {
            "test-result"
        }
        assertEquals("test-result", result)
    }

    @Test
    fun `thread safety serializes multiple concurrent acquires`() {
        val simulatedTime = AtomicLong(1_000_000L)
        val totalSlept = AtomicLong(0L)
        val completedCount = AtomicInteger(0)

        MusicBrainzRateLimiter.clock = { simulatedTime.get() }
        MusicBrainzRateLimiter.sleeper = { duration ->
            totalSlept.addAndGet(duration)
            simulatedTime.addAndGet(duration)
        }

        val threadCount = 4
        val latch = CountDownLatch(threadCount)
        val threads = (1..threadCount).map {
            Thread {
                MusicBrainzRateLimiter.acquire(1000L)
                completedCount.incrementAndGet()
                latch.countDown()
            }
        }

        threads.forEach { it.start() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        assertEquals(threadCount, completedCount.get())
        // 4 calls at 1000ms delay: first has 0 sleep, next 3 must sleep 1000ms each = 3000ms
        assertEquals(3000L, totalSlept.get())
    }
}
