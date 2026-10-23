package io.github.ultrapg.capturemonitor.model

/**
 * Represents the detected or overridden USB port link standard and its practical payload ceiling.
 */
enum class UsbStandard(
    val label: String,
    val specName: String,
    val theoreticalMbps: Int,
    val maxPracticalPayloadMbps: Int
) {
    USB_1_1("USB 1.1 Full-Speed", "USB 1.1", 12, 9),
    USB_2_0("USB 2.0 High-Speed", "USB 2.0", 480, 340),
    USB_3_0_GEN1("USB 3.2 Gen 1 (5 Gbps)", "USB 3.0 / 3.1 Gen 1", 5000, 3200),
    USB_3_2_GEN2("USB 3.2 Gen 2 (10 Gbps)", "USB 3.1 Gen 2 / 3.2", 10000, 7200),
    USB_4_GEN3("USB4 / Thunderbolt (20-40 Gbps)", "USB4", 20000, 15000);

    companion object {
        fun fromUsbVersionBcd(bcdUsb: Int, maxPacketSize: Int, hasSuperSpeedEndpoint: Boolean): UsbStandard {
            return when {
                bcdUsb >= 0x0400 -> USB_4_GEN3
                bcdUsb >= 0x0320 -> USB_3_2_GEN2
                bcdUsb >= 0x0300 || hasSuperSpeedEndpoint || maxPacketSize >= 1024 -> USB_3_0_GEN1
                bcdUsb >= 0x0200 -> USB_2_0
                else -> USB_2_0
            }
        }
    }
}

/**
 * Pixel format / transport compression used between capture card and Android host.
 */
enum class VideoFormat(
    val shortName: String,
    val fullName: String,
    val bitsPerPixel: Float,
    val description: String
) {
    MJPEG(
        shortName = "MJPEG",
        fullName = "Motion JPEG (Hardware Accelerated)",
        bitsPerPixel = 2.2f,
        description = "Low USB bandwidth, supports 1080p60/120 & 4K60 even on constrained USB ports"
    ),
    YUY2(
        shortName = "YUY2 (4:2:2)",
        fullName = "Uncompressed YUYV 4:2:2",
        bitsPerPixel = 16.0f,
        description = "Zero-compression studio color accuracy. Requires USB 3.0+ for 1080p60"
    ),
    NV12(
        shortName = "NV12 (4:2:0)",
        fullName = "Uncompressed YUV 4:2:0 Planar",
        bitsPerPixel = 12.0f,
        description = "Uncompressed 12-bit planar format used by Magewell USB Capture Gen 2 / Pro"
    ),
    UYVY(
        shortName = "UYVY (4:2:2)",
        fullName = "Uncompressed UYVY 4:2:2",
        bitsPerPixel = 16.0f,
        description = "Broadcast SDI/HDMI 16-bit packed 4:2:2 format supported by Magewell & Elgato"
    ),
    P010(
        shortName = "P010 (10-bit HDR)",
        fullName = "10-bit 4:2:0 HDR Planar",
        bitsPerPixel = 15.0f,
        description = "10-bit High Dynamic Range capture format for USB 3.2 Gen 1/2 cards"
    ),
    H264(
        shortName = "H.264 / HEVC",
        fullName = "Hardware MediaCodec Stream",
        bitsPerPixel = 0.6f,
        description = "Ultra-low USB bandwidth hardware-encoded stream"
    );

    fun requiredBandwidthMbps(width: Int, height: Int, fps: Int): Int {
        val bitsPerSecond = width.toLong() * height.toLong() * fps.toLong() * bitsPerPixel
        return (bitsPerSecond / 1_000_000f).toInt().coerceAtLeast(1)
    }
}

/**
 * Standard, ultrawide, tablet, or user-defined custom monitor resolutions & framerates.
 */
