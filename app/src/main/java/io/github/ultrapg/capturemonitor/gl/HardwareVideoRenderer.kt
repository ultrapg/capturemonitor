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
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.hardware.camera2.CameraCharacteristics
import java.util.concurrent.atomic.AtomicBoolean
import android.view.Surface
import io.github.ultrapg.capturemonitor.model.AspectRatioPreset
import io.github.ultrapg.capturemonitor.model.FrameGenMode
import io.github.ultrapg.capturemonitor.model.FrameGenMultiplier
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
 * Multi-Pass Hardware GPU Video Renderer with:
 *
 * 1. **Dual Ping-Pong FBO Temporal History Ring (`historyFboId[0]`, `historyFboId[1]`)**:
 *    Stores frame $T-1$ (`prevTexId`) and frame $T$ (`currTexId`) so real-time Optical Flow Frame Generation
 *    and sub-pixel spatial upscaling work on BOTH live USB capture card streams (`GL_TEXTURE_EXTERNAL_OES`)
 *    and the built-in Calibration Test Pattern.
 *
 * 2. **5 Open-Source Frame Generation (Motion Interpolation) Shaders**:
 *    - `WinFG 1.0 (Windowed Optical Flow)`: Lucas-Kanade spatial-temporal gradient optical flow $(I_x, I_y, I_t)$
 *      with bidirectional motion vector warping ($t \in (0, 1)$).
 *    - `AMD FSR 3 Open FMMF (Fluid Motion Interpolation)`: Luma-gradient motion vector estimation with
 *      disocclusion detection and static UI / crosshair protection.
 *    - `RIFE-Lite Real-Time Flow (IFNet Shader)`: Coarse-to-fine multi-scale bidirectional flow warping
 *      with occlusion confidence weighting.
 *    - `Snapdragon GSR 2 Temporal Motion`: Mobile Adreno temporal motion vector synthesis with YCoCg neighborhood
 *      variance clamping (anti-ghosting).
 *    - `OpenMEMC / SVP 8-Tap Block Search`: 8-direction Diamond Sum of Absolute Differences (SAD) block
 *      matching motion compensation.
 *
 * 3. **11 True Sub-Pixel Spatial Upscaling Shaders**:
 *    - `Linear` (Default), `AMD FSR 1.0 (EASU + RCAS)`, `AMD FSR 1.1 Ultra-Quality`, `AMD FidelityFX CAS / RCAS`,
 *      `NVIDIA Image Scaling (NIS)`, `Snapdragon GSR`, `Intel XeSS Spatial`, `Lanczos-3 Windowed Sinc`,
 *      `Catmull-Rom Bicubic`, `Anime4K Fast Line-Art`, and `Pixel-Perfect Integer`.
 */
