package com.teleprompter.app

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.*
import android.hardware.camera2.params.Face
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.TextureView


class CameraHelper(
    private val context: Context,
    private val textureView: TextureView
) {
    private val TAG = "CameraHelper"

    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var videoRecorder: MirrorVideoRecorder? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var isRecording = false
    var isOpened = false
        private set

    private var currentVideoPath: String? = null
    private var frontCameraId: String? = null

    // Sensor video size (always landscape orientation from camera sensor)
    // e.g. 1920x1080 — these are the raw sensor dimensions
    private var sensorWidth = 1920
    private var sensorHeight = 1080

    // Portrait output dimensions (swapped for display/recording in portrait)
    // e.g. 1080x1920 — what the final video looks like when rotated
    val portraitWidth: Int get() = sensorHeight  // 1080
    val portraitHeight: Int get() = sensorWidth  // 1920

    // Zoom control — reduces wide-angle barrel distortion
    // Front cameras typically have ~2-3mm focal length (very wide), causing
    // noticeable face distortion at close range. A 1.3-1.5x zoom crops the
    // edges (where distortion is worst) and simulates a ~4-5mm focal length,
    // giving much more natural-looking close-up video.
    private var zoomRatio = 1.0f  // 1.0 = no zoom (wide angle), 1.5 = moderate zoom
    var maxZoomRatio = 1.0f  // hardware max, public for MainActivity to read
    private var activeArrayRect: android.graphics.Rect? = null  // sensor active array

    // ---- 美颜 ----
    private var beautyRenderer: BeautyRenderer? = null
    private var beautySmooth = 0f
    private var faceDetectMode = CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_OFF
    var faceDetectSupported = false
        private set
    // 部分机型开着人脸检测无法配置相机会话：配置失败一次后置此标志并永久关闭（本次运行）
    private var faceDetectDisabledByFailure = false
    // 美颜渲染器初始化失败后不再反复重试（避免每次开相机/录像都卡 5 秒）
    private var beautyRendererFailed = false
    private var smoothedFace: FloatArray? = null
    private var faceMissCount = 0

    /** 当前是否应在请求里开启人脸检测 */
    private fun faceDetectEnabled() =
        faceDetectMode != CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_OFF &&
            !faceDetectDisabledByFailure

    // Callback for recording state
    var onRecordingStopped: ((String?) -> Unit)? = null

    init {
        startBackgroundThread()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        try {
            backgroundThread?.quitSafely()
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    fun openFrontCamera() {
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            // Find front camera
            frontCameraId = null
            for (id in cameraManager!!.cameraIdList) {
                val chars = cameraManager!!.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontCameraId = id
                    break
                }
            }

            if (frontCameraId == null) {
                Log.e(TAG, "No front camera found on this device")
                Handler(context.mainLooper).post {
                    android.widget.Toast.makeText(context, "未找到前置摄像头", android.widget.Toast.LENGTH_LONG).show()
                }
                return
            }

            // Choose a supported video size (sensor reports landscape dimensions)
            val characteristics = cameraManager!!.getCameraCharacteristics(frontCameraId!!)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val videoSizes = map?.getOutputSizes(MediaRecorder::class.java)

            // Read zoom range and active array for crop-region zoom
            activeArrayRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val maxDigitalZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                ?: 1.0f
            maxZoomRatio = maxDigitalZoom.coerceAtMost(5.0f)  // cap at 5x for safety
            Log.d(TAG, "Active array: ${activeArrayRect}, max digital zoom: ${maxDigitalZoom}")

            // 人脸检测能力：优先 SIMPLE（兼容性最好，只需要人脸框），其次 FULL
            val faceModes = characteristics.get(
                CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES
            ) ?: intArrayOf()
            faceDetectMode = when {
                faceModes.contains(CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_SIMPLE) ->
                    CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_SIMPLE
                faceModes.contains(CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_FULL) ->
                    CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_FULL
                else -> CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_OFF
            }
            faceDetectSupported = faceDetectMode != CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_OFF
            Log.d(TAG, "Face detect modes: ${faceModes.toList()}, using: $faceDetectMode")

            if (!videoSizes.isNullOrEmpty()) {
                // Prefer 1920x1080 (landscape sensor dimensions)
                val preferred = videoSizes.firstOrNull {
                    it.width == 1920 && it.height == 1080
                }
                if (preferred != null) {
                    sensorWidth = preferred.width   // 1920
                    sensorHeight = preferred.height  // 1080
                } else {
                    // Pick largest size that is at most 1920x1080
                    val sorted = videoSizes.sortedByDescending { it.width * it.height }
                    val chosen = sorted.firstOrNull { it.width <= 1920 && it.height <= 1080 }
                        ?: sorted.last()
                    sensorWidth = chosen.width
                    sensorHeight = chosen.height
                }
                Log.d(TAG, "Chosen sensor size: ${sensorWidth}x${sensorHeight} (portrait: ${portraitWidth}x${portraitHeight})")
                Log.d(TAG, "Available sensor sizes: ${videoSizes.map { "${it.width}x${it.height}" }}")
            }

            // Check permission
            if (context.checkSelfPermission(android.Manifest.permission.CAMERA) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "Camera permission not granted")
                return
            }

            openCameraDevice()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open front camera", e)
            Handler(context.mainLooper).post {
                android.widget.Toast.makeText(context, "打开摄像头失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openCameraDevice() {
        try {
            cameraManager?.openCamera(frontCameraId!!, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "Camera opened successfully: ${camera.id}")
                    cameraDevice = camera
                    isOpened = true
                    startPreview()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    camera.close()
                    cameraDevice = null
                    isOpened = false
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera open error: $error")
                    camera.close()
                    cameraDevice = null
                    isOpened = false
                    Handler(context.mainLooper).post {
                        android.widget.Toast.makeText(context, "摄像头打开出错(错误码:$error)", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Exception opening camera device", e)
        }
    }

    private fun startPreview() {
        val device = cameraDevice ?: return
        val viewSurfaceTexture = textureView.surfaceTexture
        if (viewSurfaceTexture == null) {
            Log.w(TAG, "SurfaceTexture not ready")
            return
        }
        try {
            // 预览走美颜 GL 管线；初始化失败则退回系统直送 TextureView（无美颜）
            val previewTarget = tryGetBeautySurface(viewSurfaceTexture) ?: run {
                viewSurfaceTexture.setDefaultBufferSize(sensorWidth, sensorHeight)
                Surface(viewSurfaceTexture)
            }

            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewTarget)
                // Apply zoom to reduce wide-angle distortion
                if (zoomRatio > 1.0f) {
                    set(CaptureRequest.SCALER_CROP_REGION, getCropRegion())
                }
                if (faceDetectEnabled()) {
                    set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceDetectMode)
                }
            }

            device.createCaptureSession(
                listOf(previewTarget),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "Preview session configured OK")
                        captureSession = session
                        try {
                            requestBuilder.build().let { request ->
                                session.setRepeatingRequest(request, captureCallback, backgroundHandler)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start repeating preview", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Preview session configuration FAILED")
                        // 部分机型开着人脸检测无法配置会话：关掉后重试一次
                        if (!faceDetectDisabledByFailure &&
                            faceDetectMode != CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_OFF
                        ) {
                            faceDetectDisabledByFailure = true
                            faceDetectSupported = false
                            Log.w(TAG, "Retrying preview session WITHOUT face detection")
                            startPreview()
                        }
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting preview", e)
        }
    }

    /** 创建（或复用）美颜预览渲染器；返回相机预览目标 Surface，失败返回 null 走降级 */
    private fun tryGetBeautySurface(viewSurfaceTexture: android.graphics.SurfaceTexture): Surface? {
        beautyRenderer?.let { return it.inputSurface }
        if (beautyRendererFailed) return null
        return try {
            // 渲染目标是 TextureView：缓冲尺寸交给系统按 view 尺寸管理，
            // 画面宽高比由 BeautyRenderer 内部按缓冲实际尺寸居中适配（防半屏黑/变形）
            val renderer = BeautyRenderer(viewSurfaceTexture, sensorWidth, sensorHeight)
            renderer.setSmoothing(beautySmooth)
            smoothedFace?.let { renderer.setFaceRect(it) }
            beautyRenderer = renderer
            Log.d(TAG, "Beauty preview renderer created")
            renderer.inputSurface
        } catch (e: Exception) {
            Log.e(TAG, "BeautyRenderer init failed, fallback to direct preview", e)
            beautyRendererFailed = true
            null
        }
    }

    // ---- 人脸检测：把人脸框换算成 buffer 纹理坐标，推给预览/录制两条 GL 管线 ----

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            if (faceDetectMode == CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_OFF) return
            handleFaces(result.get(CaptureResult.STATISTICS_FACES))
        }
    }

    private fun handleFaces(faces: Array<Face>?) {
        val biggest = faces?.maxByOrNull { it.bounds.width() * it.bounds.height() }
        if (biggest == null) {
            // 连续多帧无人脸才清除，避免检测抖动导致美颜忽闪
            if (++faceMissCount >= 8 && smoothedFace != null) {
                smoothedFace = null
                pushFaceRect(BeautyRenderer.NO_FACE)
            }
            return
        }
        faceMissCount = 0

        // face bounds 是 sensor active-array 坐标；输出缓冲区内容 = 裁剪区缩放后的画面，
        // 归一化到裁剪区即得到 shader 采样用的 buffer 纹理坐标
        val crop = getCropRegion()
        val b = biggest.bounds
        val cx = ((b.exactCenterX() - crop.left) / crop.width()).coerceIn(-0.2f, 1.2f)
        val cy = ((b.exactCenterY() - crop.top) / crop.height()).coerceIn(-0.2f, 1.2f)
        // 框放大到约 1.4x/1.5x（半径取宽/高的 0.70/0.75 倍），包住额头和下巴
        val rx = (b.width() * 0.70f / crop.width()).coerceAtMost(0.5f)
        val ry = (b.height() * 0.75f / crop.height()).coerceAtMost(0.5f)

        val prev = smoothedFace
        val out = if (prev == null) {
            floatArrayOf(cx, cy, rx, ry)
        } else {
            // 指数平滑，减少人脸框抖动
            floatArrayOf(
                prev[0] + (cx - prev[0]) * 0.4f,
                prev[1] + (cy - prev[1]) * 0.4f,
                prev[2] + (rx - prev[2]) * 0.4f,
                prev[3] + (ry - prev[3]) * 0.4f
            )
        }
        smoothedFace = out
        pushFaceRect(out)
    }

    private fun pushFaceRect(rect: FloatArray) {
        beautyRenderer?.setFaceRect(rect)
        videoRecorder?.setFaceRect(rect)
    }

    /** 设置磨皮强度（0~1）。预览和录制同时生效，录制中也可实时调整 */
    fun setSmoothing(smooth: Float) {
        beautySmooth = smooth.coerceIn(0f, 1f)
        beautyRenderer?.setSmoothing(beautySmooth)
        videoRecorder?.setSmoothing(beautySmooth)
    }

    fun updatePreview() {
        if (cameraDevice != null && isOpened) {
            startPreview()
        }
    }

    /**
     * Set zoom ratio. 1.0 = no zoom (wide angle), 1.5 = moderate zoom.
     * Higher zoom reduces wide-angle distortion but crops more of the frame.
     * Recommended range: 1.2 - 1.5 for natural-looking portrait video.
     */
    fun setZoom(ratio: Float) {
        zoomRatio = ratio.coerceIn(1.0f, maxZoomRatio)
        Log.d(TAG, "Zoom set to ${zoomRatio}x")
        // Restart preview to apply new zoom
        if (isOpened && !isRecording) {
            startPreview()
        }
    }

    fun getZoom(): Float = zoomRatio
    fun getMaxZoom(): Float = maxZoomRatio

    /**
     * Calculate the crop region for the current zoom ratio.
     * Zoom works by cropping the sensor active array — a smaller crop = more zoom.
     * This is equivalent to increasing focal length (reducing wide-angle distortion).
     */
    private fun getCropRegion(): android.graphics.Rect {
        val array = activeArrayRect ?: return android.graphics.Rect(0, 0, sensorWidth, sensorHeight)
        if (zoomRatio <= 1.0f) return android.graphics.Rect(array)

        val w = (array.width() / zoomRatio).toInt()
        val h = (array.height() / zoomRatio).toInt()
        val left = (array.width() - w) / 2 + array.left
        val top = (array.height() - h) / 2 + array.top
        return android.graphics.Rect(left, top, left + w, top + h)
    }

    fun startRecording(outputPath: String): Boolean {
        if (cameraDevice == null || !isOpened) {
            Log.e(TAG, "Camera not opened, cannot record")
            Handler(context.mainLooper).post {
                android.widget.Toast.makeText(context, "摄像头未就绪", android.widget.Toast.LENGTH_SHORT).show()
            }
            return false
        }

        if (isRecording) {
            Log.w(TAG, "Already recording")
            return false
        }

        try {
            // Close existing preview session
            captureSession?.close()
            captureSession = null

            val viewSurfaceTexture = textureView.surfaceTexture
            if (viewSurfaceTexture == null) {
                Log.e(TAG, "Preview surface unavailable")
                return false
            }
            // 录制期间预览同样走美颜 GL 管线（失败退回直送）
            val previewSurface = tryGetBeautySurface(viewSurfaceTexture) ?: run {
                viewSurfaceTexture.setDefaultBufferSize(sensorWidth, sensorHeight)
                Surface(viewSurfaceTexture)
            }

            // 首选：MediaCodec + MediaMuxer 标准录像管线，可在 GL 中翻转画面
            val mirror = try {
                MirrorVideoRecorder(
                    outputPath = outputPath,
                    sensorWidth = sensorWidth,
                    sensorHeight = sensorHeight,
                    // getTransformMatrix 采样出的录制画面本身就是正确方向（上下对+左右非镜像），
                    // 无需任何翻转，故传 false（实测：翻转任一轴都会把上下或左右弄反）
                    mirrorHorizontal = false
                ).also {
                    videoRecorder = it
                    // 录制管线同步当前磨皮参数和人脸位置
                    it.setSmoothing(beautySmooth)
                    smoothedFace?.let { face -> it.setFaceRect(face) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "MirrorVideoRecorder init failed, fallback to MediaRecorder", e)
                videoRecorder = null
                null
            }

            val recorder: MediaRecorder?
            val recordTarget: Surface
            if (mirror != null) {
                recorder = null
                mediaRecorder = null
                recordTarget = mirror.inputSurface
                Log.d(TAG, "Using MediaCodec recorder with mirror correction")
            } else {
                recorder = createLegacyRecorder(outputPath)
                mediaRecorder = recorder
                recordTarget = recorder.surface
                Handler(context.mainLooper).post {
                    android.widget.Toast.makeText(
                        context,
                        "镜像录制初始化失败，已改用普通录制",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }

            // Create capture session with both surfaces
            val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                addTarget(recordTarget)
                // Apply zoom during recording too
                if (zoomRatio > 1.0f) {
                    set(CaptureRequest.SCALER_CROP_REGION, getCropRegion())
                }
                if (faceDetectEnabled()) {
                    set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceDetectMode)
                }
            }

            val recordSurfaces = listOf(previewSurface, recordTarget)
            cameraDevice?.createCaptureSession(
                recordSurfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            captureRequestBuilder.build().let { request ->
                                session.setRepeatingRequest(request, captureCallback, backgroundHandler)
                            }
                            // Start recording
                            Handler(context.mainLooper).post {
                                try {
                                    if (mirror != null) {
                                        mirror.start()
                                    } else {
                                        recorder!!.start()
                                    }
                                    isRecording = true
                                    currentVideoPath = outputPath
                                    Log.d(TAG, "Recording started successfully: $outputPath")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to start recorder", e)
                                    Handler(context.mainLooper).post {
                                        android.widget.Toast.makeText(context, "录制启动失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to set repeating request for recording", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Recording session configuration FAILED")
                        // 部分机型开着人脸检测无法配置录像会话：关掉人脸检测重试一次
                        if (!faceDetectDisabledByFailure &&
                            faceDetectMode != CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_OFF
                        ) {
                            faceDetectDisabledByFailure = true
                            faceDetectSupported = false
                            Log.w(TAG, "Retrying record session WITHOUT face detection")
                            captureRequestBuilder.set(
                                CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                                CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_OFF
                            )
                            try {
                                cameraDevice?.createCaptureSession(recordSurfaces, this, backgroundHandler)
                            } catch (e: Exception) {
                                Log.e(TAG, "Retry record session failed", e)
                            }
                            return
                        }
                        // 彻底失败：释放录像器并通知界面恢复状态
                        isRecording = false
                        runCatching { videoRecorder?.stop() }
                        videoRecorder = null
                        runCatching { mediaRecorder?.release() }
                        mediaRecorder = null
                        Handler(context.mainLooper).post {
                            android.widget.Toast.makeText(context, "录制会话配置失败，请重试", android.widget.Toast.LENGTH_LONG).show()
                            onRecordingStopped?.invoke(null)
                        }
                    }
                },
                backgroundHandler
            )

            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            Handler(context.mainLooper).post {
                android.widget.Toast.makeText(context, "录制启动异常: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
            return false
        }
    }

    private fun createLegacyRecorder(outputPath: String): MediaRecorder {
        val recorder = MediaRecorder()
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setOutputFile(outputPath)
        recorder.setVideoEncodingBitRate(8 * 1024 * 1024)
        recorder.setVideoFrameRate(30)
        recorder.setVideoSize(sensorWidth, sensorHeight)
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioEncodingBitRate(128000)
        recorder.setAudioSamplingRate(44100)
        recorder.setOrientationHint(270)
        recorder.prepare()
        Log.d(TAG, "Legacy MediaRecorder prepared: ${sensorWidth}x${sensorHeight}")
        return recorder
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        val savedPath = currentVideoPath
        val recorder = videoRecorder
        val legacy = mediaRecorder
        videoRecorder = null
        mediaRecorder = null

        // 结束编码、封装、写相册都比较耗时，放后台线程，避免主线程卡住
        backgroundHandler?.post {
            // 先关掉录制会话，避免摄像头继续往即将释放的 Surface 送帧
            try {
                captureSession?.close()
                captureSession = null
            } catch (e: Exception) {
                Log.w(TAG, "Error closing recording session", e)
            }

            try {
                recorder?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping mirror recorder", e)
            }

            try {
                legacy?.apply {
                    stop()
                    release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping mediaRecorder", e)
                try {
                    legacy?.release()
                } catch (_: Exception) {}
            }

            if (savedPath != null) {
                saveVideoToGallery(savedPath)
            }

            Handler(context.mainLooper).post {
                try {
                    startPreview()
                } catch (e: Exception) {
                    Log.e(TAG, "Error restarting preview after recording", e)
                }
                Log.d(TAG, "Recording stopped. File: $savedPath")
                onRecordingStopped?.invoke(savedPath)
            }
        }
    }

    private fun saveVideoToGallery(filePath: String) {
        try {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "Video file does not exist: $filePath")
                return
            }
            if (file.length() <= 0L) {
                Log.e(TAG, "Video file is empty: $filePath")
                Handler(context.mainLooper).post {
                    android.widget.Toast.makeText(context, "录制失败，视频文件为空", android.widget.Toast.LENGTH_LONG).show()
                }
                return
            }

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.TITLE, file.name)
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
                put(MediaStore.Video.Media.SIZE, file.length())
            }

            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    java.io.FileInputStream(file).use { input ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Video saved to gallery: $uri")
                Handler(context.mainLooper).post {
                    android.widget.Toast.makeText(context, "视频已保存到相册", android.widget.Toast.LENGTH_LONG).show()
                }
            } else {
                Log.e(TAG, "Failed to insert video into MediaStore")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving video to gallery", e)
            Handler(context.mainLooper).post {
                android.widget.Toast.makeText(context, "视频保存到相册失败，文件在: $filePath", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun close() {
        try {
            if (isRecording) {
                stopRecording()
            }
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            isOpened = false
            videoRecorder = null
            mediaRecorder?.release()
            mediaRecorder = null
            beautyRenderer?.release()
            beautyRenderer = null
            smoothedFace = null
            faceMissCount = 0
            stopBackgroundThread()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
    }
}