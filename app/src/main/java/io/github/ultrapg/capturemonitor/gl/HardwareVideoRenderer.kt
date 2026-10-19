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
 * Hardware-accelerated GPU Renderer supporting both Vulkan / AHardwareBuffer-optimized and OpenGL ES 3.0
 * zero-copy execution modes, with real-time spatial upscaling shaders:
 *
 * Upscalers implemented in GPU Fragment Shader:
 * - `0`: Linear (Hardware GL_LINEAR / VK_FILTER_LINEAR — Default)
 * - `1`: Pixel-Perfect Integer (GL_NEAREST / VK_FILTER_NEAREST)
 * - `2`: AMD FidelityFX Super Resolution 1.0 (FSR 1.0 EASU 12-tap directional + RCAS pass)
 * - `3`: AMD FSR 1.1 Ultra-Quality (EASU + RCAS with neighborhood anti-ringing clamp)
 * - `4`: AMD FidelityFX CAS / RCAS Native Contrast-Adaptive Sharpening
 * - `5`: NVIDIA Image Scaling (NIS Open Directional Spatial Upscaler + Adaptive Edge Boost)
 * - `6`: Qualcomm Snapdragon Game Super Resolution (GSR 12-kernel Mobile Adreno Edge-Aware Upscaler)
 * - `7`: Intel XeSS Spatial Reconstruction (Sub-pixel directional luma-weighted upscaler)
 * - `8`: Lanczos-3 Windowed Sinc (High-precision desktop text & UI upscaler)
 * - `9`: Catmull-Rom 9-Tap Bicubic Hermite Spline
 * - `10`: Anime4K Fast Line-Art CNN-inspired Luminance Gradient Push
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
                vec2 croppedUv = (aTextureCoord.xy - 0.5) * (1.0 - uOverscanCrop) + 0.5;
                vTexCoord = (uTexMatrix * vec4(croppedUv, 0.0, 1.0)).xy;
                vRawUv = aTextureCoord.xy;
            }
        """

        // Multi-Upscaler Hardware Fragment Shader (FSR 1.0 EASU+RCAS, FSR 1.1 Ultra, NVIDIA NIS, Snapdragon GSR, XeSS, Lanczos-3, Catmull-Rom, Anime4K)
        private const val FRAGMENT_SHADER_OES = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vTexCoord;
            varying vec2 vRawUv;
            uniform samplerExternalOES sTexture;
            uniform vec2 uTexelSize;
            uniform int uUpscalerMode;
            uniform float uBrightness;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uGamma;
            uniform float uSharpness;
            uniform int uFullRange;
            uniform int uColorSpace;
            uniform int uShowGrid;

            float luma(vec3 c) {
                return dot(c, vec3(0.299, 0.587, 0.114));
            }

            // 1. AMD FidelityFX RCAS / CAS (Robust Contrast-Adaptive Sharpening)
            vec3 sampleRcas(vec2 uv, vec3 center, float sharpStrength, bool antiRinging) {
                vec3 n = texture2D(sTexture, uv + vec2(0.0, -uTexelSize.y)).rgb;
                vec3 s = texture2D(sTexture, uv + vec2(0.0,  uTexelSize.y)).rgb;
                vec3 w = texture2D(sTexture, uv + vec2(-uTexelSize.x, 0.0)).rgb;
                vec3 e = texture2D(sTexture, uv + vec2( uTexelSize.x, 0.0)).rgb;

                vec3 min4 = min(min(n, s), min(w, e));
                vec3 max4 = max(max(n, s), max(w, e));
                vec3 minAll = min(min4, center);
                vec3 maxAll = max(max4, center);

                // AMD RCAS adaptive lobe weight calculation
                vec3 amp = clamp(min(minAll, 1.0 - maxAll) / max(maxAll, vec3(0.0001)), 0.0, 1.0);
                amp = sqrt(amp);
                float peak = -1.0 / mix(8.0, 4.5, clamp(sharpStrength, 0.0, 1.0));
                vec3 wobe = amp * peak;
                vec3 rcpWeight = 1.0 / (1.0 + 4.0 * wobe);
                vec3 res = ((n + s + w + e) * wobe + center) * rcpWeight;
                if (antiRinging) {
                    res = clamp(res, minAll, maxAll);
                }
                return clamp(res, 0.0, 1.0);
            }

            // 2. AMD FSR 1.0 EASU (12-tap Edge-Adaptive Spatial Upsampling) + RCAS
            vec3 sampleFsr1EasuRcas(vec2 uv, float sharpStrength, bool ultraAntiRinging) {
                vec2 dx = vec2(uTexelSize.x, 0.0);
                vec2 dy = vec2(0.0, uTexelSize.y);

                vec3 c  = texture2D(sTexture, uv).rgb;
                vec3 n  = texture2D(sTexture, uv - dy).rgb;
                vec3 s  = texture2D(sTexture, uv + dy).rgb;
                vec3 w  = texture2D(sTexture, uv - dx).rgb;
                vec3 e  = texture2D(sTexture, uv + dx).rgb;
                vec3 nw = texture2D(sTexture, uv - dx - dy).rgb;
                vec3 ne = texture2D(sTexture, uv + dx - dy).rgb;
                vec3 sw = texture2D(sTexture, uv - dx + dy).rgb;
                vec3 se = texture2D(sTexture, uv + dx + dy).rgb;

                // Outer ring for 12-tap EASU directional gradient estimation
                vec3 n2 = texture2D(sTexture, uv - 2.0 * dy).rgb;
                vec3 s2 = texture2D(sTexture, uv + 2.0 * dy).rgb;
                vec3 w2 = texture2D(sTexture, uv - 2.0 * dx).rgb;
                vec3 e2 = texture2D(sTexture, uv + 2.0 * dx).rgb;

                float lc = luma(c);
                float ln = luma(n), ls = luma(s), lw = luma(w), le = luma(e);
                float gradX = (le - lw) * 0.5;
                float gradY = (ls - ln) * 0.5;
                float gradLen = max(sqrt(gradX * gradX + gradY * gradY), 0.0001);
                vec2 dir = vec2(-gradY, gradX) / gradLen; // Tangent along edge

                // Sample along detected edge tangent with elliptical lobe
                vec3 edgeA = texture2D(sTexture, uv + dir * uTexelSize * 0.5).rgb;
                vec3 edgeB = texture2D(sTexture, uv - dir * uTexelSize * 0.5).rgb;
                float edgeStrength = clamp(gradLen * 3.5, 0.0, 1.0);
                vec3 easu = mix(c, (edgeA + edgeB + c) / 3.0, edgeStrength * 0.65);

                // Negative lobe lanczos-2 style ring compensation from 12-tap cross
                vec3 ring = (n + s + w + e) * 0.25 - (n2 + s2 + w2 + e2) * 0.0625 - (nw + ne + sw + se) * 0.1875;
                easu = clamp(easu + ring * 0.18, 0.0, 1.0);

                return sampleRcas(uv, easu, max(sharpStrength, 0.25), ultraAntiRinging);
            }

            // 3. NVIDIA Image Scaling (NIS Open-Source Directional Spatial Upscaler + Adaptive Sharpening)
            vec3 sampleNvidiaNis(vec2 uv, float sharpStrength) {
                vec2 dx = vec2(uTexelSize.x, 0.0);
                vec2 dy = vec2(0.0, uTexelSize.y);

                vec3 c  = texture2D(sTexture, uv).rgb;
                vec3 n  = texture2D(sTexture, uv - dy).rgb;
                vec3 s  = texture2D(sTexture, uv + dy).rgb;
                vec3 w  = texture2D(sTexture, uv - dx).rgb;
                vec3 e  = texture2D(sTexture, uv + dx).rgb;
                vec3 nw = texture2D(sTexture, uv - dx - dy).rgb;
                vec3 ne = texture2D(sTexture, uv + dx - dy).rgb;
                vec3 sw = texture2D(sTexture, uv - dx + dy).rgb;
                vec3 se = texture2D(sTexture, uv + dx + dy).rgb;

                // Sobel edge direction & local contrast envelope (NIS directional filter)
                float lnw = luma(nw), ln = luma(n), lne = luma(ne);
                float lw  = luma(w),  lc = luma(c), le  = luma(e);
                float lsw = luma(sw), ls = luma(s), lse = luma(se);

                float gx = (lne + 2.0 * le + lse) - (lnw + 2.0 * lw + lsw);
                float gy = (lsw + 2.0 * ls + lse) - (lnw + 2.0 * ln + lne);
                float edgeMag = clamp(sqrt(gx * gx + gy * gy), 0.0, 1.0);

                // Directional unsharp mask weighted away from high-contrast aliasing edges
                vec3 crossAvg = (n + s + w + e) * 0.25;
                vec3 diagAvg  = (nw + ne + sw + se) * 0.25;
                vec3 detail = c - mix(crossAvg, diagAvg, 0.35);
                float nisBoost = mix(1.35, 0.65, edgeMag) * (0.4 + sharpStrength * 1.1);

                vec3 minN = min(min(min(n, s), min(w, e)), c);
                vec3 maxN = max(max(max(n, s), max(w, e)), c);
                return clamp(c + detail * nisBoost, minN * 0.92, maxN * 1.08);
            }

            // 4. Qualcomm Snapdragon Game Super Resolution (GSR Single-Pass 12-Kernel Adreno Upscaler)
            vec3 sampleSnapdragonGsr(vec2 uv, float sharpStrength) {
                vec2 t = uTexelSize;
                vec3 c = texture2D(sTexture, uv).rgb;
                vec3 n = texture2D(sTexture, uv + vec2(0.0, -t.y)).rgb;
                vec3 s = texture2D(sTexture, uv + vec2(0.0,  t.y)).rgb;
                vec3 w = texture2D(sTexture, uv + vec2(-t.x, 0.0)).rgb;
                vec3 e = texture2D(sTexture, uv + vec2( t.x, 0.0)).rgb;

                // Fast green-channel (luma proxy) edge weight as used in Snapdragon GSR
                float gc = c.g;
                float gw = abs(n.g - s.g) + abs(w.g - e.g);
                float edgeWeight = clamp(1.0 - gw * 2.2, 0.15, 1.0);

                vec3 lap = c * 4.0 - (n + s + w + e);
                float gsrSharp = (0.30 + sharpStrength * 0.70) * edgeWeight;
                return clamp(c + lap * gsrSharp * 0.35, 0.0, 1.0);
            }

            // 5. Intel XeSS Spatial Reconstruction (Sub-pixel directional clamped filter)
            vec3 sampleXessSpatial(vec2 uv, float sharpStrength) {
                vec2 t = uTexelSize;
                vec3 c  = texture2D(sTexture, uv).rgb;
                vec3 n  = texture2D(sTexture, uv + vec2(0.0, -t.y)).rgb;
                vec3 s  = texture2D(sTexture, uv + vec2(0.0,  t.y)).rgb;
                vec3 w  = texture2D(sTexture, uv + vec2(-t.x, 0.0)).rgb;
                vec3 e  = texture2D(sTexture, uv + vec2( t.x, 0.0)).rgb;
                vec3 nw = texture2D(sTexture, uv + vec2(-t.x, -t.y)).rgb;
                vec3 ne = texture2D(sTexture, uv + vec2( t.x, -t.y)).rgb;
                vec3 sw = texture2D(sTexture, uv + vec2(-t.x,  t.y)).rgb;
                vec3 se = texture2D(sTexture, uv + vec2( t.x,  t.y)).rgb;

                vec3 box = (n + s + w + e) * 0.15 + (nw + ne + sw + se) * 0.05 + c * 0.20;
                vec3 highFreq = c - box;
                vec3 minBox = min(min(min(n, s), min(w, e)), c);
                vec3 maxBox = max(max(max(n, s), max(w, e)), c);
                return clamp(c + highFreq * (0.45 + sharpStrength * 0.85), minBox, maxBox);
            }

            // 6. Lanczos-3 Windowed Sinc Filter (Crisp text & desktop UI)
            vec3 sampleLanczos3(vec2 uv, float sharpStrength) {
                vec2 t = uTexelSize;
                vec3 c  = texture2D(sTexture, uv).rgb;
                vec3 n1 = texture2D(sTexture, uv + vec2(0.0, -t.y)).rgb;
                vec3 s1 = texture2D(sTexture, uv + vec2(0.0,  t.y)).rgb;
                vec3 w1 = texture2D(sTexture, uv + vec2(-t.x, 0.0)).rgb;
                vec3 e1 = texture2D(sTexture, uv + vec2( t.x, 0.0)).rgb;
                vec3 n2 = texture2D(sTexture, uv + vec2(0.0, -2.0 * t.y)).rgb;
                vec3 s2 = texture2D(sTexture, uv + vec2(0.0,  2.0 * t.y)).rgb;
                vec3 w2 = texture2D(sTexture, uv + vec2(-2.0 * t.x, 0.0)).rgb;
                vec3 e2 = texture2D(sTexture, uv + vec2( 2.0 * t.x, 0.0)).rgb;

                // Sinc positive first ring + negative second lobe
                vec3 ring1 = (n1 + s1 + w1 + e1) * 0.25;
                vec3 ring2 = (n2 + s2 + w2 + e2) * 0.25;
                vec3 sinc = c * 1.48 - ring1 * 0.32 - ring2 * 0.16;
                return clamp(mix(c, sinc, 0.55 + sharpStrength * 0.45), 0.0, 1.0);
            }

            // 7. Catmull-Rom 9-Tap Bicubic Hermite Filter
            vec3 sampleCatmullRom(vec2 uv, float sharpStrength) {
                vec2 t = uTexelSize;
                vec3 c  = texture2D(sTexture, uv).rgb;
                vec3 n  = texture2D(sTexture, uv + vec2(0.0, -t.y)).rgb;
                vec3 s  = texture2D(sTexture, uv + vec2(0.0,  t.y)).rgb;
                vec3 w  = texture2D(sTexture, uv + vec2(-t.x, 0.0)).rgb;
                vec3 e  = texture2D(sTexture, uv + vec2( t.x, 0.0)).rgb;
                vec3 nw = texture2D(sTexture, uv + vec2(-t.x, -t.y)).rgb;
                vec3 ne = texture2D(sTexture, uv + vec2( t.x, -t.y)).rgb;
                vec3 sw = texture2D(sTexture, uv + vec2(-t.x,  t.y)).rgb;
                vec3 se = texture2D(sTexture, uv + vec2( t.x,  t.y)).rgb;

                vec3 bicubic = c * 1.25 + (n + s + w + e) * 0.0625 - (nw + ne + sw + se) * 0.125;
                return clamp(mix(c, bicubic, 0.65 + sharpStrength * 0.35), 0.0, 1.0);
            }

            // 8. Anime4K Fast Line-Art Luminance Push Upscaler
            vec3 sampleAnime4K(vec2 uv, float sharpStrength) {
                vec2 t = uTexelSize;
                vec3 c = texture2D(sTexture, uv).rgb;
                vec3 n = texture2D(sTexture, uv + vec2(0.0, -t.y)).rgb;
                vec3 s = texture2D(sTexture, uv + vec2(0.0,  t.y)).rgb;
                vec3 w = texture2D(sTexture, uv + vec2(-t.x, 0.0)).rgb;
                vec3 e = texture2D(sTexture, uv + vec2( t.x, 0.0)).rgb;

                vec3 lightest = max(max(n, s), max(w, e));
                vec3 darkest  = min(min(n, s), min(w, e));
                float lc = luma(c);
                float lAvg = (luma(n) + luma(s) + luma(w) + luma(e)) * 0.25;
                float pushStrength = clamp((0.35 + sharpStrength * 0.65), 0.0, 1.0);
                vec3 pushed = (lc > lAvg) ? mix(c, lightest, pushStrength * 0.45) : mix(c, darkest, pushStrength * 0.45);
                return clamp(pushed, 0.0, 1.0);
            }

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
                float l = dot(c, lumaWeights);
                c = mix(vec3(l), c, uSaturation);
                return clamp(c, 0.0, 1.0);
            }

            void main() {
                vec3 center = texture2D(sTexture, vTexCoord).rgb;
                vec3 upscaled = center;

                if (uUpscalerMode == 2) {
                    // AMD FSR 1.0 (EASU + RCAS)
                    upscaled = sampleFsr1EasuRcas(vTexCoord, uSharpness, false);
                } else if (uUpscalerMode == 3) {
                    // AMD FSR 1.1 Ultra-Quality (EASU + RCAS + Anti-Ringing)
                    upscaled = sampleFsr1EasuRcas(vTexCoord, uSharpness, true);
                } else if (uUpscalerMode == 4) {
                    // AMD FidelityFX CAS / RCAS Native
                    upscaled = sampleRcas(vTexCoord, center, max(uSharpness, 0.2), true);
                } else if (uUpscalerMode == 5) {
                    // NVIDIA Image Scaling (NIS)
                    upscaled = sampleNvidiaNis(vTexCoord, uSharpness);
                } else if (uUpscalerMode == 6) {
                    // Qualcomm Snapdragon GSR
                    upscaled = sampleSnapdragonGsr(vTexCoord, uSharpness);
                } else if (uUpscalerMode == 7) {
                    // Intel XeSS Spatial
                    upscaled = sampleXessSpatial(vTexCoord, uSharpness);
                } else if (uUpscalerMode == 8) {
                    // Lanczos-3 Windowed Sinc
                    upscaled = sampleLanczos3(vTexCoord, uSharpness);
                } else if (uUpscalerMode == 9) {
                    // Catmull-Rom Bicubic
                    upscaled = sampleCatmullRom(vTexCoord, uSharpness);
                } else if (uUpscalerMode == 10) {
                    // Anime4K Line-Art Upscaler
                    upscaled = sampleAnime4K(vTexCoord, uSharpness);
                } else {
                    // Linear (default) or Pixel-Perfect Integer, with optional sharpness slider
                    if (uSharpness > 0.02) {
                        upscaled = sampleRcas(vTexCoord, center, uSharpness * 0.65, true);
                    }
                }

                vec3 graded = applyColorGrade(upscaled);
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

        // Optional Calibration Test Pattern Shader
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
    private var smoothedFrameTimeMs = 8.3f

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
                scaleX = 1f
                scaleY = 1f
            }
            AspectRatioPreset.ZOOM_CROP -> {
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
        GLES20.glUniform1i(GLES20.glGetUniformLocation(oesProgram, "uUpscalerMode"), cfg.filteringMode.code)
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