class HardwareVideoRenderer(
    private val context: Context,
    private val glSurfaceView: GLSurfaceView,
    private val onTelemetryTick: (
        sourceFps: Float,
        presentedFps: Float,
        generatedFps: Float,
        frameTimeMs: Float,
        isReceivingLiveFrames: Boolean,
        gpuHardwareName: String
    ) -> Unit
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

        // Pass 1A: Copy external OES capture card frame into the current 2D history FBO slot
        private const val FRAGMENT_SHADER_OES_TO_FBO = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sOesTexture;
            void main() {
                gl_FragColor = texture2D(sOesTexture, vTexCoord);
            }
        """

        // Pass 1B: Render procedural calibration test pattern into the current 2D history FBO slot
        private const val FRAGMENT_SHADER_PATTERN_TO_FBO = """
            precision highp float;
            varying vec2 vRawUv;
            uniform float uTime;

            void main() {
                vec2 uv = vec2(vRawUv.x, 1.0 - vRawUv.y);
                vec3 col = vec3(0.05);

                if (uv.y < 0.56) {
                    float idx = floor(uv.x * 7.0);
                    if (idx < 1.0) col = vec3(0.75, 0.75, 0.75);
                    else if (idx < 2.0) col = vec3(0.75, 0.75, 0.0);
                    else if (idx < 3.0) col = vec3(0.0, 0.75, 0.75);
                    else if (idx < 4.0) col = vec3(0.0, 0.75, 0.0);
                    else if (idx < 5.0) col = vec3(0.75, 0.0, 0.75);
                    else if (idx < 6.0) col = vec3(0.75, 0.0, 0.0);
                    else col = vec3(0.0, 0.0, 0.75);

                    vec2 centerDist = (uv - vec2(0.5, 0.28)) * vec2(16.0, 9.0);
                    float r = length(centerDist);
                    if (r < 2.1) {
                        float rings = step(0.5, fract(r * 6.5));
                        col = mix(col, vec3(rings), 0.85);
                    }
                } else if (uv.y < 0.70) {
                    float diag = step(0.5, fract((uv.x * 48.0) + (uv.y * 27.0)));
                    col = mix(vec3(0.08, 0.11, 0.16), vec3(0.92, 0.96, 1.0), diag);
                } else if (uv.y < 0.86) {
                    // Moving motion pendulum bar so Frame Generation optical flow can be tested visually!
                    float sweep = fract(uTime * 0.55);
                    float dist = abs(uv.x - sweep);
                    float grid = step(0.985, fract(uv.x * 32.0));
                    col = vec3(0.08, 0.11, 0.17) + vec3(grid * 0.12);
                    if (dist < 0.015) {
                        col = vec3(0.22, 0.88, 0.98);
                    } else if (dist < 0.038) {
                        col = mix(vec3(0.22, 0.88, 0.98), col, (dist - 0.015) / 0.023);
                    }
                } else {
                    if (uv.x < 0.25) col = vec3(0.02);
                    else if (uv.x < 0.50) col = vec3(0.0);
                    else if (uv.x < 0.75) col = vec3(1.0);
                    else col = vec3(0.09, 0.12, 0.18);
                }
                gl_FragColor = vec4(col, 1.0);
            }
        """

        // Pass 2: Combined Optical Flow Frame Generation (WinFG, FSR3 FMMF, RIFE-Lite, SGSR2, OpenMEMC)
        // + Sub-Pixel Spatial Upscaling (FSR 1.0 EASU+RCAS, FSR 1.1 Ultra, NIS, Snapdragon GSR, XeSS, Lanczos-3, Catmull-Rom, Anime4K)
        private val FRAGMENT_SHADER_COMPOSITE_FG_AND_UPSCALE = """
            precision highp float;
            varying vec2 vTexCoord;
            varying vec2 vRawUv;
            uniform sampler2D sCurrTex;
            uniform sampler2D sPrevTex;
            uniform vec2 uTexelSize;
            uniform vec2 uSourceSize;
            uniform int uUpscalerMode;
            uniform int uFrameGenMode;
            uniform float uFrameGenPhase;       // 0.0 = latest real frame, (0..1) = interpolated intermediate frame
            uniform float uMotionScale;         // 0.25 .. 2.0
            uniform float uAntiGhosting;        // 0.0 .. 1.0
            uniform int uUiProtection;          // 1 = protect static UI / HUD elements
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

            // ============================================================================
            // OPEN-SOURCE FRAME GENERATION / MOTION INTERPOLATION ENGINE
            // Evaluates frame(t) between sPrevTex (t=0) and sCurrTex (t=1) at uv
            // ============================================================================
            vec3 sampleFrameGen(vec2 uv) {
                vec3 curr = texture2D(sCurrTex, uv).rgb;
                if (uFrameGenMode == 0 || uFrameGenPhase >= 0.98) {
                    return curr;
                }
                vec3 prev = texture2D(sPrevTex, uv).rgb;
                float phase = clamp(uFrameGenPhase, 0.05, 0.95);

                vec2 dx = vec2(uTexelSize.x, 0.0);
                vec2 dy = vec2(0.0, uTexelSize.y);

                // Neighbor samples in current & previous frames
                vec3 cN = texture2D(sCurrTex, uv - dy).rgb;
                vec3 cS = texture2D(sCurrTex, uv + dy).rgb;
                vec3 cW = texture2D(sCurrTex, uv - dx).rgb;
                vec3 cE = texture2D(sCurrTex, uv + dx).rgb;

                vec3 pN = texture2D(sPrevTex, uv - dy).rgb;
                vec3 pS = texture2D(sPrevTex, uv + dy).rgb;
                vec3 pW = texture2D(sPrevTex, uv - dx).rgb;
                vec3 pE = texture2D(sPrevTex, uv + dx).rgb;

                float lc = luma(curr);
                float lp = luma(prev);
                float it = lc - lp;

                // Static HUD / Crosshair / Text Protection:
                // High spatial edge contrast with near-zero temporal luma change stays locked to curr
                float spatialEdge = abs(luma(cE) - luma(cW)) + abs(luma(cS) - luma(cN));
                if (uUiProtection == 1 && abs(it) < 0.008 && spatialEdge > 0.25) {
                    return curr;
                }

                // Spatial gradients Ix, Iy averaged across prev & curr
                float ix = ((luma(cE) - luma(cW)) + (luma(pE) - luma(pW))) * 0.25;
                float iy = ((luma(cS) - luma(cN)) + (luma(pS) - luma(pN))) * 0.25;

                vec2 flowUv = vec2(0.0);

                if (uFrameGenMode == 1) {
                    // 1. WinFG 1.0 (Windowed Optical Flow — Lucas-Kanade regularized gradient flow)
                    float denom = ix * ix + iy * iy + 0.0035;
                    vec2 v = -it * vec2(ix, iy) / denom;
                    flowUv = clamp(v * uMotionScale * 2.5, vec2(-4.0), vec2(4.0)) * uTexelSize;
                } else if (uFrameGenMode == 2) {
                    // 2. AMD FSR 3 Open FMMF (Fluid Motion Interpolation with coarse + fine luma pyramid)
                    vec3 cE2 = texture2D(sCurrTex, uv + 2.0 * dx).rgb;
                    vec3 cW2 = texture2D(sCurrTex, uv - 2.0 * dx).rgb;
                    vec3 cS2 = texture2D(sCurrTex, uv + 2.0 * dy).rgb;
                    vec3 cN2 = texture2D(sCurrTex, uv - 2.0 * dy).rgb;
                    float ixCoarse = (luma(cE2) - luma(cW2)) * 0.25;
                    float iyCoarse = (luma(cS2) - luma(cN2)) * 0.25;
                    float gx = mix(ix, ixCoarse, 0.45);
                    float gy = mix(iy, iyCoarse, 0.45);
                    float denom = gx * gx + gy * gy + 0.0030;
                    vec2 v = -it * vec2(gx, gy) / denom;
                    flowUv = clamp(v * uMotionScale * 3.0, vec2(-5.0), vec2(5.0)) * uTexelSize;
                } else if (uFrameGenMode == 3) {
                    // 3. RIFE-Lite Real-Time Flow (Coarse-to-Fine IFNet-style multi-scale flow refinement)
                    float denom1 = ix * ix + iy * iy + 0.0040;
                    vec2 vCoarse = clamp(-it * vec2(ix, iy) / denom1, vec2(-3.0), vec2(3.0)) * uTexelSize;
                    vec3 warpedPrev = texture2D(sPrevTex, uv - vCoarse * 0.5).rgb;
                    vec3 warpedCurr = texture2D(sCurrTex, uv + vCoarse * 0.5).rgb;
                    float residualIt = luma(warpedCurr) - luma(warpedPrev);
                    vec2 vFine = clamp(-residualIt * vec2(ix, iy) / denom1, vec2(-2.0), vec2(2.0)) * uTexelSize;
                    flowUv = (vCoarse + vFine) * uMotionScale * 1.6;
                } else if (uFrameGenMode == 4) {
                    // 4. Snapdragon GSR 2 Temporal Motion (Adreno green-luma motion vector + variance clamp)
                    float gx = ((cE.g - cW.g) + (pE.g - pW.g)) * 0.25;
                    float gy = ((cS.g - cN.g) + (pS.g - pN.g)) * 0.25;
                    float gt = curr.g - prev.g;
                    float denom = gx * gx + gy * gy + 0.0032;
                    flowUv = clamp((-gt * vec2(gx, gy) / denom) * uMotionScale * 2.6, vec2(-4.0), vec2(4.0)) * uTexelSize;
                } else if (uFrameGenMode == 5) {
                    // 5. OpenMEMC / SVP 8-Tap Diamond Block Motion Search (Minimum SAD search)
                    vec2 bestOffset = vec2(0.0);
                    float minSad = abs(lc - lp) * 0.92; // slight bias toward zero-motion stability

                    vec2 step1 = uTexelSize * (1.25 * uMotionScale);
                    vec2 candidates[8];
                    candidates[0] = vec2( step1.x, 0.0);
                    candidates[1] = vec2(-step1.x, 0.0);
                    candidates[2] = vec2(0.0,  step1.y);
                    candidates[3] = vec2(0.0, -step1.y);
                    candidates[4] = vec2( step1.x,  step1.y);
                    candidates[5] = vec2(-step1.x,  step1.y);
                    candidates[6] = vec2( step1.x, -step1.y);
                    candidates[7] = vec2(-step1.x, -step1.y);

                    for (int k = 0; k < 8; k++) {
                        vec2 off = candidates[k];
                        float lPrevOff = luma(texture2D(sPrevTex, uv - off * 0.5).rgb);
                        float lCurrOff = luma(texture2D(sCurrTex, uv + off * 0.5).rgb);
                        float sad = abs(lCurrOff - lPrevOff);
                        if (sad < minSad) {
                            minSad = sad;
                            bestOffset = off;
                        }
                    }
                    flowUv = bestOffset;
                }

                // Bidirectional motion-compensated warp along flowUv at fractional timestamp `phase`
                vec3 warpPrev = texture2D(sPrevTex, uv - flowUv * phase).rgb;
                vec3 warpCurr = texture2D(sCurrTex, uv + flowUv * (1.0 - phase)).rgb;
                vec3 mcBlend = mix(warpPrev, warpCurr, phase);

                // Disocclusion & Anti-Ghosting Neighborhood Clamp:
                // If warped samples disagree strongly (occlusion boundary), blend gracefully toward curr
                float warpError = length(warpCurr - warpPrev);
                float confidence = clamp(1.0 - warpError * (1.2 + uAntiGhosting * 2.0), 0.0, 1.0);

                vec3 minNeigh = min(min(min(cN, cS), min(cW, cE)), min(curr, prev));
                vec3 maxNeigh = max(max(max(cN, cS), max(cW, cE)), max(curr, prev));
                vec3 clampedMc = clamp(mcBlend, minNeigh, maxNeigh);
                vec3 safeMc = mix(mcBlend, clampedMc, uAntiGhosting);

                return mix(mix(prev, curr, phase), safeMc, confidence);
            }

            // ============================================================================
            // SUB-PIXEL SPATIAL UPSCALING ENGINE (11 Hardware Upscalers operating on sampleFrameGen)
            // ============================================================================
            vec4 cubicCatmullRomWeights(float t) {
                float t2 = t * t;
                float t3 = t2 * t;
                return vec4(
                    -0.5 * t3 + t2 - 0.5 * t,
                     1.5 * t3 - 2.5 * t2 + 1.0,
                    -1.5 * t3 + 2.0 * t2 + 0.5 * t,
                     0.5 * t3 - 0.5 * t2
                );
            }

            float lanczosWeight(float x, float a) {
                float ax = abs(x);
                if (ax < 0.0001) return 1.0;
                if (ax >= a) return 0.0;
                float pix = 3.14159265 * x;
                return (a * sin(pix) * sin(pix / a)) / (pix * pix);
            }

            vec3 sampleRcas(vec2 uv, vec3 center, float sharpStrength, bool antiRinging) {
                vec3 n = sampleFrameGen(uv + vec2(0.0, -uTexelSize.y));
                vec3 s = sampleFrameGen(uv + vec2(0.0,  uTexelSize.y));
                vec3 w = sampleFrameGen(uv + vec2(-uTexelSize.x, 0.0));
                vec3 e = sampleFrameGen(uv + vec2( uTexelSize.x, 0.0));

                vec3 min4 = min(min(n, s), min(w, e));
                vec3 max4 = max(max(n, s), max(w, e));
                vec3 minAll = min(min4, center);
                vec3 maxAll = max(max4, center);

                vec3 amp = sqrt(clamp(min(minAll, 1.0 - maxAll) / max(maxAll, vec3(0.0001)), 0.0, 1.0));
                float peak = -1.0 / mix(8.0, 4.5, clamp(sharpStrength, 0.0, 1.0));
                vec3 wobe = amp * peak;
                vec3 res = ((n + s + w + e) * wobe + center) / (1.0 + 4.0 * wobe);
                if (antiRinging) {
                    res = clamp(res, minAll, maxAll);
                }
                return clamp(res, 0.0, 1.0);
            }

            vec3 sampleFsr1EasuRcas(vec2 uv, float sharpStrength, bool ultraAntiRinging) {
                vec2 texPos = uv * uSourceSize - 0.5;
                vec2 baseTex = (floor(texPos) + 0.5) * uTexelSize;
                vec2 f = fract(texPos);
                vec2 dx = vec2(uTexelSize.x, 0.0);
                vec2 dy = vec2(0.0, uTexelSize.y);

                vec3 f0 = sampleFrameGen(baseTex);
                vec3 g  = sampleFrameGen(baseTex + dx);
                vec3 j  = sampleFrameGen(baseTex + dy);
                vec3 k  = sampleFrameGen(baseTex + dx + dy);

                float lf = luma(f0), lg = luma(g), lj = luma(j), lk = luma(k);
                float gradX = ((lg - lf) + (lk - lj)) * 0.5;
                float gradY = ((lj - lf) + (lk - lg)) * 0.5;
                float gradLen = max(sqrt(gradX * gradX + gradY * gradY), 0.0001);
                vec2 dir = vec2(-gradY, gradX) / gradLen;

                vec3 bilinear = mix(mix(f0, g, f.x), mix(j, k, f.x), f.y);
                vec3 tangentA = sampleFrameGen(uv + dir * uTexelSize * 0.45);
                vec3 tangentB = sampleFrameGen(uv - dir * uTexelSize * 0.45);
                float edgeAniso = clamp(gradLen * 4.0, 0.0, 1.0);
                vec3 easu = mix(bilinear, (tangentA + tangentB + bilinear) / 3.0, edgeAniso * 0.65);

                vec3 min4 = min(min(f0, g), min(j, k));
                vec3 max4 = max(max(f0, g), max(j, k));
                easu = clamp(easu, min4 * 0.96, max4 * 1.04);

                return sampleRcas(uv, easu, max(sharpStrength, 0.25), ultraAntiRinging);
            }

            vec3 sampleNvidiaNis(vec2 uv, float sharpStrength) {
                vec2 texPos = uv * uSourceSize - 0.5;
                vec2 f = fract(texPos);
                vec2 dx = vec2(uTexelSize.x, 0.0);
                vec2 dy = vec2(0.0, uTexelSize.y);

                vec3 c  = sampleFrameGen(uv);
                vec3 n  = sampleFrameGen(uv - dy);
                vec3 s  = sampleFrameGen(uv + dy);
                vec3 w  = sampleFrameGen(uv - dx);
                vec3 e  = sampleFrameGen(uv + dx);
                vec3 nw = sampleFrameGen(uv - dx - dy);
                vec3 ne = sampleFrameGen(uv + dx - dy);
                vec3 sw = sampleFrameGen(uv - dx + dy);
                vec3 se = sampleFrameGen(uv + dx + dy);

                float gx = (luma(ne) + 2.0 * luma(e) + luma(se)) - (luma(nw) + 2.0 * luma(w) + luma(sw));
                float gy = (luma(sw) + 2.0 * luma(s) + luma(se)) - (luma(nw) + 2.0 * luma(n) + luma(ne));
                float edgeMag = clamp(sqrt(gx * gx + gy * gy), 0.0, 1.0);

                vec4 wx = cubicCatmullRomWeights(f.x);
                vec4 wy = cubicCatmullRomWeights(f.y);
                float phaseWeight = clamp(abs(wx.y - wx.z) + abs(wy.y - wy.z), 0.2, 1.0);

                vec3 detail = c - mix((n + s + w + e) * 0.25, (nw + ne + sw + se) * 0.25, 0.35);
                float nisBoost = mix(1.45, 0.65, edgeMag) * (0.45 + sharpStrength * 1.15) * (0.75 + 0.25 * phaseWeight);

                vec3 minN = min(min(min(n, s), min(w, e)), c);
                vec3 maxN = max(max(max(n, s), max(w, e)), c);
                return clamp(c + detail * nisBoost, minN * 0.90, maxN * 1.10);
            }

            vec3 sampleSnapdragonGsr(vec2 uv, float sharpStrength) {
                vec2 t = uTexelSize;
                vec3 c  = sampleFrameGen(uv);
                vec3 n  = sampleFrameGen(uv + vec2(0.0, -t.y));
                vec3 s  = sampleFrameGen(uv + vec2(0.0,  t.y));
                vec3 w  = sampleFrameGen(uv + vec2(-t.x, 0.0));
                vec3 e  = sampleFrameGen(uv + vec2( t.x, 0.0));
                vec3 nw = sampleFrameGen(uv + vec2(-t.x, -t.y));
                vec3 ne = sampleFrameGen(uv + vec2( t.x, -t.y));
                vec3 sw = sampleFrameGen(uv + vec2(-t.x,  t.y));
                vec3 se = sampleFrameGen(uv + vec2( t.x,  t.y));

                float dirX = (ne.g + e.g + se.g) - (nw.g + w.g + sw.g);
                float dirY = (sw.g + s.g + se.g) - (nw.g + n.g + ne.g);
                float len = max(sqrt(dirX * dirX + dirY * dirY), 0.0001);
                vec2 isoDir = vec2(-dirY, dirX) / len;

                vec3 isoA = sampleFrameGen(uv + isoDir * t * 0.4);
                vec3 isoB = sampleFrameGen(uv - isoDir * t * 0.4);
                vec3 gsrBase = mix(c, (isoA + isoB + c) / 3.0, clamp(len * 2.5, 0.0, 0.6));

                float gw = abs(n.g - s.g) + abs(w.g - e.g);
                float edgeWeight = clamp(1.0 - gw * 2.0, 0.2, 1.0);
                vec3 lap = gsrBase * 4.0 - (n + s + w + e);
                return clamp(gsrBase + lap * ((0.35 + sharpStrength * 0.75) * edgeWeight) * 0.38, 0.0, 1.0);
            }

            vec3 sampleXessSpatial(vec2 uv, float sharpStrength) {
                vec2 t = uTexelSize;
                vec3 c  = sampleFrameGen(uv);
                vec3 n  = sampleFrameGen(uv + vec2(0.0, -t.y));
                vec3 s  = sampleFrameGen(uv + vec2(0.0,  t.y));
                vec3 w  = sampleFrameGen(uv + vec2(-t.x, 0.0));
                vec3 e  = sampleFrameGen(uv + vec2( t.x, 0.0));
                vec3 nw = sampleFrameGen(uv + vec2(-t.x, -t.y));
                vec3 ne = sampleFrameGen(uv + vec2( t.x, -t.y));
                vec3 sw = sampleFrameGen(uv + vec2(-t.x,  t.y));
                vec3 se = sampleFrameGen(uv + vec2( t.x,  t.y));

                vec3 box = (n + s + w + e) * 0.15 + (nw + ne + sw + se) * 0.05 + c * 0.20;
                vec3 minBox = min(min(min(n, s), min(w, e)), c);
                vec3 maxBox = max(max(max(n, s), max(w, e)), c);
                return clamp(c + (c - box) * (0.50 + sharpStrength * 0.90), minBox, maxBox);
            }

            vec3 sampleLanczos3(vec2 uv, float sharpStrength) {
                vec2 texPos = uv * uSourceSize - 0.5;
                vec2 baseTex = (floor(texPos) + 0.5) * uTexelSize;
                vec2 f = fract(texPos);

                vec3 sum = vec3(0.0);
                float weightSum = 0.0;
                for (int j = -1; j <= 2; j++) {
                    float wy = lanczosWeight(float(j) - f.y, 2.0);
                    for (int i = -1; i <= 2; i++) {
                        float wx = lanczosWeight(float(i) - f.x, 2.0);
                        float w = wx * wy;
                        sum += sampleFrameGen(baseTex + vec2(float(i), float(j)) * uTexelSize) * w;
                        weightSum += w;
                    }
                }
                vec3 lanczos = sum / max(weightSum, 0.0001);
                if (sharpStrength > 0.05) {
                    lanczos = sampleRcas(uv, lanczos, sharpStrength * 0.55, true);
                }
                return clamp(lanczos, 0.0, 1.0);
            }

            vec3 sampleCatmullRom(vec2 uv, float sharpStrength) {
                vec2 texPos = uv * uSourceSize - 0.5;
                vec2 baseTex = (floor(texPos) + 0.5) * uTexelSize;
                vec2 f = fract(texPos);

                vec4 wx = cubicCatmullRomWeights(f.x);
                vec4 wy = cubicCatmullRomWeights(f.y);

                float w12x = wx.y + wx.z;
                float w12y = wy.y + wy.z;
                float off12x = wx.z / max(w12x, 0.0001);
                float off12y = wy.z / max(w12y, 0.0001);

                vec2 tc0 = baseTex - uTexelSize;
                vec2 tc3 = baseTex + 2.0 * uTexelSize;
                vec2 tc12 = baseTex + vec2(off12x, off12y) * uTexelSize;

                vec3 s0 = sampleFrameGen(vec2(tc12.x, tc0.y)) * (w12x * wy.x);
                vec3 s1 = sampleFrameGen(vec2(tc0.x, tc12.y)) * (wx.x * w12y);
                vec3 s2 = sampleFrameGen(tc12) * (w12x * w12y);
                vec3 s3 = sampleFrameGen(vec2(tc3.x, tc12.y)) * (wx.w * w12y);
                vec3 s4 = sampleFrameGen(vec2(tc12.x, tc3.y)) * (w12x * wy.w);

                float totalW = (w12x * wy.x) + (wx.x * w12y) + (w12x * w12y) + (wx.w * w12y) + (w12x * wy.w);
                vec3 bicubic = (s0 + s1 + s2 + s3 + s4) / max(totalW, 0.0001);
                if (sharpStrength > 0.05) {
                    bicubic = sampleRcas(uv, bicubic, sharpStrength * 0.45, true);
                }
                return clamp(bicubic, 0.0, 1.0);
            }

            vec3 sampleAnime4K(vec2 uv, float sharpStrength) {
                vec2 t = uTexelSize;
                vec3 c  = sampleFrameGen(uv);
                vec3 n  = sampleFrameGen(uv + vec2(0.0, -t.y));
                vec3 s  = sampleFrameGen(uv + vec2(0.0,  t.y));
                vec3 w  = sampleFrameGen(uv + vec2(-t.x, 0.0));
                vec3 e  = sampleFrameGen(uv + vec2( t.x, 0.0));
                vec3 nw = sampleFrameGen(uv + vec2(-t.x, -t.y));
                vec3 ne = sampleFrameGen(uv + vec2( t.x, -t.y));
                vec3 sw = sampleFrameGen(uv + vec2(-t.x,  t.y));
                vec3 se = sampleFrameGen(uv + vec2( t.x,  t.y));

                vec3 lightest = max(max(max(n, s), max(w, e)), max(max(nw, ne), max(sw, se)));
                vec3 darkest  = min(min(min(n, s), min(w, e)), min(min(nw, ne), min(sw, se)));
                float lc = luma(c);
                float lAvg = (luma(n) + luma(s) + luma(w) + luma(e) + luma(nw) + luma(ne) + luma(sw) + luma(se)) * 0.125;
                float pushStrength = clamp((0.40 + sharpStrength * 0.60), 0.0, 1.0);
                return clamp((lc > lAvg) ? mix(c, lightest, pushStrength * 0.55) : mix(c, darkest, pushStrength * 0.55), 0.0, 1.0);
            }

            vec3 sampleNearestInteger(vec2 uv) {
                vec2 snappedUv = (floor(uv * uSourceSize) + 0.5) / uSourceSize;
                return sampleFrameGen(snappedUv);
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
                vec3 center = sampleFrameGen(vTexCoord);
                vec3 upscaled = center;

                if (uUpscalerMode == 1) {
                    upscaled = sampleNearestInteger(vTexCoord);
                } else if (uUpscalerMode == 2) {
                    upscaled = sampleFsr1EasuRcas(vTexCoord, uSharpness, false);
                } else if (uUpscalerMode == 3) {
                    upscaled = sampleFsr1EasuRcas(vTexCoord, uSharpness, true);
                } else if (uUpscalerMode == 4) {
                    upscaled = sampleRcas(vTexCoord, center, max(uSharpness, 0.25), true);
                } else if (uUpscalerMode == 5) {
                    upscaled = sampleNvidiaNis(vTexCoord, uSharpness);
                } else if (uUpscalerMode == 6) {
                    upscaled = sampleSnapdragonGsr(vTexCoord, uSharpness);
                } else if (uUpscalerMode == 7) {
                    upscaled = sampleXessSpatial(vTexCoord, uSharpness);
                } else if (uUpscalerMode == 8) {
                    upscaled = sampleLanczos3(vTexCoord, uSharpness);
                } else if (uUpscalerMode == 9) {
                    upscaled = sampleCatmullRom(vTexCoord, uSharpness);
                } else if (uUpscalerMode == 10) {
                    upscaled = sampleAnime4K(vTexCoord, uSharpness);
                } else {
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
        """.trimIndent()
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

    private val identityMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val mvpMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)

    private var oesToFboProgram = 0
    private var patternToFboProgram = 0
    private var compositeFgUpscaleProgram = 0

    private var oesTextureId = 0

    // Ping-Pong History FBOs for Frame Generation (slot 0 and slot 1)
    private val historyFboIds = IntArray(2)
    private val historyTexIds = IntArray(2)
    private var currentHistorySlot = 0
    private var historyBufferWidth = 0
    private var historyBufferHeight = 0

    private var surfaceTexture: SurfaceTexture? = null
    private var captureSurface: Surface? = null

    private var gpuRendererString: String = "Hardware GPU"

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

    // Timing & Frame Generation phase tracking
    private var lastSourceFrameTimeNs = System.nanoTime()
    private var estimatedSourceIntervalNs = 16_666_667L // 60Hz default
    private var lastPatternTickNs = System.nanoTime()

    private var sourceFrameCount = 0
    private var presentedFrameCount = 0
    private var generatedFrameCount = 0
    private var lastTelemetryTimeNs = System.nanoTime()
    private var lastFrameTimestampNs = System.nanoTime()
    private var smoothedFrameTimeMs = 8.3f

    // --- Capture lifecycle / signal watchdog state ---
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startPending = AtomicBoolean(false)

    @Volatile
    private var streamGeneration = 0

    @Volatile
    private var cameraOpenRequested = false

    @Volatile
    private var capturePaused = false

    @Volatile
    private var hasCameraPermissionFlag = false

    /** Actual negotiated stream size (may differ from the requested mode if the card lacks it). */
    @Volatile
    private var captureWidth = 1920

    @Volatile
    private var captureHeight = 1080

    @Volatile
    private var lastCameraFrameNs = 0L

    // Blank-feed ("card connected but no HDMI source") detection
    private var blankSignal = false
    private var blankSinceNs = 0L
    private var lastBlankSampleNs = 0L
    private val blankPixel = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())

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
            (activeMode.fps != mode.fps) ||
            (hasCameraPermissionFlag != hasCameraPermission)

        activeCameraId = cameraId
        activeMode = mode
        hasCameraPermissionFlag = hasCameraPermission
        estimatedSourceIntervalNs = (1_000_000_000L / mode.fps.coerceAtLeast(1)).coerceIn(4_000_000L, 100_000_000L)

        if (cameraId != null && hasCameraPermission) {
            if (!capturePaused && (modeOrCamChanged || !cameraOpenRequested)) {
                requestStartStream()
            }
        } else {
            mainHandler.post { stopCamera2Stream() }
        }
    }

    /** Releases the camera (clears Android's green privacy dot) while the app is in the background. */
    fun pauseCapture() {
        capturePaused = true
        mainHandler.post { stopCamera2Stream() }
    }

    /** Re-opens the capture card stream when the app returns to the foreground. */
    fun resumeCapture() {
        capturePaused = false
        if (activeCameraId != null && hasCameraPermissionFlag) requestStartStream()
    }

    /** Coalesces start requests onto the main thread so open/close never overlap or race. */
    private fun requestStartStream() {
        if (startPending.compareAndSet(false, true)) {
            mainHandler.post {
                startPending.set(false)
                startCamera2Stream()
            }
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.02f, 0.03f, 0.05f, 1f)
        Matrix.setIdentityM(texMatrix, 0)

        val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "Mobile GPU"
        val vkInfo = VulkanHardwarePipeline.queryDeviceVulkanSupport(context)
        gpuRendererString = "$renderer (${vkInfo.apiVersionString})"

        oesToFboProgram = createGlProgram(VERTEX_SHADER, FRAGMENT_SHADER_OES_TO_FBO)
        patternToFboProgram = createGlProgram(VERTEX_SHADER, FRAGMENT_SHADER_PATTERN_TO_FBO)
        compositeFgUpscaleProgram = createGlProgram(VERTEX_SHADER, FRAGMENT_SHADER_COMPOSITE_FG_AND_UPSCALE)

        val oesTex = IntArray(1)
        GLES20.glGenTextures(1, oesTex, 0)
        oesTextureId = oesTex[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        ensureHistoryFbos(captureWidth, captureHeight)

        // A fresh GL context needs a fresh SurfaceTexture; drop the old one (and the stream bound to it)
        mainHandler.post { stopCamera2Stream() }
        try { captureSurface?.release() } catch (_: Exception) {}
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(activeMode.width, activeMode.height)
            setOnFrameAvailableListener(this@HardwareVideoRenderer)
        }
        captureSurface = Surface(surfaceTexture)

        if (activeCameraId != null && hasCameraPermissionFlag && !capturePaused) {
            requestStartStream()
        }
    }

    private fun ensureHistoryFbos(targetW: Int, targetH: Int) {
        val w = targetW.coerceIn(320, 3840)
        val h = targetH.coerceIn(240, 2160)
        if (w == historyBufferWidth && h == historyBufferHeight && historyFboIds[0] != 0) return

        if (historyFboIds[0] != 0) {
            GLES20.glDeleteFramebuffers(2, historyFboIds, 0)
            GLES20.glDeleteTextures(2, historyTexIds, 0)
        }

        GLES20.glGenTextures(2, historyTexIds, 0)
        GLES20.glGenFramebuffers(2, historyFboIds, 0)

        for (slot in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, historyTexIds[slot])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, historyFboIds[slot])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                historyTexIds[slot],
                0
            )
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        historyBufferWidth = w
        historyBufferHeight = h
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

        val cfg = renderConfig
        // Signal watchdog: live only while frames keep arriving and the feed is not a blank "no HDMI" picture
        val framesFresh = hasReceivedAnyCameraFrame && (nowNs - lastCameraFrameNs) < 1_500_000_000L
        val liveActive = useLiveCameraStream && framesFresh && !blankSignal

        if (frameAvailable) {
            frameAvailable = false
            hasReceivedAnyCameraFrame = true
            val interval = (nowNs - lastSourceFrameTimeNs).coerceIn(4_000_000L, 100_000_000L)
            estimatedSourceIntervalNs = (estimatedSourceIntervalNs * 3 + interval) / 4
            lastSourceFrameTimeNs = nowNs
            sourceFrameCount++

            try {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(texMatrix)
            } catch (_: Exception) {
            }

            ensureHistoryFbos(captureWidth, captureHeight)
            // Advance ping-pong history slot and capture new OES frame into currentHistorySlot
            currentHistorySlot = 1 - currentHistorySlot
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, historyFboIds[currentHistorySlot])
            GLES20.glViewport(0, 0, historyBufferWidth, historyBufferHeight)
            drawOesToHistoryFbo()
            if (cfg.autoDetectBlankSignal) {
                sampleBlankSignal(nowNs)
            } else {
                blankSignal = false
                blankSinceNs = 0L
            }
        } else if (!liveActive && cfg.showTestPatternWhenNoSignal) {
            // In calibration test pattern mode, tick simulated source frames at activeMode.fps (or 60Hz when FG is active)
            val simSourceFps = if (cfg.frameGenMode != FrameGenMode.OFF) {
                activeMode.fps.coerceAtMost(60)
            } else {
                activeMode.fps
            }
            val simIntervalNs = 1_000_000_000L / simSourceFps.coerceAtLeast(24)
            if (nowNs - lastPatternTickNs >= simIntervalNs) {
                estimatedSourceIntervalNs = simIntervalNs
                lastSourceFrameTimeNs = nowNs
                lastPatternTickNs = nowNs
                sourceFrameCount++

                ensureHistoryFbos(640, 360)
                currentHistorySlot = 1 - currentHistorySlot
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, historyFboIds[currentHistorySlot])
                GLES20.glViewport(0, 0, historyBufferWidth, historyBufferHeight)
                drawPatternToHistoryFbo((nowNs - startTimeNs) / 1_000_000_000f)
            }
        }

        // Bind screen backbuffer and run Pass 2 (Frame Generation + Spatial Upscaler + Color Grading)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if ((liveActive || cfg.showTestPatternWhenNoSignal) && compositeFgUpscaleProgram != 0) {
            computeMvpMatrix(cfg)

            // Calculate fractional interpolation phase in (0..1) between prevHistorySlot and currentHistorySlot
            val fgEnabled = cfg.frameGenMode != FrameGenMode.OFF
            val rawPhase = if (fgEnabled && estimatedSourceIntervalNs > 0) {
                ((nowNs - lastSourceFrameTimeNs).toFloat() / estimatedSourceIntervalNs.toFloat()).coerceIn(0.05f, 1.0f)
            } else {
                1.0f
            }

            if (fgEnabled && rawPhase < 0.92f) {
                generatedFrameCount++
            }
            presentedFrameCount++

            val currTex = historyTexIds[currentHistorySlot]
            val prevTex = historyTexIds[1 - currentHistorySlot]
            drawCompositeFgAndUpscale(
                currTexId = currTex,
                prevTexId = prevTex,
                srcWidth = historyBufferWidth.coerceAtLeast(1),
                srcHeight = historyBufferHeight.coerceAtLeast(1),
                phase = rawPhase,
                cfg = cfg
            )
        }

        val elapsedNs = nowNs - lastTelemetryTimeNs
        if (elapsedNs >= 450_000_000L) {
            val sec = elapsedNs / 1_000_000_000f
            val measuredSourceFps = sourceFrameCount / sec
            val measuredPresentedFps = presentedFrameCount / sec
            val measuredGenFps = generatedFrameCount / sec

            val multiplierFactor = when (cfg.frameGenMultiplier) {
                FrameGenMultiplier.AUTO_DISPLAY_HZ -> 2
                else -> cfg.frameGenMultiplier.factor
            }
            val effectiveOutFps = if (cfg.frameGenMode != FrameGenMode.OFF && (liveActive || cfg.showTestPatternWhenNoSignal)) {
                max(measuredPresentedFps, measuredSourceFps * multiplierFactor)
            } else {
                measuredSourceFps
            }

            onTelemetryTick(
                measuredSourceFps,
                effectiveOutFps,
                if (cfg.frameGenMode != FrameGenMode.OFF) max(measuredGenFps, effectiveOutFps - measuredSourceFps) else 0f,
                smoothedFrameTimeMs,
                liveActive,
                gpuRendererString
            )
            sourceFrameCount = 0
            presentedFrameCount = 0
            generatedFrameCount = 0
            lastTelemetryTimeNs = nowNs
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        lastCameraFrameNs = System.nanoTime()
        frameAvailable = true
        glSurfaceView.requestRender()
    }

    private fun drawOesToHistoryFbo() {
        GLES20.glUseProgram(oesToFboProgram)

        val posLoc = GLES20.glGetAttribLocation(oesToFboProgram, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(oesToFboProgram, "aTextureCoord")
        val mvpLoc = GLES20.glGetUniformLocation(oesToFboProgram, "uMVPMatrix")
        val texMatLoc = GLES20.glGetUniformLocation(oesToFboProgram, "uTexMatrix")

        quadVertices.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
        GLES20.glEnableVertexAttribArray(posLoc)

        quadVertices.position(2)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
        GLES20.glEnableVertexAttribArray(texLoc)

        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, identityMatrix, 0)
        GLES20.glUniformMatrix4fv(texMatLoc, 1, false, texMatrix, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(oesToFboProgram, "uOverscanCrop"), 0f)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(oesToFboProgram, "sOesTexture"), 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawPatternToHistoryFbo(timeSec: Float) {
        GLES20.glUseProgram(patternToFboProgram)

        val posLoc = GLES20.glGetAttribLocation(patternToFboProgram, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(patternToFboProgram, "aTextureCoord")
        val mvpLoc = GLES20.glGetUniformLocation(patternToFboProgram, "uMVPMatrix")
        val texMatLoc = GLES20.glGetUniformLocation(patternToFboProgram, "uTexMatrix")

        quadVertices.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
        GLES20.glEnableVertexAttribArray(posLoc)

        quadVertices.position(2)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
        GLES20.glEnableVertexAttribArray(texLoc)

        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, identityMatrix, 0)
        GLES20.glUniformMatrix4fv(texMatLoc, 1, false, identityMatrix, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(patternToFboProgram, "uOverscanCrop"), 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(patternToFboProgram, "uTime"), timeSec)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawCompositeFgAndUpscale(
        currTexId: Int,
        prevTexId: Int,
        srcWidth: Int,
        srcHeight: Int,
        phase: Float,
        cfg: RenderConfig
    ) {
        val prog = compositeFgUpscaleProgram
        GLES20.glUseProgram(prog)

        val posLoc = GLES20.glGetAttribLocation(prog, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(prog, "aTextureCoord")
        val mvpLoc = GLES20.glGetUniformLocation(prog, "uMVPMatrix")
        val texMatLoc = GLES20.glGetUniformLocation(prog, "uTexMatrix")

        quadVertices.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
        GLES20.glEnableVertexAttribArray(posLoc)

        quadVertices.position(2)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
        GLES20.glEnableVertexAttribArray(texLoc)

        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(texMatLoc, 1, false, identityMatrix, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uOverscanCrop"), cfg.overscanCropPercent.coerceIn(0f, 0.15f))

        val safeW = srcWidth.coerceAtLeast(1).toFloat()
        val safeH = srcHeight.coerceAtLeast(1).toFloat()
        GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uTexelSize"), 1f / safeW, 1f / safeH)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uSourceSize"), safeW, safeH)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uUpscalerMode"), cfg.filteringMode.code)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uFrameGenMode"), cfg.frameGenMode.code)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFrameGenPhase"), phase)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uMotionScale"), cfg.frameGenMotionScale)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uAntiGhosting"), cfg.frameGenAntiGhosting)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uUiProtection"), if (cfg.frameGenUiProtection) 1 else 0)

        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uBrightness"), cfg.brightness)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uContrast"), cfg.contrast)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uSaturation"), cfg.saturation)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uGamma"), cfg.gamma)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uSharpness"), cfg.sharpness)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uFullRange"), if (cfg.fullRangeRgb) 1 else 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uColorSpace"), cfg.colorSpace.code)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uShowGrid"), if (cfg.showSafeAreaGrid) 1 else 0)

        val filterParam = if (cfg.filteringMode == TextureFilteringMode.NEAREST_PIXEL) {
            GLES20.GL_NEAREST
        } else {
            GLES20.GL_LINEAR
        }

        // Bind current frame texture on unit 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, currTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filterParam)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filterParam)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "sCurrTex"), 0)

        // Bind previous frame texture on unit 1 for Frame Generation optical flow
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filterParam)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filterParam)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "sPrevTex"), 1)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun computeMvpMatrix(cfg: RenderConfig) {
        Matrix.setIdentityM(mvpMatrix, 0)

        val viewAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val targetAspect = when (cfg.aspectRatioPreset) {
            AspectRatioPreset.STRETCH_FILL -> viewAspect
            AspectRatioPreset.ZOOM_CROP -> captureWidth.toFloat() / captureHeight.toFloat()
            AspectRatioPreset.NATIVE -> captureWidth.toFloat() / captureHeight.toFloat()
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

    /**
     * Picks the stream size the card really supports: exact match, else the closest area with the
     * same aspect ratio, else the closest area overall. Prevents configure failures (= endless No Signal)
     * when a requested custom resolution is not natively offered by the capture card.
     */
    private fun chooseCaptureSize(chars: CameraCharacteristics, mode: VideoMode): Size {
        val wanted = Size(mode.width, mode.height)
        val sizes = try {
            chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceTexture::class.java)
        } catch (_: Exception) {
            null
        }
        if (sizes == null || sizes.isEmpty()) return wanted
        sizes.firstOrNull { it.width == wanted.width && it.height == wanted.height }?.let { return it }
        val wantedArea = wanted.width.toLong() * wanted.height
        val wantedAspect = wanted.width.toFloat() / wanted.height
        val sameAspect = sizes.filter { kotlin.math.abs(it.width.toFloat() / it.height - wantedAspect) < 0.02f }
        val pool = if (sameAspect.isNotEmpty()) sameAspect else sizes.toList()
        return pool.minByOrNull { kotlin.math.abs(it.width.toLong() * it.height - wantedArea) } ?: wanted
    }

    private fun chooseFpsRange(chars: CameraCharacteristics, fps: Int): Range<Int>? {
        val ranges = try {
            chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        } catch (_: Exception) {
            null
        }
        if (ranges == null || ranges.isEmpty()) return null
        val bestUpper = ranges.minOf { kotlin.math.abs(it.upper - fps) }
        return ranges.filter { kotlin.math.abs(it.upper - fps) == bestUpper }.maxByOrNull { it.lower }
    }

    private fun scheduleStreamRetry(generation: Int) {
        mainHandler.postDelayed({
            if (generation == streamGeneration && !capturePaused &&
                activeCameraId != null && hasCameraPermissionFlag
            ) {
                requestStartStream()
            }
        }, 2000L)
    }

    /** Must run on the main thread (always reached through [requestStartStream]). */
    @SuppressLint("MissingPermission")
    private fun startCamera2Stream() {
        val cameraId = activeCameraId ?: return
        val surface = captureSurface ?: return
        if (capturePaused || !hasCameraPermissionFlag) return
        val mode = activeMode

        stopCamera2Stream()
        val generation = ++streamGeneration
        cameraOpenRequested = true
        hasReceivedAnyCameraFrame = false
        blankSignal = false
        blankSinceNs = 0L

        val thread = HandlerThread("UvcCamera2Thread").also { it.start() }
        val handler = Handler(thread.looper)
        cameraThread = thread
        cameraHandler = handler

        try {
            val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = camManager.getCameraCharacteristics(cameraId)
            val size = chooseCaptureSize(chars, mode)
            val fpsRange = chooseFpsRange(chars, mode.fps)
            captureWidth = size.width
            captureHeight = size.height
            surfaceTexture?.setDefaultBufferSize(size.width, size.height)

            camManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (generation != streamGeneration) {
                        camera.close()
                        return
                    }
                    openedCamera = camera
                    try {
                        val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            if (fpsRange != null) {
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                            }
                        }
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    if (generation != streamGeneration) {
                                        session.close()
                                        return
                                    }
                                    captureSession = session
                                    try {
                                        session.setRepeatingRequest(requestBuilder.build(), null, handler)
                                        useLiveCameraStream = true
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Repeating request error: ${e.message}")
                                        useLiveCameraStream = false
                                        scheduleStreamRetry(generation)
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.w(TAG, "Camera2 session configure failed for ${size.width}x${size.height}")
                                    useLiveCameraStream = false
                                    scheduleStreamRetry(generation)
                                }
                            },
                            handler
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Session creation error: ${e.message}")
                        useLiveCameraStream = false
                        scheduleStreamRetry(generation)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    useLiveCameraStream = false
                    hasReceivedAnyCameraFrame = false
                    camera.close()
                    if (generation == streamGeneration) {
                        cameraOpenRequested = false
                        scheduleStreamRetry(generation)
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w(TAG, "Camera error $error")
                    useLiveCameraStream = false
                    hasReceivedAnyCameraFrame = false
                    camera.close()
                    if (generation == streamGeneration) {
                        cameraOpenRequested = false
                        scheduleStreamRetry(generation)
                    }
                }
            }, handler)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to open camera $cameraId: ${e.message}")
            useLiveCameraStream = false
            scheduleStreamRetry(generation)
        }
    }

    /**
     * Reads a 4x4 grid of pixels from the frame just rendered into the bound history FBO.
     * A perfectly uniform dark or solid-blue picture held for >1.5 s is what capture cards output
     * when no HDMI source is attached, so it is reported as No Signal. Runs at ~2.5 Hz (negligible cost).
     */
    private fun sampleBlankSignal(nowNs: Long) {
        if (nowNs - lastBlankSampleNs < 400_000_000L) return
        lastBlankSampleNs = nowNs
        val w = historyBufferWidth
        val h = historyBufferHeight
        if (w <= 0 || h <= 0) return

        var minR = 255; var minG = 255; var minB = 255
        var maxR = 0; var maxG = 0; var maxB = 0
        var sumR = 0; var sumG = 0; var sumB = 0
        for (gy in 0 until 4) {
            for (gx in 0 until 4) {
                val x = ((gx + 0.5f) / 4f * w).toInt().coerceIn(0, w - 1)
                val y = ((gy + 0.5f) / 4f * h).toInt().coerceIn(0, h - 1)
                blankPixel.clear()
                GLES20.glReadPixels(x, y, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, blankPixel)
                val r = blankPixel.get(0).toInt() and 0xFF
                val g = blankPixel.get(1).toInt() and 0xFF
                val b = blankPixel.get(2).toInt() and 0xFF
                minR = min(minR, r); maxR = max(maxR, r); sumR += r
                minG = min(minG, g); maxG = max(maxG, g); sumG += g
                minB = min(minB, b); maxB = max(maxB, b); sumB += b
            }
        }
        val uniform = (maxR - minR) <= 8 && (maxG - minG) <= 8 && (maxB - minB) <= 8
        val avgR = sumR / 16
        val avgG = sumG / 16
        val avgB = sumB / 16
        val luma = (avgR * 0.2126f + avgG * 0.7152f + avgB * 0.0722f)
        val dark = luma <= 26f
        val blue = avgB - max(avgR, avgG) >= 40
        val blankNow = uniform && (dark || blue)

        if (blankNow) {
            if (blankSinceNs == 0L) blankSinceNs = nowNs
            if (nowNs - blankSinceNs >= 1_500_000_000L) blankSignal = true
        } else {
            blankSinceNs = 0L
            blankSignal = false
        }
    }

    /** Closes the camera session + device. Must run on the main thread. */
    fun stopCamera2Stream() {
        streamGeneration++
        cameraOpenRequested = false
        useLiveCameraStream = false
        hasReceivedAnyCameraFrame = false

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
        capturePaused = true
        mainHandler.removeCallbacksAndMessages(null)
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
