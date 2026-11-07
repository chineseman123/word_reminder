package com.teleprompter.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 前置摄像头录像器：摄像头帧 -> GL(前置镜像修正) -> MediaCodec 编码 -> MediaMuxer 封装。
 *
 * 不使用 MediaRecorder：它的 Surface 输入对帧和时间戳是黑盒，插入 GL 转发后
 * 容易出现"只有声音 / 时长错乱"的问题。这里用标准相机录像管线，全部自己控制。
 *
 * 方向处理：用 SurfaceTexture.getTransformMatrix() 把横向 sensor 缓冲区
 * 旋转成正确的竖屏画面，同时它自带前置摄像头的镜像变换；再按需做一次
 * 水平(u轴)翻转，得到"非镜像"（别人看你的视角）的竖屏视频。
 */
class MirrorVideoRecorder(
    outputPath: String,
    private val sensorWidth: Int,
    private val sensorHeight: Int,
    private val outputWidth: Int = sensorHeight,
    private val outputHeight: Int = sensorWidth,
    /** true = 消除前置摄像头左右镜像（录成非镜像，别人看你的视角） */
    private val mirrorHorizontal: Boolean = true
) {

    companion object {
        private const val TAG = "MirrorVideoRecorder"

        private const val FRAME_RATE = 30
        private const val IFRAME_INTERVAL = 1
        private const val VIDEO_BITRATE = 8_000_000

        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_CHANNELS = 1
        private const val AUDIO_BITRATE = 128_000

        private const val EGL_RECORDABLE_ANDROID = 0x3142

        /** 画面已经在 GL 里旋转成竖屏，封装时不再写旋转标记 */
        private const val ORIENTATION_HINT = 0

        // 与预览 BeautyRenderer 共用同一套 shader：美颜参数为 0 时画面与原先完全一致
        private const val VERTEX_SHADER = BeautyRenderer.VERTEX_SHADER
        private const val FRAGMENT_SHADER = BeautyRenderer.FRAGMENT_SHADER

        private val QUAD_COORDS = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val TEX_COORDS = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    }

    // ==================== 编码 / 封装 ====================

    private val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
        setOrientationHint(ORIENTATION_HINT)
    }
    private val muxerLock = Object()
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false

    private val videoCodec: MediaCodec
    private val videoInputSurface: Surface

    private val audioCodec: MediaCodec
    private val audioRecord: AudioRecord

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    // ==================== GL ====================

    private val glThread = HandlerThread("MirrorRecorderGL").apply { start() }
    private val glHandler = Handler(glThread.looper)

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE

    private var textureId = 0
    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var flipHandle = 0
    private var textureHandle = 0
    private var stMatrixHandle = 0
    private var faceHandle = 0
    private var whitenHandle = 0
    private var smoothHandle = 0

    private lateinit var surfaceTexture: android.graphics.SurfaceTexture
    private lateinit var cameraSurface: Surface

    private val stMatrix = FloatArray(16)

    private val quadBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(QUAD_COORDS); position(0) }

    private val texCoordBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(TEX_COORDS); position(0) }

    // mirrorHorizontal=true 时只翻 v 轴消除前置左右镜像。
    // 实测：v 轴管左右（翻转可消镜像），u 轴管上下——u 轴一翻就会上下颠倒，故保持 0
    private val flipVector =
        if (mirrorHorizontal) floatArrayOf(0f, 1f) else floatArrayOf(0f, 0f)

    // ==================== 美颜参数（录制期间可实时调整） ====================

    @Volatile
    private var whiten = 0f

    @Volatile
    private var smooth = 0f

    @Volatile
    private var faceRect = BeautyRenderer.NO_FACE

    /** whiten / smooth: 0.0 ~ 1.0 */
    fun setBeauty(whiten: Float, smooth: Float) {
        this.whiten = whiten.coerceIn(0f, 1f)
        this.smooth = smooth.coerceIn(0f, 1f)
    }

    /** (cx, cy, rx, ry) buffer 纹理坐标；传 BeautyRenderer.NO_FACE 表示无人脸 */
    fun setFaceRect(rect: FloatArray) {
        faceRect = rect
    }

    /** 首帧时间戳作为基准，送进编码器的 PTS 从 0 开始 */
    private var baseTimestampNs = 0L
    private var wallStartNs = 0L

    @Volatile
    private var rendering = false

    @Volatile
    private var stopping = false

    private var started = false
    private var released = false

    /** 摄像头输出到这个 Surface */
    val inputSurface: Surface get() = cameraSurface

    private val framesDrawn: Long get() = frameCount

    @Volatile
    private var frameCount = 0L

    init {
        val videoFormat = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, outputWidth, outputHeight
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
        }
        videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        videoInputSurface = videoCodec.createInputSurface()

        val audioFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord 初始化失败")
        }

        // GL 初始化必须同步完成，摄像机会话创建前就要拿到 Surface
        val latch = CountDownLatch(1)
        glHandler.post {
            try {
                setupGL()
            } catch (e: Exception) {
                Log.e(TAG, "GL setup failed", e)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw IllegalStateException("GL 初始化超时")
        }
        if (!::cameraSurface.isInitialized) {
            throw IllegalStateException("GL 初始化失败")
        }
    }

    // ==================== 对外接口 ====================

    /** 摄像机会话配置完成后调用 */
    fun start() {
        if (started || released) return
        started = true
        stopping = false

        videoCodec.start()
        audioCodec.start()
        audioRecord.startRecording()

        baseTimestampNs = 0L
        wallStartNs = System.nanoTime()

        videoThread = Thread({ drainVideo() }, "MirrorVideoDrain").apply { start() }
        audioThread = Thread({ pumpAudio() }, "MirrorAudioDrain").apply { start() }

        rendering = true
        Log.d(TAG, "Recorder started: out=${outputWidth}x${outputHeight}, sensor=${sensorWidth}x${sensorHeight}")
    }

    /** 停止采集并完成封装 */
    fun stop() {
        if (released) return
        released = true
        stopping = true
        rendering = false

        try {
            if (started) audioRecord.stop()
        } catch (e: Exception) {
            Log.w(TAG, "audioRecord.stop failed", e)
        }

        // 等 GL 线程把手上这一帧画完，再结束编码，避免 EOS 之后还提交帧
        val glIdle = CountDownLatch(1)
        glHandler.post { glIdle.countDown() }
        runCatching { glIdle.await(800, TimeUnit.MILLISECONDS) }

        try {
            if (started) videoCodec.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.w(TAG, "signalEndOfInputStream failed", e)
        }

        videoThread?.let { runCatching { it.join(5000) } }
        audioThread?.let { runCatching { it.join(5000) } }

        try {
            if (started) videoCodec.stop()
        } catch (e: Exception) {
            Log.w(TAG, "videoCodec.stop failed", e)
        }
        runCatching { videoCodec.release() }
        try {
            if (started) audioCodec.stop()
        } catch (e: Exception) {
            Log.w(TAG, "audioCodec.stop failed", e)
        }
        runCatching { audioCodec.release() }
        runCatching { audioRecord.release() }

        synchronized(muxerLock) {
            if (muxerStarted) {
                try {
                    muxer.stop()
                    Log.d(TAG, "Muxer stopped, frames drawn: $frameCount")
                } catch (e: Exception) {
                    Log.e(TAG, "muxer.stop failed", e)
                }
            } else {
                Log.e(TAG, "Muxer never started (no encoded samples)")
            }
            runCatching { muxer.release() }
        }

        releaseGL()
        glThread.quitSafely()
        Log.d(TAG, "Recorder released, frames: $frameCount")
    }

    // ==================== GL ====================

    private fun setupGL() {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                throw IllegalStateException("eglGetDisplay failed")
            }

            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                throw IllegalStateException("eglInitialize failed")
            }

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0) ||
                numConfigs[0] == 0
            ) {
                throw IllegalStateException("eglChooseConfig failed")
            }

            eglContext = EGL14.eglCreateContext(
                eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                throw IllegalStateException("eglCreateContext failed")
            }

            eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, configs[0], videoInputSurface, intArrayOf(EGL14.EGL_NONE), 0
            )
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                throw IllegalStateException("eglCreateWindowSurface failed: ${EGL14.eglGetError()}")
            }

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw IllegalStateException("eglMakeCurrent failed")
            }

            textureId = createExternalTexture()
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            if (program == 0) {
                throw IllegalStateException("createProgram failed")
            }
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            flipHandle = GLES20.glGetUniformLocation(program, "uFlip")
            textureHandle = GLES20.glGetUniformLocation(program, "sTexture")
            stMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
            faceHandle = GLES20.glGetUniformLocation(program, "uFace")
            whitenHandle = GLES20.glGetUniformLocation(program, "uWhiten")
            smoothHandle = GLES20.glGetUniformLocation(program, "uSmooth")

            val texture = android.graphics.SurfaceTexture(textureId).apply {
                setDefaultBufferSize(sensorWidth, sensorHeight)
                setOnFrameAvailableListener(
                    { onFrameAvailable() },
                    glHandler
                )
            }
            surfaceTexture = texture
            cameraSurface = Surface(texture)

            Log.d(TAG, "GL ready, camera surface: $cameraSurface")
        } catch (e: Exception) {
            Log.e(TAG, "GL setup failed", e)
            throw e
        }
    }

    private fun onFrameAvailable() {
        if (!::surfaceTexture.isInitialized) return
        // 录制开始前也要把帧取走，避免 SurfaceTexture 队列塞满导致摄像头停止送帧
        try {
            surfaceTexture.updateTexImage()
        } catch (e: Exception) {
            Log.e(TAG, "updateTexImage failed", e)
            return
        }
        if (!rendering) return
        drawFrame()
    }

    private fun drawFrame() {
        try {
            // 更新后立即读取变换矩阵，把横向 sensor 纹理旋转成竖屏
            surfaceTexture.getTransformMatrix(stMatrix)

            GLES20.glViewport(0, 0, outputWidth, outputHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(textureHandle, 0)
            GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0)
            GLES20.glUniform2f(flipHandle, flipVector[0], flipVector[1])

            val face = faceRect
            GLES20.glUniform4f(faceHandle, face[0], face[1], face[2], face[3])
            GLES20.glUniform1f(whitenHandle, whiten)
            GLES20.glUniform1f(smoothHandle, smooth)

            quadBuffer.position(0)
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)

            texCoordBuffer.position(0)
            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

            // PTS 以首帧为 0 点，绝对时间戳会让播放时长错乱
            val ptsNs: Long
            val ts = surfaceTexture.timestamp
            if (ts > 0) {
                if (baseTimestampNs == 0L) baseTimestampNs = ts
                ptsNs = ts - baseTimestampNs
            } else {
                ptsNs = System.nanoTime() - wallStartNs
            }

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsNs)
            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                Log.w(TAG, "eglSwapBuffers failed: ${EGL14.eglGetError()}")
            } else {
                frameCount++
            }
        } catch (e: Exception) {
            Log.e(TAG, "drawFrame failed", e)
        }
    }

    private fun releaseGL() {
        if (!::surfaceTexture.isInitialized) {
            glThread.quitSafely()
            return
        }
        val latch = CountDownLatch(1)
        glHandler.post {
            try {
                surfaceTexture.setOnFrameAvailableListener(null)
                surfaceTexture.release()
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, eglContext
                    )
                    if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                    if (program != 0) GLES20.glDeleteProgram(program)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    }
                    if (eglContext != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(eglDisplay, eglContext)
                    }
                    EGL14.eglReleaseThread()
                    EGL14.eglTerminate(eglDisplay)
                }
                cameraSurface.release()
            } catch (e: Exception) {
                Log.e(TAG, "releaseGL failed", e)
            } finally {
                latch.countDown()
            }
        }
        runCatching { latch.await(3, TimeUnit.SECONDS) }
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        return textures[0]
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource) ?: return 0
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource) ?: return 0
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vertexShader)
        GLES20.glAttachShader(prog, fragmentShader)
        GLES20.glLinkProgram(prog)

        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Program link error: ${GLES20.glGetProgramInfoLog(prog)}")
            GLES20.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    private fun compileShader(type: Int, source: String): Int? {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile error: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return null
        }
        return shader
    }

    // ==================== 编码线程 ====================

    private fun maybeStartMuxer() {
        if (!muxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
            muxer.start()
            muxerStarted = true
            Log.d(TAG, "Muxer started (video=$videoTrackIndex, audio=$audioTrackIndex)")
        }
    }

    private fun drainVideo() {
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val index = videoCodec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(muxerLock) {
                            videoTrackIndex = muxer.addTrack(videoCodec.outputFormat)
                            maybeStartMuxer()
                        }
                    }
                    index >= 0 -> {
                        val buffer = videoCodec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            synchronized(muxerLock) {
                                if (muxerStarted) {
                                    muxer.writeSampleData(videoTrackIndex, buffer, info)
                                }
                            }
                        }
                        videoCodec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "drainVideo failed", e)
        }
    }

    private fun pumpAudio() {
        val info = MediaCodec.BufferInfo()
        var ptsUs = 0L
        var eosQueued = false

        try {
            while (true) {
                if (!eosQueued) {
                    val inIndex = audioCodec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuffer = audioCodec.getInputBuffer(inIndex)!!
                        inBuffer.clear()
                        val readBytes = try {
                            if (stopping) 0 else audioRecord.read(inBuffer, inBuffer.capacity())
                        } catch (e: Exception) {
                            0
                        }
                        if (readBytes > 0) {
                            audioCodec.queueInputBuffer(inIndex, 0, readBytes, ptsUs, 0)
                            // PCM 16bit 单声道：字节数 / 2 = 采样数
                            ptsUs += (readBytes / 2).toLong() * 1_000_000L / AUDIO_SAMPLE_RATE
                        } else if (stopping) {
                            audioCodec.queueInputBuffer(
                                inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            eosQueued = true
                        } else {
                            audioCodec.queueInputBuffer(inIndex, 0, 0, ptsUs, 0)
                        }
                    }
                }

                while (true) {
                    val outIndex = audioCodec.dequeueOutputBuffer(info, 0)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            synchronized(muxerLock) {
                                audioTrackIndex = muxer.addTrack(audioCodec.outputFormat)
                                maybeStartMuxer()
                            }
                        }
                        outIndex >= 0 -> {
                            val outBuffer = audioCodec.getOutputBuffer(outIndex)
                            if (outBuffer != null && info.size > 0) {
                                outBuffer.position(info.offset)
                                outBuffer.limit(info.offset + info.size)
                                synchronized(muxerLock) {
                                    if (muxerStarted) {
                                        muxer.writeSampleData(audioTrackIndex, outBuffer, info)
                                    }
                                }
                            }
                            audioCodec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "pumpAudio failed", e)
        }
    }
}