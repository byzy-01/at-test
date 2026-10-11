package com.example.autotoolkit.clicker

import android.os.Handler
import android.os.Looper
import com.example.autotoolkit.service.AutoClickService

class ClickerEngine {

    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var isRunning = false
    private val points = mutableListOf<ClickPoint>()

    fun addPoint(point: ClickPoint) {
        points.add(point)
    }

    fun removePoint(id: Int) {
        points.removeAll { it.id == id }
    }

    fun getPoints(): List<ClickPoint> = points.toList()

    fun updatePoint(updated: ClickPoint) {
        val index = points.indexOfFirst { it.id == updated.id }
        if (index >= 0) points[index] = updated
    }

    fun start() {
        if (isRunning) return
        if (points.none { it.enabled }) return
        isRunning = true
        executeNext(0, 0)
    }

    @Synchronized
    fun stop() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
    }

    fun isRunning() = isRunning

    private fun executeNext(index: Int, currentCycle: Int) {
        if (!isRunning) return

        if (index >= points.size) {
            if (isRunning) executeNext(0, currentCycle + 1)
            return
        }

        val point = points[index]
        if (!point.enabled) {
            executeNext(index + 1, currentCycle)
            return
        }

        if (point.repeatCount > 0 && currentCycle >= point.repeatCount) {
            executeNext(index + 1, currentCycle)
            return
        }

        val service = AutoClickService.instance
        if (service == null) {
            stop()
            return
        }

        val jitterX = Randomizer.jitterCoordinate(point.x, point.randomOffsetX)
        val jitterY = Randomizer.jitterCoordinate(point.y, point.randomOffsetY)
        val delay = Randomizer.jitterDelay(point.delayBeforeClick, point.randomDelayExtra)
        val duration = Randomizer.jitterDuration(point.pressDuration, point.randomDurationExtra)

        handler.postDelayed({
            if (!isRunning) return@postDelayed

            val success = service.performClick(jitterX, jitterY, duration)
            if (!success) {
                stop()
                return@postDelayed
            }

            handler.postDelayed({
                executeNext(index + 1, currentCycle)
            }, 50L)
        }, delay)
    }
}
