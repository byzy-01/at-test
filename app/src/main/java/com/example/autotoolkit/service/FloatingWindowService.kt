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
import com.example.autotoolkit.recorder.GestureRecorder
import com.example.autotoolkit.recorder.RecordedAction
import com.example.autotoolkit.recorder.ScriptPlayer
import java.util.concurrent.atomic.AtomicInteger

class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var controlBar: LinearLayout
    private lateinit var controlBarParams: WindowManager.LayoutParams

    private val pointMarkers = mutableMapOf<Int, View>()
    private val pointParams = mutableMapOf<Int, WindowManager.LayoutParams>()

    private val engine = ClickerEngine()
    private val scriptPlayer = ScriptPlayer()
    private val recorder = GestureRecorder()
    private val idGenerator = AtomicInteger(0)

    private var markersVisible = true
    private var controlBarLocked = false

    private var recordOverlay: View? = null
    private var recordingActive = false
    private var lastRecording: List<RecordedAction> = emptyList()

    private var settingsPanel: View? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AutoClickService.ACTION_STOP_ALL) {
                engine.stop()
                scriptPlayer.stop()
                if (recordingActive) stopRecording()
                mainHandler.post {
                    Toast.makeText(
                        this@FloatingWindowService,
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
        scriptPlayer.stop()
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
        dismissSettingsPanel()
        pointMarkers.values.forEach {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        recordOverlay?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        if (::controlBar.isInitialized) {
            try { windowManager.removeView(controlBar) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- 前台通知 ----------

    private fun startForegroundNotification() {
        val channelId = "autotoolkit_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                channelId, "AutoToolKit",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AutoToolKit 运行中")
            .setContentText("音量减键可紧急停止")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
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

    // ---------- 主控制条 ----------

    private fun createControlBar() {
        controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 30f
                setColor(Color.parseColor("#CC333333"))
            }
            setPadding(8, 8, 8, 8)
        }

        // 拖动把手
        val handle = TextView(this).apply {
            text = "⋮⋮"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 6)
        }
        controlBar.addView(
            handle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val buttons = listOf(
            Triple("▶", "开始/停止点击") { toggleClicker() },
            Triple("＋", "添加点位") { addNewPoint() },
            Triple("－", "删除最后点位") { removeLastPoint() },
            Triple("⚙", "全局设置") { showGlobalSettings() },
            Triple("👁", "显示/隐藏点位") { toggleMarkers() },
            Triple("🔒", "锁定控制条") { toggleLock() },
            Triple("●", "录制/停止") { toggleRecording() },
            Triple("↻", "回放") { playRecorded() },
            Triple("✕", "关闭服务") { stopSelf() }
        )

        buttons.forEach { (text, _, action) ->
            val btn = Button(this).apply {
                this.text = text
                textSize = 16f
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(20, 6, 20, 6)
                setOnClickListener { action() }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 3, 0, 3)
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
        makeHandleDraggable(handle)
    }

    private fun makeHandleDraggable(handle: View) {
        handle.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                if (controlBarLocked) return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = controlBarParams.x
                        initialY = controlBarParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        controlBarParams.x = initialX + dx.toInt()
                        controlBarParams.y = initialY + dy.toInt()
                        try {
                            windowManager.updateViewLayout(controlBar, controlBarParams)
                        } catch (_: Exception) {}
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
                }
                return false
            }
        })
    }

    // ---------- 点位管理 ----------

    private fun addNewPoint() {
        val id = idGenerator.incrementAndGet()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val point = ClickPoint(
            id = id,
            x = screenWidth / 2f + ((id % 3) - 1) * 120f,
            y = screenHeight / 3f + (id % 3) * 120f
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
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#DD2196F3"))
                setStroke(3, Color.WHITE)
            }
            val size = (48 * resources.displayMetrics.density).toInt()
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
                        if (Math.abs(dx) > 15 || Math.abs(dy) > 15) {
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
        Toast.makeText(
            this,
            if (markersVisible) "点位已显示" else "点位已隐藏",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleLock() {
        controlBarLocked = !controlBarLocked
        Toast.makeText(
            this,
            if (controlBarLocked) "控制条已锁定" else "控制条已解锁",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ---------- 点击器控制 ----------

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

    // ---------- 点位参数设置面板 ----------

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

        val title = TextView(this).apply {
            text = "点位 #${point.id} 设置"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 16)
        }
        layout.addView(title)

        val fields = listOf(
            "点击前延迟 (ms)" to point.delayBeforeClick.toString(),
            "按压时长 (ms)" to point.pressDuration.toString(),
            "点击次数 (0=无限)" to point.repeatCount.toString(),
            "随机位置 ± (px)" to point.randomOffsetX.toString(),
            "随机延迟 ± (ms)" to point.randomDelayExtra.toString(),
            "随机时长 ± (ms)" to point.randomDurationExtra.toString()
        )

        val edits = mutableListOf<EditText>()
        fields.forEach { (label, value) ->
            val tv = TextView(this).apply {
                text = label
                setTextColor(Color.BLACK)
                setPadding(0, 16, 0, 4)
            }
            val et = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(value)
                setTextColor(Color.BLACK)
                setSelection(text.length)
            }
            layout.addView(tv)
            layout.addView(et)
            edits.add(et)
        }

        val btnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 0)
        }

        btnLayout.addView(Button(this).apply {
            text = "保存"
            setOnClickListener {
                point.delayBeforeClick = edits[0].text.toString().toLongOrNull() ?: 300L
                point.pressDuration = edits[1].text.toString().toLongOrNull() ?: 50L
                point.repeatCount = edits[2].text.toString().toIntOrNull() ?: 0
                point.randomOffsetX = edits[3].text.toString().toIntOrNull() ?: 5
                point.randomDelayExtra = edits[4].text.toString().toLongOrNull() ?: 100L
                point.randomDurationExtra = edits[5].text.toString().toLongOrNull() ?: 20L
                Toast.makeText(
                    this@FloatingWindowService, "已保存", Toast.LENGTH_SHORT
                ).show()
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
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

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

    private fun showGlobalSettings() {
        Toast.makeText(
            this,
            "点位数：${engine.getPoints().size}\n拖动点位移动位置\n长按点位修改参数\n音量减键急停",
            Toast.LENGTH_LONG
        ).show()
    }

    // ---------- 录制/回放 ----------

    private fun toggleRecording() {
        if (recordingActive) stopRecording() else startRecording()
    }

    private fun startRecording() {
        markersVisible = false
        pointMarkers.values.forEach { it.visibility = View.GONE }
        controlBar.visibility = View.GONE

        val overlay = View(this).apply {
            setBackgroundColor(Color.parseColor("#20000000"))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        overlay.setOnTouchListener { _, event ->
            recorder.onTouch(event)
            true
        }

        try {
            windowManager.addView(overlay, params)
            recordOverlay = overlay
            recorder.start()
            recordingActive = true
            Toast.makeText(this, "录制中... 按音量减键停止", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {}
    }

    private fun stopRecording() {
        val actions = recorder.stop()
        recordingActive = false

        recordOverlay?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        recordOverlay = null

        markersVisible = true
        pointMarkers.values.forEach { it.visibility = View.VISIBLE }
        controlBar.visibility = View.VISIBLE

        lastRecording = actions
        Toast.makeText(
            this,
            "录制完成，共 ${actions.size} 个动作",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun playRecorded() {
        if (lastRecording.isEmpty()) {
            Toast.makeText(this, "还没有录制内容", Toast.LENGTH_SHORT).show()
            return
        }
        if (scriptPlayer.isPlaying()) {
            scriptPlayer.stop()
            Toast.makeText(this, "已停止回放", Toast.LENGTH_SHORT).show()
        } else {
            scriptPlayer.play(lastRecording, 0)
            Toast.makeText(this, "开始回放（音量减键停止）", Toast.LENGTH_SHORT).show()
        }
    }
}
