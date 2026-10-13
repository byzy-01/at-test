package com.example.autotoolkit.clicker

import android.os.Handler
import android.os.Looper
import com.example.autotoolkit.service.AutoClickService

class ClickerEngine {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private val points = mutableListOf<ClickPoint>()
    private val counters = mutableMapOf<Int, Int>()
    private val nextFireTime = mutableMapOf<Int, Long>()

    fun getPoints(): MutableList<ClickPoint> = points

    fun addPoint(point: ClickPoint) { points.add(point) }

    fun removePoint(id: Int) {
        points.removeAll { it.id == id }
        counters.remove(id)
        nextFireTime.remove(id)
    }

    @Synchronized
    fun start() {
        if (running) return
        if (points.none { it.enabled }) return
        running = true
        counters.clear()
        nextFireTime.clear()
        val now = System.currentTimeMillis()
        points.filter { it.enabled }.forEach { nextFireTime[it.id] = now }
        scheduleNext()
    }

    @Synchronized
    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    fun isRunning() = running

    fun resetCounters() {
        counters.clear()
        val now = System.currentTimeMillis()
        points.filter { it.enabled }.forEach { nextFireTime[it.id] = now }
    }

    private fun scheduleNext() {
        if (!running) return
        val now = System.currentTimeMillis()

        val active = points.filter { it.enabled && nextFireTime.containsKey(it.id) }
        if (active.isEmpty()) {
            running = false
            return
        }

        val next = active.minByOrNull { nextFireTime[it.id] ?: Long.MAX_VALUE } ?: return
        val fireAt = nextFireTime[next.id] ?: now
        val waitMs = (fireAt - now).coerceAtLeast(0L)

        handler.postDelayed({
            if (!running) return@postDelayed
            // 重新确认还是这个点位
            val stillNext = points
                .filter { it.enabled && nextFireTime.containsKey(it.id) }
                .minByOrNull { nextFireTime[it.id] ?: Long.MAX_VALUE }
            if (stillNext?.id != next.id) {
                scheduleNext()
                return@postDelayed
            }
            fire(next)
        }, waitMs)
    }

    private fun fire(point: ClickPoint) {
        if (!running) return

        val executed = counters[point.id] ?: 0
        if (point.repeatCount > 0 && executed >= point.repeatCount) {
            nextFireTime.remove(point.id)
            scheduleNext()
            return
        }

        val service = AutoClickService.instance
        if (service == null) {
            running = false
            return
        }

        val x = Randomizer.jitterCoordinate(point.x, point.randomOffsetX)
        val y = Randomizer.jitterCoordinate(point.y, point.randomOffsetY)
        val dur = Randomizer.jitterDuration(point.pressDuration, point.randomDurationExtra)

        counters[point.id] = executed + 1
        val nextDelay = Randomizer.jitterDelay(point.delayBeforeClick, point.randomDelayExtra)
        nextFireTime[point.id] = System.currentTimeMillis() + nextDelay

        val ok = service.performClickWithCallback(x, y, dur) {
            handler.post { scheduleNext() }
        }
        // 如果派发失败，50ms 后重试调度，避免死锁
        if (!ok) {
            handler.postDelayed({ scheduleNext() }, 50L)
        }
    }
}
