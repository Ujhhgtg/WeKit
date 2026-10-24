package dev.ujhhgtg.wekit.ui.animation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES31
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Choreographer
import android.view.TextureView
import dev.ujhhgtg.wekit.ui.animation.ThanosEffect.Companion.supported
import dev.ujhhgtg.wekit.utils.WeLogger
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Telegram 的「灭霸」粒子消散特效 (`org.telegram.ui.Components.ThanosEffect`) 的移植。
 *
 * 全窗口透明 [TextureView], GL 线程上用 transform feedback 双缓冲驱动粒子: 每个粒子 采样位图上一格像素, 从左到右依次被「吹散」并淡出。着色器与物理参数与
 * Telegram 一致。
 *
 * 与原实现的差异:
 * - 只保留「给定位图 + 位置」一种入口, 不依赖 Telegram 的 ChatMessageCell 绘制细节;
 * - 位图可为 HARDWARE 配置, 在 GL 线程上复制为 ARGB_8888 后裁掉全透明边缘再上传, 让粒子预算集中在可见内容上;
 * - GL 初始化/交换失败时置 [supported] = false (Telegram 的 `nothanos`), 调用方回退淡出。
 *
 * 所有动画结束 (或出错/表面销毁) 后调用 [onFinished], 由调用方把本视图移出父布局。
 */
