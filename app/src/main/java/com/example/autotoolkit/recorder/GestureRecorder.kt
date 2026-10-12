package com.example.autotoolkit.recorder

import android.view.MotionEvent

class GestureRecorder {
    private val actions = mutableListOf<RecordedAction>()
    @Volatile private var recording = false
    private var startTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    fun start() {
        actions.clear()
        startTime = System.currentTimeMillis()
        recording = true
    }

    fun stop(): List<RecordedAction> {
        recording = false
        return actions.toList()
    }

    fun isRecording() = recording

    fun onTouch(event: MotionEvent) {
        if (!recording) return
        val elapsed = System.currentTimeMillis() - startTime
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                downTime = elapsed
            }
            MotionEvent.ACTION_UP -> {
                val upX = event.rawX
                val upY = event.rawY
                val pressDuration = elapsed - downTime
                val dist = kotlin.math.hypot((upX - downX).toDouble(), (upY - downY).toDouble())
                val action = when {
                    dist > 50.0 -> RecordedAction(
                        RecordedAction.ActionType.SWIPE,
                        downX, downY, downTime, pressDuration, upX, upY
                    )
                    pressDuration > 500L -> RecordedAction(
                        RecordedAction.ActionType.LONG_PRESS,
                        downX, downY, downTime, pressDuration
                    )
                    else -> RecordedAction(
                        RecordedAction.ActionType.CLICK,
                        downX, downY, downTime, pressDuration
                    )
                }
                actions.add(action)
            }
        }
    }
}
