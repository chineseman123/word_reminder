package com.teleprompter.app

import android.graphics.Color
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.view.ViewTreeObserver
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.hypot
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
    private lateinit var seekBg: SeekBar
    private lateinit var txtBg: TextView
    private lateinit var seekSmooth: SeekBar
    private lateinit var txtSmooth: TextView
    private lateinit var btnPanelHandle: ImageView

    // Camera & Recording
    private var cameraHelper: CameraHelper? = null
    private var isRecording = false
    private var recordingStartTime = 0L
    private var timerHandler: Handler? = null
    private var timerRunnable: Runnable? = null

    // Teleprompter state
    private var fontSize = 22f
    private var textOpacity = 80  // 0-100 percent
    private var bgAlpha = 25      // 字幕背景灰度 0-100
    private var beautySmooth = 0  // 磨皮强度 0-100
    private var scrollSpeed = 6   // 速度档位 0-12，实际滚动速度=档位/2（对应旧 0-6 档，精度翻倍）
    private var scrollRemainder = 0f  // 半档速度的小数累积器
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
        seekBg = findViewById(R.id.seekBg)
        txtBg = findViewById(R.id.txtBg)
        seekSmooth = findViewById(R.id.seekSmooth)
        txtSmooth = findViewById(R.id.txtSmooth)
        btnPanelHandle = findViewById(R.id.btnPanelHandle)

        // 恢复上次保存的文案，避免每次打开都被新手介绍覆盖
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val saved = prefs.getString(KEY_CONTENT, null)
        if (saved != null) {
            teleprompterContent = saved
        }

        // 恢复字幕背景灰度 + 上次拖放的位置和大小
        bgAlpha = prefs.getInt(KEY_BG_ALPHA, 25)
        seekBg.progress = bgAlpha
        txtBg.text = "$bgAlpha%"
        applySubtitleBackground()

        // 恢复磨皮强度
        beautySmooth = prefs.getInt(KEY_BEAUTY_SMOOTH, 0)
        seekSmooth.progress = beautySmooth
        txtSmooth.text = "$beautySmooth%"

        // 字幕拖拽手柄初始定位（等布局完成后）
        teleprompterScroll.post {
            if (controlsVisible) {
                btnPanelHandle.visibility = View.VISIBLE
                updatePanelHandle()
            }
        }
        teleprompterScroll.post {
            val pw = prefs.getInt(KEY_PANEL_W, 0)
            val ph = prefs.getInt(KEY_PANEL_H, 0)
            if (pw > 0 && ph > 0) {
                val lp = teleprompterScroll.layoutParams
                lp.width = pw
                lp.height = ph
                teleprompterScroll.layoutParams = lp
            }
            teleprompterScroll.translationX = prefs.getFloat(KEY_PANEL_X, 0f)
            teleprompterScroll.translationY = prefs.getFloat(KEY_PANEL_Y, 0f)
            updatePanelHandle()
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
        // CameraHelper 重建后恢复磨皮参数（内部会同步给预览/录制两条 GL 管线）
        cameraHelper?.setSmoothing(beautySmooth / 100f)
        // 录像启动失败（如会话配置失败）时恢复界面状态
        cameraHelper?.onRecordingStopped = { path ->
            if (path == null && isRecording) {
                isRecording = false
                btnRecord.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_record))
                btnRecord.contentDescription = "开始录制"
                stopTimer()
                pauseScroll()
                applyTopBarVisibility()
                bottomBar.visibility = View.VISIBLE
                btnPanelHandle.visibility = View.VISIBLE
                controlsVisible = true
            }
        }

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

        // Subtitle background shade
        seekBg.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                bgAlpha = progress
                txtBg.text = "$progress%"
                applySubtitleBackground()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putInt(KEY_BG_ALPHA, bgAlpha).apply()
            }
        })

        // 美颜：磨皮（祛痘/黑头淡化，依赖人脸检测，只作用于人脸区域）
        seekSmooth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                beautySmooth = progress
                txtSmooth.text = "$progress%"
                cameraHelper?.setSmoothing(beautySmooth / 100f)
                if (fromUser && progress > 0 && cameraHelper?.faceDetectSupported == false) {
                    Toast.makeText(this@MainActivity, "本机不支持人脸检测，磨皮可能不生效", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putInt(KEY_BEAUTY_SMOOTH, beautySmooth).apply()
            }
        })

        // 字幕右下角手柄：横向拖动=拉伸字幕宽度，竖向拖动=上下移动字幕
        var handleLastX = 0f
        var handleLastY = 0f
        btnPanelHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handleLastX = event.rawX
                    handleLastY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - handleLastX
                    val dy = event.rawY - handleLastY
                    handleLastX = event.rawX
                    handleLastY = event.rawY
                    val parent = teleprompterScroll.parent as View
                    // 横向：拉伸宽度（左边缘锚定）
                    val lp = teleprompterScroll.layoutParams
                    lp.width = (teleprompterScroll.width + dx.toInt())
                        .coerceIn(parent.width / 3, parent.width)
                    teleprompterScroll.layoutParams = lp
                    // 竖向：上下移动字幕块
                    teleprompterScroll.translationY = (teleprompterScroll.translationY + dy)
                        .coerceIn(
                            -teleprompterScroll.top.toFloat(),
                            (parent.height - teleprompterScroll.top - teleprompterScroll.height).toFloat()
                        )
                    updatePanelHandle()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    savePanelGeometry()
                    true
                }
                else -> false
            }
        }

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
        findViewById<Button>(R.id.btnSpeechTest).setOnClickListener {
            testSpeechRecognition()
        }

        // Tap to show/hide controls during recording
        cameraPreview.setOnClickListener {
            if (isRecording) {
                toggleControlsVisibility()
            }
        }

        // 字幕块手势：拖动移动 / 双指缩放 / 竖滑滚动文字 / 单击切换控制栏
        setupTeleprompterGestures()
    }

    private fun showEditPanel() {
        editText.setText(teleprompterContent)
        editPanel.visibility = View.VISIBLE
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
        btnExpandTop.visibility = View.GONE
        btnPanelHandle.visibility = View.GONE
    }

    private fun hideEditPanel() {
        editPanel.visibility = View.GONE
        bottomBar.visibility = View.VISIBLE
        btnPanelHandle.visibility = View.VISIBLE
        updatePanelHandle()
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

    // ---- 语音识别可用性验证 ----
    private fun testSpeechRecognition() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "此设备不支持系统语音识别（可能缺少语音服务）", Toast.LENGTH_LONG).show()
            return
        }

        val resultView = TextView(this).apply {
            setPadding(48, 32, 48, 32)
            textSize = 16f
            text = "准备中..."
        }
        var sr: android.speech.SpeechRecognizer? = null
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("语音识别测试")
            .setView(resultView)
            .setNegativeButton("关闭", null)
            .create()
        dialog.setOnDismissListener {
            sr?.stopListening()
            sr?.destroy()
            sr = null
        }
        dialog.show()

        val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        sr = recognizer
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { resultView.text = "请开始说话…" }
            override fun onBeginningOfSpeech() { resultView.text = "聆听中…" }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { resultView.text = "识别中…" }
            override fun onError(error: Int) {
                resultView.text = "识别失败（错误码 $error）\n\n" + when (error) {
                    android.speech.SpeechRecognizer.ERROR_NO_MATCH,
                    android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听清或没检测到说话，关闭后重试"
                    android.speech.SpeechRecognizer.ERROR_CLIENT -> "设备语音服务异常（部分国产机在线识别不可用）"
                    android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
                    android.speech.SpeechRecognizer.ERROR_NETWORK,
                    android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络问题（在线识别需联网）"
                    android.speech.SpeechRecognizer.ERROR_AUDIO -> "录音失败"
                    else -> "请重试"
                }
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                resultView.text = "识别成功 ✓\n\n${matches?.firstOrNull() ?: "（空）"}"
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) resultView.text = "识别中：${matches[0]}"
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            resultView.text = "启动识别失败：${e.message}"
        }
    }

    private fun saveTeleprompterContent() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_CONTENT, teleprompterContent)
            .apply()
    }

    // ---- 字幕背景：灰度 + 可拖拽/缩放 ----

    private fun applySubtitleBackground() {
        val a = (bgAlpha * 255 / 100).coerceIn(0, 255)
        teleprompterScroll.setBackgroundColor(Color.argb(a, 128, 128, 128))
    }

    /** 把手柄贴到字幕块右下角（字幕块位置/尺寸变化后调用） */
    private fun updatePanelHandle() {
        if (!::btnPanelHandle.isInitialized) return
        btnPanelHandle.post {
            val half = btnPanelHandle.width / 2f
            btnPanelHandle.x = teleprompterScroll.x + teleprompterScroll.width - half
            btnPanelHandle.y = teleprompterScroll.y + teleprompterScroll.height - half
        }
    }

    private fun savePanelGeometry() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putFloat(KEY_PANEL_X, teleprompterScroll.translationX)
            .putFloat(KEY_PANEL_Y, teleprompterScroll.translationY)
            .putInt(KEY_PANEL_W, teleprompterScroll.width)
            .putInt(KEY_PANEL_H, teleprompterScroll.height)
            .apply()
    }

    /**
     * 字幕块手势：
     * - 单指横向拖动 → 移动字幕块位置（随后 X/Y 都跟随手指）
     * - 双指捏合 → 缩放字幕块宽高
     * - 单指竖向滑动 → 交给 ScrollView 滚动文字（保留原有手动滚动）
     * - 录制时单击 → 切换控制栏显隐
     */
    private fun setupTeleprompterGestures() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var mode = 0  // 0=未判定 1=拖动 2=滚动文字 3=捏合缩放 4=捏合后剩余手指(忽略)
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        var lastY = 0f
        var pinchStartDist = 0f
        var pinchStartW = 0
        var pinchStartH = 0

        teleprompterScroll.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mode = 0
                    downX = event.rawX
                    downY = event.rawY
                    lastX = event.rawX
                    lastY = event.rawY
                    false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        mode = 3
                        pinchStartDist = hypot(
                            event.getX(1) - event.getX(0),
                            event.getY(1) - event.getY(0)
                        )
                        pinchStartW = v.width
                        pinchStartH = v.height
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> when (mode) {
                    3 -> {
                        if (event.pointerCount >= 2 && pinchStartDist > 0) {
                            val d = hypot(
                                event.getX(1) - event.getX(0),
                                event.getY(1) - event.getY(0)
                            )
                            val scale = (d / pinchStartDist).coerceIn(0.5f, 3f)
                            val parent = v.parent as View
                            val lp = v.layoutParams
                            lp.width = (pinchStartW * scale).toInt()
                                .coerceIn(parent.width / 3, parent.width)
                            lp.height = (pinchStartH * scale).toInt()
                                .coerceIn((120 * resources.displayMetrics.density).toInt(), parent.height * 7 / 10)
                            v.layoutParams = lp
                            updatePanelHandle()
                        }
                        true
                    }
                    2, 4 -> false  // 竖向滚动文字 / 捏合后剩余手指，交给 ScrollView
                    else -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (mode == 0 && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            mode = if (abs(dy) > abs(dx) * 1.5f) 2 else 1
                        }
                        if (mode == 1) {
                            val parent = v.parent as View
                            v.translationX = (v.translationX + event.rawX - lastX)
                                .coerceIn(-v.left.toFloat(), (parent.width - v.left - v.width).toFloat())
                            v.translationY = (v.translationY + event.rawY - lastY)
                                .coerceIn(-v.top.toFloat(), (parent.height - v.top - v.height).toFloat())
                            updatePanelHandle()
                        }
                        lastX = event.rawX
                        lastY = event.rawY
                        mode == 1
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (mode == 3 && event.pointerCount <= 2) {
                        savePanelGeometry()
                        mode = 4  // 避免剩余手指继续移动造成跳动
                    }
                    true
                }
                MotionEvent.ACTION_UP -> when (mode) {
                    1 -> {
                        savePanelGeometry()
                        true
                    }
                    0 -> {
                        // 单击：录制时切换控制栏显隐
                        if (isRecording) {
                            toggleControlsVisibility()
                        }
                        false
                    }
                    else -> false
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (mode == 1) savePanelGeometry()
                    false
                }
                else -> false
            }
        }
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
                    btnPanelHandle.visibility = View.GONE
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
        btnPanelHandle.visibility = View.VISIBLE
        updatePanelHandle()
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
                    // 档位/2 = 每 50ms 滚动像素，小数部分累积到下次，保证半档速度平滑
                    scrollRemainder += scrollSpeed / 2f
                    val px = scrollRemainder.toInt()
                    if (px > 0) {
                        teleprompterScroll.smoothScrollBy(0, px)
                        scrollRemainder -= px
                    }
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
            btnPanelHandle.visibility = View.VISIBLE
            updatePanelHandle()
        } else {
            topBar.visibility = View.GONE
            bottomBar.visibility = View.GONE
            btnExpandTop.visibility = View.GONE
            btnPanelHandle.visibility = View.GONE
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
        const val KEY_BG_ALPHA = "bg_alpha"
        const val KEY_PANEL_X = "panel_tx"
        const val KEY_PANEL_Y = "panel_ty"
        const val KEY_PANEL_W = "panel_w"
        const val KEY_PANEL_H = "panel_h"
        const val KEY_BEAUTY_SMOOTH = "beauty_smooth"
    }
}