package com.cadence.music.data.metadata

object MusicBrainzRateLimiter {
    const val DEFAULT_MIN_DELAY_MS = 1000L
    private val lock = Any()
    private var lastRequestTimeMs = 0L

    @Volatile
    internal var clock: () -> Long = { System.currentTimeMillis() }

    @Volatile
    internal var sleeper: (Long) -> Unit = { delayMs ->
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /**
     * Enforces the MusicBrainz rate limiting policy by blocking until at least
     * [minDelayMs] (default 1000ms) has elapsed since the previous outbound request.
     * Safe across threads and coroutine dispatchers.
     */
    fun acquire(minDelayMs: Long = DEFAULT_MIN_DELAY_MS) {
        synchronized(lock) {
            val now = clock()
            val elapsed = now - lastRequestTimeMs
            if (lastRequestTimeMs > 0L && elapsed < minDelayMs) {
                val toSleep = minDelayMs - elapsed
                sleeper(toSleep)
            }
            lastRequestTimeMs = clock()
        }
    }

    /**
     * Executes [block] after acquiring rate limit clearance.
     */
    fun <T> execute(minDelayMs: Long = DEFAULT_MIN_DELAY_MS, block: () -> T): T {
        acquire(minDelayMs)
        return block()
    }

    /** Resets the rate limiter state (primarily for testing). */
    internal fun reset() {
        synchronized(lock) {
            lastRequestTimeMs = 0L
            clock = { System.currentTimeMillis() }
            sleeper = { delayMs ->
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }
    }
}
