package com.example.autotoolkit.clicker

import kotlin.random.Random

object Randomizer {

    fun jitterCoordinate(center: Float, range: Int): Float {
        if (range <= 0) return center
        return center + Random.nextInt(-range, range + 1)
    }

    fun jitterDelay(baseMs: Long, extraMs: Long): Long {
        if (extraMs <= 0) return baseMs
        return baseMs + Random.nextLong(0, extraMs + 1)
    }

    fun jitterDuration(baseMs: Long, extraMs: Long): Long {
        if (extraMs <= 0) return baseMs
        val result = baseMs + Random.nextLong(-extraMs, extraMs + 1)
        return result.coerceAtLeast(10L)
    }
}
