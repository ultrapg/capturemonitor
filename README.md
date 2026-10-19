# CaptureMonitor

**CaptureMonitor** is a high-performance, hardware-accelerated, open-source Android app (Material Design 3) that converts any Android phone or tablet into a low-latency external HDMI/SDI monitor using a USB or USB-C capture card.

Built as a clean, zero-bloat, open-source alternative to apps like **Noir**, with first-class support for **Magewell USB Capture HDMI** cards, Elgato, AVerMedia, MacroSilicon (MS2109 / MS2130), and all standard UVC capture devices.

---

## Key Features

- **Zero-Copy Hardware Acceleration (OpenGL ES 2.0 / 3.0)**:
  - Direct DMA `GL_TEXTURE_EXTERNAL_OES` rendering pipeline from the USB capture stream to the GPU surface with zero CPU frame copying.
  - Real-time GPU Fragment Shaders: Contrast-Adaptive Sharpening (CAS), Rec.709 / Rec.601 / sRGB color matrix grading, Full (0–255) vs. Limited (16–235) RGB range expansion, Brightness/Contrast/Saturation, Aspect Ratio scaling (`16:9`, `16:10`, `4:3`, `21:9`, Native, Stretch), 90°/180°/270° rotation, H/V mirror, pinch-to-zoom, and broadcast rule-of-thirds / safe-area overlays.
  - Automatically requests the Android display's highest native refresh rate (`90Hz` / `120Hz` / `144Hz` / `240Hz`).
- **Universal USB & Capture Card Support (Special Magewell Support)**:
  - Dedicated USB Vendor & FPGA scaler/descriptor support for **Magewell USB Capture HDMI / Gen 2 / Pro / Plus** (`VID 0x2935`), alongside **Elgato** (`0x0FD9`), **AVerMedia** (`0x07CA`), **MacroSilicon MS2109/MS2130** (`0x534D` / `0x345F`), and all USB Video Class (`UVC Class 0x0E`) cards.
- **USB Port Link Speed Analyzer & Force-Mode Engine**:
  - Inspects `bcdUSB` and endpoint descriptors to identify whether the device port or OTG cable is operating at **USB 2.0 High-Speed (480 Mbps)**, **USB 3.2 Gen 1 (5 Gbps)**, **USB 3.2 Gen 2 (10 Gbps)**, or **USB4**.
  - Calculates real-time payload bandwidth requirements for every combination of resolution (`480p` up to `4K UHD 3840×2160`), refresh rate (`24Hz` up to `240Hz`), and pixel format (`MJPEG`, uncompressed `YUY2 4:2:2`, `NV12 4:2:0`, `H.264`).
  - **Force Mode**: Clearly warns when a selected resolution/refresh rate exceeds the current USB port ceiling, while providing a **Force UVC Probe & Commit (`VS_PROBE_CONTROL` / `VS_COMMIT_CONTROL`)** override to force-negotiate the stream anyway.
- **Slide-Over Material Design 3 Control Drawer**:
  - Swipe inward from the right edge (or double-tap anywhere / tap the edge handle) to reveal the translucent Material 3 configuration drawer while watching the live feed.
  - Tap the video canvas or swipe right to hide all UI for a pure, distraction-free fullscreen monitor.
- **Low-Latency 48kHz Stereo USB Audio Passthrough**:
  - Direct `AudioRecord` (`UNPROCESSED` USB audio source) to `AudioTrack` (`PERFORMANCE_MODE_LOW_LATENCY`) digital loopback with preamp gain and live RMS VU meter.

---

## Building from Source

```bash
./gradlew assembleDebug assembleRelease
```

## License

MIT License