data class VideoMode(
    val width: Int,
    val height: Int,
    val fps: Int,
    val format: VideoFormat = VideoFormat.MJPEG,
    val isNativeReported: Boolean = true,
    val isCustom: Boolean = false
) {
    val resolutionLabel: String
        get() = "${width}×${height}"

    val commonName: String
        get() = when {
            isCustom -> "Custom"
            width == 3840 && height == 2160 -> "4K UHD"
            width == 3440 && height == 1440 -> "UWQHD 21:9"
            width == 2880 && height == 1800 -> "2.8K 16:10"
            width == 2560 && height == 1600 -> "WQXGA 16:10"
            width == 2560 && height == 1440 -> "1440p QHD"
            width == 2560 && height == 1080 -> "UWFHD 21:9"
            width == 2400 && height == 1080 -> "FHD+ 20:9"
            width == 1920 && height == 1200 -> "1200p 16:10"
            width == 1920 && height == 1080 -> "1080p FHD"
            width == 1680 && height == 1050 -> "WSXGA+"
            width == 1600 && height == 900 -> "900p HD+"
            width == 1440 && height == 900 -> "WXGA+"
            width == 1366 && height == 768 -> "WXGA"
            width == 1280 && height == 800 -> "800p 16:10"
            width == 1280 && height == 720 -> "720p HD"
            width == 1024 && height == 768 -> "XGA 4:3"
            width == 800 && height == 600 -> "SVGA 4:3"
            width == 720 && height == 576 -> "576p PAL"
            width == 720 && height == 480 -> "480p NTSC"
            width == 640 && height == 480 -> "480p VGA"
            else -> "${height}p"
        }

    val requiredMbps: Int
        get() = format.requiredBandwidthMbps(width, height, fps)

    fun exceedsUsbBandwidth(usbStandard: UsbStandard): Boolean {
        return requiredMbps > usbStandard.maxPracticalPayloadMbps
    }

    fun bandwidthUtilizationPercent(usbStandard: UsbStandard): Int {
        return ((requiredMbps.toFloat() / usbStandard.maxPracticalPayloadMbps.toFloat()) * 100f).toInt()
    }
}

/**
 * Known capture card profiles with specialized support for Magewell, Elgato, AVerMedia, MacroSilicon, etc.
 */
enum class CaptureCardVendor(
    val vendorId: Int,
    val brandName: String,
    val badgeText: String,
    val supportsHardwareScaler: Boolean,
    val supportsLutAndColorMatrix: Boolean
) {
    MAGEWELL(0x2935, "Magewell", "Magewell FPGA Engine", true, true),
    ELGATO(0x0FD9, "Elgato", "Elgato Cam Link / HD60", true, false),
    AVERMEDIA(0x07CA, "AVerMedia", "AVerMedia UVC Engine", true, false),
    MACROSILICON_1(0x534D, "MacroSilicon", "MS2109 / MS2130 HDMI", false, false),
    MACROSILICON_2(0x345F, "MacroSilicon", "MS2130 USB 3.0 HDMI", false, false),
    EVGA(0x3842, "EVGA", "EVGA XR1 Capture", true, false),
    RAZOR(0x1532, "Razer", "Razer Ripsaw HDMI", true, false),
    GENERIC_UVC(0x0000, "Generic UVC", "Standard USB Video Class", false, false);

    companion object {
        fun fromVendorId(vid: Int): CaptureCardVendor {
            return entries.firstOrNull { it.vendorId == vid } ?: GENERIC_UVC
        }
    }
}

/**
 * Represents a physically connected USB Capture Card or Android External Camera2 UVC source.
 */
data class CaptureDeviceInfo(
    val id: String,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val vendor: CaptureCardVendor,
    val usbStandard: UsbStandard,
    val usbVersionString: String,
    val camera2Id: String?,
    val usbDeviceName: String?,
    val supportedModes: List<VideoMode>,
    val isSimulated: Boolean = false,
    val hasUsbPermission: Boolean = true,
    val serialNumber: String? = null
) {
    val hexVidPid: String
        get() = String.format("%04X:%04X", vendorId, productId)
}

/**
 * Defaults to STRETCH_FILL so the full Android screen is always utilized edge-to-edge by default,
 * even when the incoming resolution has a slightly different aspect ratio.
 */
enum class AspectRatioPreset(val label: String, val ratio: Float?) {
    STRETCH_FILL("Full Screen (Stretch Fill)", 0f),
    ZOOM_CROP("Full Screen (Crop to Fill)", -1f),
    NATIVE("Source Native (1:1 Aspect)", null),
    RATIO_16_9("16:9 Widescreen", 16f / 9f),
    RATIO_16_10("16:10 Tablet / Steam Deck", 16f / 10f),
    RATIO_21_9("21:9 Ultrawide", 21f / 9f),
    RATIO_4_3("4:3 Retro / iPad", 4f / 3f)
}

/**
 * Hardware Graphics Rendering Backend.
 * Defaults to VULKAN as requested, with live switching between the Vulkan Surface/AGSL pipeline
 * and the OpenGL ES 3.0 Multi-Pass FBO pipeline.
 */
enum class GraphicsBackend(
    val label: String,
    val engineTitle: String,
    val description: String
) {
    VULKAN(
        label = "Vulkan 1.3 (Default)",
        engineTitle = "Vulkan 1.3 Hardware Pipeline (SkiaVk + SPIR-V / Multi-Pass)",
        description = "Low-overhead Vulkan hardware pipeline with SPIR-V AGSL RuntimeShader + multi-pass FBO temporal history"
    ),
    OPENGL_ES(
        label = "OpenGL ES 3.0",
        engineTitle = "OpenGL ES 3.0 Multi-Pass FBO + Zero-Copy OES",
        description = "Universal hardware-accelerated EGLImage OES + dual ping-pong FBO pipeline"
    )
}

