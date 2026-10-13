package com.example.autotoolkit.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.autotoolkit.R
import com.example.autotoolkit.recorder.ScriptStorage
import com.example.autotoolkit.service.AutoClickService
import com.example.autotoolkit.service.ClickerWindowService
import com.example.autotoolkit.service.RecorderWindowService

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !Settings.canDrawOverlays(this)
            ) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        findViewById<Button>(R.id.btnStartClicker).setOnClickListener {
            if (!checkPrerequisites()) return@setOnClickListener
            startForegroundServiceCompat(ClickerWindowService::class.java)
            Toast.makeText(this, "点击器控制条已启动", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnStartRecorder).setOnClickListener {
            if (!checkPrerequisites()) return@setOnClickListener
            startForegroundServiceCompat(RecorderWindowService::class.java)
            Toast.makeText(this, "录制器控制条已启动", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        refreshScriptList()
    }

    private fun startForegroundServiceCompat(cls: Class<*>) {
        val intent = Intent(this, cls)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun checkPrerequisites(): Boolean {
        if (AutoClickService.instance == null) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun updateStatus() {
        val statusText = findViewById<TextView>(R.id.statusText)
        val accessibilityOk = AutoClickService.instance != null
        val overlayOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
        statusText.text = buildString {
            append("无障碍：")
            append(if (accessibilityOk) "✓" else "✗")
            append("   悬浮窗：")
            append(if (overlayOk) "✓" else "✗")
        }
    }

    private fun refreshScriptList() {
        val container = findViewById<LinearLayout>(R.id.scriptListContainer)
        container.removeAllViews()
        val scripts = ScriptStorage.loadAll(this)
        if (scripts.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "暂无已保存的录制"
                setTextColor(Color.GRAY)
                textSize = 13f
                setPadding(0, 8, 0, 8)
            })
            return
        }
        scripts.forEach { script ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            row.addView(TextView(this).apply {
                text = "${script.name} (${script.actions.size} 个动作)"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            row.addView(Button(this).apply {
                text = "删除"
                setOnClickListener {
                    ScriptStorage.delete(this@MainActivity, script.name)
                    refreshScriptList()
                }
            })
            container.addView(row)
        }
    }
}
