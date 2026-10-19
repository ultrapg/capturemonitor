package io.github.ultrapg.capturemonitor.gl

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import io.github.ultrapg.capturemonitor.model.AspectRatioPreset
import io.github.ultrapg.capturemonitor.model.RenderConfig
import io.github.ultrapg.capturemonitor.model.TextureFilteringMode
import io.github.ultrapg.capturemonitor.model.VideoMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

/**
 * Hardware-accelerated OpenGL ES 2.0 / 3.0 Renderer for zero-copy USB Capture Card & UVC External Camera streaming.
 *
 * Features:
 * - Zero-copy `GL_TEXTURE_EXTERNAL_OES` direct DMA surface target for Camera2 / UVC hardware decoders
 * - Default Fullscreen Stretch / Crop-to-Fill / Aspect Ratio scaling
 * - GPU Contrast-Adaptive Sharpening (CAS), Brightness/Contrast/Saturation/Gamma, Full/Limited RGB range expansion,
 *   Rec.709 / Rec.601 / sRGB / DCI-P3 color grading, Overscan crop, and Nearest-Neighbor vs Bilinear filtering
 * - Clean dark obsidian canvas when no capture card is connected (with optional toggleable calibration test pattern)
 */
class HardwareVideoRenderer(
    private val context: Context,
    private val glSurfaceView: GLSurfaceView,
    private val onTelemetryTick: (fps: Float, frameTimeMs: Float, isReceivingLiveFrames: Boolean) -> Unit
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    companion object {
        private const val TAG = "HardwareVideoRenderer"

        private const val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uTexMatrix;
            uniform float uOverscanCrop;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTexCoord;
            varying vec2 vRawUv;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                // Apply optional overscan crop around center (0.5, 0.5)
                vec2 croppedUv = (aTextureCoord.xy - 0.5) * (1.0 - uOverscanCrop) + 0.5;
                vTexCoord = (uTexMatrix * vec4(croppedUv, 0.0, 1.0)).xy;
                vRawUv = aTextureCoord.xy;
            }
        """

        // External OES Fragment Shader with CAS sharpening, Gamma, Color Space grading, Full/Limited range, and Safe Area Grid
        private const val FRAGMENT_SHADER_OES = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vTexCoord;
            varying vec2 vRawUv;
            uniform samplerExternalOES sTexture;
            uniform vec2 uTexelSize;
            uniform float uBrightness;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uGamma;
            uniform float uSharpness;
            uniform int uFullRange;
            uniform int uColorSpace;
            uniform int uShowGrid;

            vec3 applyColorGrade(vec3 c) {
                if (uFullRange == 0) {
                    // Expand Limited (16..235) to Full (0..255)
                    c = clamp((c - vec3(0.062745)) * 1.164383, 0.0, 1.0);
                }
                // Contrast & Brightness
                c = clamp((c - 0.5) * uContrast + 0.5 + uBrightness, 0.0, 1.0);
                // Gamma correction
                if (abs(uGamma - 1.0) > 0.01) {
                    c = pow(c, vec3(1.0 / max(uGamma, 0.2)));
                }
                // Luma coefficients: Rec.709 vs Rec.601
                vec3 lumaWeights = (uColorSpace == 1)
                    ? vec3(0.299, 0.587, 0.114)
                    : vec3(0.2126, 0.7152, 0.0722);
                float luma = dot(c, lumaWeights);
                c = mix(vec3(luma), c, uSaturation);
                return clamp(c, 0.0, 1.0);
            }

            void main() {
                vec3 center = texture2D(sTexture, vTexCoord).rgb;
                if (uSharpness > 0.01) {
                    vec3 n = texture2D(sTexture, vTexCoord + vec2(0.0, -uTexelSize.y)).rgb;
                    vec3 s = texture2D(sTexture, vTexCoord + vec2(0.0, uTexelSize.y)).rgb;
                    vec3 e = texture2D(sTexture, vTexCoord + vec2(uTexelSize.x, 0.0)).rgb;
                    vec3 w = texture2D(sTexture, vTexCoord + vec2(-uTexelSize.x, 0.0)).rgb;
                    vec3 laplacian = (4.0 * center) - (n + s + e + w);
                    center = clamp(center + laplacian * (uSharpness * 0.45), 0.0, 1.0);
                }
                vec3 graded = applyColorGrade(center);
                if (uShowGrid == 1) {
                    bool thirdsX = abs(vRawUv.x - 0.3333) < 0.0018 || abs(vRawUv.x - 0.6667) < 0.0018;
                    bool thirdsY = abs(vRawUv.y - 0.3333) < 0.0028 || abs(vRawUv.y - 0.6667) < 0.0028;
                    bool safeBorder = (
                        (abs(vRawUv.x - 0.05) < 0.0018 || abs(vRawUv.x - 0.95) < 0.0018) && vRawUv.y >= 0.05 && vRawUv.y <= 0.95
                    ) || (
                        (abs(vRawUv.y - 0.05) < 0.0028 || abs(vRawUv.y - 0.95) < 0.0028) && vRawUv.x >= 0.05 && vRawUv.x <= 0.95
                    );
                    if (thirdsX || thirdsY) {
                        graded = mix(graded, vec3(0.22, 0.74, 0.97), 0.55);
                    } else if (safeBorder) {
                        graded = mix(graded, vec3(0.98, 0.75, 0.18), 0.65);
                    }
                }
                gl_FragColor = vec4(graded, 1.0);
            }
        """

        // Optional Calibration Test Pattern Shader (only drawn when user enables calibration pattern in settings)
        private const val FRAGMENT_SHADER_SYNTHETIC = """
            precision highp float;
            varying vec2 vRawUv;
            uniform float uTime;
            uniform float uBrightness;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uGamma;
            uniform int uFullRange;
            uniform int uColorSpace;
            uniform int uShowGrid;

            vec3 applyColorGrade(vec3 c) {
                if (uFullRange == 0) {
                    c = clamp((c - vec3(0.062745)) * 1.164383, 0.0, 1.0);
                }
                c = clamp((c - 0.5) * uContrast + 0.5 + uBrightness, 0.0, 1.0);
                if (abs(uGamma - 1.0) > 0.01) {
                    c = pow(c, vec3(1.0 / max(uGamma, 0.2)));
                }
                vec3 lumaWeights = (uColorSpace == 1)
                    ? vec3(0.299, 0.587, 0.114)
                    : vec3(0.2126, 0.7152, 0.0722);
                float luma = dot(c, lumaWeights);
                c = mix(vec3(luma), c, uSaturation);
                return clamp(c, 0.0, 1.0);
            }

            void main() {
                vec2 uv = vec2(vRawUv.x, 1.0 - vRawUv.y);
                vec3 col = vec3(0.05);

                if (uv.y < 0.64) {
                    float idx = floor(uv.x * 7.0);
                    if (idx < 1.0) col = vec3(0.75, 0.75, 0.75);
                    else if (idx < 2.0) col = vec3(0.75, 0.75, 0.0);
                    else if (idx < 3.0) col = vec3(0.0, 0.75, 0.75);
                    else if (idx < 4.0) col = vec3(0.0, 0.75, 0.0);
                    else if (idx < 5.0) col = vec3(0.75, 0.0, 0.75);
                    else if (idx < 6.0) col = vec3(0.75, 0.0, 0.0);
                    else col = vec3(0.0, 0.0, 0.75);
                } else if (uv.y < 0.74) {
                    col = vec3(uv.x);
                } else if (uv.y < 0.86) {
                    float sweep = fract(uTime * 0.45);
                    float dist = abs(uv.x - sweep);
                    float grid = step(0.985, fract(uv.x * 32.0));
                    col = vec3(0.08, 0.11, 0.17) + vec3(grid * 0.12);
                    if (dist < 0.012) {
                        col = vec3(0.22, 0.88, 0.98);
                    } else if (dist < 0.035) {
                        col = mix(vec3(0.22, 0.88, 0.98), col, (dist - 0.012) / 0.023);
                    }
                } else {
                    if (uv.x < 0.25) col = vec3(0.02);
                    else if (uv.x < 0.50) col = vec3(0.0);
                    else if (uv.x < 0.75) col = vec3(1.0);
                    else col = vec3(0.09, 0.12, 0.18);
                }

                vec3 graded = applyColorGrade(col);
                if (uShowGrid == 1) {
                    bool thirdsX = abs(vRawUv.x - 0.3333) < 0.0018 || abs(vRawUv.x - 0.6667) < 0.0018;
                    bool thirdsY = abs(vRawUv.y - 0.3333) < 0.0028 || abs(vRawUv.y - 0.6667) < 0.0028;
                    if (thirdsX || thirdsY) {
                        graded = mix(graded, vec3(0.22, 0.74, 0.97), 0.55);
                    }
                }
                gl_FragColor = vec4(graded, 1.0);
            }
        """
    }

    private val quadVertices: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(
                floatArrayOf(
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f
                )
            )
            position(0)
        }

    private val mvpMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)

    private var oesProgram = 0
    private var synthProgram = 0
    private var oesTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var captureSurface: Surface? = null

    @Volatile
    private var frameAvailable = false

    @Volatile
    private var renderConfig = RenderConfig()

    @Volatile
    private var activeMode = VideoMode(1920, 1080, 60)

    @Volatile
    private var activeCameraId: String? = null

    @Volatile
    private var useLiveCameraStream = false

    @Volatile
    private var hasReceivedAnyCameraFrame = false

    private var viewportWidth = 1920
    private var viewportHeight = 1080
    private var startTimeNs = System.nanoTime()

    private var frameCount = 0
    private var lastTelemetryTimeNs = System.nanoTime()
    private var lastFrameTimestampNs = System.nanoTime()
    private var smoothedFrameTimeMs = 16.6f

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var openedCamera: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    fun updateRenderConfig(config: RenderConfig) {
        renderConfig = config
    }

    fun bindSource(cameraId: String?, mode: VideoMode, hasCameraPermission: Boolean) {
        val modeOrCamChanged = (activeCameraId != cameraId) ||
            (activeMode.width != mode.width) ||
            (activeMode.height != mode.height) ||
            (activeMode.fps != mode.fps)

        activeCameraId = cameraId
        activeMode = mode

        if (cameraId != null && hasCameraPermission) {
            if (modeOrCamChanged || !useLiveCameraStream) {
                startCamera2Stream(cameraId, mode)
            }
        } else {
            useLiveCameraStream = false
            hasReceivedAnyCameraFrame = false
            stopCamera2Stream()
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.02f, 0.03f, 0.05f, 1f)
        Matrix.setIdentityM(texMatrix, 0)

        oesProgram = createGlProgram(VERTEX_SHADER, FRAGMENT_SHADER_OES)
        synthProgram = createGlProgram(VERTEX_SHADER, FRAGMENT_SHADER_SYNTHETIC)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        oesTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(activeMode.width, activeMode.height)
            setOnFrameAvailableListener(this@HardwareVideoRenderer)
        }
        captureSurface = Surface(surfaceTexture)

        val camId = activeCameraId
        if (camId != null) {
            startCamera2Stream(camId, activeMode)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = max(1, width)
        viewportHeight = max(1, height)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        val nowNs = System.nanoTime()
        val deltaMs = (nowNs - lastFrameTimestampNs) / 1_000_000f
        lastFrameTimestampNs = nowNs
        smoothedFrameTimeMs = smoothedFrameTimeMs * 0.85f + min(deltaMs, 100f) * 0.15f

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (frameAvailable) {
            frameAvailable = false
            hasReceivedAnyCameraFrame = true
            try {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(texMatrix)
            } catch (_: Exception) {
            }
        }

        val cfg = renderConfig
        computeMvpMatrix(cfg)

        if (useLiveCameraStream && hasReceivedAnyCameraFrame && oesProgram != 0) {
            drawOesStream(cfg)
        } else if (cfg.showTestPatternWhenNoSignal && synthProgram != 0) {
            drawSyntheticReference(cfg, (nowNs - startTimeNs) / 1_000_000_000f)
        }

        frameCount++
        val elapsedNs = nowNs - lastTelemetryTimeNs
        if (elapsedNs >= 450_000_000L) {
            val measuredFps = (frameCount * 1_000_000_000f) / elapsedNs.toFloat()
            val live = useLiveCameraStream && hasReceivedAnyCameraFrame
            val reportedFps = when {
                live -> measuredFps
                cfg.showTestPatternWhenNoSignal -> activeMode.fps.toFloat()
                else -> 0f
            }
            onTelemetryTick(reportedFps, smoothedFrameTimeMs, live)
            frameCount = 0
            lastTelemetryTimeNs = nowNs
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        frameAvailable = true
        glSurfaceView.requestRender()
    }

    private fun computeMvpMatrix(cfg: RenderConfig) {
        Matrix.setIdentityM(mvpMatrix, 0)

        val viewAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val targetAspect = when (cfg.aspectRatioPreset) {
            AspectRatioPreset.STRETCH_FILL -> viewAspect
            AspectRatioPreset.ZOOM_CROP -> activeMode.width.toFloat() / activeMode.height.toFloat()
            AspectRatioPreset.NATIVE -> activeMode.width.toFloat() / activeMode.height.toFloat()
            else -> cfg.aspectRatioPreset.ratio ?: viewAspect
        }

        var scaleX = 1f
        var scaleY = 1f
        when (cfg.aspectRatioPreset) {
            AspectRatioPreset.STRETCH_FILL -> {
                // Full screen edge-to-edge regardless of source aspect ratio
                scaleX = 1f
                scaleY = 1f
            }
            AspectRatioPreset.ZOOM_CROP -> {
                // Fill entire screen while preserving aspect ratio (crops overflow)
                if (viewAspect > targetAspect) {
                    scaleY = viewAspect / targetAspect
                } else {
                    scaleX = targetAspect / viewAspect
                }
            }
            else -> {
                if (viewAspect > targetAspect) {
                    scaleX = targetAspect / viewAspect
                } else {
                    scaleY = viewAspect / targetAspect
                }
            }
        }

        scaleX *= cfg.zoomScale * (if (cfg.mirrorHorizontal) -1f else 1f)
        scaleY *= cfg.zoomScale * (if (cfg.flipVertical) -1f else 1f)

        if (cfg.panX != 0f || cfg.panY != 0f) {
            Matrix.translateM(mvpMatrix, 0, cfg.panX, cfg.panY, 0f)
        }
        if (cfg.rotationDegrees != 0) {
            Matrix.rotateM(mvpMatrix, 0, cfg.rotationDegrees.toFloat(), 0f, 0f, 1f)
        }
        Matrix.scaleM(mvpMatrix, 0, scaleX, scaleY, 1f)
    }

    private fun drawOesStream(cfg: RenderConfig) {
        GLES20.glUseProgram(oesProgram)

        val posLoc = GLES20.glGetAttribLocation(oesProgram, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(oesProgram, "aTextureCoord")
        val mvpLoc = GLES20.glGetUniformLocation(oesProgram, "uMVPMatrix")
        val texMatLoc = GLES20.glGetUniformLocation(oesProgram, "uTexMatrix")

        quadVertices.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
        GLES20.glEnableVertexAttribArray(posLoc)

        quadVertices.position(2)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
        GLES20.glEnableVertexAttribArray(texLoc)

        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(texMatLoc, 1, false, texMatrix, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(oesProgram, "uOverscanCrop"), cfg.overscanCropPercent.coerceIn(0f, 0.15f))

        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(oesProgram, "uTexelSize"),
            1f / activeMode.width.coerceAtLeast(1).toFloat(),
            1f / activeMode.height.coerceAtLeast(1).toFloat()
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(oesProgram, "uBrightness"), cfg.brightness)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(oesProgram, "uContrast"), cfg.contrast)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(oesProgram, "uSaturation"), cfg.saturation)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(oesProgram, "uGamma"), cfg.gamma)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(oesProgram, "uSharpness"), cfg.sharpness)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(oesProgram, "uFullRange"), if (cfg.fullRangeRgb) 1 else 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(oesProgram, "uColorSpace"), cfg.colorSpace.code)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(oesProgram, "uShowGrid"), if (cfg.showSafeAreaGrid) 1 else 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

        val filterParam = if (cfg.filteringMode == TextureFilteringMode.NEAREST_PIXEL) {
            GLES20.GL_NEAREST.toFloat()
        } else {
            GLES20.GL_LINEAR.toFloat()
        }
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, filterParam)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, filterParam)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawSyntheticReference(cfg: RenderConfig, timeSec: Float) {
        GLES20.glUseProgram(synthProgram)

        val posLoc = GLES20.glGetAttribLocation(synthProgram, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(synthProgram, "aTextureCoord")
        val mvpLoc = GLES20.glGetUniformLocation(synthProgram, "uMVPMatrix")
        val texMatLoc = GLES20.glGetUniformLocation(synthProgram, "uTexMatrix")

        quadVertices.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
        GLES20.glEnableVertexAttribArray(posLoc)

        quadVertices.position(2)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
        GLES20.glEnableVertexAttribArray(texLoc)

        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(texMatLoc, 1, false, texMatrix, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(synthProgram, "uOverscanCrop"), cfg.overscanCropPercent.coerceIn(0f, 0.15f))

        GLES20.glUniform1f(GLES20.glGetUniformLocation(synthProgram, "uTime"), timeSec)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(synthProgram, "uBrightness"), cfg.brightness)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(synthProgram, "uContrast"), cfg.contrast)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(synthProgram, "uSaturation"), cfg.saturation)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(synthProgram, "uGamma"), cfg.gamma)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(synthProgram, "uFullRange"), if (cfg.fullRangeRgb) 1 else 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(synthProgram, "uColorSpace"), cfg.colorSpace.code)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(synthProgram, "uShowGrid"), if (cfg.showSafeAreaGrid) 1 else 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    @SuppressLint("MissingPermission")
    private fun startCamera2Stream(cameraId: String, mode: VideoMode) {
        val surface = captureSurface ?: return
        stopCamera2Stream()

        val thread = HandlerThread("UvcCamera2Thread").also { it.start() }
        val handler = Handler(thread.looper)
        cameraThread = thread
        cameraHandler = handler

        try {
            surfaceTexture?.setDefaultBufferSize(mode.width, mode.height)
            val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            camManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    openedCamera = camera
                    try {
                        val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            set(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                Range(mode.fps.coerceAtMost(60), mode.fps.coerceAtMost(60))
                            )
                        }
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    try {
                                        session.setRepeatingRequest(requestBuilder.build(), null, handler)
                                        useLiveCameraStream = true
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Repeating request error: ${e.message}")
                                        useLiveCameraStream = false
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.w(TAG, "Camera2 session configure failed for ${mode.resolutionLabel}")
                                    useLiveCameraStream = false
                                }
                            },
                            handler
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Session creation error: ${e.message}")
                        useLiveCameraStream = false
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    useLiveCameraStream = false
                    hasReceivedAnyCameraFrame = false
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    useLiveCameraStream = false
                    hasReceivedAnyCameraFrame = false
                    camera.close()
                }
            }, handler)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to open camera $cameraId: ${e.message}")
            useLiveCameraStream = false
        }
    }

    fun stopCamera2Stream() {
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null

        try {
            openedCamera?.close()
        } catch (_: Exception) {
        }
        openedCamera = null

        try {
            cameraThread?.quitSafely()
        } catch (_: Exception) {
        }
        cameraThread = null
        cameraHandler = null
    }

    fun release() {
        stopCamera2Stream()
        try {
            captureSurface?.release()
        } catch (_: Exception) {
        }
        try {
            surfaceTexture?.release()
        } catch (_: Exception) {
        }
    }

    private fun createGlProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) return 0
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentShader == 0) return 0

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "GL program link error: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "GL shader compile error: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