/**
 * Comprehensive suite of hardware-accelerated upscaling & reconstruction filters.
 * Defaults to LINEAR (`Linear`) as requested.
 */
enum class TextureFilteringMode(
    val label: String,
    val code: Int,
    val description: String
) {
    OFF_PASSTHROUGH(
        label = "Off (1:1 Direct Passthrough)",
        code = -1,
        description = "All upscaling & post-processing disabled. Displays raw capture card output 1:1 with zero shader overhead (Default)"
    ),
    LINEAR(
        label = "Linear",
        code = 0,
        description = "Hardware Linear texture filtering with zero multi-tap overhead"
    ),
    AMD_FSR_1_0(
        label = "AMD FSR 1.0 (EASU + RCAS)",
        code = 2,
        description = "FidelityFX Super Resolution 1.0: 12-tap Edge-Adaptive Spatial Upsampling + Robust Contrast-Adaptive Sharpening"
    ),
    AMD_FSR_ULTRA(
        label = "AMD FSR 1.1 Ultra-Quality (Anti-Ringing)",
        code = 3,
        description = "Enhanced FSR spatial reconstruction with neighborhood clamping to eliminate haloing on HDMI text & UI"
    ),
    AMD_FSR_RCAS(
        label = "AMD FidelityFX CAS / RCAS Native",
        code = 4,
        description = "Pure FidelityFX Robust Contrast-Adaptive Sharpening for native 1080p/4K signals"
    ),
    NVIDIA_NIS(
        label = "NVIDIA Image Scaling (NIS Open Shader)",
        code = 5,
        description = "Open-source NVIDIA directional 6-tap spatial upscaler with adaptive edge-direction boost"
    ),
    SNAPDRAGON_GSR(
        label = "Snapdragon GSR (Adreno Super Resolution)",
        code = 6,
        description = "Qualcomm Snapdragon Game Super Resolution: single-pass 12-kernel edge-aware mobile GPU upscaler"
    ),
    INTEL_XESS_SPATIAL(
        label = "Intel XeSS Spatial (DP4a / Clamped)",
        code = 7,
        description = "Sub-pixel directional spatial reconstruction with luma-weighted anti-aliasing"
    ),
    LANCZOS_3(
        label = "Lanczos-3 Windowed Sinc",
        code = 8,
        description = "High-precision Sinc-Windowed Lanczos reconstruction for razor-sharp desktop monitors & text"
    ),
    CATMULL_ROM_BICUBIC(
        label = "Catmull-Rom 9-Tap Bicubic",
        code = 9,
        description = "Bicubic Hermite spline interpolation for smooth gradients and cinema playback"
    ),
    ANIME4K_CNN(
        label = "Anime4K Fast Line-Art Upscaler",
        code = 10,
        description = "Real-time luminance gradient push & deblur shader ideal for 2D games, Switch, and animation"
    ),
    NEAREST_PIXEL(
        label = "Pixel-Perfect Integer",
        code = 1,
        description = "Nearest-neighbor crisp pixels for retro consoles & pixel art"
    )
}

/**
 * Open-source Frame Generation & Motion Interpolation models.
 * Runs in real-time on the GPU using a ping-pong Framebuffer Object (FBO) history ring (`prevFrameTex` + `currFrameTex`)
 * to generate intermediate frames at the display's native refresh rate (e.g., 30->60, 60->120, 60->240Hz).
 */
enum class FrameGenMode(
    val label: String,
    val code: Int,
    val description: String
) {
    OFF(
        label = "Off (Native Passthrough)",
        code = 0,
        description = "Zero frame generation; displays raw capture card frames with minimum latency"
    ),
    WINFG_OPTICAL_FLOW(
        label = "WinFG 1.0 (Windowed Optical Flow)",
        code = 1,
        description = "Open Windowed Frame Generation: Lucas-Kanade spatial-temporal gradient optical flow with bidirectional motion vector warping (60Hz → 120Hz/240Hz)"
    ),
    AMD_FSR3_FMMF(
        label = "AMD FSR 3 Open FMMF (Fluid Motion)",
        code = 2,
        description = "Open FidelityFX Fluid Motion Interpolation: Luma pyramid motion estimation + disocclusion fallback & static HUD protection"
    ),
    RIFE_LITE_SHADER(
        label = "RIFE-Lite Real-Time Flow (IFNet Shader)",
        code = 3,
        description = "Real-Time Intermediate Flow Estimation shader: Multi-scale coarse-to-fine flow warping with confidence blending"
    ),
    SGSR_2_TEMPORAL(
        label = "Snapdragon GSR 2 Temporal Motion",
        code = 4,
        description = "Mobile-optimized Adreno temporal motion vector synthesis + neighborhood variance clamping (YCoCg anti-ghosting)"
    ),
    SVP_MEMC_BLOCK(
        label = "OpenMEMC / SVP 8-Tap Block Search",
        code = 5,
        description = "Diamond 8-direction Sum of Absolute Differences (SAD) block motion compensation for ultra-smooth 24/30/60fps video"
    )
}

