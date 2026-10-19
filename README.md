# CaptureMonitor

**CaptureMonitor** is a high-performance, hardware-accelerated, open-source Android app (Material Design 3) that converts any Android phone or tablet into a low-latency external HDMI/SDI monitor using a USB or USB-C capture card.

Built as a clean, zero-bloat, open-source alternative to apps like **Noir**, with first-class support for **Magewell USB Capture HDMI** cards, Elgato, AVerMedia, MacroSilicon (MS2109 / MS2130), EVGA, Razer, and all standard UVC capture devices.

---

## Key Features (v0.3.0)

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
- **Zero-Copy Hardware Acceleration (OpenGL ES 2.0 / 3.0)**:
  - Direct DMA `GL_TEXTURE_EXTERNAL_OES` rendering pipeline with **Linear** filtering (default) and **Pixel-Perfect Integer** (`GL_NEAREST`) filtering, **Contrast-Adaptive Sharpening (CAS)**, **Rec.709 / Rec.601 / sRGB / DCI-P3** color grading, **Gamma**, **HDMI Overscan Edge Crop**, and **Full/Limited RGB** range expansion.
- **USB Port Link Speed Analyzer & UVC Force-Mode Engine**:
  - Inspects `bcdUSB` and endpoint descriptors (`USB 2.0`, `USB 3.2 Gen 1`, `USB 3.2 Gen 2`, `USB4`), warns when a mode exceeds port bandwidth, and allows forcing any resolution/refresh rate via raw `VS_PROBE_CONTROL` / `VS_COMMIT_CONTROL`.

---

## Building from Source

```bash
./gradlew assembleDebug assembleRelease
```

## License

MIT License
