package com.example.autotoolkit.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.autotoolkit.clicker.ClickPoint
import com.example.autotoolkit.clicker.ClickerEngine
import java.util.concurrent.atomic.AtomicInteger

class ClickerWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var controlBar: LinearLayout
    private lateinit var controlBarParams: WindowManager.LayoutParams

    private val pointMarkers = mutableMapOf<Int, View>()
    private val pointParams = mutableMapOf<Int, WindowManager.LayoutParams>()

    private val engine = ClickerEngine()
    private val idGenerator = AtomicInteger(0)

    private var markersVisible = true
    private var controlBarLocked = false
    private var settingsPanel: View? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AutoClickService.ACTION_STOP_ALL) {
                engine.stop()
                mainHandler.post {
                    Toast.makeText(
                        this@ClickerWindowService,
                        "已通过音量减键停止",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        registerStopReceiver()
        createControlBar()
        addNewPoint()
    }

    override fun onDestroy() {
        engine.stop()
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
        dismissSettingsPanel()
        pointMarkers.values.forEach {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        if (::controlBar.isInitialized) {
            try { windowManager.removeView(controlBar) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotification() {
        val channelId = "autotoolkit_clicker"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                channelId, "点击器运行中",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("点击器运行中")
            .setContentText("音量减键可紧急停止")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun registerStopReceiver() {
        val filter = IntentFilter(AutoClickService.ACTION_STOP_ALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stopReceiver, filter)
        }
    }

    private fun createControlBar() {
        controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 30f
                setColor(Color.parseColor("#CC333333"))
            }
            setPadding(8, 8, 8, 8)
        }

        val buttons = listOf(
            "▶" to { toggleClicker() },
            "＋" to { addNewPoint() },
            "－" to { removeLastPoint() },
            "👁" to { toggleMarkers() },
            "🔒" to { toggleLock() },
            "✕" to { stopSelf() }
        )

        buttons.forEach { (text, action) ->
            val btn = Button(this).apply {
                this.text = text
                textSize = 18f
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(30, 10, 30, 10)
                setOnClickListener { action() }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 4, 0, 4)
            controlBar.addView(btn, lp)
        }

        controlBarParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 300
        }

        windowManager.addView(controlBar, controlBarParams)
        // 整个控制条都可拖动
        makeControlBarDraggable()
    }

    private fun makeControlBarDraggable() {
        controlBar.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var dragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                if (controlBarLocked) return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = controlBarParams.x
                        initialY = controlBarParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        dragging = false
                        return false // 让子 View（按钮）也能收到事件
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (Math.abs(dx) > 15 || Math.abs(dy) > 15) dragging = true
                        if (dragging) {
                            controlBarParams.x = initialX + dx.toInt()
                            controlBarParams.y = initialY + dy.toInt()
                            try {
                                windowManager.updateViewLayout(controlBar, controlBarParams)
                            } catch (_: Exception) {}
                        }
                        return dragging
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val wasDragging = dragging
                        dragging = false
                        return wasDragging
                    }
                }
                return false
            }
        })
    }

    private fun addNewPoint() {
        val id = idGenerator.incrementAndGet()
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val point = ClickPoint(
            id = id,
            x = w / 2f + ((id % 3) - 1) * 150f,
            y = h / 3f + (id % 3) * 150f
        )
        engine.addPoint(point)
        createMarkerFor(point)
        if (!markersVisible) pointMarkers[id]?.visibility = View.GONE
        Toast.makeText(this, "已添加点位 #$id", Toast.LENGTH_SHORT).show()
    }

    private fun removeLastPoint() {
        val points = engine.getPoints()
        if (points.isEmpty()) {
            Toast.makeText(this, "没有点位可删除", Toast.LENGTH_SHORT).show()
            return
        }
        val last = points.last()
        engine.removePoint(last.id)
        pointMarkers[last.id]?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        pointMarkers.remove(last.id)
        pointParams.remove(last.id)
        Toast.makeText(this, "已删除点位 #${last.id}", Toast.LENGTH_SHORT).show()
    }

    private fun createMarkerFor(point: ClickPoint) {
        val marker = TextView(this).apply {
            text = point.id.toString()
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#DD2196F3"))
                setStroke(3, Color.WHITE)
            }
            val size = (52 * resources.displayMetrics.density).toInt()
            width = size
            height = size
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = point.x.toInt()
            y = point.y.toInt()
        }

        makeMarkerDraggable(marker, params, point)
        windowManager.addView(marker, params)
        pointMarkers[point.id] = marker
        pointParams[point.id] = params
    }

    private fun makeMarkerDraggable(
        marker: View,
        params: WindowManager.LayoutParams,
        point: ClickPoint
    ) {
        marker.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false
            private var longPressTriggered = false
            private val longPressRunnable = Runnable {
                longPressTriggered = true
                showPointSettings(point)
            }

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        longPressTriggered = false
                        mainHandler.postDelayed(longPressRunnable, 600L)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (Math.abs(dx) > 12 || Math.abs(dy) > 12) {
                            isDragging = true
                            mainHandler.removeCallbacks(longPressRunnable)
                        }
                        if (isDragging) {
                            params.x = initialX + dx.toInt()
                            params.y = initialY + dy.toInt()
                            point.x = params.x.toFloat()
                            point.y = params.y.toFloat()
                            try {
                                windowManager.updateViewLayout(marker, params)
                            } catch (_: Exception) {}
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        mainHandler.removeCallbacks(longPressRunnable)
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun toggleMarkers() {
        markersVisible = !markersVisible
        pointMarkers.values.forEach {
            it.visibility = if (markersVisible) View.VISIBLE else View.GONE
        }
        Toast.makeText(this, if (markersVisible) "点位已显示" else "点位已隐藏",
            Toast.LENGTH_SHORT).show()
    }

    private fun toggleLock() {
        controlBarLocked = !controlBarLocked
        Toast.makeText(this, if (controlBarLocked) "控制条已锁定" else "控制条已解锁",
            Toast.LENGTH_SHORT).show()
    }

    private fun toggleClicker() {
        if (engine.isRunning()) {
            engine.stop()
            Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show()
        } else {
            engine.resetCounters()
            engine.start()
            Toast.makeText(this, "已开始", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPointSettings(point: ClickPoint) {
        if (settingsPanel != null) return

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            background = GradientDrawable().apply {
                cornerRadius = 30f
                setColor(Color.WHITE)
            }
        }

        layout.addView(TextView(this).apply {
            text = "点位 #${point.id} 设置"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 12)
        })

        val fields = listOf(
            "点击前延迟 (ms，两次点击之间的间隔)" to point.delayBeforeClick.toString(),
            "按压时长 (ms，手指按下持续时间)" to point.pressDuration.toString(),
            "点击次数 (0=无限)" to point.repeatCount.toString(),
            "随机位置 ± (px，防检测)" to point.randomOffsetX.toString(),
            "随机延迟 ± (ms，防检测)" to point.randomDelayExtra.toString(),
            "随机时长 ± (ms，防检测)" to point.randomDurationExtra.toString()
        )

        val edits = mutableListOf<EditText>()
        fields.forEach { (label, value) ->
            layout.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(Color.GRAY)
                setPadding(0, 12, 0, 2)
            })
            val et = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(value)
                setTextColor(Color.BLACK)
                setSelection(text.length)
            }
            layout.addView(et)
            edits.add(et)
        }

        val btnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 0)
        }
        btnLayout.addView(Button(this).apply {
            text = "保存"
            setOnClickListener {
                point.delayBeforeClick = edits[0].text.toString().toLongOrNull() ?: 300L
                point.pressDuration = edits[1].text.toString().toLongOrNull() ?: 50L
                point.repeatCount = edits[2].text.toString().toIntOrNull() ?: 0
                point.randomOffsetX = edits[3].text.toString().toIntOrNull() ?: 5
                point.randomOffsetY = point.randomOffsetX
                point.randomDelayExtra = edits[4].text.toString().toLongOrNull() ?: 100L
                point.randomDurationExtra = edits[5].text.toString().toLongOrNull() ?: 20L
                Toast.makeText(this@ClickerWindowService, "已保存", Toast.LENGTH_SHORT).show()
                dismissSettingsPanel()
            }
        })
        btnLayout.addView(Button(this).apply {
            text = "删除"
            setOnClickListener {
                engine.removePoint(point.id)
                pointMarkers[point.id]?.let {
                    try { windowManager.removeView(it) } catch (_: Exception) {}
                }
                pointMarkers.remove(point.id)
                pointParams.remove(point.id)
                dismissSettingsPanel()
            }
        })
        btnLayout.addView(Button(this).apply {
            text = "取消"
            setOnClickListener { dismissSettingsPanel() }
        })
        layout.addView(btnLayout)

        val scroll = ScrollView(this).apply { addView(layout) }

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        try {
            windowManager.addView(scroll, params)
            settingsPanel = scroll
        } catch (_: Exception) {}
    }

    private fun dismissSettingsPanel() {
        settingsPanel?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        settingsPanel = null
    }
}
