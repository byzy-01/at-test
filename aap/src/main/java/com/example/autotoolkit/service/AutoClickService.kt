package com.example.autotoolkit.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class AutoClickService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AutoClickService? = null
            private set

        const val ACTION_STOP_ALL = "com.example.autotoolkit.STOP_ALL"
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var lastClickTime = 0L

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
                sendBroadcast(
                    android.content.Intent(ACTION_STOP_ALL)
                        .setPackage(packageName)
                )
                return true
            }
            if (event.action == KeyEvent.ACTION_UP) {
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    @Synchronized
    fun performClick(x: Float, y: Float, durationMs: Long = 50L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val now = System.currentTimeMillis()
        if (now - lastClickTime < 30L) {
            return false
        }
        lastClickTime = now

        val safeDuration = durationMs.coerceAtLeast(10L)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(path, 0, safeDuration)
            )
            .build()

        return try {
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    fun performSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 300L
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val safeDuration = durationMs.coerceAtLeast(50L)
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(path, 0, safeDuration)
            )
            .build()

        return try {
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            false
        }
    }
}
