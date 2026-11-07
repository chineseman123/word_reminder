package com.teleprompter.app

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
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
 * 预览美颜渲染器：相机帧 -> OES 纹理 -> 美颜 shader -> TextureView。
 *
 * 预览画面保持镜像（自拍镜子的视角，与系统直送 TextureView 的行为一致）；
 * 录制走 MirrorVideoRecorder（非镜像），两者共用同一套美颜 shader。
 *
 * 人脸识别框由 CameraHelper 通过 Camera2 STATISTICS_FACES 提供，
 * 坐标为 buffer 纹理坐标（已按 zoom 裁剪区归一化），直接传给 shader。
 */
class BeautyRenderer(
    viewSurfaceTexture: SurfaceTexture,
    private val sensorWidth: Int,
    private val sensorHeight: Int
) {

    companion object {
        private const val TAG = "BeautyRenderer"

        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        // uSTMatrix 负责旋转竖屏/方向修正；uFlip 按需做水平翻转（预览镜像用）。
        // uFace: (cx, cy, rx, ry) 人脸椭圆，buffer 纹理坐标系；rx<=0 表示未检测到人脸。
        // 磨皮只做颜色相近加权的小核模糊（保边缘），且严格限制在人脸椭圆内 —— 背景绝不被虚化。
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying highp vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            uniform highp mat4 uSTMatrix;
            uniform highp vec2 uFlip;
            uniform highp vec4 uFace;
            uniform float uWhiten;
            uniform float uSmooth;

            // 8 邻域采样，颜色与中心差异越小权重越大（类双边滤波，保住五官/背景边缘）
            vec3 smoothSample(vec2 uv, float radius) {
                vec3 base = texture2D(sTexture, uv).rgb;
                vec3 sum = base;
                float wsum = 1.0;
                vec3 c;
                float w;
                c = texture2D(sTexture, uv + vec2(radius, 0.0)).rgb;
                w = max(0.0, 1.0 - dot(abs(c - base), vec3(5.0))); sum += c * w; wsum += w;
                c = texture2D(sTexture, uv - vec2(radius, 0.0)).rgb;
                w = max(0.0, 1.0 - dot(abs(c - base), vec3(5.0))); sum += c * w; wsum += w;
                c = texture2D(sTexture, uv + vec2(0.0, radius)).rgb;
                w = max(0.0, 1.0 - dot(abs(c - base), vec3(5.0))); sum += c * w; wsum += w;
                c = texture2D(sTexture, uv - vec2(0.0, radius)).rgb;
                w = max(0.0, 1.0 - dot(abs(c - base), vec3(5.0))); sum += c * w; wsum += w;
                c = texture2D(sTexture, uv + vec2(radius, radius)).rgb;
                w = max(0.0, 0.7 - dot(abs(c - base), vec3(4.0))); sum += c * w; wsum += w;
                c = texture2D(sTexture, uv - vec2(radius, radius)).rgb;
                w = max(0.0, 0.7 - dot(abs(c - base), vec3(4.0))); sum += c * w; wsum += w;
                c = texture2D(sTexture, uv + vec2(radius, -radius)).rgb;
                w = max(0.0, 0.7 - dot(abs(c - base), vec3(4.0))); sum += c * w; wsum += w;
                c = texture2D(sTexture, uv - vec2(radius, -radius)).rgb;
                w = max(0.0, 0.7 - dot(abs(c - base), vec3(4.0))); sum += c * w; wsum += w;
                return sum / wsum;
            }

            void main() {
                vec2 tc = (uSTMatrix * vec4(vTexCoord, 0.0, 1.0)).xy;
                vec2 uv = vec2(abs(uFlip.x - tc.x), abs(uFlip.y - tc.y));
                vec4 color = texture2D(sTexture, uv);

                // 人脸椭圆遮罩（uv 与 uFace 同在 buffer 纹理坐标系，椭圆随画面旋转自动对齐）
                bool hasFace = uFace.z > 0.0001;
                float mask = 1.0;
                if (hasFace) {
                    vec2 d = (uv - uFace.xy) / uFace.zw;
                    mask = 1.0 - smoothstep(0.64, 1.0, dot(d, d));
                }

                // 磨皮（淡化痘痘/黑头）：只在人脸区域内生效，背景绝不动
                if (uSmooth > 0.001 && hasFace && mask > 0.001) {
                    vec3 blurred = smoothSample(uv, 0.003 + 0.007 * uSmooth);
                    color.rgb = mix(color.rgb, blurred, clamp(uSmooth * mask, 0.0, 1.0));
                }

                // 美白：检测到人脸只美白人脸；检测不到时全局提亮（可手动调）
                if (uWhiten > 0.001) {
                    float w = uWhiten * (hasFace ? mask : 1.0);
                    if (w > 0.001) {
                        vec3 c0 = color.rgb;
                        vec3 c1 = c0 + (vec3(1.0) - c0) * (0.40 * w) + vec3(0.05 * w);
                        color.rgb = clamp(mix(c0, c1, w), 0.0, 1.0);
                    }
                }

                gl_FragColor = color;
            }
        """

        private val QUAD_COORDS = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val TEX_COORDS = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        /** 无人脸时传的占位值 */
        val NO_FACE = floatArrayOf(0f, 0f, 0f, 0f)
    }

    private val glThread = HandlerThread("BeautyPreviewGL").apply { start() }
    private val glHandler = Handler(glThread.looper)

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var surfaceWidth = 0
    private var surfaceHeight = 0

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

    private lateinit var surfaceTexture: SurfaceTexture
    private lateinit var cameraSurface: Surface
    private val viewSurface: Surface = Surface(viewSurfaceTexture)

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

    // 预览保持镜像（自拍视角）。uv.x = abs(1 - tc.x) 即水平翻转
    private val flipVector = floatArrayOf(1f, 0f)

    @Volatile
    private var whiten = 0f

    @Volatile
    private var smooth = 0f

    @Volatile
    private var faceRect = NO_FACE

    @Volatile
    private var released = false

    /** 摄像头输出到这个 Surface */
    val inputSurface: Surface get() = cameraSurface

    init {
        val latch = CountDownLatch(1)
        var initError: Exception? = null
        glHandler.post {
            try {
                setupGL()
            } catch (e: Exception) {
                Log.e(TAG, "GL setup failed", e)
                initError = e
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw IllegalStateException("BeautyRenderer GL 初始化超时")
        }
        initError?.let { throw it }
    }

    // ==================== 对外接口 ====================

    /** whiten / smooth: 0.0 ~ 1.0 */
    fun setBeauty(whiten: Float, smooth: Float) {
        this.whiten = whiten.coerceIn(0f, 1f)
        this.smooth = smooth.coerceIn(0f, 1f)
    }

    /** (cx, cy, rx, ry) buffer 纹理坐标；传 NO_FACE 表示无人脸 */
    fun setFaceRect(rect: FloatArray) {
        faceRect = rect
    }

    fun release() {
        if (released) return
        released = true
        val latch = CountDownLatch(1)
        glHandler.post {
            try {
                if (::surfaceTexture.isInitialized) {
                    surfaceTexture.setOnFrameAvailableListener(null)
                    surfaceTexture.release()
                }
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
                if (::cameraSurface.isInitialized) cameraSurface.release()
                viewSurface.release()
            } catch (e: Exception) {
                Log.e(TAG, "release failed", e)
            } finally {
                latch.countDown()
            }
        }
        runCatching { latch.await(3, TimeUnit.SECONDS) }
        glThread.quitSafely()
    }

    // ==================== GL 内部 ====================

    private fun setupGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw IllegalStateException("eglGetDisplay failed")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw IllegalStateException("eglInitialize failed")
        }

        val configAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttrs, 0, configs, 0, 1, numConfigs, 0)) {
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
            eglDisplay, configs[0], viewSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw IllegalStateException("eglCreateWindowSurface failed: ${EGL14.eglGetError()}")
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw IllegalStateException("eglMakeCurrent failed")
        }

        val w = IntArray(1)
        val h = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, h, 0)
        surfaceWidth = w[0]
        surfaceHeight = h[0]

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

        val texture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(sensorWidth, sensorHeight)
            setOnFrameAvailableListener({ onFrameAvailable() }, glHandler)
        }
        surfaceTexture = texture
        cameraSurface = Surface(texture)

        Log.d(TAG, "Beauty preview GL ready, surface=${surfaceWidth}x${surfaceHeight}")
    }

    private fun onFrameAvailable() {
        if (released || !::surfaceTexture.isInitialized) return
        try {
            surfaceTexture.updateTexImage()
        } catch (e: Exception) {
            Log.e(TAG, "updateTexImage failed", e)
            return
        }
        drawFrame()
    }

    private fun drawFrame() {
        try {
            surfaceTexture.getTransformMatrix(stMatrix)

            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
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

            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                Log.w(TAG, "eglSwapBuffers failed: ${EGL14.eglGetError()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "drawFrame failed", e)
        }
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
}
