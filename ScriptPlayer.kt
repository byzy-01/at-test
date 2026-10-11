package com.example.autotoolkit.recorder

import android.os.Handler
import android.os.Looper
import com.example.autotoolkit.service.AutoClickService

class ScriptPlayer {

    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var isPlaying = false

    fun play(actions: List<RecordedAction>, loopCount: Int = 1) {
        if (actions.isEmpty()) return
        if (isPlaying) return
        isPlaying = true
        playFromIndex(actions, 0, 0, loopCount)
    }

    private fun playFromIndex(
        actions: List<RecordedAction>,
        index: Int,
        currentLoop: Int,
        maxLoop: Int
    ) {
        if (!isPlaying) return

        if (index >= actions.size) {
            if (maxLoop == 0 || currentLoop < maxLoop - 1) {
                playFromIndex(actions, 0, currentLoop + 1, maxLoop)
            } else {
                isPlaying = false
            }
            return
        }

        val service = AutoClickService.instance
        if (service == null) {
            isPlaying = false
            return
        }

        val action = actions[index]
        val prevEndTime = if (index == 0) 0L
        else actions[index - 1].timestamp + actions[index - 1].duration
        val waitTime = (action.timestamp - prevEndTime).coerceAtLeast(0L)

        handler.postDelayed({
            if (!isPlaying) return@postDelayed

            val success = when (action.type) {
                RecordedAction.ActionType.CLICK,
                RecordedAction.ActionType.LONG_PRESS -> {
                    service.performClick(action.x, action.y, action.duration)
                }
                RecordedAction.ActionType.SWIPE -> {
                    service.performSwipe(
                        action.x, action.y, action.endX, action.endY, action.duration
                    )
                }
            }

            if (!success) {
                isPlaying = false
                return@postDelayed
            }

            handler.postDelayed({
                playFromIndex(actions, index + 1, currentLoop, maxLoop)
            }, 50L)
        }, waitTime)
    }

    fun stop() {
        isPlaying = false
        handler.removeCallbacksAndMessages(null)
    }

    fun isPlaying() = isPlaying
}
