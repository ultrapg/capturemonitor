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
 * Texture filtering mode. Defaults to LINEAR (`Linear`) as requested.
 */
enum class TextureFilteringMode(val label: String, val description: String) {
    LINEAR("Linear", "Hardware GL_LINEAR texture filtering with optional CAS sharpening (Default)"),
    NEAREST_PIXEL("Pixel-Perfect Integer", "Nearest-neighbor crisp pixels for retro consoles & pixel art")
}

enum class ColorSpaceMode(val label: String, val code: Int) {
    REC_709("Rec.709 (HD Studio)", 0),
    REC_601("Rec.601 (SD / Legacy)", 1),
    SRGB_FULL("sRGB Full (0–255 PC)", 2),
    DCI_P3("DCI-P3 Wide Gamut", 3)
}

/**
 * Hardware-accelerated GPU shader & viewport configuration.
 * Defaults to STRETCH_FILL and LINEAR filtering so the entire phone/tablet screen is used out of the box.
 */
data class RenderConfig(
    val aspectRatioPreset: AspectRatioPreset = AspectRatioPreset.STRETCH_FILL,
    val filteringMode: TextureFilteringMode = TextureFilteringMode.LINEAR,
    val colorSpace: ColorSpaceMode = ColorSpaceMode.REC_709,
    val fullRangeRgb: Boolean = true,
    val brightness: Float = 0.0f,     // -0.5 to +0.5
    val contrast: Float = 1.0f,       // 0.5 to 2.0
    val saturation: Float = 1.0f,     // 0.0 to 2.0
    val gamma: Float = 1.0f,          // 0.5 to 2.2
    val sharpness: Float = 0.20f,     // 0.0 to 1.0 (CAS shader)
    val mirrorHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val rotationDegrees: Int = 0,     // 0, 90, 180, 270
    val zoomScale: Float = 1.0f,      // 0.5 to 3.0
    val panX: Float = 0.0f,           // -1.0 to 1.0
    val panY: Float = 0.0f,           // -1.0 to 1.0
    val overscanCropPercent: Float = 0.0f, // 0% to 10% edge crop for HDMI blanking intervals
    val showSafeAreaGrid: Boolean = false,
    val showTestPatternWhenNoSignal: Boolean = false,
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
 * Live telemetry metrics from the OpenGL ES 3.0 render pipeline & capture engine.
 */
data class StreamTelemetry(
    val currentFps: Float = 0f,
    val frameTimeMs: Float = 0f,
    val estimatedBitrateMbps: Float = 0f,
    val droppedFrames: Long = 0L,
    val activeEngineName: String = "OpenGL ES 3.0 Zero-Copy",
    val isForcedModeActive: Boolean = false,
    val negotiateStatus: String = "Standby",
    val usbAudioDeviceName: String? = null
)
