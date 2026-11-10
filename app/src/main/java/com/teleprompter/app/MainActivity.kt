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
    private lateinit var bottomSliders: LinearLayout
    private lateinit var btnCollapseBottom: ImageButton
    private lateinit var btnExpandBottom: ImageButton
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
        bottomSliders = findViewById(R.id.bottomSliders)
        btnCollapseBottom = findViewById(R.id.btnCollapseBottom)
        btnExpandBottom = findViewById(R.id.btnExpandBottom)
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
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                btnRecord.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_record))
                btnRecord.contentDescription = "开始录制"
                stopTimer()
                pauseScroll()
                applyTopBarVisibility()
                applyBottomBarVisibility()
                btnPanelHandle.visibility = View.VISIBLE
                controlsVisible = true
            }
        }

        // Default zoom 1.0x, applied when preview starts
        currentZoom = 1.0f

        // 预览始终铺满全屏：TextureView 保持 match_parent，
        // 画面宽高比差异由 BeautyRenderer 居中裁切吸收（只裁不拉，无黑边）

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
                    // surface 销毁时旧 helper 已被 close（后台线程已停），
                    // 直接调 openFrontCamera 会发到死线程上永远开不了 → 黑屏，需整体重建
                    val h = cameraHelper
                    if (h == null || h.isClosed) {
                        startCamera()
                        return
                    }
                    h.openFrontCamera()
                    cameraPreview.postDelayed({
                        cameraHelper?.setZoom(currentZoom)
                    }, 500)
                }
                override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    cameraHelper?.onViewSizeChanged(width, height)
                }
                override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                    cameraHelper?.close()
                    return true
                }
                override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
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

        // 底部滑块区（速度/背景/磨皮）收起 / 展开
        btnCollapseBottom.setOnClickListener { setBottomBarCollapsed(true) }
        btnExpandBottom.setOnClickListener { setBottomBarCollapsed(false) }

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

        // 字幕右下角手柄：横拖=调宽度，竖拖=调高度
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
                    val lp = teleprompterScroll.layoutParams
                    // 横向：拉伸宽度（左边缘锚定）
                    lp.width = (teleprompterScroll.width + dx.toInt())
                        .coerceIn(parent.width / 4, parent.width)
                    // 竖向：拉伸高度（上边缘锚定，向下拖=变长）
                    // 上限给到屏幕 85% 位置（留 15% 底部空间给功能栏/录制键）
                    val topY = teleprompterScroll.top
                    lp.height = (teleprompterScroll.height + dy.toInt())
                        .coerceIn(parent.height / 8, (parent.height * 0.85f - topY).toInt())
                    teleprompterScroll.layoutParams = lp
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
        // 一键清空编辑框：清掉上次台词，方便直接粘贴新台词
        findViewById<Button>(R.id.btnClearEdit).setOnClickListener {
            editText.setText("")
            Toast.makeText(this, "已清空，可粘贴新台词", Toast.LENGTH_SHORT).show()
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
        btnExpandBottom.visibility = View.GONE
        btnPanelHandle.visibility = View.GONE
    }

    private fun hideEditPanel() {
        editPanel.visibility = View.GONE
        applyBottomBarVisibility()
        btnPanelHandle.visibility = View.VISIBLE
        updatePanelHandle()
        applyTopBarVisibility()
        controlsVisible = true
    }

    // ---- 台词本：书签式历史台词（带保存时间，可点选回填，左滑单条删除）----
    private fun saveScriptToHistory(content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        val list = loadScriptHistory().toMutableList()
        list.removeAll { it.second == trimmed }                 // 同内容去重，刷新到最新
        list.add(0, System.currentTimeMillis() to trimmed)      // 最新在前
        persistHistory(list)
    }

    private fun persistHistory(list: List<Pair<Long, String>>) {
        val arr = JSONArray()
        list.take(20).forEach { (time, text) ->                 // 最多保留 20 条
            arr.put(org.json.JSONObject().apply {
                put("time", time)
                put("content", text)
            })
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString("script_history", arr.toString()).apply()
    }

    /** 返回 (保存时间戳, 台词内容) 列表；兼容旧版纯字符串格式（时间显示为“早期记录”） */
    private fun loadScriptHistory(): List<Pair<Long, String>> {
        val json = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString("script_history", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                when (val el = arr.get(i)) {
                    is org.json.JSONObject -> el.optLong("time", 0L) to el.optString("content")
                    else -> 0L to el.toString()
                }
            }.filter { it.second.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun showScriptHistory() {
        val history = loadScriptHistory().toMutableList()
        if (history.isEmpty()) {
            Toast.makeText(this, "暂无历史台词", Toast.LENGTH_SHORT).show()
            return
        }
        val density = resources.displayMetrics.density
        val deleteW = 64 * density                       // 删除按钮露出的宽度
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val rowGap = (8 * density).toInt()
        val cardRadius = 12 * density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(rowGap, rowGap, rowGap, rowGap)
        }
        val scroll = ScrollView(this).apply {
            addView(container)
            setBackgroundColor(0xFF16162A.toInt())
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("历史台词")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .show()
        // 拉长窗口：高取屏幕 70%，宽取 92%，看起来像独立管理页
        val dm = resources.displayMetrics
        dialog.window?.setLayout((dm.widthPixels * 0.92f).toInt(), (dm.heightPixels * 0.7f).toInt())

        var openedRow: View? = null                     // 当前滑开露出删除按钮的行

        fun closeOpened(except: View? = null) {
            if (openedRow != null && openedRow != except) {
                openedRow?.animate()?.translationX(0f)?.setDuration(150)?.start()
            }
            openedRow = except
        }

        history.forEach { item ->
            // 行结构：FrameLayout 底层是右侧红色“删除”，上层内容可左滑露出它
            val rowRoot = FrameLayout(this)
            val deleteBtn = TextView(this).apply {
                text = "删除"
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFE05252.toInt())
                    cornerRadii = floatArrayOf(
                        0f, 0f, cardRadius, cardRadius,
                        cardRadius, cardRadius, 0f, 0f
                    )
                }
                layoutParams = FrameLayout.LayoutParams(
                    deleteW.toInt(), FrameLayout.LayoutParams.MATCH_PARENT, android.view.Gravity.END
                )
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF262640.toInt())
                    cornerRadius = cardRadius
                }
                setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }
            content.addView(TextView(this).apply {
                text = if (item.first > 0) fmt.format(Date(item.first)) else "早期记录"
                textSize = 11f
                setTextColor(0xFF7C9EFF.toInt())
            })
            content.addView(TextView(this).apply {
                text = item.second.replace("\n", " ").take(40)
                textSize = 14f
                setTextColor(0xFFEDEDF5.toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, (4 * density).toInt(), 0, 0)
            })

            var downX = 0f
            var downY = 0f
            var startTx = 0f
            var dragging = false
            content.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX; downY = ev.rawY; startTx = v.translationX; dragging = false
                        true  // 必须消费 DOWN，否则收不到后续 MOVE/UP
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                            dragging = true
                            v.parent.requestDisallowInterceptTouchEvent(true)
                        }
                        if (dragging) {
                            v.translationX = (startTx + dx).coerceIn(-deleteW, 0f)
                            true
                        } else false
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragging) {
                            // 松手吸附：滑过一半就停住露出删除键，否则弹回
                            val target = if (v.translationX < -deleteW / 2) -deleteW else 0f
                            v.animate().translationX(target).setDuration(150).start()
                            closeOpened(if (target < 0f) v else null)
                            true
                        } else {
                            if (v.translationX < -1f) {
                                // 行已滑开时点击 = 收起
                                v.animate().translationX(0f).setDuration(150).start()
                                closeOpened()
                            } else {
                                closeOpened()
                                editText.setText(item.second)
                                dialog.dismiss()
                            }
                            false
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
                            v.animate().translationX(startTx).setDuration(150).start()
                            true
                        } else false
                    }
                    else -> false
                }
            }
            deleteBtn.setOnClickListener {
                val idx = history.indexOf(item)
                if (idx >= 0) {
                    history.removeAt(idx)
                    persistHistory(history)
                }
                container.removeView(rowRoot)
                closeOpened()
                if (history.isEmpty()) {
                    dialog.dismiss()
                    Toast.makeText(this, "历史台词已全部删除", Toast.LENGTH_SHORT).show()
                }
            }

            rowRoot.addView(deleteBtn)
            rowRoot.addView(content)
            // 卡片间距
            container.addView(rowRoot, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = rowGap })
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

    /** 把手柄贴到字幕块右下角（字幕块位置/尺寸变化后调用），并与屏幕边缘保持安全距离避免误触侧滑返回 */
    private fun updatePanelHandle() {
        if (!::btnPanelHandle.isInitialized) return
        btnPanelHandle.post {
            val parent = teleprompterScroll.parent as? View ?: return@post
            val half = btnPanelHandle.width / 2f
            val margin = 20 * resources.displayMetrics.density
            btnPanelHandle.x = (teleprompterScroll.x + teleprompterScroll.width - half)
                .coerceIn(margin, parent.width - btnPanelHandle.width - margin)
            btnPanelHandle.y = (teleprompterScroll.y + teleprompterScroll.height - half)
                .coerceIn(margin, parent.height - btnPanelHandle.height - margin)
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
            // 录制期间保持屏幕常亮，避免系统 1 分钟息屏打断录制
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Dim controls after a delay
            Handler(Looper.getMainLooper()).postDelayed({
                if (isRecording) {
                    topBar.visibility = View.GONE
                    bottomBar.visibility = View.GONE
                    btnExpandTop.visibility = View.GONE
                    btnExpandBottom.visibility = View.GONE
                    btnPanelHandle.visibility = View.GONE
                }
            }, 3000)
        } else {
            Toast.makeText(this, "录制启动失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraHelper?.stopRecording()
        isRecording = false
        btnRecord.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_record))
        btnRecord.contentDescription = "开始录制"
        stopTimer()
        pauseScroll()
        applyTopBarVisibility()
        applyBottomBarVisibility()
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
    private var isBottomBarCollapsed = false

    private fun toggleControlsVisibility() {
        controlsVisible = !controlsVisible
        if (controlsVisible) {
            applyTopBarVisibility()
            applyBottomBarVisibility()
            btnPanelHandle.visibility = View.VISIBLE
            updatePanelHandle()
        } else {
            topBar.visibility = View.GONE
            bottomBar.visibility = View.GONE
            btnExpandTop.visibility = View.GONE
            btnExpandBottom.visibility = View.GONE
            btnPanelHandle.visibility = View.GONE
        }
    }

    /** 收起 / 展开顶部功能栏：收起后只留右上角一个小按钮 */
    private fun setTopBarCollapsed(collapsed: Boolean) {
        isTopBarCollapsed = collapsed
        controlsVisible = true
        applyTopBarVisibility()
        applyBottomBarVisibility()
    }

    /** 收起 / 展开底部滑块区：收起后只留右下角一个小按钮，录制键保留可用 */
    private fun setBottomBarCollapsed(collapsed: Boolean) {
        isBottomBarCollapsed = collapsed
        controlsVisible = true
        applyTopBarVisibility()
        applyBottomBarVisibility()
    }

    private fun applyTopBarVisibility() {
        topBar.visibility = if (isTopBarCollapsed) View.GONE else View.VISIBLE
        btnExpandTop.visibility = if (isTopBarCollapsed) View.VISIBLE else View.GONE
    }

    private fun applyBottomBarVisibility() {
        bottomBar.visibility = View.VISIBLE
        bottomSliders.visibility = if (isBottomBarCollapsed) View.GONE else View.VISIBLE
        btnExpandBottom.visibility = if (isBottomBarCollapsed) View.VISIBLE else View.GONE
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
        // 应用切后台时去掉息屏锁，避免锁屏后继续亮屏耗电
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (isRecording) {
            // Don't stop recording on pause - keep recording in background
        }
        cameraHelper?.close()
    }

    override fun onResume() {
        super.onResume()
        // 只在 helper 已被关闭（onPause/surface销毁/相机出错）时才重建；
        // 不能看 isOpened：相机异步打开中也是 false，重复 startCamera 会让
        // 两个 helper 抢同一摄像头，后开的那个失败 → 黑屏只能重启
        if (cameraHelper == null || cameraHelper!!.isClosed) {
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