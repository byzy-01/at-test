package com.example.autotoolkit.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class AutoClickService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AutoClickService? = null
            private set
        const val ACTION_STOP_ALL = "com.example.autotoolkit.STOP_ALL"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val intent = Intent(ACTION_STOP_ALL)
                intent.setPackage(packageName)
                sendBroadcast(intent)
            }
            return true
        }
        return super.onKeyEvent(event)
    }

    fun performClick(x: Float, y: Float, durationMs: Long): Boolean {
        return performClickWithCallback(x, y, durationMs) {}
    }

    fun performClickWithCallback(x: Float, y: Float, durationMs: Long, onDone: () -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val safeDuration = durationMs.coerceAtLeast(10L)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, safeDuration))
            .build()
        return try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { onDone() }
                override fun onCancelled(gestureDescription: GestureDescription?) { onDone() }
            }, null)
        } catch (e: Exception) {
            false
        }
    }

    fun performSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val safeDuration = durationMs.coerceAtLeast(50L)
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, safeDuration))
            .build()
        return try {
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            false
        }
    }
}
