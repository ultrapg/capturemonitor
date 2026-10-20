# CaptureMonitor

**CaptureMonitor** is a high-performance, hardware-accelerated, open-source Android app (Material Design 3) that converts any Android phone or tablet into a low-latency external HDMI/SDI monitor using a USB or USB-C capture card.

Built as a clean, zero-bloat, open-source alternative to apps like **Noir**, with first-class support for **Magewell USB Capture HDMI** cards, Elgato, AVerMedia, MacroSilicon (MS2109 / MS2130), EVGA, Razer, and all standard UVC capture devices.

---

## Key Features (v0.5.0)

- **Open Frame Generation & Motion Interpolation Suite (Ping-Pong Temporal FBO Pipeline)**:
  - Captures every incoming UVC frame into a dual-frame ping-pong `GL_TEXTURE_2D` FBO ring (`sPrevTex` + `sCurrTex`) to synthesize intermediate sub-frames at the Android display's native refresh rate (`90Hz`, `120Hz`, `144Hz`, `240Hz`):
  - **WinFG 1.0 (Open Windowed Optical Flow)**: Bidirectional spatial-temporal Lucas-Kanade optical flow estimation with motion-vector warping and disocclusion clamping
  - **AMD FSR 3 Open Fluid Motion (FMMF)**: Coarse + fine pyramid optical flow search with game-motion luminance weighting and non-linear confidence blending
  - **RIFE-Lite Flow (Real-Time Intermediate Flow)**: Two-stage coarse-to-fine intermediate flow estimation with bidirectional residual warping
  - **Snapdragon GSR 2 Temporal (Adreno Motion)**: Mobile tile-cache-optimized green-channel luma gradient motion vectors with temporal neighborhood clamping
  - **OpenMEMC / SVP 8-Tap Block Search**: 8-direction Diamond Sum-of-Absolute-Differences (SAD) block matching with sub-pixel refinement
  - **Full FrameGen Controls**: Target Frame Rate Multiplier (`Auto Display Hz`, `2×`, `3×`, `4×`), Motion Vector Search Scale (`0.25×` to `2.50×`), Disocclusion & Anti-Ghosting (`0%` to `100%`), and Static HUD / Crosshair / Text Edge Protection.
- **Vulkan 1.3 & OpenGL ES 3.0 Hardware Backend Switcher (Vulkan Default)**:
  - Defaults to **Vulkan 1.3 (`Vulkan 1.3 Hardware Compute + AHardwareBuffer Zero-Copy + AGSL SPIR-V RenderEffect`)** on Android 13+ (SkiaVk RenderThread) for low-overhead hardware acceleration across all modern GPUs, with instant one-tap switching between **Vulkan 1.3** and **OpenGL ES 3.0**.
- **Comprehensive Hardware Upscaling & Super Resolution Suite (`Linear` Default)**:
  - All 11 spatial upscalers sample directly through the temporal FrameGen reconstruction pipeline:
  - **Linear** (Default hardware linear texture filtering)
  - **AMD FSR 1.0 (EASU + RCAS)**: 12-tap Edge-Adaptive Spatial Upsampling + Robust Contrast-Adaptive Sharpening
  - **AMD FSR 1.1 Ultra-Quality (Anti-Ringing)**: Enhanced FSR spatial reconstruction with neighborhood clamping
  - **AMD FidelityFX CAS / RCAS Native**: Pure Robust Contrast-Adaptive Sharpening
  - **NVIDIA Image Scaling (NIS Open Shader)**: Directional 6-tap spatial upscaler with adaptive edge boost
  - **Snapdragon GSR (Adreno Super Resolution)**: Qualcomm single-pass 12-kernel edge-aware mobile GPU upscaler
  - **Intel XeSS Spatial (DP4a / Clamped)**: Sub-pixel directional spatial reconstruction
  - **Lanczos-3 Windowed Sinc**: High-precision Sinc-Windowed Lanczos reconstruction for desktop UI & text
  - **Catmull-Rom 9-Tap Bicubic**: Bicubic Hermite spline interpolation
  - **Anime4K Fast Line-Art Upscaler**: Real-time luminance gradient push shader for 2D/Switch/animation
  - **Pixel-Perfect Integer**: Nearest-neighbor crisp pixels for retro consoles
- **100% Fullscreen Edge-to-Edge Viewport & True 90Hz / 120Hz / 144Hz / 240Hz Display Lock**:
  - Both the **HUD** and the **Control Menu** are completely hidden by default (with zero floating handles or buttons blocking the canvas) so 100% of the phone/tablet screen is utilized.
  - Defaults to **Full Screen (Stretch Fill)** and queries `DisplayManager` + `WindowMetrics` + `Surface.setFrameRate` to lock onto the Android device's highest native refresh rate (`90Hz` / `120Hz` / `144Hz` / `240Hz`) and physical resolution.
  - **Swipe from either screen edge**, **press the Back button / gesture** (like Steam Link / Moonlight), or **double-tap** to toggle the Material Design 3 slide-over control drawer at any time.
- **Real Capture Card Hardware Detection & Minimalist Material 3 "No Signal" State**:
  - Strictly distinguishes attached USB capture cards and external UVC cameras from internal phone cameras.
  - Displays a clean, minimalistic Material Design 3 **No Signal** card when no capture card is plugged in, with an optional toggleable SMPTE calibration test pattern in settings.
- **Hiss-Free USB Digital Audio Passthrough**:
  - Strictly locks `AudioRecord` onto external USB / HDMI digital audio endpoints (`TYPE_USB_DEVICE`, `TYPE_USB_ACCESSORY`, `TYPE_HDMI`) and verifies `routedDevice` so it **never** falls back to the built-in device microphone when unplugged—completely eliminating microphone feedback loops and high-pitched hissing noise.
  - Includes an adjustable **Digital Noise Gate (`-90 dBFS` to `-25 dBFS`)** and sub-LSB deadband filter to silence idle USB ADC floor hiss.
- **Custom Resolution Builder & Full Capture Card Mode Mapping**:
  - Enter any custom resolution (`320×240` up to `7680×4320`) and refresh rate (`1Hz` to `240Hz`), or one-tap **Match Screen** to lock onto the Android device's exact physical resolution and max refresh rate (`90Hz` / `120Hz` / `144Hz` / `165Hz` / `240Hz`).
  - Parses all discrete UVC `VS_FRAME` descriptors from the capture card and maps 22+ standard 16:9, 16:10, 21:9 ultrawide, 4:3, and PAL/NTSC presets across `MJPEG`, `YUY2 4:2:2`, `UYVY 4:2:2`, `NV12 4:2:0`, `P010 10-bit HDR`, and `H.264`.
- **USB Port Link Speed Analyzer & UVC Force-Mode Engine**:
  - Inspects `bcdUSB` and endpoint descriptors (`USB 2.0`, `USB 3.2 Gen 1`, `USB 3.2 Gen 2`, `USB4`), warns when a mode exceeds port bandwidth, and allows forcing any resolution/refresh rate via raw `VS_PROBE_CONTROL` / `VS_COMMIT_CONTROL`.

---

## Building from Source

```bash
./gradlew assembleDebug assembleRelease
```

## License

MIT License