class ThanosEffect(context: Context, private val onFinished: () -> Unit) : TextureView(context) {
    companion object {
        private const val TAG = "ThanosEffect"

        /** GL 路径曾失败过则本进程内不再尝试 */
        @Volatile
        var supported = true
            private set

        private val VERTEX_SHADER =
            """
            #version 300 es
            precision highp float;
            layout(location = 0) in vec2 inUV;
            layout(location = 1) in vec2 inPosition;
            layout(location = 2) in vec2 inVelocity;
            layout(location = 3) in float inTime;
            out vec2 outUV;
            out vec2 outPosition;
            out vec2 outVelocity;
            out float outTime;
            out vec2 uvcenter;
            out vec2 uvsize;
            out float alpha;
            uniform mat3 matrix;
            uniform vec2 rectSize;
            uniform float reset;
            uniform float time;
            uniform float deltaTime;
            uniform vec2 size;
            uniform vec3 gridSize;
            uniform float seed;
            uniform float dp;
            uniform vec2 offset;
            uniform float scale;
            uniform float uvOffset;
            float rand(vec2 n) {
              return fract(sin(dot(n, vec2(12.9898, 4.1414 - seed * .42))) * 43758.5453);
            }
            void main() {
              vec2 uv = inUV;
              vec2 position = inPosition;
              vec2 velocity = inVelocity;
              float particleTime = inTime;
              float id = float(gl_VertexID);
              if (reset > 0.) {
                uv = vec2(mod(id, gridSize.x), floor(id / gridSize.x)) / gridSize.xy;
                position = (matrix * vec3(uv + .5 / gridSize.xy, 1.0)).xy;
                float direction = rand(3. * uv) * (3.14159265 * 2.0);
                velocity = vec2(cos(direction), sin(direction)) * (0.1 + rand(5. * uv) * (0.2 - 0.1)) * 260.0 * scale * dp;
                particleTime = (0.7 + rand(uv) * (1.5 - 0.7)) / 1.15;
              }
              float effectFraction = max(0.0, min(0.35, time)) / 0.35;
              float particleFraction = max(0.0, min(0.2, .1 + time - uv.x * uvOffset)) / 0.2;
              position += velocity * deltaTime * particleFraction;
              velocity += vec2(19.0 * (velocity.x > 0.0 ? 1.0 : -1.0) * (1.0 - effectFraction), -65.0) * deltaTime * dp * particleFraction;
              particleTime = max(0.0, particleTime - 1.2 * deltaTime * particleFraction);
              outUV = uv;
              outPosition = position;
              outVelocity = velocity;
              outTime = particleTime;
              alpha = max(0.0, min(0.55, particleTime) / 0.55);
              gl_PointSize = (gridSize.z + 1.);
              position.y = size.y - position.y;
              gl_Position = vec4((position + offset) / size * 2.0 - vec2(1.0), 0.0, 1.0);
              uvcenter = uv;
              uvsize = (vec2(gridSize.z + 1.) / rectSize.xy);
            }
            """
                .trimIndent()

        private val FRAGMENT_SHADER =
            """
            #version 300 es
            precision highp float;
            in vec2 uvcenter;
            in vec2 uvsize;
            in float alpha;
            out vec4 fragColor;
            uniform sampler2D tex;
            void main() {
              if (alpha <= 0.0) {
                discard;
              }
              fragColor = texture(tex, uvcenter + gl_PointCoord * uvsize).rgba * alpha;
            }
            """
                .trimIndent()

        private const val MAX_PARTICLES = 120_000
        private const val LONGEVITY = 1.5f
        private const val TIME_SCALE = 1.15f
        private const val STRIDE = 28
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var drawThread: DrawingThread? = null
    private val queued = ArrayList<Animation>()
    private var finished = false

    private val frameCallback =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val thread = drawThread ?: return
                thread.requestDraw()
                if (thread.running) Choreographer.getInstance().postFrameCallback(this)
            }
        }

    init {
        isOpaque = false
        surfaceTextureListener =
            object : SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    drawThread?.kill()
                    val thread = DrawingThread(surface, width, height)
                    drawThread = thread
                    queued.forEach(thread::add)
                    queued.clear()
                    scheduleFrames()
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    drawThread?.resize(width, height)
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    drawThread?.kill()
                    drawThread = null
                    finish()
                    // DrawingThread 在退出时自行 release
                    return false
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
    }

    /**
     * 在本视图坐标 ([left], [top]) 处把 [bitmap] 粒子化消散。 [onStart] 在首帧粒子上屏后调用 (此时应隐藏原内容), [onDone]
     * 在动画结束或被中止时调用。
     */
    fun animate(bitmap: Bitmap, left: Float, top: Float, onStart: () -> Unit, onDone: () -> Unit) {
        val animation = Animation(bitmap, left, top, onStart, onDone)
        val thread = drawThread
        if (thread != null) {
            thread.add(animation)
            scheduleFrames()
        } else {
            queued += animation
        }
    }

    private fun scheduleFrames() {
        val choreographer = Choreographer.getInstance()
        choreographer.removeFrameCallback(frameCallback)
        choreographer.postFrameCallback(frameCallback)
    }

    fun kill() {
        queued.forEach { it.finish() }
        queued.clear()
        drawThread?.kill()
        finish()
    }

    private fun finish() = onUiThread {
        if (!finished) {
            finished = true
            onFinished()
        }
    }

    private fun onUiThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private inner class Animation(
        var bitmap: Bitmap?,
        var left: Float,
        var top: Float,
        onStart: () -> Unit,
        onDone: () -> Unit,
    ) {
        var startCallback: (() -> Unit)? = onStart
        var doneCallback: (() -> Unit)? = onDone

        var viewWidth = 0
        var viewHeight = 0
        var time = 0f
        var lastDrawTime = -1L
        var firstDraw = true
        val seed = (Math.random() * 2.0).toFloat()

        var particlesCount = 0
        var gridWidth = 0
        var gridHeight = 0
        var gridSize = 0f
        var currentBuffer = 0
        val texture = IntArray(1)
        val buffer = IntArray(2)
        val glMatrixValues = FloatArray(9)

        val isDead
            get() = time > LONGEVITY + .9f

        /** 在主线程或 GL 线程调用均可; 回调只执行一次且总在主线程 */
        fun start() {
            startCallback?.let {
                startCallback = null
                onUiThread(it)
            }
        }

        fun finish() {
            start()
            doneCallback?.let {
                doneCallback = null
                onUiThread(it)
            }
        }
    }

    private inner class DrawingThread(
        private val surfaceTexture: SurfaceTexture,
        private var width: Int,
        private var height: Int,
    ) : HandlerThread("WeKit.ThanosEffect") {
        @Volatile var running = true

        @Volatile private var alive = true
        private val handler: Handler

        private lateinit var egl: EGL10
        private var eglDisplay: EGLDisplay? = null
        private var eglSurface: EGLSurface? = null
        private var eglContext: EGLContext? = null
        private var program = 0

        private var matrixHandle = 0
        private var resetHandle = 0
        private var timeHandle = 0
        private var deltaTimeHandle = 0
        private var sizeHandle = 0
        private var gridSizeHandle = 0
        private var rectSizeHandle = 0
        private var seedHandle = 0
        private var textureHandle = 0
        private var densityHandle = 0
        private var offsetHandle = 0
        private var scaleHandle = 0
        private var uvOffsetHandle = 0

        private val density = resources.displayMetrics.density
        private val animations = ArrayList<Animation>()
        private val started = ArrayList<Animation>()
        private var drawnAnimations = false

        init {
            start()
            handler = Handler(looper)
            post {
                if (!initGl()) fail()
            }
        }

        fun add(animation: Animation) {
            running = true
            post(onDropped = animation::finish) { addInternal(animation) }
        }

        fun requestDraw() {
            post { draw() }
        }

        fun resize(width: Int, height: Int) {
            post {
                this.width = width
                this.height = height
                GLES31.glViewport(0, 0, width, height)
                GLES31.glUniform2f(sizeHandle, width.toFloat(), height.toFloat())
                draw()
            }
        }

        fun kill() {
            post { killInternal() }
        }

        /** GL 线程已退出时丢弃 [action] 并在主线程调用 [onDropped] */
        private fun post(onDropped: () -> Unit = {}, action: () -> Unit) {
            val posted =
                alive &&
                    handler.post {
                        if (alive) action() else onDropped()
                    }
            if (!posted) onDropped()
        }

        private fun initGl(): Boolean {
            egl = EGLContext.getEGL() as EGL10
            val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            eglDisplay = display
            if (display === EGL10.EGL_NO_DISPLAY || !egl.eglInitialize(display, IntArray(2))) {
                WeLogger.e(TAG, "eglInitialize failed")
                return false
            }
            val configs = arrayOfNulls<EGLConfig>(1)
            val configAttributes =
                intArrayOf(
                    EGL14.EGL_RED_SIZE,
                    8,
                    EGL14.EGL_GREEN_SIZE,
                    8,
                    EGL14.EGL_BLUE_SIZE,
                    8,
                    EGL14.EGL_ALPHA_SIZE,
                    8,
                    EGL14.EGL_RENDERABLE_TYPE,
                    EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_NONE,
                )
            val numConfigs = IntArray(1)
            if (
                !egl.eglChooseConfig(display, configAttributes, configs, 1, numConfigs) ||
                    numConfigs[0] == 0
            ) {
                WeLogger.e(TAG, "eglChooseConfig failed")
                return false
            }
            val config = configs[0]
            val context =
                egl.eglCreateContext(
                    display,
                    config,
                    EGL10.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                )
            if (context == null || context === EGL10.EGL_NO_CONTEXT) {
                WeLogger.e(TAG, "eglCreateContext failed")
                return false
            }
            eglContext = context
            val surface = egl.eglCreateWindowSurface(display, config, surfaceTexture, null)
            if (surface == null || surface === EGL10.EGL_NO_SURFACE) {
                WeLogger.e(TAG, "eglCreateWindowSurface failed")
                return false
            }
            eglSurface = surface
            if (!egl.eglMakeCurrent(display, surface, surface, context)) {
                WeLogger.e(TAG, "eglMakeCurrent failed")
                return false
            }

            val vertexShader = compileShader(GLES31.GL_VERTEX_SHADER, VERTEX_SHADER) ?: return false
            val fragmentShader =
                compileShader(GLES31.GL_FRAGMENT_SHADER, FRAGMENT_SHADER) ?: return false
            program = GLES31.glCreateProgram()
            if (program == 0) {
                WeLogger.e(TAG, "glCreateProgram failed")
                return false
            }
            GLES31.glAttachShader(program, vertexShader)
            GLES31.glAttachShader(program, fragmentShader)
            GLES31.glTransformFeedbackVaryings(
                program,
                arrayOf("outUV", "outPosition", "outVelocity", "outTime"),
                GLES31.GL_INTERLEAVED_ATTRIBS,
            )
            GLES31.glLinkProgram(program)
            val status = IntArray(1)
            GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, status, 0)
            if (status[0] != GLES31.GL_TRUE) {
                WeLogger.e(TAG, "link program error: ${GLES31.glGetProgramInfoLog(program)}")
                return false
            }

            matrixHandle = GLES31.glGetUniformLocation(program, "matrix")
            rectSizeHandle = GLES31.glGetUniformLocation(program, "rectSize")
            resetHandle = GLES31.glGetUniformLocation(program, "reset")
            timeHandle = GLES31.glGetUniformLocation(program, "time")
            deltaTimeHandle = GLES31.glGetUniformLocation(program, "deltaTime")
            sizeHandle = GLES31.glGetUniformLocation(program, "size")
            gridSizeHandle = GLES31.glGetUniformLocation(program, "gridSize")
            textureHandle = GLES31.glGetUniformLocation(program, "tex")
            seedHandle = GLES31.glGetUniformLocation(program, "seed")
            densityHandle = GLES31.glGetUniformLocation(program, "dp")
            offsetHandle = GLES31.glGetUniformLocation(program, "offset")
            scaleHandle = GLES31.glGetUniformLocation(program, "scale")
            uvOffsetHandle = GLES31.glGetUniformLocation(program, "uvOffset")

            GLES31.glViewport(0, 0, width, height)
            GLES31.glDisable(GLES31.GL_BLEND)
            GLES31.glClearColor(0f, 0f, 0f, 0f)
            GLES31.glUseProgram(program)
            GLES31.glUniform2f(sizeHandle, width.toFloat(), height.toFloat())
            return true
        }

        private fun compileShader(type: Int, source: String): Int? {
            val shader = GLES31.glCreateShader(type)
            if (shader == 0) {
                WeLogger.e(TAG, "glCreateShader($type) failed")
                return null
            }
            GLES31.glShaderSource(shader, source)
            GLES31.glCompileShader(shader)
            val status = IntArray(1)
            GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, status, 0)
            if (status[0] != GLES31.GL_TRUE) {
                WeLogger.e(TAG, "compile shader error: ${GLES31.glGetShaderInfoLog(shader)}")
                GLES31.glDeleteShader(shader)
                return null
            }
            return shader
        }

        private fun addInternal(animation: Animation) {
            val source = animation.bitmap!!
            animation.bitmap = null
            val software =
                if (source.config == Bitmap.Config.HARDWARE) {
                    source.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    source
                }
            val cropped = cropTransparentEdges(software, animation)
            if (cropped == null) {
                // 位图完全透明, 没有可消散的内容
                animation.finish()
                return
            }
            GLES31.glGenTextures(1, animation.texture, 0)
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, animation.texture[0])
            GLES31.glTexParameteri(
                GLES31.GL_TEXTURE_2D,
                GLES31.GL_TEXTURE_MIN_FILTER,
                GLES31.GL_LINEAR,
            )
            GLES31.glTexParameteri(
                GLES31.GL_TEXTURE_2D,
                GLES31.GL_TEXTURE_MAG_FILTER,
                GLES31.GL_LINEAR,
            )
            GLES31.glTexParameteri(
                GLES31.GL_TEXTURE_2D,
                GLES31.GL_TEXTURE_WRAP_S,
                GLES31.GL_CLAMP_TO_EDGE,
            )
            GLES31.glTexParameteri(
                GLES31.GL_TEXTURE_2D,
                GLES31.GL_TEXTURE_WRAP_T,
                GLES31.GL_CLAMP_TO_EDGE,
            )
            GLUtils.texImage2D(GLES31.GL_TEXTURE_2D, 0, cropped, 0)
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, 0)
            if (cropped !== source) cropped.recycle()
            if (software !== source && software !== cropped) software.recycle()
            animations += animation
            running = true
        }

        /** 裁掉四周全透明像素并据此修正动画位置; 全透明时返回 null */
        private fun cropTransparentEdges(bitmap: Bitmap, animation: Animation): Bitmap? {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            var minX = w
            var minY = h
            var maxX = -1
            var maxY = -1
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    if (pixels[row + x] ushr 24 != 0) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        maxY = y
                    }
                }
            }
            if (maxX < 0) return null
            animation.left += minX
            animation.top += minY
            animation.viewWidth = maxX - minX + 1
            animation.viewHeight = maxY - minY + 1
            if (minX == 0 && minY == 0 && maxX == w - 1 && maxY == h - 1) return bitmap
            return Bitmap.createBitmap(
                bitmap,
                minX,
                minY,
                animation.viewWidth,
                animation.viewHeight,
            )
        }

        private fun calcParticlesGrid(animation: Animation) {
            val totalHeight = animations.sumOf { it.viewHeight }
            val part = animation.viewHeight.toFloat() / totalHeight
            val p = max(.4f * density, 1f)
            val w = animation.viewWidth
            val h = animation.viewHeight
            // 与 Telegram 的 Utilities.clamp(value, max, min) 相同
            val count =
                min(max((w * h / (p * p)).toInt(), 10), max((MAX_PARTICLES * part).toInt(), 10))
            val aspectRatio = w.toFloat() / h
            var gridHeight = max(sqrt(count / aspectRatio).roundToInt(), 1)
            var gridWidth = max((count.toFloat() / gridHeight).roundToInt(), 1)
            while (gridWidth * gridHeight < count) {
                if (gridWidth.toFloat() / gridHeight < aspectRatio) gridWidth++ else gridHeight++
            }
            animation.gridWidth = gridWidth
            animation.gridHeight = gridHeight
            animation.particlesCount = gridWidth * gridHeight
            animation.gridSize = max(w.toFloat() / gridWidth, h.toFloat() / gridHeight)

            GLES31.glGenBuffers(2, animation.buffer, 0)
            for (i in 0..1) {
                GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, animation.buffer[i])
                GLES31.glBufferData(
                    GLES31.GL_ARRAY_BUFFER,
                    animation.particlesCount * STRIDE,
                    null,
                    GLES31.GL_DYNAMIC_DRAW,
                )
            }

            val matrix = Matrix()
            matrix.postScale(w.toFloat(), h.toFloat())
            matrix.postTranslate(animation.left, animation.top)
            val values = FloatArray(9)
            matrix.getValues(values)
            // android.graphics.Matrix 为行主序, GL 需要列主序
            animation.glMatrixValues[0] = values[0]
            animation.glMatrixValues[1] = values[3]
            animation.glMatrixValues[2] = values[6]
            animation.glMatrixValues[3] = values[1]
            animation.glMatrixValues[4] = values[4]
            animation.glMatrixValues[5] = values[7]
            animation.glMatrixValues[6] = values[2]
            animation.glMatrixValues[7] = values[5]
            animation.glMatrixValues[8] = values[8]
        }

        private fun draw() {
            GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)
            val iterator = animations.iterator()
            while (iterator.hasNext()) {
                val animation = iterator.next()
                if (animation.firstDraw) {
                    calcParticlesGrid(animation)
                    started += animation
                }
                drawnAnimations = true
                drawAnimation(animation)
                if (animation.isDead) {
                    releaseAnimation(animation)
                    animation.finish()
                    iterator.remove()
                }
            }
            running = animations.isNotEmpty()
            while (true) {
                val error = GLES31.glGetError()
                if (error == GLES31.GL_NO_ERROR) break
                WeLogger.e(TAG, "gles error $error")
            }
            if (!egl.eglSwapBuffers(eglDisplay, eglSurface)) {
                WeLogger.e(TAG, "eglSwapBuffers failed: ${egl.eglGetError()}")
                fail()
                return
            }
            started.forEach { it.start() }
            started.clear()
            if (animations.isEmpty() && drawnAnimations) killInternal()
        }

        private fun drawAnimation(animation: Animation) {
            val now = System.nanoTime()
            val deltaTime =
                if (animation.lastDrawTime < 0) 0f else (now - animation.lastDrawTime) / 1e9f
            animation.lastDrawTime = now
            animation.time += deltaTime * TIME_SCALE

            GLES31.glUniformMatrix3fv(matrixHandle, 1, false, animation.glMatrixValues, 0)
            GLES31.glUniform1f(resetHandle, if (animation.firstDraw) 1f else 0f)
            GLES31.glUniform1f(timeHandle, animation.time)
            GLES31.glUniform1f(deltaTimeHandle, deltaTime * TIME_SCALE)
            GLES31.glUniform3f(
                gridSizeHandle,
                animation.gridWidth.toFloat(),
                animation.gridHeight.toFloat(),
                animation.gridSize,
            )
            GLES31.glUniform2f(offsetHandle, 0f, 0f)
            GLES31.glUniform1f(scaleHandle, 1f)
            GLES31.glUniform1f(uvOffsetHandle, .6f)
            GLES31.glUniform2f(
                rectSizeHandle,
                animation.viewWidth.toFloat(),
                animation.viewHeight.toFloat(),
            )
            GLES31.glUniform1f(seedHandle, animation.seed)
            GLES31.glUniform1f(densityHandle, density)

            GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, animation.texture[0])
            GLES31.glUniform1i(textureHandle, 0)

            GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, animation.buffer[animation.currentBuffer])
            GLES31.glVertexAttribPointer(0, 2, GLES31.GL_FLOAT, false, STRIDE, 0)
            GLES31.glEnableVertexAttribArray(0)
            GLES31.glVertexAttribPointer(1, 2, GLES31.GL_FLOAT, false, STRIDE, 8)
            GLES31.glEnableVertexAttribArray(1)
            GLES31.glVertexAttribPointer(2, 2, GLES31.GL_FLOAT, false, STRIDE, 16)
            GLES31.glEnableVertexAttribArray(2)
            GLES31.glVertexAttribPointer(3, 1, GLES31.GL_FLOAT, false, STRIDE, 24)
            GLES31.glEnableVertexAttribArray(3)
            GLES31.glBindBufferBase(
                GLES31.GL_TRANSFORM_FEEDBACK_BUFFER,
                0,
                animation.buffer[1 - animation.currentBuffer],
            )

            GLES31.glBeginTransformFeedback(GLES31.GL_POINTS)
            GLES31.glDrawArrays(GLES31.GL_POINTS, 0, animation.particlesCount)
            GLES31.glEndTransformFeedback()

            GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, 0)
            GLES31.glBindBuffer(GLES31.GL_TRANSFORM_FEEDBACK_BUFFER, 0)

            animation.firstDraw = false
            animation.currentBuffer = 1 - animation.currentBuffer
        }

        private fun releaseAnimation(animation: Animation) {
            if (animation.buffer[0] != 0) GLES31.glDeleteBuffers(2, animation.buffer, 0)
            if (animation.texture[0] != 0) GLES31.glDeleteTextures(1, animation.texture, 0)
        }

        /** 对应 Telegram 写入 `nothanos`: 本进程内后续删除改用淡出 */
        private fun fail() {
            supported = false
            killInternal()
        }

        private fun killInternal() {
            if (!alive) return
            alive = false
            running = false
            animations.forEach {
                releaseAnimation(it)
                it.finish()
            }
            animations.clear()
            started.forEach { it.start() }
            started.clear()
            val display = eglDisplay
            if (display != null) {
                if (program != 0) GLES31.glDeleteProgram(program)
                egl.eglMakeCurrent(
                    display,
                    EGL10.EGL_NO_SURFACE,
                    EGL10.EGL_NO_SURFACE,
                    EGL10.EGL_NO_CONTEXT,
                )
                eglSurface?.let { egl.eglDestroySurface(display, it) }
                eglContext?.let { egl.eglDestroyContext(display, it) }
            }
            surfaceTexture.release()
            finish()
            quit()
        }
    }
}
