package com.teleprompter.app

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
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
 * 录制镜像纠正通道。
 *
 * 不少机型的前置摄像头在 HAL 层直接输出镜像画面，MediaRecorder 拿到什么像素就编什么像素，
 * Camera2 本身没有「关闭镜像」的开关。这里在摄像头和编码器之间插入一层 GL：
 *
 *   摄像头 -> SurfaceTexture(OES 纹理) -> GL 采样翻转 -> MediaRecorder 编码 Surface
 *
 * 音频采集、MP4 封装、朝向元数据仍然交给 MediaRecorder，只替换掉「摄像头直连编码器」这一环。
 */
class MirrorRecorder(
    private val encoderSurface: Surface,
    private val width: Int,
    private val height: Int,
    flipVertical: Boolean = true
) {
    private val TAG = "MirrorRecorder"

    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE

    private var surfaceTexture: SurfaceTexture? = null
    private var outputSurface: Surface? = null
    private var textureId = 0
    private var program = 0

    private var positionHandle = 0
    private var texCoordHandle = 0
    private var flipHandle = 0
    private var textureHandle = 0

    /** 首帧时间戳做基准，送进编码器的 PTS 从 0 开始 */
    private var baseTimestampNs = 0L
    private var wallStartNs = 0L

    /** 采样坐标翻转方向：纵向翻转对应竖屏输出下画面左右镜像 */
    private val flipVector =
        if (flipVertical) floatArrayOf(0f, 1f) else floatArrayOf(1f, 0f)

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

    @Volatile
    private var rendering = false

    private var setupSucceeded = false

    /** 摄像头可以挂到这个 Surface 上取帧 */
    val inputSurface: Surface?
        get() = outputSurface

    val isReady: Boolean
        get() = setupSucceeded

    init {
        glThread = HandlerThread("MirrorRecorderGL").apply { start() }
        glHandler = Handler(glThread!!.looper)
        val latch = CountDownLatch(1)
        glHandler?.post {
            setup()
            latch.countDown()
        }
        if (!latch.await(3, TimeUnit.SECONDS)) {
            Log.e(TAG, "GL setup timeout")
        }
    }

    /** 必须在 MediaRecorder.start() 之后调用，编码器启动后才往它的 Surface 送帧 */
    fun startRendering() {
        rendering = true
    }

    fun stop() {
        rendering = false
        val latch = CountDownLatch(1)
        glHandler?.post {
            setupSucceeded = false
            releaseGL()
            latch.countDown()
        }
        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        glThread?.quitSafely()
        try {
            glThread?.join(500)
        } catch (_: InterruptedException) {
        }
        glThread = null
        glHandler = null
    }

    private fun onFrameAvailable() {
        val st = surfaceTexture ?: return
        // 即使还没开始录制也要把帧取出来，保证缓冲区队列不被塞满
        try {
            st.updateTexImage()
        } catch (e: Exception) {
            Log.e(TAG, "updateTexImage failed", e)
            return
        }
        if (!rendering) return
        drawFrame(st)
    }

    private fun setup() {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                Log.e(TAG, "eglGetDisplay failed")
                return
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                Log.e(TAG, "eglInitialize failed")
                return
            }

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            val chosen = EGL14.eglChooseConfig(
                eglDisplay, EGL_CONFIG_ATTRIBS, 0, configs, 0, configs.size, numConfigs, 0
            )
            if (!chosen || numConfigs[0] <= 0 || configs[0] == null) {
                Log.e(TAG, "eglChooseConfig failed")
                return
            }
            val config = configs[0]!!

            eglContext = EGL14.eglCreateContext(
                eglDisplay, config, EGL14.EGL_NO_CONTEXT, EGL_CONTEXT_ATTRIBS, 0
            )
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                Log.e(TAG, "eglCreateContext failed: ${EGL14.eglGetError()}")
                return
            }

            eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, config, encoderSurface, EGL_SURFACE_ATTRIBS, 0
            )
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "eglCreateWindowSurface failed: ${EGL14.eglGetError()}")
                return
            }

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                Log.e(TAG, "eglMakeCurrent failed: ${EGL14.eglGetError()}")
                return
            }

            textureId = createExternalTexture()
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            if (program == 0) {
                Log.e(TAG, "createProgram failed")
                return
            }
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            flipHandle = GLES20.glGetUniformLocation(program, "uFlip")
            textureHandle = GLES20.glGetUniformLocation(program, "sTexture")

            val texture = SurfaceTexture(textureId).apply {
                setDefaultBufferSize(width, height)
                setOnFrameAvailableListener(
                    SurfaceTexture.OnFrameAvailableListener { onFrameAvailable() },
                    glHandler
                )
            }
            surfaceTexture = texture
            outputSurface = Surface(texture)
            setupSucceeded = true
            Log.d(TAG, "GL recorder ready: ${width}x$height, flip=$flipVector")
        } catch (e: Exception) {
            Log.e(TAG, "GL setup exception", e)
            releaseGL()
        }
    }

    private fun drawFrame(st: SurfaceTexture) {
        try {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(textureHandle, 0)
            GLES20.glUniform2f(flipHandle, flipVector[0], flipVector[1])

            quadBuffer.position(0)
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(
                positionHandle, 2, GLES20.GL_FLOAT, false, 0, quadBuffer
            )

            texCoordBuffer.position(0)
            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(
                texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer
            )

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

            // PTS 必须以首帧为 0 点，直接送开机时间会让编码器算出的时长完全不对
            val ptsNs: Long
            val ts = st.timestamp
            if (ts > 0) {
                if (baseTimestampNs == 0L) baseTimestampNs = ts
                ptsNs = ts - baseTimestampNs
            } else {
                val now = System.nanoTime()
                if (wallStartNs == 0L) wallStartNs = now
                ptsNs = now - wallStartNs
            }
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsNs)
            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                Log.w(TAG, "eglSwapBuffers failed: ${EGL14.eglGetError()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "drawFrame exception", e)
        }
    }

    private fun releaseGL() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY &&
            EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT
        ) {
            if (textureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                textureId = 0
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
        }

        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release()
        surfaceTexture = null
        outputSurface?.release()
        outputSurface = null

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
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
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "program link failed: ${GLES20.glGetProgramInfoLog(prog)}")
            GLES20.glDeleteProgram(prog)
            return 0
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return prog
    }

    private fun compileShader(type: Int, source: String): Int? {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return null
        }
        return shader
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142

        val EGL_CONFIG_ATTRIBS = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )

        val EGL_CONTEXT_ATTRIBS = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )

        val EGL_SURFACE_ATTRIBS = intArrayOf(EGL14.EGL_NONE)

        // 裁剪空间坐标（左下 -> 右下 -> 左上 -> 右上）
        val QUAD_COORDS = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )

        // 纹理坐标（y 轴向下）
        val TEX_COORDS = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )

        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        // uFlip 取 (0,1) 时纵向翻转，(1,0) 时横向翻转
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            uniform vec2 uFlip;
            void main() {
                vec2 uv = abs(uFlip - vTexCoord);
                gl_FragColor = texture2D(sTexture, uv);
            }
        """
    }
}
