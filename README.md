# CaptureMonitor

**CaptureMonitor** is a high-performance, hardware-accelerated, open-source Android app (Material Design 3) that converts any Android phone or tablet into a low-latency external HDMI/SDI monitor using a USB or USB-C capture card.

Built as a clean, zero-bloat, open-source alternative to apps like **Noir**, with first-class support for **Magewell USB Capture HDMI** cards, Elgato, AVerMedia, MacroSilicon (MS2109 / MS2130), EVGA, Razer, and all standard UVC capture devices.

---

## What's new in v0.6.3

- **Ultra-Low-Latency Decoupled USB UVC Pipeline (`UvcUsbStreamEngine`)**:
  - **Separated Real-Time USB URB Reaping from Frame Decoding**: Dedicated high-priority `UvcUsbReapThread` (`THREAD_PRIORITY_URGENT_AUDIO`) uses blocking Linux kernel hardware interrupt wakeup (`USBDEVFS_REAPURB`) + batch drain (`USBDEVFS_REAPURBNDELAY`) with 12 in-flight URBs and zero `Thread.sleep` polling, writing raw packet payloads into a lock-free 3-slot ring (`RawFrameSlot[0..2]`).
  - **Zero-Queue-Latency Latest-Frame Decoder (`UvcFrameDecodeThread`)**: Wakes in nanoseconds via `LockSupport.unpark()` as soon as an End-of-Frame arrives and always decodes the newest frame (`latestReadySlot.getAndSet(-1)`), eliminating frame queue buildup.
  - **Zero-Copy `Bitmap` -> `GLUtils.texSubImage2D` GPU Upload**: Eliminates the 8.3 MB `copyPixelsToBuffer` CPU copy per frame and decodes MJPEG into a reusable triple-buffered `RGB_565` / `ARGB_8888` `Bitmap` ring uploaded directly from native Skia memory to the GPU.
- **True 60Hz / 120Hz+ Capture Card Refresh Rate Lock**:
  - Enforces `dwFrameInterval` in both `VS_PROBE_CONTROL` and `VS_COMMIT_CONTROL`, prioritizes high-bandwidth Isochronous alternate settings (`3072 bytes/microframe` = 24 MB/s), and selects format descriptors that genuinely support the target 60Hz / 120Hz refresh rate.
  - Fixed a bug where `startDevicePolling()` and `forceUvcStreamNegotiation()` opened competing `UsbDeviceConnection` handles every 2 seconds or on mode changes, which previously reset the USB VideoStreaming interface and throttled frame rate to ~15 FPS.
- **Event-Driven `RENDERMODE_WHEN_DIRTY` Display Presentation**:
  - Uses `RENDERMODE_WHEN_DIRTY` when Frame Generation is off so `eglSwapBuffers()` is only called when a fresh capture card frame arrives—preventing duplicate frames from filling Android's 3-slot `SurfaceFlinger` `BufferQueue` and cutting 33–50 ms of display queue latency.

## What's new in v0.6.2

- **Never Opens the Phone's Built-in Camera**:
  - Removed the fallback Camera2 candidate heuristic that could select an auxiliary internal phone camera (`LENS_FACING_BACK` or `LENS_FACING_FRONT`, such as camera ID `"2"`) on devices where Android's `CameraManager` does not expose external USB UVC capture cards.
  - Added strict `LENS_FACING_EXTERNAL` enforcement in both `UsbCaptureDiscovery` and `HardwareVideoRenderer` so the phone's front/rear cameras are **never** opened under any circumstances.
- **Direct User-Space USB Video Class (UVC 1.0 / 1.1 / 1.5) Streaming Engine (`UvcUsbStreamEngine`)**:
  - Streams live HDMI video directly from USB / USB-C Capture Cards (Magewell USB Capture HDMI, MacroSilicon MS2109 / MS2130, Elgato Cam Link, AVerMedia, EVGA XR1, Razer, and generic UVC capture cards) via `UsbManager.openDevice()` and Linux `usbfs` (`USBDEVFS_SUBMITURB` / `USBDEVFS_REAPURBNDELAY`).
  - Supports both **USB Bulk** (`USB_ENDPOINT_XFER_BULK`) and **USB Isochronous** (`USB_ENDPOINT_XFER_ISOC`) streaming endpoints with automatic alternate-setting bandwidth selection and UVC `VS_PROBE_CONTROL` / `VS_COMMIT_CONTROL` negotiation.
  - Real-time decoding of `MJPEG`, `YUY2`, `UYVY`, and `NV12` frames directly into the GPU spatial upscaler (`tex2dUpscaleProgram`) and temporal Frame Generation (`frameGenProgram`) pipeline.
  - Because direct USB UVC streaming bypasses `CameraManager`, it also avoids triggering Android's camera privacy indicator dot on standard USB capture cards.

## What's new in v0.6.1

- **Default 1080p @ 30 FPS (`1920×1080 @ 30Hz MJPEG`)**: Defaults out-of-the-box to 1080p @ 30 FPS for universal plug-and-play compatibility across all USB 2.0 and USB 3.0 HDMI capture cards while keeping all other default settings intact.
- **Fixed Black Screen / Display Output Pipeline**:
  - Removed the `SurfaceView` `RenderEffect` layer that intercepted the `GLSurfaceView` window hole-punch and turned it opaque black.
  - Restored the direct zero-copy `GL_TEXTURE_EXTERNAL_OES` upscaling shader path when Frame Generation is off, and separated Frame Generation (`frameGenProgram`) and Spatial Upscaling (`tex2dUpscaleProgram`) into distinct passes so mobile GPU shader compilers never fail due to function-inlining limits.
  - Fixed a race condition between `onSurfaceCreated` and `bindSource` that could stop the Camera2 session immediately after opening it.
  - Improved UVC Camera2 session startup (`TEMPLATE_PREVIEW` + flexible FPS range + 3-tier automatic fallback) and external UVC camera ID matching.

## What's new in v0.6.0

- **Reliable "No Signal"**: a connected card with no HDMI source now shows a clear No Signal screen (frame watchdog + blank/solid-blue feed detection, toggleable), and video returns automatically when a source is connected.
- **Reliable hot-plug auto-detect**: debounced USB + Camera2 callbacks plus a 2 s safety-net rescan; USB permission prompt shown once per plug-in.
- **Robust capture**: negotiates the closest stream size / FPS range the card really supports, retries on errors, never races open/close.
- **Privacy indicator**: the camera and microphone are fully released whenever the app is in the background or no card is attached. While a card is actively streaming, Android itself shows the green camera dot; apps cannot hide it. (Optional, user-side: `adb shell device_config put privacy camera_mic_icons_enabled false`.)
- **Audio**: output is silenced when there is no picture, and the loopback thread can no longer be duplicated after pause/resume.

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