/**
 * Target frame multiplier when Frame Generation is enabled.
 */
enum class FrameGenMultiplier(val label: String, val factor: Int) {
    AUTO_DISPLAY_HZ("Auto Match Screen Hz", 0),
    DOUBLE_2X("2× FPS (e.g. 60 → 120Hz)", 2),
    TRIPLE_3X("3× FPS (e.g. 60 → 180Hz)", 3),
    QUAD_4X("4× FPS (e.g. 60 → 240Hz)", 4)
}

enum class ColorSpaceMode(val label: String, val code: Int) {
    REC_709("Rec.709 (HD Studio)", 0),
    REC_601("Rec.601 (SD / Legacy)", 1),
    SRGB_FULL("sRGB Full (0–255 PC)", 2),
    DCI_P3("DCI-P3 Wide Gamut", 3)
}

/**
 * Hardware-accelerated GPU shader, backend, upscaler, frame generation & viewport configuration.
 * Defaults to VULKAN backend, STRETCH_FILL fullscreen, and LINEAR filtering.
 */
data class RenderConfig(
    val graphicsBackend: GraphicsBackend = GraphicsBackend.VULKAN,
    val aspectRatioPreset: AspectRatioPreset = AspectRatioPreset.STRETCH_FILL,
    val filteringMode: TextureFilteringMode = TextureFilteringMode.OFF_PASSTHROUGH,
    val directPassthrough1To1: Boolean = true,   // Bypasses all upscalers & post-processing shaders for pure 1:1 capture card output
    val frameGenMode: FrameGenMode = FrameGenMode.OFF,
    val frameGenMultiplier: FrameGenMultiplier = FrameGenMultiplier.AUTO_DISPLAY_HZ,
    val frameGenMotionScale: Float = 1.0f,       // 0.25 to 2.0 optical flow vector strength
    val frameGenAntiGhosting: Float = 0.75f,     // 0.0 to 1.0 disocclusion / YCoCg variance clamp
    val frameGenUiProtection: Boolean = true,    // Protects static HUDs / crosshairs / text from warping
    val colorSpace: ColorSpaceMode = ColorSpaceMode.REC_709,
    val fullRangeRgb: Boolean = true,
    val brightness: Float = 0.0f,     // -0.5 to +0.5
    val contrast: Float = 1.0f,       // 0.5 to 2.0
    val saturation: Float = 1.0f,     // 0.0 to 2.0
    val gamma: Float = 1.0f,          // 0.5 to 2.2
    val sharpness: Float = 0.0f,      // 0.0 to 1.0 (FSR RCAS / NIS / GSR / CAS strength; 0 by default for pure passthrough)
    val mirrorHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val rotationDegrees: Int = 0,     // 0, 90, 180, 270
    val zoomScale: Float = 1.0f,      // 0.5 to 3.0
    val panX: Float = 0.0f,           // -1.0 to 1.0
    val panY: Float = 0.0f,           // -1.0 to 1.0
    val overscanCropPercent: Float = 0.0f, // 0% to 10% edge crop for HDMI blanking intervals
    val showSafeAreaGrid: Boolean = false,
    val showTestPatternWhenNoSignal: Boolean = false,
    val autoDetectBlankSignal: Boolean = false, // Optional: treat uniform black/blue frame as No Signal
    val ultraLowLatencyMode: Boolean = true
)

/**
 * Host Android device display capabilities (physical screen resolution and maximum supported refresh rate).
 */
data class HostDisplayInfo(
    val physicalWidth: Int = 1920,
    val physicalHeight: Int = 1080,
    val maxRefreshRateHz: Int = 120,
    val supportedRefreshRatesHz: List<Int> = listOf(120, 90, 60)
) {
    val label: String
        get() = "${physicalWidth}×${physicalHeight} @ ${maxRefreshRateHz}Hz"
}

/**
 * Live telemetry metrics from the Vulkan / OpenGL ES render pipeline, upscaler & frame generator.
 */
data class StreamTelemetry(
    val currentFps: Float = 0f,
    val outputPresentedFps: Float = 0f,
    val generatedFramesPerSec: Float = 0f,
    val frameTimeMs: Float = 0f,
    val estimatedBitrateMbps: Float = 0f,
    val droppedFrames: Long = 0L,
    val activeEngineName: String = GraphicsBackend.VULKAN.engineTitle,
    val isForcedModeActive: Boolean = false,
    val negotiateStatus: String = "Standby",
    val usbAudioDeviceName: String? = null
)
