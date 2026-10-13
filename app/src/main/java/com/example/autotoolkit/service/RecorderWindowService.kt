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
import com.example.autotoolkit.recorder.GestureRecorder
import com.example.autotoolkit.recorder.RecordedAction
import com.example.autotoolkit.recorder.ScriptPlayer
import com.example.autotoolkit.recorder.ScriptStorage

class RecorderWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var controlBar: LinearLayout
    private lateinit var controlBarParams: WindowManager.LayoutParams

    private val recorder = GestureRecorder()
    private val player = ScriptPlayer()

    private var recordOverlay: View? = null
    private var recordingActive = false
    private var lastRecording: List<RecordedAction> = emptyList()

    private val recordMarkers = mutableListOf<View>()
    private var markersVisible = true
    private var counter = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private var dialogPanel: View? = null

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AutoClickService.ACTION_STOP_ALL) {
                player.stop()
                if (recordingActive) stopRecording()
                mainHandler.post {
                    Toast.makeText(
                        this@RecorderWindowService,
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
    }

    override fun onDestroy() {
        player.stop()
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
        dismissDialog()
        recordMarkers.forEach {
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

    private fun startForegroundNotification() {
        val channelId = "autotoolkit_recorder"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                channelId, "录制器运行中",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("录制器运行中")
            .setContentText("音量减键可紧急停止")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(2, notification)
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
                setColor(Color.parseColor("#CC8B0000"))
            }
            setPadding(8, 8, 8, 8)
        }

        val buttons = listOf(
            "●" to { toggleRecording() },      // 开始/停止录制
            "▶" to { togglePlayback() },        // 回放最后一个
            "📁" to { showScriptList() },       // 选择已保存的录制
            "👁" to { toggleMarkers() },        // 显示/隐藏数字标记
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
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = controlBarParams.x
                        initialY = controlBarParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        dragging = false
                        return false
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

    private fun toggleRecording() {
        if (recordingActive) stopRecording() else startRecording()
    }

    private fun startRecording() {
        // 清除旧标记
        recordMarkers.forEach {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        recordMarkers.clear()
        counter = 0

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
        ).apply { gravity = Gravity.TOP or Gravity.START }

        overlay.setOnTouchListener { _, event ->
            recorder.onTouch(event)
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                addNumberMarker(event.rawX, event.rawY)
            }
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

    private fun addNumberMarker(x: Float, y: Float) {
        counter++
        val marker = TextView(this).apply {
            text = counter.toString()
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#DDFF5722"))
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = (x - 24 * resources.displayMetrics.density).toInt()
            this.y = (y - 24 * resources.displayMetrics.density).toInt()
        }
        try {
            windowManager.addView(marker, params)
            recordMarkers.add(marker)
            if (!markersVisible) marker.visibility = View.GONE
        } catch (_: Exception) {}
    }

    private fun stopRecording() {
        val actions = recorder.stop()
        recordingActive = false

        recordOverlay?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        recordOverlay = null
        controlBar.visibility = View.VISIBLE

        lastRecording = actions
        Toast.makeText(this, "录制完成，共 ${actions.size} 个动作，请保存", Toast.LENGTH_LONG).show()

        if (actions.isNotEmpty()) {
            showSaveDialog(actions)
        }
    }

    private fun showSaveDialog(actions: List<RecordedAction>) {
        dismissDialog()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            background = GradientDrawable().apply {
                cornerRadius = 30f
                setColor(Color.WHITE)
            }
        }
        layout.addView(TextView(this).apply {
            text = "保存录制（${actions.size} 个动作）"
            textSize = 16f
            setTextColor(Color.BLACK)
        })
        val nameInput = EditText(this).apply {
            hint = "输入名称"
            setText("录制_${System.currentTimeMillis() % 10000}")
            setTextColor(Color.BLACK)
            setSelection(text.length)
        }
        layout.addView(nameInput)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 0)
        }
        btnRow.addView(Button(this).apply {
            text = "保存"
            setOnClickListener {
                val name = nameInput.text.toString().ifBlank { "未命名" }
                ScriptStorage.save(this@RecorderWindowService, name, actions)
                Toast.makeText(
                    this@RecorderWindowService, "已保存：$name", Toast.LENGTH_SHORT
                ).show()
                dismissDialog()
            }
        })
        btnRow.addView(Button(this).apply {
            text = "不保存"
            setOnClickListener { dismissDialog() }
        })
        layout.addView(btnRow)

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        try {
            windowManager.addView(layout, params)
            dialogPanel = layout
        } catch (_: Exception) {}
    }

    private fun showScriptList() {
        dismissDialog()
        val scripts = ScriptStorage.loadAll(this)
        if (scripts.isEmpty()) {
            Toast.makeText(this, "还没有保存的录制", Toast.LENGTH_SHORT).show()
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            background = GradientDrawable().apply {
                cornerRadius = 30f
                setColor(Color.WHITE)
            }
        }
        layout.addView(TextView(this).apply {
            text = "选择要回放的录制"
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 12)
        })

        scripts.forEach { script ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }
            row.addView(TextView(this).apply {
                text = "${script.name} (${script.actions.size})"
                setTextColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            row.addView(Button(this).apply {
                text = "播放"
                setOnClickListener {
                    player.play(script.actions, 0)
                    Toast.makeText(
                        this@RecorderWindowService, "开始回放", Toast.LENGTH_SHORT
                    ).show()
                    dismissDialog()
                }
            })
            row.addView(Button(this).apply {
                text = "删除"
                setOnClickListener {
                    ScriptStorage.delete(this@RecorderWindowService, script.name)
                    Toast.makeText(
                        this@RecorderWindowService, "已删除", Toast.LENGTH_SHORT
                    ).show()
                    dismissDialog()
                }
            })
            layout.addView(row)
        }

        layout.addView(Button(this).apply {
            text = "关闭"
            setOnClickListener { dismissDialog() }
        })

        val scroll = ScrollView(this).apply { addView(layout) }

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            (resources.displayMetrics.heightPixels * 0.6).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        try {
            windowManager.addView(scroll, params)
            dialogPanel = scroll
        } catch (_: Exception) {}
    }

    private fun togglePlayback() {
        if (lastRecording.isEmpty()) {
            Toast.makeText(this, "还没有录制，请先点 ● 录制", Toast.LENGTH_SHORT).show()
            return
        }
        if (player.isPlaying()) {
            player.stop()
            Toast.makeText(this, "已停止回放", Toast.LENGTH_SHORT).show()
        } else {
            player.play(lastRecording, 0)
            Toast.makeText(this, "开始回放（音量减键停止）", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleMarkers() {
        markersVisible = !markersVisible
        recordMarkers.forEach {
            it.visibility = if (markersVisible) View.VISIBLE else View.GONE
        }
        Toast.makeText(
            this,
            if (markersVisible) "标记已显示" else "标记已隐藏",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun dismissDialog() {
        dialogPanel?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        dialogPanel = null
    }
}
