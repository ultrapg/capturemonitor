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
import io.github.ultrapg.capturemonitor.model.UsbStandard
import io.github.ultrapg.capturemonitor.model.VideoFormat
import io.github.ultrapg.capturemonitor.model.VideoMode
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles USB Host discovery, descriptor parsing (bcdUSB link speed, UVC VS_FORMAT / VS_FRAME descriptors),
 * Magewell USB Capture HDMI identification, and UVC VS_PROBE_CONTROL / VS_COMMIT_CONTROL force-negotiation.
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
        private const val UVC_GET_MIN = 0x82
        private const val UVC_GET_MAX = 0x83
        private const val VS_PROBE_CONTROL = 0x0100
        private const val VS_COMMIT_CONTROL = 0x0200

        /**
         * Comprehensive list of standard & pro monitor modes that users can select or force-negotiate.
         */
        val ALL_STANDARD_MODES: List<VideoMode> = buildList {
            val resolutions = listOf(
                3840 to 2160,
                2560 to 1440,
                1920 to 1200,
                1920 to 1080,
                1600 to 900,
                1280 to 720,
                720 to 576,
                720 to 480,
                640 to 480
            )
            val framerates = listOf(240, 144, 120, 60, 59, 50, 30, 25, 24)
            val formats = listOf(VideoFormat.MJPEG, VideoFormat.YUY2, VideoFormat.NV12, VideoFormat.H264)

            for ((w, h) in resolutions) {
                for (fps in framerates) {
                    // Skip unrealistic combinations like 4K@240Hz over USB
                    if (w == 3840 && fps > 60) continue
                    if (w == 2560 && fps > 144) continue
                    for (fmt in formats) {
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
        receiverRegistered = true
    }

    fun unregister() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(usbReceiver)
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

    /**
     * Scans both Android UsbManager (for raw USB capture cards including Magewell, Elgato, MacroSilicon)
     * and CameraManager (for external/UVC camera endpoints mapped by the Android kernel).
     */
    fun discoverCaptureDevices(): List<CaptureDeviceInfo> {
        val discovered = mutableListOf<CaptureDeviceInfo>()

        // 1. Inspect external & available Camera2 devices
        val externalCameraIds = mutableListOf<String>()
        val fallbackCameraIds = mutableListOf<String>()
        try {
            for (camId in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(camId)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                    externalCameraIds.add(camId)
                } else {
                    fallbackCameraIds.add(camId)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to query Camera2 characteristics: ${e.message}")
        }

        // 2. Inspect connected USB devices via UsbManager
        val usbDevices = usbManager.deviceList.values.toList()
        for (usbDevice in usbDevices) {
            if (isVideoCaptureUsbDevice(usbDevice)) {
                val vendor = CaptureCardVendor.fromVendorId(usbDevice.vendorId)
                val (usbStd, bcdVerStr, parsedModes) = inspectUsbDeviceDescriptors(usbDevice)
                val matchedCameraId = externalCameraIds.firstOrNull() ?: fallbackCameraIds.firstOrNull()

                val productName = buildDeviceTitle(usbDevice, vendor)
                val combinedModes = mergeModesWithAllQualities(parsedModes, MatchedCameraModes(matchedCameraId))

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
                        serialNumber = try {
                            if (usbManager.hasPermission(usbDevice)) usbDevice.serialNumber else null
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
                val camModes = MatchedCameraModes(extCamId)
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
                        isSimulated = false
                    )
                )
            }
        }

        // 4. Always provide a built-in Reference Signal / Magewell Hardware Simulator profile
        // so the user can test all resolutions, USB 2.0/3.0 bandwidth limits, Force-Mode negotiation,
        // and OpenGL ES shaders even before plugging in a physical HDMI source.
        discovered.add(
            CaptureDeviceInfo(
                id = "sim_magewell_hdmi",
                name = "Magewell USB Capture HDMI Gen 2 (Reference Signal)",
                vendorId = 0x2935,
                productId = 0x0002,
                vendor = CaptureCardVendor.MAGEWELL,
                usbStandard = UsbStandard.USB_3_0_GEN1,
                usbVersionString = "USB 3.2 Gen 1 (bcdUSB 0x0310)",
                camera2Id = null,
                usbDeviceName = null,
                supportedModes = ALL_STANDARD_MODES,
                isSimulated = true,
                serialNumber = "MW-HDMI-4K60-REF"
            )
        )

        return discovered
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
        // Known capture card vendor IDs (Magewell 0x2935, Elgato 0x0FD9, AVerMedia 0x07CA, MacroSilicon 0x534D / 0x345F)
        if (CaptureCardVendor.fromVendorId(device.vendorId) != CaptureCardVendor.GENERIC_UVC) {
            return true
        }
        // Check device class (0x0E = Video, 0xEF = Misc IAD)
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_VIDEO) {
                return true
            }
        }
        return false
    }

    private fun MatchedCameraModes(cameraId: String?): List<VideoMode> {
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
                val candidateFps = listOf(maxFps, 60, 50, 30, 24).distinct().filter { it <= maxFps.coerceAtLeast(60) }
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
     * Parses raw USB descriptors (Device descriptor bcdUSB, Endpoint wMaxPacketSize, and UVC VS_FRAME descriptors)
     * when permission is granted, or infers from endpoint packet sizes when permission is pending.
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
                        // Bytes 2..3 of Device Descriptor are bcdUSB (little-endian)
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
     * directly from the USB configuration descriptor byte array.
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
                        // VS_FRAME_UNCOMPRESSED (0x05), VS_FRAME_MJPEG (0x07), VS_FRAME_FRAME_BASED (0x11)
                        if (length >= 26) {
                            val width = ((raw[offset + 6].toInt() and 0xFF) shl 8) or (raw[offset + 5].toInt() and 0xFF)
                            val height = ((raw[offset + 8].toInt() and 0xFF) shl 8) or (raw[offset + 7].toInt() and 0xFF)
                            val defaultInterval = ByteBuffer.wrap(raw, offset + 21, 4)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .int
                            if (width in 160..7680 && height in 120..4320 && defaultInterval > 0) {
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
            offset += length
        }
        return modes.distinct()
    }

    private fun mergeModesWithAllQualities(
        descriptorModes: List<VideoMode>,
        cameraModes: List<VideoMode>
    ): List<VideoMode> {
        val nativeSet = (descriptorModes + cameraModes).map { Triple(it.width, it.height, it.fps) }.toSet()
        return ALL_STANDARD_MODES.map { mode ->
            val isNative = nativeSet.isEmpty() || nativeSet.contains(Triple(mode.width, mode.height, mode.fps))
            mode.copy(isNativeReported = isNative)
        }
    }

    /**
     * Sends a real UVC 1.1 / 1.5 `VS_PROBE_CONTROL` and `VS_COMMIT_CONTROL` sequence over USB Control Endpoint 0
     * to force a target resolution, frame interval (100ns units), and payload transfer size—even when the USB host
     * controller reports a lower speed or bandwidth ceiling.
     *
     * Also supports Magewell Vendor-Specific FPGA scaler / timing commit when a Magewell card (0x2935) is attached.
     */
    fun forceUvcStreamNegotiation(
        deviceInfo: CaptureDeviceInfo,
        targetMode: VideoMode,
        ignoreBandwidthCeiling: Boolean
    ): ForceNegotiationResult {
        val usbDevice = usbManager.deviceList[deviceInfo.usbDeviceName]
            ?: usbManager.deviceList.values.firstOrNull {
                it.vendorId == deviceInfo.vendorId && it.productId == deviceInfo.productId
            }

        if (usbDevice == null) {
            return ForceNegotiationResult(
                success = true,
                appliedByUvcCommit = false,
                statusMessage = "Forced ${targetMode.resolutionLabel}@${targetMode.fps}Hz (${targetMode.format.shortName}) in pipeline"
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
            // Find VideoStreaming (VS) interface (Class 0x0E, Subclass 0x02)
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

            // Build 34-byte UVC 1.1/1.5 VS_PROBE_CONTROL structure
            val frameInterval100ns = (10_000_000L / targetMode.fps.coerceAtLeast(1)).toInt()
            val probeBuffer = ByteBuffer.allocate(34).order(ByteOrder.LITTLE_ENDIAN)
            // bmHint: bit 0 = dwFrameInterval, bit 1 = wKeyFrameRate, bit 4 = wCompQuality
            probeBuffer.putShort(0x0001)
            // bFormatIndex (1 = first format, 2 = second format)
            val formatIndex: Byte = when (targetMode.format) {
                VideoFormat.MJPEG -> 1
                VideoFormat.YUY2 -> 2
                VideoFormat.NV12 -> 2
                VideoFormat.H264 -> 3
            }
            probeBuffer.put(formatIndex)
            // bFrameIndex
            probeBuffer.put(1.toByte())
            // dwFrameInterval in 100ns units (e.g. 166666 for 60fps, 83333 for 120fps)
            probeBuffer.putInt(frameInterval100ns)
            // wKeyFrameRate, wPFrameRate, wCompQuality, wCompWindowSize, wDelay
            probeBuffer.putShort(0)
            probeBuffer.putShort(0)
            probeBuffer.putShort(if (ignoreBandwidthCeiling) 10000.toShort() else 6000.toShort())
            probeBuffer.putShort(0)
            probeBuffer.putShort(0)
            // dwMaxVideoFrameSize
            val maxFrameBytes = targetMode.width * targetMode.height * 2
            probeBuffer.putInt(maxFrameBytes)
            // dwMaxPayloadTransferSize (if forcing over USB 2.0, request max high-bandwidth ISO/Bulk payload)
            val maxPayloadBytes = if (ignoreBandwidthCeiling) 3072 else 1024
            probeBuffer.putInt(maxPayloadBytes)

            val payload = probeBuffer.array()

            // 1. UVC_SET_CUR -> VS_PROBE_CONTROL
            val probeSet = connection.controlTransfer(
                RT_CLASS_INTERFACE_SET,
                UVC_SET_CUR,
                VS_PROBE_CONTROL,
                interfaceNum,
                payload,
                payload.size,
                1000
            )

            // 2. UVC_GET_CUR -> VS_PROBE_CONTROL (read back device negotiation)
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

            // If user opted to Force Mode, overwrite readback's dwFrameInterval & format with requested values
            val commitPayload = if (ignoreBandwidthCeiling) payload else readback

            // 3. UVC_SET_CUR -> VS_COMMIT_CONTROL
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
