package com.example.engine.download

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared token-bucket bandwidth limiter supporting global speed limits and instant Slow Mode
 * toggles without interrupting active HTTP streams.
 */
class SpeedLimiter {
    @Volatile
    private var maxBytesPerSec: Long = 0L

    private val mutex = Mutex()
    private var availableTokens: Double = 0.0
    private var lastRefillNanos: Long = System.nanoTime()

    fun setLimitBytesPerSec(limit: Long) {
        maxBytesPerSec = limit.coerceAtLeast(0L)
    }

    fun getLimitBytesPerSec(): Long = maxBytesPerSec

    suspend fun acquire(bytes: Int) {
        val limit = maxBytesPerSec
        if (limit <= 0L || bytes <= 0) return

        while (true) {
            val currentLimit = maxBytesPerSec
            if (currentLimit <= 0L) return

            val waitMs: Long = mutex.withLock {
                val now = System.nanoTime()
                val elapsedSec = (now - lastRefillNanos).coerceAtLeast(0L) / 1_000_000_000.0
                lastRefillNanos = now

                val maxBurst = currentLimit.toDouble().coerceAtLeast(16_384.0)
                availableTokens = (availableTokens + elapsedSec * currentLimit.toDouble())
                    .coerceAtMost(maxBurst)

                if (availableTokens >= bytes) {
                    availableTokens -= bytes
                    0L
                } else {
                    val deficit = bytes - availableTokens
                    availableTokens = 0.0
                    ((deficit / currentLimit.toDouble()) * 1000.0).toLong().coerceIn(5L, 500L)
                }
            }
            if (waitMs <= 0L) return
            delay(waitMs)
        }
    }
}
