package com.teleprompter.app

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.view.ViewTreeObserver
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import org.json.JSONArray


class MainActivity : AppCompatActivity() {

    // Views
    private lateinit var cameraPreview: TextureView
    private lateinit var teleprompterScroll: ScrollView
    private lateinit var teleprompterText: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var editPanel: LinearLayout
    private lateinit var editText: EditText
    private lateinit var btnRecord: ImageButton
    private lateinit var btnEdit: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnReset: ImageButton
    private lateinit var btnFontUp: ImageButton
    private lateinit var btnFontDown: ImageButton
    private lateinit var btnOpacityUp: ImageButton
    private lateinit var btnOpacityDown: ImageButton
    private lateinit var btnZoomIn: ImageButton
    private lateinit var btnZoomOut: ImageButton
    private lateinit var txtZoom: TextView
    private lateinit var txtFont: TextView
    private lateinit var txtSpeed: TextView
    private lateinit var btnCollapseTop: ImageButton
    private lateinit var btnExpandTop: ImageButton
    private lateinit var btnConfirmEdit: Button
    private lateinit var btnCancelEdit: Button
    private lateinit var seekSpeed: SeekBar
    private lateinit var txtTimer: TextView

    // Camera & Recording
    private var cameraHelper: CameraHelper? = null
    private var isRecording = false
    private var recordingStartTime = 0L
    private var timerHandler: Handler? = null
    private var timerRunnable: Runnable? = null

    // Teleprompter state
    private var fontSize = 22f
    private var textOpacity = 80  // 0-100 percent
    private var scrollSpeed = 3
    private var isScrolling = false
    private var scrollHandler: Handler? = null
    private var scrollRunnable: Runnable? = null

    // Zoom state — default 1.0x (无缩放)
    private var currentZoom = 1.0f

