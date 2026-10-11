package com.example.autotoolkit.recorder

import java.io.Serializable

data class RecordedAction(
    val type: ActionType,
    val x: Float,
    val y: Float,
    val timestamp: Long,
    val duration: Long = 0L,
    val endX: Float = x,
    val endY: Float = y
) : Serializable {
    enum class ActionType { CLICK, LONG_PRESS, SWIPE }
}
