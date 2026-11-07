package com.teleprompter.app

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.*
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
        val surfaceTexture = textureView.surfaceTexture
        if (surfaceTexture == null) {
            Log.w(TAG, "SurfaceTexture not ready")
            return
        }
        try {
            // Set buffer size to match sensor dimensions for correct aspect ratio
            surfaceTexture.setDefaultBufferSize(sensorWidth, sensorHeight)

            val surface = Surface(surfaceTexture)

            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                // Apply zoom to reduce wide-angle distortion
                if (zoomRatio > 1.0f) {
                    set(CaptureRequest.SCALER_CROP_REGION, getCropRegion())
                }
            }

            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "Preview session configured OK")
                        captureSession = session
                        try {
                            requestBuilder.build().let { request ->
                                session.setRepeatingRequest(request, null, backgroundHandler)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start repeating preview", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Preview session configuration FAILED")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting preview", e)
        }
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

            // Setup MediaRecorder
            val recorder = MediaRecorder()
            mediaRecorder = recorder

            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(outputPath)
            recorder.setVideoEncodingBitRate(8 * 1024 * 1024) // 8 Mbps for 1080p
            recorder.setVideoFrameRate(30)
            // Must use sensor dimensions (landscape) for setVideoSize
            // The orientation hint tells the player to display as portrait
            recorder.setVideoSize(sensorWidth, sensorHeight)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(128000)
            recorder.setAudioSamplingRate(44100)
            // Front camera portrait = 270 degrees
            recorder.setOrientationHint(270)

            recorder.prepare()
            Log.d(TAG, "MediaRecorder prepared, sensor: ${sensorWidth}x${sensorHeight}, portrait hint: 270°")

            val surfaceTexture = textureView.surfaceTexture!!
            surfaceTexture.setDefaultBufferSize(sensorWidth, sensorHeight)
            val previewSurface = Surface(surfaceTexture)

            // 录制直接写入 MediaRecorder 的 Surface（保持原有稳定路径）
            val recordTarget: Surface = recorder.surface

            // Create capture session with both surfaces
            val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                addTarget(recordTarget)
                // Apply zoom during recording too
                if (zoomRatio > 1.0f) {
                    set(CaptureRequest.SCALER_CROP_REGION, getCropRegion())
                }
            }

            cameraDevice?.createCaptureSession(
                listOf(previewSurface, recordTarget),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            captureRequestBuilder.build().let { request ->
                                session.setRepeatingRequest(request, null, backgroundHandler)
                            }
                            // Start recording
                            Handler(context.mainLooper).post {
                                try {
                                    recorder.start()
                                    isRecording = true
                                    currentVideoPath = outputPath
                                    Log.d(TAG, "Recording started successfully: $outputPath")
                                } catch (e: Exception) {
                                    Log.e(TAG, "mediaRecorder.start() failed", e)
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
                        Handler(context.mainLooper).post {
                            android.widget.Toast.makeText(context, "录制会话配置失败", android.widget.Toast.LENGTH_LONG).show()
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

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        val savedPath = currentVideoPath

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping mediaRecorder", e)
            try {
                mediaRecorder?.release()
                mediaRecorder = null
            } catch (_: Exception) {}
        }

        // Save to gallery so it's visible in album
        if (savedPath != null) {
            saveVideoToGallery(savedPath)
        }

        // Restart preview
        try {
            startPreview()
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting preview after recording", e)
        }

        Log.d(TAG, "Recording stopped. File: $savedPath")

        // Notify callback
        onRecordingStopped?.invoke(savedPath)
    }

    private fun saveVideoToGallery(filePath: String) {
        try {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "Video file does not exist: $filePath")
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
            mediaRecorder?.release()
            mediaRecorder = null
            stopBackgroundThread()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
    }
}