    // Default teleprompter content
    private var teleprompterContent = "欢迎使用提词器！\n\n请点击编辑按钮，粘贴您要提词的内容。\n\n录制时，提词文字会在此处滚动显示，帮助您自然地面对镜头讲话。\n\n您可以调节：\n- 文字大小（字体按钮）\n- 滚动速度（速度滑块）\n- 文字透明度（透明度按钮）\n\n开始录制后，控制栏会自动隐藏，轻触屏幕可重新显示。"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        checkPermissionsAndStart()
        setupControls()
    }

    private fun initViews() {
        cameraPreview = findViewById(R.id.cameraPreview)
        teleprompterScroll = findViewById(R.id.teleprompterScroll)
        teleprompterText = findViewById(R.id.teleprompterText)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        editPanel = findViewById(R.id.editPanel)
        editText = findViewById(R.id.editText)
        btnRecord = findViewById(R.id.btnRecord)
        btnEdit = findViewById(R.id.btnEdit)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnReset = findViewById(R.id.btnReset)
        btnFontUp = findViewById(R.id.btnFontUp)
        btnFontDown = findViewById(R.id.btnFontDown)
        btnOpacityUp = findViewById(R.id.btnOpacityUp)
        btnOpacityDown = findViewById(R.id.btnOpacityDown)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)
        txtZoom = findViewById(R.id.txtZoom)
        txtFont = findViewById(R.id.txtFont)
        txtSpeed = findViewById(R.id.txtSpeed)
        btnCollapseTop = findViewById(R.id.btnCollapseTop)
        btnExpandTop = findViewById(R.id.btnExpandTop)
        btnConfirmEdit = findViewById(R.id.btnConfirmEdit)
        btnCancelEdit = findViewById(R.id.btnCancelEdit)
        seekSpeed = findViewById(R.id.seekSpeed)
        txtTimer = findViewById(R.id.txtTimer)

        // 恢复上次保存的文案，避免每次打开都被新手介绍覆盖
        val saved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_CONTENT, null)
        if (saved != null) {
            teleprompterContent = saved
        }

        // 初始化数值显示
        txtFont.text = fontSize.toInt().toString()
        scrollSpeed = seekSpeed.progress
        txtSpeed.text = "$scrollSpeed/${seekSpeed.max}"

        // Set initial teleprompter text
        updateTeleprompterDisplay()
    }

    private fun updateTeleprompterDisplay() {
        teleprompterText.text = teleprompterContent
        teleprompterText.textSize = fontSize
        val alpha = (textOpacity * 255 / 100).coerceIn(0, 255)
        teleprompterText.setTextColor(android.graphics.Color.argb(alpha, 255, 255, 255))
        teleprompterText.paint.setShadowLayer(3f, 1f, 1f, android.graphics.Color.argb(alpha, 0, 0, 0))
    }

    private fun checkPermissionsAndStart() {
        val corePermissions = mutableListOf(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO"
        )
        if (Build.VERSION.SDK_INT <= 28) {
            corePermissions.add("android.permission.WRITE_EXTERNAL_STORAGE")
        }

        val needed = corePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val cameraGranted = ContextCompat.checkSelfPermission(this, "android.permission.CAMERA") == PackageManager.PERMISSION_GRANTED
            val audioGranted = ContextCompat.checkSelfPermission(this, "android.permission.RECORD_AUDIO") == PackageManager.PERMISSION_GRANTED
            if (cameraGranted && audioGranted) {
                startCamera()
            } else {
                Toast.makeText(this, "需要摄像头和麦克风权限才能使用", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startCamera() {
        if (cameraHelper != null) {
            cameraHelper?.close()
        }
        cameraHelper = CameraHelper(this, cameraPreview)

        // Default zoom 1.0x, applied when preview starts
        currentZoom = 1.0f

        // Adjust TextureView aspect ratio to match camera preview
        // This prevents the preview from being stretched/distorted
        adjustPreviewAspectRatio()

        if (cameraPreview.isAvailable) {
            // Surface already ready, open camera immediately
            cameraHelper?.openFrontCamera()
            // Apply zoom after camera opens (delayed to ensure camera is ready)
            cameraPreview.postDelayed({
                cameraHelper?.setZoom(currentZoom)
            }, 500)
        } else {
            // Wait for surface
            cameraPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    cameraHelper?.openFrontCamera()
                    cameraPreview.postDelayed({
                        cameraHelper?.setZoom(currentZoom)
                    }, 500)
                }
                override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    cameraHelper?.updatePreview()
                    adjustPreviewAspectRatio()
                }
                override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                    cameraHelper?.close()
                    return true
                }
                override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
            }
        }
    }

    /**
     * Adjust the TextureView's dimensions to match the camera's portrait aspect ratio.
     * Without this, the full-screen TextureView stretches the camera preview to fill
     * the screen, causing the person to appear stretched/distorted.
     * 
     * We fit the preview to fill the screen width, and adjust the height to match
     * the camera's portrait aspect ratio. If the calculated height is taller than the
     * screen, we fill the screen entirely (camera aspect ratio is very close to screen ratio,
     * so any slight mismatch is negligible). The key is ensuring the buffer ratio matches.
     */
    private fun adjustPreviewAspectRatio() {
        val helper = cameraHelper ?: return
        val portraitW = helper.portraitWidth
        val portraitH = helper.portraitHeight
        if (portraitW <= 0 || portraitH <= 0) return

        cameraPreview.post {
            val container = cameraPreview.parent as? android.view.ViewGroup ?: return@post
            val containerW = container.width
            val containerH = container.height
            if (containerW <= 0 || containerH <= 0) return@post

            // Camera portrait aspect ratio (e.g. 1080:1920 = 9:16)
            val cameraRatio = portraitW.toFloat() / portraitH.toFloat()
            // Container aspect ratio
            val containerRatio = containerW.toFloat() / containerH.toFloat()

            val params = cameraPreview.layoutParams
            if (abs(containerRatio - cameraRatio) < 0.01f) {
                // Ratios match closely enough, use match_parent
                // No adjustment needed
            } else {
                // Fit width to container, calculate height from camera ratio
                val previewW = containerW
                val previewH = (containerW / cameraRatio).toInt()
                params.width = previewW
                params.height = previewH.coerceAtMost(containerH)
                cameraPreview.layoutParams = params
            }
        }
    }

    private fun setupControls() {
        // Record button
        btnRecord.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        // Edit button
        btnEdit.setOnClickListener {
            showEditPanel()
        }

        // 顶部功能栏收起 / 展开
        btnCollapseTop.setOnClickListener { setTopBarCollapsed(true) }
        btnExpandTop.setOnClickListener { setTopBarCollapsed(false) }

        // Font size controls
        btnFontUp.setOnClickListener {
            fontSize = (fontSize + 2).coerceAtMost(40f)
            updateTeleprompterDisplay()
            txtFont.text = fontSize.toInt().toString()
        }
        btnFontDown.setOnClickListener {
            fontSize = (fontSize - 2).coerceAtLeast(12f)
            updateTeleprompterDisplay()
            txtFont.text = fontSize.toInt().toString()
        }

        // Opacity controls
        btnOpacityUp.setOnClickListener {
            textOpacity = (textOpacity + 10).coerceAtMost(100)
            updateTeleprompterDisplay()
            Toast.makeText(this, "透明度: ${textOpacity}%", Toast.LENGTH_SHORT).show()
        }
        btnOpacityDown.setOnClickListener {
            textOpacity = (textOpacity - 10).coerceAtLeast(20)
            updateTeleprompterDisplay()
            Toast.makeText(this, "透明度: ${textOpacity}%", Toast.LENGTH_SHORT).show()
        }

        // Zoom controls — adjust focal length to reduce wide-angle distortion
        btnZoomIn.setOnClickListener {
            val maxZ = cameraHelper?.maxZoomRatio ?: 5.0f
            currentZoom = (currentZoom + 0.2f).coerceAtMost(maxZ)
            cameraHelper?.setZoom(currentZoom)
            txtZoom.text = String.format("%.1fx", currentZoom)
            Toast.makeText(this, "缩放: ${String.format("%.1f", currentZoom)}x", Toast.LENGTH_SHORT).show()
        }
        btnZoomOut.setOnClickListener {
            currentZoom = (currentZoom - 0.2f).coerceAtLeast(1.0f)
            cameraHelper?.setZoom(currentZoom)
            txtZoom.text = String.format("%.1fx", currentZoom)
            Toast.makeText(this, "缩放: ${String.format("%.1f", currentZoom)}x", Toast.LENGTH_SHORT).show()
        }

        // Scroll speed
        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                scrollSpeed = progress
                txtSpeed.text = "$progress/${seekBar?.max ?: 20}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Play/Pause scroll
        btnPlayPause.setOnClickListener {
            if (isScrolling) {
                pauseScroll()
            } else {
                startScroll()
            }
        }

        // Reset scroll
        btnReset.setOnClickListener {
            teleprompterScroll.smoothScrollTo(0, 0)
            pauseScroll()
        }

        // Edit panel
        btnConfirmEdit.setOnClickListener {
            teleprompterContent = editText.text.toString()
            saveTeleprompterContent()
            saveScriptToHistory(teleprompterContent)
            updateTeleprompterDisplay()
            hideEditPanel()
        }
        btnCancelEdit.setOnClickListener {
            hideEditPanel()
        }
        findViewById<Button>(R.id.btnHistoryEdit).setOnClickListener {
            showScriptHistory()
        }

        // Tap to show/hide controls during recording
        cameraPreview.setOnClickListener {
            if (isRecording) {
                toggleControlsVisibility()
            }
        }

        // Long press on teleprompter area to show controls during recording
        teleprompterScroll.setOnTouchListener { v, event ->
            if (isRecording) {
                toggleControlsVisibility()
            }
            v.performClick()
        }
    }

    private fun showEditPanel() {
        editText.setText(teleprompterContent)
        editPanel.visibility = View.VISIBLE
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
        btnExpandTop.visibility = View.GONE
    }

    private fun hideEditPanel() {
        editPanel.visibility = View.GONE
        bottomBar.visibility = View.VISIBLE
        applyTopBarVisibility()
        controlsVisible = true
    }

    // ---- 台词本：保存/读取历史台词 ----
    private fun saveScriptToHistory(content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        val list = loadScriptHistory().toMutableList()
        list.remove(trimmed)          // 去重
        list.add(0, trimmed)          // 最新在前
        val arr = JSONArray()
        list.take(20).forEach { arr.put(it) }   // 最多保留 20 条
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString("script_history", arr.toString()).apply()
    }

    private fun loadScriptHistory(): List<String> {
        val json = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString("script_history", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun showScriptHistory() {
        val history = loadScriptHistory()
        if (history.isEmpty()) {
            Toast.makeText(this, "暂无历史台词", Toast.LENGTH_SHORT).show()
            return
        }
        val titles = history.map { it.replace("\n", " ").take(30) }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("选择历史台词")
            .setItems(titles) { _, which -> editText.setText(history[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveTeleprompterContent() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_CONTENT, teleprompterContent)
            .apply()
    }

    // ==================== Recording ====================

    private fun startRecording() {
        if (cameraHelper == null) {
            Toast.makeText(this, "摄像头未就绪，请稍候", Toast.LENGTH_SHORT).show()
            return
        }

        val videoFile = getOutputFile()
        val started = cameraHelper?.startRecording(videoFile.absolutePath)
        if (started == true) {
            isRecording = true
            btnRecord.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_stop))
            btnRecord.contentDescription = "停止录制"
            recordingStartTime = System.currentTimeMillis()
            startTimer()
            startScroll()
            // Dim controls after a delay
            Handler(Looper.getMainLooper()).postDelayed({
                if (isRecording) {
                    topBar.visibility = View.GONE
                    bottomBar.visibility = View.GONE
                    btnExpandTop.visibility = View.GONE
                }
            }, 3000)
        } else {
            Toast.makeText(this, "录制启动失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        cameraHelper?.stopRecording()
        isRecording = false
        btnRecord.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_record))
        btnRecord.contentDescription = "开始录制"
        stopTimer()
        pauseScroll()
        applyTopBarVisibility()
        bottomBar.visibility = View.VISIBLE
        controlsVisible = true
    }

    private fun getOutputFile(): File {
        val dir = File(getExternalFilesDir(null), "TeleprompterVideos")
        if (!dir.exists()) dir.mkdirs()
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        return File(dir, "VID_${dateFormat.format(Date())}.mp4")
    }

    // ==================== Timer ====================

    private fun startTimer() {
        txtTimer.visibility = View.VISIBLE
        timerHandler = Handler(Looper.getMainLooper())
        timerRunnable = object : Runnable {
            override fun run() {
                if (isRecording) {
                    val elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000
                    val min = elapsed / 60
                    val sec = elapsed % 60
                    txtTimer.text = String.format("%02d:%02d", min, sec)
                    timerHandler?.postDelayed(this, 500)
                }
            }
        }
        timerRunnable?.let { timerHandler?.post(it) }
    }

    private fun stopTimer() {
        timerRunnable?.let { timerHandler?.removeCallbacks(it) }
        txtTimer.visibility = View.GONE
    }

    // ==================== Auto Scroll ====================

    private fun startScroll() {
        isScrolling = true
        btnPlayPause.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_reset))
        scrollHandler = Handler(Looper.getMainLooper())
        scrollRunnable = object : Runnable {
            override fun run() {
                if (isScrolling) {
                    teleprompterScroll.smoothScrollBy(0, scrollSpeed)
                    scrollHandler?.postDelayed(this, 50)
                }
            }
        }
        scrollRunnable?.let { scrollHandler?.post(it) }
    }

    private fun pauseScroll() {
        isScrolling = false
        btnPlayPause.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_forward))
        scrollRunnable?.let { scrollHandler?.removeCallbacks(it) }
    }

    // ==================== Controls visibility ====================

    private var controlsVisible = true
    private var isTopBarCollapsed = false

    private fun toggleControlsVisibility() {
        controlsVisible = !controlsVisible
        if (controlsVisible) {
            applyTopBarVisibility()
            bottomBar.visibility = View.VISIBLE
        } else {
            topBar.visibility = View.GONE
            bottomBar.visibility = View.GONE
            btnExpandTop.visibility = View.GONE
        }
    }

    /** 收起 / 展开顶部功能栏：收起后只留右上角一个小按钮 */
    private fun setTopBarCollapsed(collapsed: Boolean) {
        isTopBarCollapsed = collapsed
        controlsVisible = true
        applyTopBarVisibility()
        bottomBar.visibility = View.VISIBLE
    }

    private fun applyTopBarVisibility() {
        topBar.visibility = if (isTopBarCollapsed) View.GONE else View.VISIBLE
        btnExpandTop.visibility = if (isTopBarCollapsed) View.VISIBLE else View.GONE
    }

    // ==================== Lifecycle ====================

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) {
            stopRecording()
        }
        cameraHelper?.close()
        timerRunnable?.let { timerHandler?.removeCallbacks(it) }
        scrollRunnable?.let { scrollHandler?.removeCallbacks(it) }
    }

    override fun onPause() {
        super.onPause()
        if (isRecording) {
            // Don't stop recording on pause - keep recording in background
        }
        cameraHelper?.close()
    }

    override fun onResume() {
        super.onResume()
        if (cameraHelper == null || !cameraHelper!!.isOpened) {
            startCamera()
        }
    }

    private companion object {
        const val PREFS_NAME = "teleprompter_prefs"
        const val KEY_CONTENT = "script_content"
    }
}