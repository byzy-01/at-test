package com.example.autotoolkit.clicker

import android.os.Handler
import android.os.Looper
import com.example.autotoolkit.service.AutoClickService

class ClickerEngine {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private val points = mutableListOf<ClickPoint>()
    private val counters = mutableMapOf<Int, Int>()

    fun getPoints(): MutableList<ClickPoint> = points

    fun addPoint(point: ClickPoint) {
        points.add(point)
    }

    fun removePoint(id: Int) {
        points.removeAll { it.id == id }
        counters.remove(id)
    }

    fun start() {
        if (running) return
        if (points.none { it.enabled }) return
        running = true
        counters.clear()
        points.filter { it.enabled }.forEach { startPointLoop(it) }
    }

    private fun startPointLoop(point: ClickPoint) {
        val runnable = object : Runnable {
            override fun run() {
                if (!running) return
                val executed = counters[point.id] ?: 0
                if (point.repeatCount > 0 && executed >= point.repeatCount) return

                val service = AutoClickService.instance
                if (service == null) {
                    running = false
                    return
                }

                val x = Randomizer.jitterCoordinate(point.x, point.randomOffsetX)
                val y = Randomizer.jitterCoordinate(point.y, point.randomOffsetY)
                val dur = Randomizer.jitterDuration(point.pressDuration, point.randomDurationExtra)

                service.performClick(x, y, dur)
                counters[point.id] = executed + 1

                val delay = Randomizer.jitterDelay(point.delayBeforeClick, point.randomDelayExtra)
                handler.postDelayed(this, delay)
            }
        }
        val firstDelay = Randomizer.jitterDelay(point.delayBeforeClick, point.randomDelayExtra)
        handler.postDelayed(runnable, firstDelay)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    fun isRunning() = running

    fun resetCounters() {
        counters.clear()
    }
}
