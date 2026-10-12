package com.example.autotoolkit.clicker

import java.io.Serializable

data class ClickPoint(
    val id: Int,
    var x: Float = 0f,
    var y: Float = 0f,
    var delayBeforeClick: Long = 200L,
    var pressDuration: Long = 50L,
    var repeatCount: Int = 1,
    var enabled: Boolean = true,
    var randomOffsetX: Int = 5,
    var randomOffsetY: Int = 5,
    var randomDelayExtra: Long = 100L,
    var randomDurationExtra: Long = 20L
) : Serializable
