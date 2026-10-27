package app.framelink.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** 原生取景渲染：仅保留最新帧，纹理上传完成后复用解码位图 */
class LiveViewSurface(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer {
    private val pending = AtomicReference<ByteArray?>()
    private var program = 0
    private var texture = 0
    private var bitmap: Bitmap? = null
    private var width = 1
    private var height = 1
    @Volatile var zebra = false
    @Volatile var peaking = false
    var histogram: ((IntArray) -> Unit)? = null
    private var lastAnalysis = 0L
    private val vertices =
        ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
            position(0)
        }

    init {
        setEGLContextClientVersion(2)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
        keepScreenOn = true
    }

    fun submit(frame: ByteArray) {
        pending.set(frame)
        requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        fun shader(type: Int, code: String): Int =
            GLES20.glCreateShader(type).also {
                GLES20.glShaderSource(it, code)
                GLES20.glCompileShader(it)
            }
        val vertex =
            shader(
                GLES20.GL_VERTEX_SHADER,
                "attribute vec2 p;attribute vec2 t;varying vec2 uv;void main(){gl_Position=vec4(p,0.,1.);uv=t;}",
            )
        val fragment =
            shader(
                GLES20.GL_FRAGMENT_SHADER,
                "precision mediump float;uniform sampler2D image;uniform vec2 pixel;uniform float zebra;uniform float peak;varying vec2 uv;void main(){vec3 c=texture2D(image,uv).rgb;float l=dot(c,vec3(.299,.587,.114));float e=length(c-texture2D(image,uv+pixel).rgb);if(peak>.5&&e>.24)c=mix(c,vec3(1.,.35,.25),.8);if(zebra>.5&&l>.95&&mod(gl_FragCoord.x+gl_FragCoord.y,12.)<6.)c=vec3(.85,.35,.3);gl_FragColor=vec4(c,1.);}",
            )
        program =
            GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vertex)
                GLES20.glAttachShader(it, fragment)
                GLES20.glLinkProgram(it)
            }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(.055f, .063f, .078f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val frame = pending.getAndSet(null)
        if (frame != null) {
            val options =
                BitmapFactory.Options().apply {
                    inMutable = true
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inBitmap = bitmap
                }
            val decoded =
                runCatching { BitmapFactory.decodeByteArray(frame, 0, frame.size, options) }
                    .getOrElse {
                        options.inBitmap = null
                        BitmapFactory.decodeByteArray(frame, 0, frame.size, options)
                    }
            if (decoded != null) {
                if (bitmap != null && bitmap !== decoded) bitmap?.recycle()
                bitmap = decoded
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, decoded, 0)
                if (System.currentTimeMillis() - lastAnalysis > 500) {
                    lastAnalysis = System.currentTimeMillis()
                    val bins = IntArray(64)
                    val step = maxOf(1, decoded.width / 80)
                    for (y in 0 until decoded.height step step) for (x in
                        0 until decoded.width step step) {
                        val pixel = decoded.getPixel(x, y)
                        val l =
                            (((pixel shr 16) and 255) * 299 +
                                ((pixel shr 8) and 255) * 587 +
                                (pixel and 255) * 114) / 1000
                        bins[(l / 4).coerceIn(0, 63)]++
                    }
                    histogram?.invoke(bins)
                }
            }
        }
        val image = bitmap ?: return
        val factor = minOf(width.toFloat() / image.width, height.toFloat() / image.height)
        val vw = (image.width * factor).toInt()
        val vh = (image.height * factor).toInt()
        GLES20.glViewport((width - vw) / 2, (height - vh) / 2, vw, vh)
        GLES20.glUseProgram(program)
        vertices.position(0)
        val position = GLES20.glGetAttribLocation(program, "p")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        val tex = GLES20.glGetAttribLocation(program, "t")
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "pixel"),
            1f / image.width,
            1f / image.height,
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "zebra"), if (zebra) 1f else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "peak"), if (peaking) 1f else 0f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
}
