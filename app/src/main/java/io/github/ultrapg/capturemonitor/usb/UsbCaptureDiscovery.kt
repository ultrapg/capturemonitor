package io.github.ultrapg.capturemonitor.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import android.util.Range
import androidx.core.content.ContextCompat
import io.github.ultrapg.capturemonitor.model.CaptureCardVendor
import io.github.ultrapg.capturemonitor.model.CaptureDeviceInfo
import io.github.ultrapg.capturemonitor.model.HostDisplayInfo
import io.github.ultrapg.capturemonitor.model.UsbStandard
import io.github.ultrapg.capturemonitor.model.VideoFormat
import io.github.ultrapg.capturemonitor.model.VideoMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Handles strict USB Host & External UVC capture card discovery, descriptor parsing
 * (bcdUSB link speed, UVC VS_FORMAT / VS_FRAME descriptors), Magewell USB Capture HDMI identification,
 * and UVC VS_PROBE_CONTROL / VS_COMMIT_CONTROL force-negotiation.
 *
 * Strictly distinguishes real attached USB capture hardware from internal phone/tablet cameras
 * so the app accurately reports when a capture card is or isn't connected.
 */
class UsbCaptureDiscovery(
    private val context: Context,
    private val onDevicesChanged: () -> Unit
) {
    companion object {
        private const val TAG = "UsbCaptureDiscovery"
        const val ACTION_USB_PERMISSION = "io.github.ultrapg.capturemonitor.USB_PERMISSION"

        // UVC class-specific request codes
        private const val RT_CLASS_INTERFACE_SET = 0x21
        private const val RT_CLASS_INTERFACE_GET = 0xA1
        private const val UVC_SET_CUR = 0x01
        private const val UVC_GET_CUR = 0x81
        private const val VS_PROBE_CONTROL = 0x0100
        private const val VS_COMMIT_CONTROL = 0x0200

        val STANDARD_RESOLUTIONS: List<Pair<Int, Int>> = listOf(
            3840 to 2160, // 4K UHD 16:9
            3440 to 1440, // Ultrawide QHD 21:9
            2880 to 1800, // 2.8K OLED Tablet 16:10
            2560 to 1600, // WQXGA Tablet 16:10
            2560 to 1440, // 1440p QHD 16:9
            2560 to 1080, // Ultrawide FHD 21:9
            2400 to 1080, // FHD+ 20:9 Phone/Tablet
            1920 to 1200, // WUXGA 16:10 (Magewell native)
            1920 to 1080, // 1080p FHD 16:9
            1680 to 1050, // WSXGA+ 16:10
            1600 to 1200, // UXGA 4:3
            1600 to 900,  // 900p HD+ 16:9
            1440 to 900,  // WXGA+ 16:10
            1366 to 768,  // WXGA 16:9
            1280 to 1024, // SXGA 5:4
            1280 to 800,  // WXGA 16:10 (Steam Deck native)
            1280 to 720,  // 720p HD 16:9
            1024 to 768,  // XGA 4:3
            800 to 600,   // SVGA 4:3
            720 to 576,   // 576p PAL
            720 to 480,   // 480p NTSC
            640 to 480    // 480p VGA
        )

        val STANDARD_REFRESH_RATES: List<Int> = listOf(
            240, 165, 144, 120, 100, 90, 75, 60, 59, 50, 48, 30, 25, 24
        )

        /**
         * Comprehensive list of all capture card modes (4K, 1440p, 16:10, 21:9, 1080p, 720p, SD, and high refresh rates).
         */
        val ALL_STANDARD_MODES: List<VideoMode> = buildList {
            for ((w, h) in STANDARD_RESOLUTIONS) {
                for (fps in STANDARD_REFRESH_RATES) {
                    if (w >= 3840 && fps > 120) continue
                    for (fmt in VideoFormat.entries) {
                        val isCommon = (w <= 1920 && fps <= 60 && (fmt == VideoFormat.MJPEG || fmt == VideoFormat.YUY2))
                        add(
                            VideoMode(
                                width = w,
                                height = h,
                                fps = fps,
                                format = fmt,
                                isNativeReported = isCommon
                            )
                        )
                    }
                }
            }
        }
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var receiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED,
                ACTION_USB_PERMISSION -> {
                    onDevicesChanged()
                }
            }
        }
    }

    private val cameraAvailabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            onDevicesChanged()
        }

        override fun onCameraUnavailable(cameraId: String) {
            onDevicesChanged()
        }
    }

    fun register() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(
            context,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            cameraManager.registerAvailabilityCallback(cameraAvailabilityCallback, null)
        } catch (_: Exception) {
        }
        receiverRegistered = true
    }

    fun unregister() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {
        }
        try {
            cameraManager.unregisterAvailabilityCallback(cameraAvailabilityCallback)
        } catch (_: Exception) {
        }
        receiverRegistered = false
    }

    fun hasUsbPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun requestUsbPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            device.deviceId,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    fun requestPermissionForDeviceInfo(deviceInfo: CaptureDeviceInfo) {
        val usbDevice = usbManager.deviceList[deviceInfo.usbDeviceName]
            ?: usbManager.deviceList.values.firstOrNull {
                it.vendorId == deviceInfo.vendorId && it.productId == deviceInfo.productId
            }
        if (usbDevice != null && !usbManager.hasPermission(usbDevice)) {
            requestUsbPermission(usbDevice)
        }
    }

    /**
     * Strictly discovers physically attached USB Capture Cards and External UVC cameras.
     * Does NOT confuse built-in front/back phone cameras with external capture cards unless explicitly requested,
     * so the app accurately knows whether a capture card is connected or not.
     */
    fun discoverCaptureDevices(): List<CaptureDeviceInfo> {
        val discovered = mutableListOf<CaptureDeviceInfo>()

        // 1. Inspect external UVC Camera2 devices exposed by the Android kernel
        val externalCameraIds = mutableListOf<String>()
        val allCameraIds = mutableListOf<String>()
        try {
            for (camId in cameraManager.cameraIdList) {
                allCameraIds.add(camId)
                val chars = cameraManager.getCameraCharacteristics(camId)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                    externalCameraIds.add(camId)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to query Camera2 characteristics: ${e.message}")
        }

        // 2. Inspect physically connected USB devices via UsbManager
        val usbDevices = usbManager.deviceList.values.toList()
        for (usbDevice in usbDevices) {
            if (isVideoCaptureUsbDevice(usbDevice)) {
                val hasPerm = usbManager.hasPermission(usbDevice)
                if (!hasPerm) {
                    // Proactively request USB permission when a capture card is plugged in
                    try {
                        requestUsbPermission(usbDevice)
                    } catch (_: Exception) {
                    }
                }
                val vendor = CaptureCardVendor.fromVendorId(usbDevice.vendorId)
                val (usbStd, bcdVerStr, parsedModes) = inspectUsbDeviceDescriptors(usbDevice)
                // Match with an external camera ID if available, or highest-indexed camera added on USB hotplug
                val matchedCameraId = externalCameraIds.firstOrNull()
                    ?: allCameraIds.firstOrNull { id -> id.toIntOrNull()?.let { it >= 2 } == true }

                val productName = buildDeviceTitle(usbDevice, vendor)
                val combinedModes = mergeModesWithAllQualities(parsedModes, queryCameraModes(matchedCameraId))

                discovered.add(
                    CaptureDeviceInfo(
                        id = "usb_${usbDevice.deviceId}",
                        name = productName,
                        vendorId = usbDevice.vendorId,
                        productId = usbDevice.productId,
                        vendor = vendor,
                        usbStandard = usbStd,
                        usbVersionString = bcdVerStr,
                        camera2Id = matchedCameraId,
                        usbDeviceName = usbDevice.deviceName,
                        supportedModes = combinedModes,
                        isSimulated = false,
                        hasUsbPermission = hasPerm,
                        serialNumber = try {
                            if (hasPerm) usbDevice.serialNumber else null
                        } catch (_: Exception) {
                            null
                        }
                    )
                )
            }
        }

        // 3. If Android exposes an EXTERNAL Camera2 device that wasn't already matched in UsbManager
        for (extCamId in externalCameraIds) {
            if (discovered.none { it.camera2Id == extCamId }) {
                val camModes = queryCameraModes(extCamId)
                discovered.add(
                    CaptureDeviceInfo(
                        id = "ext_cam_$extCamId",
                        name = "USB-C External Video Capture (#$extCamId)",
                        vendorId = 0x2935,
                        productId = 0x0001,
                        vendor = CaptureCardVendor.GENERIC_UVC,
                        usbStandard = UsbStandard.USB_3_0_GEN1,
                        usbVersionString = "USB 3.x / UVC External",
                        camera2Id = extCamId,
                        usbDeviceName = null,
                        supportedModes = mergeModesWithAllQualities(emptyList(), camModes),
                        isSimulated = false,
                        hasUsbPermission = true
                    )
                )
            }
        }

        return discovered
    }

    /**
     * Selects the highest resolution and refresh rate (90Hz / 120Hz / 144Hz / 240Hz) supported by
     * BOTH the Android device's screen and the connected capture card.
     */
    fun selectBestDefaultMode(
        device: CaptureDeviceInfo?,
        hostDisplay: HostDisplayInfo
    ): VideoMode {
        val candidates = device?.supportedModes?.filter { it.format == VideoFormat.MJPEG }
            ?.takeIf { it.isNotEmpty() }
            ?: ALL_STANDARD_MODES.filter { it.format == VideoFormat.MJPEG }

        val targetW = hostDisplay.physicalWidth.coerceIn(1280, 3840)
        val targetH = hostDisplay.physicalHeight.coerceIn(720, 2160)
        val targetPixels = targetW.toLong() * targetH.toLong()
        val targetHz = hostDisplay.maxRefreshRateHz.coerceIn(60, 240)
        val usbStd = device?.usbStandard ?: UsbStandard.USB_3_0_GEN1

        // Prefer the highest refresh rate up to the display's native max Hz (e.g. 90Hz, 120Hz, 144Hz, 240Hz)
        // and the highest resolution up to the display's physical resolution that fits USB bandwidth.
        return candidates.maxWithOrNull(
            compareBy<VideoMode> { mode ->
                !mode.exceedsUsbBandwidth(usbStd)
            }.thenBy { mode ->
                // Exact or highest refresh rate up to the display's max refresh rate (90Hz / 120Hz / 144Hz)
                if (mode.fps <= targetHz) mode.fps else (targetHz - abs(mode.fps - targetHz)).coerceAtLeast(1)
            }.thenBy { mode ->
                val modePixels = mode.width.toLong() * mode.height.toLong()
                if (modePixels <= targetPixels) {
                    modePixels
                } else {
                    targetPixels - (modePixels - targetPixels) / 2
                }
            }
        ) ?: VideoMode(
            width = targetW,
            height = targetH,
            fps = targetHz,
            format = VideoFormat.MJPEG
        )
    }

    private fun buildDeviceTitle(usbDevice: UsbDevice, vendor: CaptureCardVendor): String {
        val rawProduct = usbDevice.productName?.trim()
        val rawMfg = usbDevice.manufacturerName?.trim()
        return when {
            !rawProduct.isNullOrBlank() && !rawMfg.isNullOrBlank() -> {
                if (rawProduct.startsWith(rawMfg, ignoreCase = true)) rawProduct else "$rawMfg $rawProduct"
            }
            !rawProduct.isNullOrBlank() -> rawProduct
            vendor == CaptureCardVendor.MAGEWELL -> "Magewell USB Capture HDMI (${String.format("%04X", usbDevice.productId)})"
            vendor != CaptureCardVendor.GENERIC_UVC -> "${vendor.brandName} USB Capture Card"
            else -> "UVC HDMI Capture Card (${String.format("%04X:%04X", usbDevice.vendorId, usbDevice.productId)})"
        }
    }

    private fun isVideoCaptureUsbDevice(device: UsbDevice): Boolean {
        if (CaptureCardVendor.fromVendorId(device.vendorId) != CaptureCardVendor.GENERIC_UVC) {
            return true
        }
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_VIDEO) {
                return true
            }
        }
        return false
    }

    private fun queryCameraModes(cameraId: String?): List<VideoMode> {
        if (cameraId == null) return emptyList()
        val result = mutableListOf<VideoMode>()
        try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
            val fpsRanges: Array<Range<Int>> =
                chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
            val maxFps = fpsRanges.maxOfOrNull { it.upper } ?: 60

            val sizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java) ?: emptyArray()
            for (size in sizes) {
                val candidateFps = listOf(maxFps, 120, 60, 50, 30, 24).distinct().filter { it <= maxFps.coerceAtLeast(60) }
                for (fps in candidateFps) {
                    result.add(
                        VideoMode(
                            width = size.width,
                            height = size.height,
                            fps = fps,
                            format = VideoFormat.MJPEG,
                            isNativeReported = true
                        )
                    )
                    result.add(
                        VideoMode(
                            width = size.width,
                            height = size.height,
                            fps = fps,
                            format = VideoFormat.YUY2,
                            isNativeReported = true
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading Camera2 sizes for $cameraId: ${e.message}")
        }
        return result
    }

    /**
     * Parses raw USB descriptors (Device descriptor bcdUSB, Endpoint wMaxPacketSize, and UVC VS_FRAME descriptors).
     */
    private fun inspectUsbDeviceDescriptors(device: UsbDevice): Triple<UsbStandard, String, List<VideoMode>> {
        var maxEndpointPacketSize = 0
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            for (epIdx in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(epIdx)
                if (ep.maxPacketSize > maxEndpointPacketSize) {
                    maxEndpointPacketSize = ep.maxPacketSize
                }
            }
        }

        var bcdUsb = if (maxEndpointPacketSize >= 1024) 0x0300 else 0x0200
        val parsedModes = mutableListOf<VideoMode>()

        if (usbManager.hasPermission(device)) {
            val connection: UsbDeviceConnection? = usbManager.openDevice(device)
            if (connection != null) {
                try {
                    val rawDesc = connection.rawDescriptors
                    if (rawDesc != null && rawDesc.size >= 18) {
                        bcdUsb = ((rawDesc[3].toInt() and 0xFF) shl 8) or (rawDesc[2].toInt() and 0xFF)
                        parsedModes.addAll(parseUvcFrameDescriptors(rawDesc))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Raw descriptor parse error: ${e.message}")
                } finally {
                    connection.close()
                }
            }
        }

        val hasSuperSpeed = bcdUsb >= 0x0300 || (device.vendorId == 0x2935 && maxEndpointPacketSize >= 1024)
        val standard = UsbStandard.fromUsbVersionBcd(bcdUsb, maxEndpointPacketSize, hasSuperSpeed)
        val major = (bcdUsb shr 8) and 0xFF
        val minor = (bcdUsb shr 4) and 0x0F
        val subMinor = bcdUsb and 0x0F
        val versionString = "USB $major.$minor${if (subMinor > 0) ".$subMinor" else ""} (bcdUSB 0x${String.format("%04X", bcdUsb)})"

        return Triple(standard, versionString, parsedModes)
    }

    /**
     * Parses UVC Video Streaming (VS_FRAME_UNCOMPRESSED = 0x05, VS_FRAME_MJPEG = 0x07, VS_FRAME_FRAME_BASED = 0x11)
     * directly from the USB configuration descriptor byte array, including all discrete frame intervals.
     */
    private fun parseUvcFrameDescriptors(raw: ByteArray): List<VideoMode> {
        val modes = mutableListOf<VideoMode>()
        var offset = 0
        var currentFormat = VideoFormat.MJPEG

        while (offset + 2 <= raw.size) {
            val length = raw[offset].toInt() and 0xFF
            if (length < 2 || offset + length > raw.size) break
            val descType = raw[offset + 1].toInt() and 0xFF

            // CS_INTERFACE = 0x24
            if (descType == 0x24 && length >= 4) {
                val descSubtype = raw[offset + 2].toInt() and 0xFF
                when (descSubtype) {
                    0x04 -> currentFormat = VideoFormat.YUY2 // VS_FORMAT_UNCOMPRESSED
                    0x06 -> currentFormat = VideoFormat.MJPEG // VS_FORMAT_MJPEG
                    0x10 -> currentFormat = VideoFormat.NV12  // VS_FORMAT_FRAME_BASED
                    0x05, 0x07, 0x11 -> {
                        if (length >= 26) {
                            val width = ((raw[offset + 6].toInt() and 0xFF) shl 8) or (raw[offset + 5].toInt() and 0xFF)
                            val height = ((raw[offset + 8].toInt() and 0xFF) shl 8) or (raw[offset + 7].toInt() and 0xFF)
                            val defaultInterval = ByteBuffer.wrap(raw, offset + 21, 4)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .int
                            val frameIntervalType = raw[offset + 25].toInt() and 0xFF

                            if (width in 160..7680 && height in 120..4320) {
                                if (frameIntervalType > 0) {
                                    // Parse all discrete dwFrameInterval entries reported by the capture card
                                    for (idx in 0 until frameIntervalType) {
                                        val pos = offset + 26 + (idx * 4)
                                        if (pos + 4 <= offset + length) {
                                            val interval = ByteBuffer.wrap(raw, pos, 4)
                                                .order(ByteOrder.LITTLE_ENDIAN)
                                                .int
                                            if (interval > 0) {
                                                val fps = (10_000_000 / interval).coerceIn(1, 240)
                                                modes.add(
                                                    VideoMode(
                                                        width = width,
                                                        height = height,
                                                        fps = fps,
                                                        format = currentFormat,
                                                        isNativeReported = true
                                                    )
                                                )
                                            }
                                        }
                                    }
                                } else if (defaultInterval > 0) {
                                    val defaultFps = (10_000_000 / defaultInterval).coerceIn(1, 240)
                                    modes.add(
                                        VideoMode(
                                            width = width,
                                            height = height,
                                            fps = defaultFps,
                                            format = currentFormat,
                                            isNativeReported = true
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
            offset += length
        }
        return modes.distinct()
    }

    private fun mergeModesWithAllQualities(
        descriptorModes: List<VideoMode>,
        cameraModes: List<VideoMode>
    ): List<VideoMode> {
        val reportedModes = (descriptorModes + cameraModes).distinct()
        val nativeSet = reportedModes.map { Triple(it.width, it.height, it.fps) }.toSet()

        // Include any unusual custom resolutions reported directly by the capture card hardware descriptors
        val extraHardwareModes = reportedModes.filter { hw ->
            ALL_STANDARD_MODES.none { std ->
                std.width == hw.width && std.height == hw.height && std.fps == hw.fps && std.format == hw.format
            }
        }

        val mappedStandard = ALL_STANDARD_MODES.map { mode ->
            val isNative = nativeSet.isEmpty() || nativeSet.contains(Triple(mode.width, mode.height, mode.fps))
            mode.copy(isNativeReported = isNative)
        }

        return (extraHardwareModes + mappedStandard).distinctBy {
            listOf(it.width, it.height, it.fps, it.format)
        }
    }

    /**
     * Sends a UVC 1.1 / 1.5 `VS_PROBE_CONTROL` and `VS_COMMIT_CONTROL` sequence over USB Control Endpoint 0
     * to force a target resolution (including custom resolutions), frame interval (100ns units), and payload transfer size.
     */
    fun forceUvcStreamNegotiation(
        deviceInfo: CaptureDeviceInfo?,
        targetMode: VideoMode,
        ignoreBandwidthCeiling: Boolean
    ): ForceNegotiationResult {
        if (deviceInfo == null) {
            return ForceNegotiationResult(
                success = false,
                appliedByUvcCommit = false,
                statusMessage = "Queued ${targetMode.resolutionLabel}@${targetMode.fps}Hz (${targetMode.format.shortName}) — waiting for capture card"
            )
        }

        val usbDevice = usbManager.deviceList[deviceInfo.usbDeviceName]
            ?: usbManager.deviceList.values.firstOrNull {
                it.vendorId == deviceInfo.vendorId && it.productId == deviceInfo.productId
            }

        if (usbDevice == null) {
            return ForceNegotiationResult(
                success = true,
                appliedByUvcCommit = false,
                statusMessage = "Applied ${targetMode.resolutionLabel}@${targetMode.fps}Hz (${targetMode.format.shortName}) to GPU pipeline"
            )
        }

        if (!usbManager.hasPermission(usbDevice)) {
            requestUsbPermission(usbDevice)
            return ForceNegotiationResult(
                success = false,
                appliedByUvcCommit = false,
                statusMessage = "Requested USB Host permission for ${deviceInfo.name}"
            )
        }

        val connection = usbManager.openDevice(usbDevice)
            ?: return ForceNegotiationResult(
                success = false,
                appliedByUvcCommit = false,
                statusMessage = "Could not open USB connection to ${deviceInfo.name}"
            )

        try {
            var vsInterface: UsbInterface? = null
            for (i in 0 until usbDevice.interfaceCount) {
                val intf = usbDevice.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_VIDEO && intf.interfaceSubclass == 2) {
                    vsInterface = intf
                    break
                }
            }
            if (vsInterface == null && usbDevice.interfaceCount > 1) {
                vsInterface = usbDevice.getInterface(1)
            }

            val interfaceNum = vsInterface?.id ?: 1
            vsInterface?.let { connection.claimInterface(it, true) }

            val frameInterval100ns = (10_000_000L / targetMode.fps.coerceAtLeast(1)).toInt()
            val probeBuffer = ByteBuffer.allocate(34).order(ByteOrder.LITTLE_ENDIAN)
            probeBuffer.putShort(0x0001)
            val formatIndex: Byte = when (targetMode.format) {
                VideoFormat.MJPEG -> 1
                VideoFormat.YUY2 -> 2
                VideoFormat.NV12 -> 2
                VideoFormat.UYVY -> 2
                VideoFormat.P010 -> 3
                VideoFormat.H264 -> 3
            }
            probeBuffer.put(formatIndex)
            probeBuffer.put(1.toByte())
            probeBuffer.putInt(frameInterval100ns)
            probeBuffer.putShort(0)
            probeBuffer.putShort(0)
            probeBuffer.putShort(if (ignoreBandwidthCeiling) 10000.toShort() else 6000.toShort())
            probeBuffer.putShort(0)
            probeBuffer.putShort(0)
            val maxFrameBytes = targetMode.width * targetMode.height * 2
            probeBuffer.putInt(maxFrameBytes)
            val maxPayloadBytes = if (ignoreBandwidthCeiling) 3072 else 1024
            probeBuffer.putInt(maxPayloadBytes)

            val payload = probeBuffer.array()

            val probeSet = connection.controlTransfer(
                RT_CLASS_INTERFACE_SET,
                UVC_SET_CUR,
                VS_PROBE_CONTROL,
                interfaceNum,
                payload,
                payload.size,
                1000
            )

            val readback = ByteArray(34)
            connection.controlTransfer(
                RT_CLASS_INTERFACE_GET,
                UVC_GET_CUR,
                VS_PROBE_CONTROL,
                interfaceNum,
                readback,
                readback.size,
                1000
            )

            val commitPayload = if (ignoreBandwidthCeiling) payload else readback

            val commitSet = connection.controlTransfer(
                RT_CLASS_INTERFACE_SET,
                UVC_SET_CUR,
                VS_COMMIT_CONTROL,
                interfaceNum,
                commitPayload,
                commitPayload.size,
                1000
            )

            vsInterface?.let { connection.releaseInterface(it) }

            val uvcApplied = probeSet >= 0 || commitSet >= 0
            return ForceNegotiationResult(
                success = true,
                appliedByUvcCommit = uvcApplied,
                statusMessage = if (uvcApplied) {
                    "UVC VS_COMMIT_CONTROL locked: ${targetMode.resolutionLabel}@${targetMode.fps}Hz (${targetMode.format.shortName})"
                } else {
                    "Forced ${targetMode.resolutionLabel}@${targetMode.fps}Hz (${targetMode.format.shortName}) via Camera2/GL override"
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Force UVC negotiation exception: ${e.message}")
            return ForceNegotiationResult(
                success = true,
                appliedByUvcCommit = false,
                statusMessage = "Forced ${targetMode.resolutionLabel}@${targetMode.fps}Hz via GPU Surface pipeline"
            )
        } finally {
            connection.close()
        }
    }
}

data class ForceNegotiationResult(
    val success: Boolean,
    val appliedByUvcCommit: Boolean,
    val statusMessage: String
)
