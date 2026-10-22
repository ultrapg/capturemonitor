package io.github.ultrapg.capturemonitor.usb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Process
import android.util.Log
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import io.github.ultrapg.capturemonitor.model.CaptureDeviceInfo
import io.github.ultrapg.capturemonitor.model.VideoFormat
import io.github.ultrapg.capturemonitor.model.VideoMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Direct User-Space USB Video Class (UVC 1.0 / 1.1 / 1.5) Streaming Engine.
 *
 * Streams live HDMI video directly from USB / USB-C Capture Cards (Magewell USB Capture HDMI,
 * MacroSilicon MS2109 / MS2130, Elgato Cam Link, AVerMedia, EVGA XR1, Razer, and generic UVC cards)
 * via Android's `UsbManager` and Linux `usbfs` (`USBDEVFS_SUBMITURB` / `USBDEVFS_REAPURB`),
 * completely bypassing `CameraManager` so the phone's built-in front/back cameras are NEVER opened.
 *
 * Supports:
 * - Both **USB Bulk** video streaming endpoints (`USB_ENDPOINT_XFER_BULK`) and
 *   **USB Isochronous** video streaming endpoints (`USB_ENDPOINT_XFER_ISOC`).
 * - Real-time decoding of **MJPEG**, **YUY2 (YUV 4:2:2)**, **UYVY (YUV 4:2:2)**, and **NV12 (YUV 4:2:0)**
 *   into a direct RGBA `ByteBuffer` uploaded directly to the GPU texture pipeline.
 */
class UvcUsbStreamEngine(
    context: Context,
    private val onFrameDecoded: (rgbaBuffer: ByteBuffer, width: Int, height: Int, isBlankSignal: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "UvcUsbStreamEngine"

        // UVC class-specific request codes
        private const val RT_CLASS_INTERFACE_SET = 0x21
        private const val RT_CLASS_INTERFACE_GET = 0xA1
        private const val SET_CUR = 0x01
        private const val GET_CUR = 0x81
        private const val GET_MIN = 0x82
        private const val GET_MAX = 0x83
        private const val GET_DEF = 0x87

        private const val VS_PROBE_CONTROL = 0x0100
        private const val VS_COMMIT_CONTROL = 0x0200

        // Linux usbfs constants (<linux/usbdevice_fs.h>)
        private const val USBDEVFS_URB_TYPE_ISO: Byte = 0
        private const val USBDEVFS_URB_TYPE_BULK: Byte = 3
        private const val USBDEVFS_URB_ISO_ASAP = 0x02

        private val IS_64_BIT = Native.POINTER_SIZE == 8

        // Exact ioctl codes for 64-bit (arm64-v8a / x86_64) and 32-bit (armeabi-v7a / x86)
        private val USBDEVFS_SETINTERFACE = 0x80085504L
        private val USBDEVFS_SUBMITURB = if (IS_64_BIT) 0x8038550aL else 0x802c550aL
        private val USBDEVFS_DISCARDURB = 0x0000550bL
        private val USBDEVFS_REAPURB = if (IS_64_BIT) 0x4008550cL else 0x4004550cL
        private val USBDEVFS_REAPURBNDELAY = if (IS_64_BIT) 0x4008550dL else 0x4004550dL
        private val USBDEVFS_CLAIMINTERFACE = 0x8004550fL
        private val USBDEVFS_RELEASEINTERFACE = 0x80045510L
        private val USBDEVFS_CLEAR_HALT = 0x80045515L
        private val USBDEVFS_DISCONNECT_CLAIM = 0x8108551bL

        // struct usbdevfs_urb field offsets
        private val URB_HEADER_SIZE = if (IS_64_BIT) 56 else 44
        private const val OFF_TYPE = 0
        private const val OFF_ENDPOINT = 1
        private const val OFF_STATUS = 4
        private const val OFF_FLAGS = 8
        private val OFF_BUFFER = if (IS_64_BIT) 16 else 12
        private val OFF_BUFFER_LENGTH = if (IS_64_BIT) 24 else 16
        private val OFF_ACTUAL_LENGTH = if (IS_64_BIT) 28 else 20
        private val OFF_START_FRAME = if (IS_64_BIT) 32 else 24
        private val OFF_NUMBER_OF_PACKETS = if (IS_64_BIT) 36 else 28
        private val OFF_ERROR_COUNT = if (IS_64_BIT) 40 else 32
        private val OFF_SIGNR = if (IS_64_BIT) 44 else 36
        private val OFF_USERCONTEXT = if (IS_64_BIT) 48 else 40
        private const val ISO_DESC_SIZE = 12
    }

    private interface LibC : Library {
        fun ioctl(fd: Int, request: NativeLong, arg: Pointer?): Int
    }

    private val libc: LibC? = try {
        Native.load("c", LibC::class.java)
    } catch (e: Throwable) {
        Log.w(TAG, "Unable to load libc via JNA: ${e.message}")
        null
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val isRunning = AtomicBoolean(false)

    @Volatile
    private var streamGeneration = 0

    private var workerThread: Thread? = null

    data class UvcFrameDescriptorEntry(
        val format: VideoFormat,
        val formatIndex: Int,
        val frameIndex: Int,
        val width: Int,
        val height: Int,
        val defaultInterval100Ns: Int,
        val intervals100Ns: List<Int>
    )

    data class UvcEndpointCandidate(
        val interfaceNumber: Int,
        val alternateSetting: Int,
        val endpointAddress: Int,
        val isIsochronous: Boolean,
        val maxPacketBytes: Int
    )

    data class ParsedUvcTopology(
        val controlInterfaceNumber: Int,
        val streamingInterfaceNumber: Int,
        val frames: List<UvcFrameDescriptorEntry>,
        val endpoints: List<UvcEndpointCandidate>
    )

    fun start(deviceInfo: CaptureDeviceInfo, targetMode: VideoMode) {
        stop()
        val usbDevice = resolveUsbDevice(deviceInfo) ?: return
        if (!usbManager.hasPermission(usbDevice)) return

        isRunning.set(true)
        val myGen = ++streamGeneration

        workerThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            while (isRunning.get() && streamGeneration == myGen) {
                val dev = resolveUsbDevice(deviceInfo)
                if (dev == null || !usbManager.hasPermission(dev)) break
                runStreamLoop(dev, targetMode, myGen)
                if (isRunning.get() && streamGeneration == myGen) {
                    try {
                        Thread.sleep(900)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }, "UvcUsbStreamThread").also { it.start() }
    }

    fun stop() {
        streamGeneration++
        isRunning.set(false)
        try {
            workerThread?.join(350)
        } catch (_: Exception) {
        }
        workerThread = null
    }

    private fun resolveUsbDevice(deviceInfo: CaptureDeviceInfo): UsbDevice? {
        val map = usbManager.deviceList
        deviceInfo.usbDeviceName?.let { map[it]?.let { dev -> return dev } }
        return map.values.firstOrNull {
            it.vendorId == deviceInfo.vendorId && it.productId == deviceInfo.productId
        }
    }

    private fun runStreamLoop(usbDevice: UsbDevice, targetMode: VideoMode, myGen: Int) {
        var connection: UsbDeviceConnection? = null
        try {
            connection = usbManager.openDevice(usbDevice)
            if (connection == null) {
                Log.w(TAG, "usbManager.openDevice returned null for ${usbDevice.deviceName}")
                return
            }

            val fd = connection.fileDescriptor
            val rawDesc = connection.rawDescriptors
            val topology = if (rawDesc != null && rawDesc.isNotEmpty()) {
                parseUvcTopology(rawDesc, usbDevice)
            } else {
                buildFallbackTopology(usbDevice, targetMode)
            }

            val chosenFrame = chooseBestFrameEntry(topology.frames, targetMode)
            val vsIntfNum = topology.streamingInterfaceNumber
            val vcIntfNum = topology.controlInterfaceNumber

            // Detach kernel uvcvideo driver (if bound) and claim VideoControl & VideoStreaming interfaces
            disconnectAndClaimInterface(fd, connection, usbDevice, vcIntfNum)
            disconnectAndClaimInterface(fd, connection, usbDevice, vsIntfNum)

            // Reset VideoStreaming interface to altsetting 0 before probe/commit
            setInterfaceAltSetting(fd, connection, usbDevice, vsIntfNum, 0)

            // Perform UVC VS_PROBE_CONTROL & VS_COMMIT_CONTROL negotiation
            val fps = targetMode.fps.coerceIn(15, 240)
            val requestedInterval = chooseBestInterval100Ns(chosenFrame, fps)
            val negotiatedPayloadSize = negotiateUvcProbeCommit(
                connection = connection,
                vsInterfaceNum = vsIntfNum,
                formatIndex = chosenFrame.formatIndex,
                frameIndex = chosenFrame.frameIndex,
                frameInterval100Ns = requestedInterval,
                width = chosenFrame.width,
                height = chosenFrame.height,
                format = chosenFrame.format
            )

            // Choose streaming endpoint (Isochronous vs Bulk)
            val vsEndpoints = topology.endpoints.filter { it.interfaceNumber == vsIntfNum }
                .ifEmpty { topology.endpoints }

            val isoEndpoints = vsEndpoints.filter { it.isIsochronous && it.maxPacketBytes > 0 }
            val bulkEndpoints = vsEndpoints.filter { !it.isIsochronous }

            // Notice: If the card negotiated dwMaxPayloadTransferSize > 3072 (or only has Bulk endpoints),
            // it is a Bulk UVC device (e.g. Magewell USB Capture HDMI, MS2130, Elgato Cam Link).
            var isoStreamed = false
            if (isoEndpoints.isNotEmpty() && (bulkEndpoints.isEmpty() || negotiatedPayloadSize in 1..3072)) {
                val orderedIso = isoEndpoints.sortedWith(
                    compareBy<UvcEndpointCandidate> { ep ->
                        if (negotiatedPayloadSize in 1..3072 && ep.maxPacketBytes >= negotiatedPayloadSize) 0 else 1
                    }.thenBy { ep ->
                        if (negotiatedPayloadSize in 1..3072 && ep.maxPacketBytes >= negotiatedPayloadSize) {
                            ep.maxPacketBytes
                        } else {
                            -ep.maxPacketBytes
                        }
                    }
                )
                for (candidate in orderedIso) {
                    if (!isRunning.get() || streamGeneration != myGen) break
                    setInterfaceAltSetting(fd, connection, usbDevice, candidate.interfaceNumber, candidate.alternateSetting)
                    val ok = streamIsochronousUrbs(
                        fd = fd,
                        endpoint = candidate,
                        frameDesc = chosenFrame,
                        myGen = myGen
                    )
                    if (ok) {
                        isoStreamed = true
                        break
                    }
                }
                // Reset altsetting to 0 when leaving Isochronous mode
                setInterfaceAltSetting(fd, connection, usbDevice, vsIntfNum, 0)
            }

            if (!isoStreamed && bulkEndpoints.isNotEmpty()) {
                val chosenBulk = bulkEndpoints.first()
                if (chosenBulk.alternateSetting > 0) {
                    setInterfaceAltSetting(fd, connection, usbDevice, chosenBulk.interfaceNumber, chosenBulk.alternateSetting)
                }
                clearEndpointHalt(fd, chosenBulk.endpointAddress)
                streamBulkTransfers(
                    fd = fd,
                    connection = connection,
                    usbDevice = usbDevice,
                    endpointCandidate = chosenBulk,
                    frameDesc = chosenFrame,
                    negotiatedPayloadSize = negotiatedPayloadSize,
                    myGen = myGen
                )
            } else if (!isoStreamed && isoEndpoints.isEmpty()) {
                Log.w(TAG, "No IN streaming endpoints found on UVC device ${usbDevice.deviceName}")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "UVC stream error: ${e.message}")
        } finally {
            try {
                connection?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun disconnectAndClaimInterface(
        fd: Int,
        connection: UsbDeviceConnection,
        usbDevice: UsbDevice,
        interfaceNumber: Int
    ) {
        if (interfaceNumber < 0) return
        if (fd >= 0 && libc != null) {
            try {
                // struct usbdevfs_disconnect_claim { unsigned int interface; unsigned int flags; char driver[256]; }
                val mem = Memory(268)
                mem.clear()
                mem.setInt(0, interfaceNumber)
                mem.setInt(4, 0)
                libc.ioctl(fd, NativeLong(USBDEVFS_DISCONNECT_CLAIM), mem)
            } catch (_: Throwable) {
            }
        }
        for (i in 0 until usbDevice.interfaceCount) {
            val intf = usbDevice.getInterface(i)
            if (intf.id == interfaceNumber) {
                try {
                    connection.claimInterface(intf, true)
                } catch (_: Exception) {
                }
                break
            }
        }
    }

    private fun setInterfaceAltSetting(
        fd: Int,
        connection: UsbDeviceConnection,
        usbDevice: UsbDevice,
        interfaceNumber: Int,
        altSetting: Int
    ) {
        for (i in 0 until usbDevice.interfaceCount) {
            val intf = usbDevice.getInterface(i)
            if (intf.id == interfaceNumber && intf.alternateSetting == altSetting) {
                try {
                    connection.setInterface(intf)
                } catch (_: Exception) {
                }
                break
            }
        }
        if (fd >= 0 && libc != null) {
            try {
                // struct usbdevfs_setinterface { unsigned int interface; unsigned int altsetting; }
                val mem = Memory(8)
                mem.setInt(0, interfaceNumber)
                mem.setInt(4, altSetting)
                libc.ioctl(fd, NativeLong(USBDEVFS_SETINTERFACE), mem)
            } catch (_: Throwable) {
            }
        }
    }

    private fun clearEndpointHalt(fd: Int, endpointAddress: Int) {
        if (fd >= 0 && libc != null) {
            try {
                val mem = Memory(4)
                mem.setInt(0, endpointAddress)
                libc.ioctl(fd, NativeLong(USBDEVFS_CLEAR_HALT), mem)
            } catch (_: Throwable) {
            }
        }
    }

    private fun negotiateUvcProbeCommit(
        connection: UsbDeviceConnection,
        vsInterfaceNum: Int,
        formatIndex: Int,
        frameIndex: Int,
        frameInterval100Ns: Int,
        width: Int,
        height: Int,
        format: VideoFormat
    ): Int {
        // Try 34-byte (UVC 1.1) first, then 26-byte (UVC 1.0)
        var probeLen = 34
        val probeBuf = ByteArray(48)

        var getRes = connection.controlTransfer(
            RT_CLASS_INTERFACE_GET,
            GET_CUR,
            VS_PROBE_CONTROL,
            vsInterfaceNum,
            probeBuf,
            34,
            1000
        )
        if (getRes < 26) {
            getRes = connection.controlTransfer(
                RT_CLASS_INTERFACE_GET,
                GET_DEF,
                VS_PROBE_CONTROL,
                vsInterfaceNum,
                probeBuf,
                26,
                1000
            )
            probeLen = if (getRes >= 26) getRes else 26
        } else {
            probeLen = getRes
        }

        val fallbackMaxFrameBytes = if (format == VideoFormat.MJPEG) {
            width * height
        } else {
            width * height * 2
        }

        writeProbeFields(probeBuf, formatIndex, frameIndex, frameInterval100Ns, fallbackMaxFrameBytes)

        // Probe SET_CUR
        connection.controlTransfer(
            RT_CLASS_INTERFACE_SET,
            SET_CUR,
            VS_PROBE_CONTROL,
            vsInterfaceNum,
            probeBuf,
            probeLen,
            1000
        )

        // Probe GET_CUR to read card-negotiated dwMaxVideoFrameSize & dwMaxPayloadTransferSize
        val readBack = ByteArray(48)
        val curRes = connection.controlTransfer(
            RT_CLASS_INTERFACE_GET,
            GET_CUR,
            VS_PROBE_CONTROL,
            vsInterfaceNum,
            readBack,
            probeLen,
            1000
        )
        val commitBuf = if (curRes >= 26) {
            // Keep negotiated payload limits but ensure our target formatIndex & frameIndex are preserved
            readBack[2] = formatIndex.toByte()
            readBack[3] = frameIndex.toByte()
            readBack
        } else {
            probeBuf
        }

        // Commit SET_CUR
        connection.controlTransfer(
            RT_CLASS_INTERFACE_SET,
            SET_CUR,
            VS_COMMIT_CONTROL,
            vsInterfaceNum,
            commitBuf,
            probeLen,
            1000
        )

        return readU32Le(commitBuf, 22)
    }

    private fun writeProbeFields(
        buf: ByteArray,
        formatIndex: Int,
        frameIndex: Int,
        frameInterval100Ns: Int,
        maxFrameBytes: Int
    ) {
        // bmHint = 0x0001 (dwFrameInterval fixed)
        buf[0] = 0x01
        buf[1] = 0x00
        buf[2] = formatIndex.toByte()
        buf[3] = frameIndex.toByte()
        writeU32Le(buf, 4, frameInterval100Ns)
        if (readU32Le(buf, 18) <= 0) {
            writeU32Le(buf, 18, maxFrameBytes)
        }
    }

    /**
     * Isochronous URB streaming via Linux usbfs (`USBDEVFS_SUBMITURB` / `USBDEVFS_REAPURB`).
     */
    private fun streamIsochronousUrbs(
        fd: Int,
        endpoint: UvcEndpointCandidate,
        frameDesc: UvcFrameDescriptorEntry,
        myGen: Int
    ): Boolean {
        val cLib = libc ?: return false
        if (fd < 0) return false

        val pktSize = endpoint.maxPacketBytes.coerceIn(512, 3072)
        val numPackets = 32
        val bufLen = pktSize * numPackets
        val numUrbs = 6
        val urbTotalStructSize = URB_HEADER_SIZE + numPackets * ISO_DESC_SIZE

        val urbMemories = Array(numUrbs) { Memory(urbTotalStructSize.toLong()) }
        val dataMemories = Array(numUrbs) { Memory(bufLen.toLong()) }
        val packetScratch = ByteArray(pktSize)

        val assembler = UvcFrameAssembler(
            width = frameDesc.width,
            height = frameDesc.height,
            format = frameDesc.format,
            onCompleteFrame = { rgba, w, h, isBlank ->
                if (isRunning.get() && streamGeneration == myGen) {
                    onFrameDecoded(rgba, w, h, isBlank)
                }
            }
        )

        var submittedCount = 0
        try {
            for (u in 0 until numUrbs) {
                val urb = urbMemories[u]
                val data = dataMemories[u]
                urb.clear()
                data.clear()

                urb.setByte(OFF_TYPE.toLong(), USBDEVFS_URB_TYPE_ISO)
                urb.setByte(OFF_ENDPOINT.toLong(), endpoint.endpointAddress.toByte())
                urb.setInt(OFF_FLAGS.toLong(), USBDEVFS_URB_ISO_ASAP)
                urb.setPointer(OFF_BUFFER.toLong(), data)
                urb.setInt(OFF_BUFFER_LENGTH.toLong(), bufLen)
                urb.setInt(OFF_NUMBER_OF_PACKETS.toLong(), numPackets)
                urb.setPointer(OFF_USERCONTEXT.toLong(), Pointer.createConstant(u.toLong() + 1L))

                for (p in 0 until numPackets) {
                    val descOff = (URB_HEADER_SIZE + p * ISO_DESC_SIZE).toLong()
                    urb.setInt(descOff, pktSize)
                    urb.setInt(descOff + 4, 0)
                    urb.setInt(descOff + 8, 0)
                }

                val rc = cLib.ioctl(fd, NativeLong(USBDEVFS_SUBMITURB), urb)
                if (rc == 0) {
                    submittedCount++
                }
            }

            if (submittedCount == 0) {
                Log.w(TAG, "Failed to submit Isochronous URBs on alt=${endpoint.alternateSetting} pkt=${endpoint.maxPacketBytes}")
                return false
            }

            val reapedPtrHolder = Memory(Native.POINTER_SIZE.toLong())

            while (isRunning.get() && streamGeneration == myGen) {
                reapedPtrHolder.clear()
                val rc = cLib.ioctl(fd, NativeLong(USBDEVFS_REAPURBNDELAY), reapedPtrHolder)
                if (rc < 0) {
                    Thread.sleep(1)
                    continue
                }
                val reapedUrb = reapedPtrHolder.getPointer(0) ?: continue
                val userCtx = Pointer.nativeValue(reapedUrb.getPointer(OFF_USERCONTEXT.toLong())).toInt() - 1
                if (userCtx !in 0 until numUrbs) continue

                val urb = urbMemories[userCtx]
                val data = dataMemories[userCtx]

                for (p in 0 until numPackets) {
                    val descOff = (URB_HEADER_SIZE + p * ISO_DESC_SIZE).toLong()
                    val actualLen = urb.getInt(descOff + 4)
                    val pktStatus = urb.getInt(descOff + 8)
                    if (pktStatus == 0 && actualLen >= 2 && actualLen <= pktSize) {
                        data.read((p * pktSize).toLong(), packetScratch, 0, actualLen)
                        assembler.processIsoPacket(packetScratch, actualLen)
                    }
                    // Reset descriptor for resubmission
                    urb.setInt(descOff, pktSize)
                    urb.setInt(descOff + 4, 0)
                    urb.setInt(descOff + 8, 0)
                }

                if (isRunning.get() && streamGeneration == myGen) {
                    cLib.ioctl(fd, NativeLong(USBDEVFS_SUBMITURB), urb)
                }
            }
            return true
        } finally {
            for (u in 0 until numUrbs) {
                try {
                    cLib.ioctl(fd, NativeLong(USBDEVFS_DISCARDURB), urbMemories[u])
                } catch (_: Throwable) {
                }
            }
        }
    }

    /**
     * High-speed Bulk UVC streaming (used by Magewell USB Capture HDMI, MacroSilicon MS2130, Elgato Cam Link, etc.).
     */
    private fun streamBulkTransfers(
        fd: Int,
        connection: UsbDeviceConnection,
        usbDevice: UsbDevice,
        endpointCandidate: UvcEndpointCandidate,
        frameDesc: UvcFrameDescriptorEntry,
        negotiatedPayloadSize: Int,
        myGen: Int
    ) {
        val assembler = UvcFrameAssembler(
            width = frameDesc.width,
            height = frameDesc.height,
            format = frameDesc.format,
            onCompleteFrame = { rgba, w, h, isBlank ->
                if (isRunning.get() && streamGeneration == myGen) {
                    onFrameDecoded(rgba, w, h, isBlank)
                }
            }
        )

        // Resolve UsbEndpoint object for bulkTransfer
        var usbEndpoint: UsbEndpoint? = null
        for (i in 0 until usbDevice.interfaceCount) {
            val intf = usbDevice.getInterface(i)
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.address == endpointCandidate.endpointAddress) {
                    usbEndpoint = ep
                    break
                }
            }
        }

        // First try async Bulk URBs via usbfs for maximum throughput on USB 3.0 capture cards
        val cLib = libc
        if (fd >= 0 && cLib != null) {
            val urbBufSize = when {
                negotiatedPayloadSize in 4096..65536 -> negotiatedPayloadSize
                else -> 32768
            }
            val numUrbs = 4
            val urbMemories = Array(numUrbs) { Memory(URB_HEADER_SIZE.toLong()) }
            val dataMemories = Array(numUrbs) { Memory(urbBufSize.toLong()) }
            val scratch = ByteArray(urbBufSize)

            var submitted = 0
            for (u in 0 until numUrbs) {
                val urb = urbMemories[u]
                val data = dataMemories[u]
                urb.clear()
                data.clear()
                urb.setByte(OFF_TYPE.toLong(), USBDEVFS_URB_TYPE_BULK)
                urb.setByte(OFF_ENDPOINT.toLong(), endpointCandidate.endpointAddress.toByte())
                urb.setInt(OFF_FLAGS.toLong(), 0)
                urb.setPointer(OFF_BUFFER.toLong(), data)
                urb.setInt(OFF_BUFFER_LENGTH.toLong(), urbBufSize)
                urb.setPointer(OFF_USERCONTEXT.toLong(), Pointer.createConstant(u.toLong() + 1L))
                if (cLib.ioctl(fd, NativeLong(USBDEVFS_SUBMITURB), urb) == 0) {
                    submitted++
                }
            }

            if (submitted > 0) {
                try {
                    val reapedPtrHolder = Memory(Native.POINTER_SIZE.toLong())
                    while (isRunning.get() && streamGeneration == myGen) {
                        reapedPtrHolder.clear()
                        val rc = cLib.ioctl(fd, NativeLong(USBDEVFS_REAPURBNDELAY), reapedPtrHolder)
                        if (rc < 0) {
                            Thread.sleep(1)
                            continue
                        }
                        val reapedUrb = reapedPtrHolder.getPointer(0) ?: continue
                        val userCtx = Pointer.nativeValue(reapedUrb.getPointer(OFF_USERCONTEXT.toLong())).toInt() - 1
                        if (userCtx !in 0 until numUrbs) continue

                        val urb = urbMemories[userCtx]
                        val data = dataMemories[userCtx]
                        val actualLen = urb.getInt(OFF_ACTUAL_LENGTH.toLong())
                        val status = urb.getInt(OFF_STATUS.toLong())
                        if (status == 0 && actualLen > 0 && actualLen <= urbBufSize) {
                            data.read(0, scratch, 0, actualLen)
                            assembler.processBulkChunk(scratch, actualLen)
                        }
                        urb.setInt(OFF_ACTUAL_LENGTH.toLong(), 0)
                        urb.setInt(OFF_STATUS.toLong(), 0)
                        if (isRunning.get() && streamGeneration == myGen) {
                            cLib.ioctl(fd, NativeLong(USBDEVFS_SUBMITURB), urb)
                        }
                    }
                    return
                } finally {
                    for (u in 0 until numUrbs) {
                        try {
                            cLib.ioctl(fd, NativeLong(USBDEVFS_DISCARDURB), urbMemories[u])
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        }

        // Fallback to standard UsbDeviceConnection.bulkTransfer
        val ep = usbEndpoint ?: return
        val transferSize = when {
            negotiatedPayloadSize in 4096..65536 -> negotiatedPayloadSize
            else -> 32768
        }
        val readBuf = ByteArray(transferSize)
        while (isRunning.get() && streamGeneration == myGen) {
            val n = connection.bulkTransfer(ep, readBuf, readBuf.size, 250)
            if (n > 0) {
                assembler.processBulkChunk(readBuf, n)
            }
        }
    }

    private fun parseUvcTopology(raw: ByteArray, usbDevice: UsbDevice): ParsedUvcTopology {
        var vcIntf = -1
        var vsIntf = -1
        var curIntfNum = -1
        var curAltSetting = 0
        var curIntfClass = -1
        var curIntfSubClass = -1
        var currentFormat = VideoFormat.MJPEG
        var currentFormatIndex = 1

        val frames = mutableListOf<UvcFrameDescriptorEntry>()
        val endpoints = mutableListOf<UvcEndpointCandidate>()

        var i = 0
        while (i + 2 <= raw.size) {
            val len = raw[i].toInt() and 0xFF
            val type = raw[i + 1].toInt() and 0xFF
            if (len < 2 || i + len > raw.size) break

            when (type) {
                0x04 -> if (len >= 9) {
                    // INTERFACE descriptor
                    curIntfNum = raw[i + 2].toInt() and 0xFF
                    curAltSetting = raw[i + 3].toInt() and 0xFF
                    curIntfClass = raw[i + 5].toInt() and 0xFF
                    curIntfSubClass = raw[i + 6].toInt() and 0xFF

                    if (curIntfClass == UsbConstants.USB_CLASS_VIDEO) {
                        if (curIntfSubClass == 1 && vcIntf == -1) vcIntf = curIntfNum
                        if (curIntfSubClass == 2 && vsIntf == -1) vsIntf = curIntfNum
                    }
                }

                0x24 -> if (curIntfClass == UsbConstants.USB_CLASS_VIDEO && curIntfSubClass == 2 && len >= 4) {
                    // CS_INTERFACE inside VideoStreaming
                    val subType = raw[i + 2].toInt() and 0xFF
                    when (subType) {
                        0x04 -> if (len >= 21) {
                            // VS_FORMAT_UNCOMPRESSED
                            currentFormatIndex = raw[i + 3].toInt() and 0xFF
                            val c0 = raw[i + 5].toInt() and 0xFF
                            val c1 = raw[i + 6].toInt() and 0xFF
                            val c2 = raw[i + 7].toInt() and 0xFF
                            val c3 = raw[i + 8].toInt() and 0xFF
                            currentFormat = when {
                                c0 == 0x55 && c1 == 0x59 && c2 == 0x56 && c3 == 0x59 -> VideoFormat.UYVY
                                c0 == 0x4E && c1 == 0x56 && c2 == 0x31 && c3 == 0x32 -> VideoFormat.NV12
                                else -> VideoFormat.YUY2
                            }
                        }

                        0x06 -> if (len >= 6) {
                            // VS_FORMAT_MJPEG
                            currentFormatIndex = raw[i + 3].toInt() and 0xFF
                            currentFormat = VideoFormat.MJPEG
                        }

                        0x10 -> if (len >= 21) {
                            // VS_FORMAT_FRAME_BASED
                            currentFormatIndex = raw[i + 3].toInt() and 0xFF
                            val c0 = raw[i + 5].toInt() and 0xFF
                            val c1 = raw[i + 6].toInt() and 0xFF
                            currentFormat = if (c0 == 0x4E && c1 == 0x56) VideoFormat.NV12 else VideoFormat.YUY2
                        }

                        0x05, 0x07, 0x11 -> if (len >= 26) {
                            // VS_FRAME_UNCOMPRESSED (0x05), VS_FRAME_MJPEG (0x07), VS_FRAME_FRAME_BASED (0x11)
                            val frameIndex = raw[i + 3].toInt() and 0xFF
                            val w = readU16Le(raw, i + 5)
                            val h = readU16Le(raw, i + 7)
                            val defInterval = readU32Le(raw, i + 21).takeIf { it > 0 } ?: 333333
                            val intervalType = raw[i + 25].toInt() and 0xFF
                            val intervals = mutableListOf<Int>()
                            if (intervalType == 0 && i + 38 <= raw.size) {
                                val minInt = readU32Le(raw, i + 26)
                                val maxInt = readU32Le(raw, i + 30)
                                intervals.add(defInterval)
                                if (minInt > 0) intervals.add(minInt)
                                if (maxInt > 0) intervals.add(maxInt)
                            } else {
                                var pos = i + 26
                                for (k in 0 until intervalType) {
                                    if (pos + 4 <= i + len) {
                                        val iv = readU32Le(raw, pos)
                                        if (iv > 0) intervals.add(iv)
                                    }
                                    pos += 4
                                }
                            }
                            if (intervals.isEmpty()) intervals.add(defInterval)
                            if (w in 160..7680 && h in 120..4320) {
                                frames.add(
                                    UvcFrameDescriptorEntry(
                                        format = currentFormat,
                                        formatIndex = currentFormatIndex,
                                        frameIndex = frameIndex,
                                        width = w,
                                        height = h,
                                        defaultInterval100Ns = defInterval,
                                        intervals100Ns = intervals.distinct()
                                    )
                                )
                            }
                        }
                    }
                }

                0x05 -> if (len >= 7 && (curIntfClass == UsbConstants.USB_CLASS_VIDEO && curIntfSubClass == 2 || curIntfNum == vsIntf)) {
                    // ENDPOINT descriptor inside VideoStreaming
                    val epAddr = raw[i + 2].toInt() and 0xFF
                    val bmAttr = raw[i + 3].toInt() and 0x03
                    val rawMaxPkt = readU16Le(raw, i + 4)
                    if ((epAddr and 0x80) != 0 && (bmAttr == UsbConstants.USB_ENDPOINT_XFER_ISOC || bmAttr == UsbConstants.USB_ENDPOINT_XFER_BULK)) {
                        val isIso = bmAttr == UsbConstants.USB_ENDPOINT_XFER_ISOC
                        val basePkt = rawMaxPkt and 0x07FF
                        val mult = 1 + ((rawMaxPkt shr 11) and 0x03)
                        var effectiveMaxPkt = if (isIso) basePkt * mult else basePkt

                        // Check if SuperSpeed Endpoint Companion (0x30) follows immediately
                        val nextIdx = i + len
                        if (nextIdx + 6 <= raw.size && (raw[nextIdx + 1].toInt() and 0xFF) == 0x30) {
                            val ssBytesPerInterval = readU16Le(raw, nextIdx + 4)
                            if (isIso && ssBytesPerInterval > effectiveMaxPkt) {
                                effectiveMaxPkt = ssBytesPerInterval
                            }
                        }

                        endpoints.add(
                            UvcEndpointCandidate(
                                interfaceNumber = curIntfNum,
                                alternateSetting = curAltSetting,
                                endpointAddress = epAddr,
                                isIsochronous = isIso,
                                maxPacketBytes = effectiveMaxPkt
                            )
                        )
                    }
                }
            }
            i += len
        }

        if (vcIntf == -1 || vsIntf == -1 || endpoints.isEmpty()) {
            val fb = buildFallbackTopology(usbDevice, VideoMode(1920, 1080, 30))
            if (vcIntf == -1) vcIntf = fb.controlInterfaceNumber
            if (vsIntf == -1) vsIntf = fb.streamingInterfaceNumber
            if (endpoints.isEmpty()) endpoints.addAll(fb.endpoints)
        }

        return ParsedUvcTopology(
            controlInterfaceNumber = vcIntf,
            streamingInterfaceNumber = vsIntf,
            frames = frames,
            endpoints = endpoints
        )
    }

    private fun buildFallbackTopology(usbDevice: UsbDevice, targetMode: VideoMode): ParsedUvcTopology {
        var vcIntf = 0
        var vsIntf = 1
        val endpoints = mutableListOf<UvcEndpointCandidate>()

        for (idx in 0 until usbDevice.interfaceCount) {
            val intf = usbDevice.getInterface(idx)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_VIDEO) {
                if (intf.interfaceSubclass == 1) vcIntf = intf.id
                if (intf.interfaceSubclass == 2) vsIntf = intf.id
            }
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.direction == UsbConstants.USB_DIR_IN &&
                    (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC || ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK)
                ) {
                    if (vsIntf == 1 && intf.id != vcIntf) vsIntf = intf.id
                    endpoints.add(
                        UvcEndpointCandidate(
                            interfaceNumber = intf.id,
                            alternateSetting = intf.alternateSetting,
                            endpointAddress = ep.address,
                            isIsochronous = ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC,
                            maxPacketBytes = ep.maxPacketSize.coerceAtLeast(512)
                        )
                    )
                }
            }
        }

        return ParsedUvcTopology(
            controlInterfaceNumber = vcIntf,
            streamingInterfaceNumber = vsIntf,
            frames = listOf(
                UvcFrameDescriptorEntry(
                    format = VideoFormat.MJPEG,
                    formatIndex = 1,
                    frameIndex = 1,
                    width = targetMode.width,
                    height = targetMode.height,
                    defaultInterval100Ns = 333333,
                    intervals100Ns = listOf(333333, 166666)
                )
            ),
            endpoints = endpoints
        )
    }

    private fun chooseBestFrameEntry(
        entries: List<UvcFrameDescriptorEntry>,
        targetMode: VideoMode
    ): UvcFrameDescriptorEntry {
        if (entries.isEmpty()) {
            return UvcFrameDescriptorEntry(
                format = VideoFormat.MJPEG,
                formatIndex = 1,
                frameIndex = 1,
                width = targetMode.width,
                height = targetMode.height,
                defaultInterval100Ns = (10_000_000 / targetMode.fps.coerceAtLeast(1)),
                intervals100Ns = listOf(10_000_000 / targetMode.fps.coerceAtLeast(1))
            )
        }

        val targetArea = targetMode.width.toLong() * targetMode.height.toLong()
        return entries.minWithOrNull(
            compareBy<UvcFrameDescriptorEntry> { entry ->
                // Prefer exact resolution match first
                if (entry.width == targetMode.width && entry.height == targetMode.height) 0 else 1
            }.thenBy { entry ->
                // Prefer requested format (or MJPEG -> YUY2 -> UYVY -> NV12)
                when {
                    entry.format == targetMode.format -> 0
                    entry.format == VideoFormat.MJPEG -> 1
                    entry.format == VideoFormat.YUY2 -> 2
                    entry.format == VideoFormat.UYVY -> 3
                    else -> 4
                }
            }.thenBy { entry ->
                abs(entry.width.toLong() * entry.height.toLong() - targetArea)
            }
        )!!
    }

    private fun chooseBestInterval100Ns(entry: UvcFrameDescriptorEntry, targetFps: Int): Int {
        val wanted100Ns = (10_000_000 / targetFps.coerceIn(1, 240))
        return entry.intervals100Ns.minByOrNull { abs(it - wanted100Ns) } ?: entry.defaultInterval100Ns
    }

    private fun readU16Le(b: ByteArray, off: Int): Int {
        if (off + 2 > b.size) return 0
        return (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    }

    private fun readU32Le(b: ByteArray, off: Int): Int {
        if (off + 4 > b.size) return 0
        return (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeU32Le(b: ByteArray, off: Int, value: Int) {
        if (off + 4 > b.size) return
        b[off] = (value and 0xFF).toByte()
        b[off + 1] = ((value shr 8) and 0xFF).toByte()
        b[off + 2] = ((value shr 16) and 0xFF).toByte()
        b[off + 3] = ((value shr 24) and 0xFF).toByte()
    }
}

/**
 * Reassembles UVC payload packets from either Isochronous URBs or Bulk USB transfers
 * and decodes MJPEG, YUY2, UYVY, and NV12 frames into a direct RGBA `ByteBuffer`.
 */
private class UvcFrameAssembler(
    val width: Int,
    val height: Int,
    val format: VideoFormat,
    private val onCompleteFrame: (rgbaBuffer: ByteBuffer, width: Int, height: Int, isBlankSignal: Boolean) -> Unit
) {
    private val expectedRawBytes = when (format) {
        VideoFormat.NV12 -> width * height * 3 / 2
        else -> width * height * 2
    }
    private val maxFrameCapacity = max(expectedRawBytes + 65536, 4 * 1024 * 1024)
    private val frameBuffer = ByteArray(maxFrameCapacity)
    private var framePos = 0
    private var currentFid = -1

    // Double-buffered direct RGBA buffers so the GL thread can upload one while the CPU decodes the next
    private val rgbaBuffers = Array(2) {
        ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
    }
    private var activeRgbaSlot = 0
    private val argbPixels = IntArray(width * height)
    private var reusableBitmap: Bitmap? = null
    private val bitmapOptions = BitmapFactory.Options().apply {
        inMutable = true
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    private var consecutiveBlankFrames = 0

    fun processIsoPacket(pkt: ByteArray, len: Int) {
        if (len < 2) return
        val hlen = pkt[0].toInt() and 0xFF
        val bfh = pkt[1].toInt() and 0xFF
        if (hlen < 2 || hlen > 12 || hlen > len) return
        // Bit 6 (0x40) is UVC payload error bit
        if ((bfh and 0x40) != 0) {
            framePos = 0
            return
        }

        val fid = bfh and 0x01
        if (currentFid != -1 && fid != currentFid) {
            if (framePos > 0) {
                tryEmitFrame()
            }
            framePos = 0
        }
        currentFid = fid

        val payloadLen = len - hlen
        if (payloadLen > 0) {
            if (framePos + payloadLen <= frameBuffer.size) {
                System.arraycopy(pkt, hlen, frameBuffer, framePos, payloadLen)
                framePos += payloadLen
            } else {
                framePos = 0
            }
        }

        // Bit 1 (0x02) is End-of-Frame (EOF)
        if ((bfh and 0x02) != 0 && framePos > 0) {
            tryEmitFrame()
            framePos = 0
        }
    }

    fun processBulkChunk(chunk: ByteArray, len: Int) {
        if (len <= 0) return

        if (format == VideoFormat.MJPEG) {
            processMjpegBulkChunk(chunk, len)
        } else {
            processUncompressedBulkChunk(chunk, len)
        }
    }

    private fun processMjpegBulkChunk(chunk: ByteArray, len: Int) {
        val hlen = chunk[0].toInt() and 0xFF
        val bfh = if (len >= 2) chunk[1].toInt() and 0xFF else 0
        val looksLikeUvcHeader = len >= hlen + 2 &&
            (hlen == 2 || hlen == 6 || hlen == 8 || hlen == 12) &&
            (bfh and 0x80) != 0 &&
            (bfh and 0x30) == 0

        if (looksLikeUvcHeader) {
            val fid = bfh and 0x01
            val isJpegSoi = (chunk[hlen].toInt() and 0xFF) == 0xFF &&
                (chunk[hlen + 1].toInt() and 0xFF) == 0xD8
            if (isJpegSoi || (currentFid != -1 && fid != currentFid)) {
                if (framePos > 1024) {
                    tryEmitFrame()
                }
                framePos = 0
            }
            currentFid = fid

            val payloadLen = len - hlen
            if (payloadLen > 0 && framePos + payloadLen <= frameBuffer.size) {
                System.arraycopy(chunk, hlen, frameBuffer, framePos, payloadLen)
                framePos += payloadLen
            }
            if ((bfh and 0x02) != 0 && framePos > 1024) {
                tryEmitFrame()
                framePos = 0
            }
        } else {
            // Raw continuation bulk chunk; also check if SOI marker starts at offset 0
            if (len >= 2 && (chunk[0].toInt() and 0xFF) == 0xFF && (chunk[1].toInt() and 0xFF) == 0xD8) {
                if (framePos > 1024) {
                    tryEmitFrame()
                }
                framePos = 0
            }
            if (framePos + len <= frameBuffer.size) {
                System.arraycopy(chunk, 0, frameBuffer, framePos, len)
                framePos += len
            } else {
                framePos = 0
            }
            // Check if chunk ends with JPEG EOI (FF D9)
            if (framePos > 1024 &&
                (frameBuffer[framePos - 2].toInt() and 0xFF) == 0xFF &&
                (frameBuffer[framePos - 1].toInt() and 0xFF) == 0xD9
            ) {
                tryEmitFrame()
                framePos = 0
            }
        }
    }

    private fun processUncompressedBulkChunk(chunk: ByteArray, len: Int) {
        val hlen = chunk[0].toInt() and 0xFF
        val bfh = if (len >= 2) chunk[1].toInt() and 0xFF else 0
        val hasUvcHeader = len > hlen &&
            (hlen == 2 || hlen == 6 || hlen == 8 || hlen == 12) &&
            (bfh and 0x80) != 0 &&
            (bfh and 0x30) == 0 &&
            (framePos == 0 || (bfh and 0x01) == currentFid || (bfh and 0x02) != 0)

        val srcOffset: Int
        val copyLen: Int
        if (hasUvcHeader) {
            val fid = bfh and 0x01
            if (currentFid != -1 && fid != currentFid) {
                if (framePos >= expectedRawBytes * 9 / 10) {
                    tryEmitFrame()
                }
                framePos = 0
            }
            currentFid = fid
            srcOffset = hlen
            copyLen = len - hlen
        } else {
            srcOffset = 0
            copyLen = len
        }

        if (copyLen > 0) {
            val spaceLeft = expectedRawBytes - framePos
            val toCopy = min(copyLen, spaceLeft.coerceAtLeast(0))
            if (toCopy > 0 && framePos + toCopy <= frameBuffer.size) {
                System.arraycopy(chunk, srcOffset, frameBuffer, framePos, toCopy)
                framePos += toCopy
            }
            if (framePos >= expectedRawBytes || (hasUvcHeader && (bfh and 0x02) != 0 && framePos >= expectedRawBytes * 9 / 10)) {
                tryEmitFrame()
                framePos = 0
            }
        }
    }

    private fun tryEmitFrame() {
        val size = framePos
        if (size <= 0) return

        if (format == VideoFormat.MJPEG) {
            decodeMjpegFrame(size)
        } else {
            if (size >= expectedRawBytes * 8 / 10) {
                decodeYuvFrame(size)
            }
        }
    }

    private fun decodeMjpegFrame(length: Int) {
        // Locate SOI (FF D8) in case of leading padding
        var start = 0
        while (start + 1 < min(length, 64)) {
            if ((frameBuffer[start].toInt() and 0xFF) == 0xFF &&
                (frameBuffer[start + 1].toInt() and 0xFF) == 0xD8
            ) {
                break
            }
            start++
        }
        val jpegLen = length - start
        if (jpegLen < 512) return

        try {
            bitmapOptions.inBitmap = reusableBitmap
            val bmp = BitmapFactory.decodeByteArray(frameBuffer, start, jpegLen, bitmapOptions) ?: return
            if (reusableBitmap == null && bmp.isMutable) {
                reusableBitmap = bmp
            }
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) return

            val outBuf = ensureRgbaBuffer(w, h)
            outBuf.clear()
            bmp.copyPixelsToBuffer(outBuf)
            outBuf.position(0)

            val isBlank = checkIfFrameIsFlatNoSignal(outBuf, w, h)
            onCompleteFrame(outBuf, w, h, isBlank)
        } catch (_: IllegalArgumentException) {
            // If inBitmap size mismatched, retry once without inBitmap
            try {
                reusableBitmap = null
                bitmapOptions.inBitmap = null
                val bmp = BitmapFactory.decodeByteArray(frameBuffer, start, jpegLen, bitmapOptions) ?: return
                reusableBitmap = bmp
                val w = bmp.width
                val h = bmp.height
                val outBuf = ensureRgbaBuffer(w, h)
                outBuf.clear()
                bmp.copyPixelsToBuffer(outBuf)
                outBuf.position(0)
                val isBlank = checkIfFrameIsFlatNoSignal(outBuf, w, h)
                onCompleteFrame(outBuf, w, h, isBlank)
            } catch (_: Throwable) {
            }
        } catch (_: Throwable) {
        }
    }

    private fun decodeYuvFrame(length: Int) {
        val totalPixels = width * height
        val outBuf = ensureRgbaBuffer(width, height)
        var pixIdx = 0

        if (format == VideoFormat.NV12) {
            val yPlaneSize = totalPixels
            if (length < yPlaneSize + totalPixels / 2) return
            for (y in 0 until height) {
                val yRow = y * width
                val uvRow = yPlaneSize + (y shr 1) * width
                var x = 0
                while (x < width) {
                    val y0 = frameBuffer[yRow + x].toInt() and 0xFF
                    val y1 = frameBuffer[yRow + x + 1].toInt() and 0xFF
                    val u = (frameBuffer[uvRow + x].toInt() and 0xFF) - 128
                    val v = (frameBuffer[uvRow + x + 1].toInt() and 0xFF) - 128
                    argbPixels[yRow + x] = yuvToAbgrInt(y0, u, v)
                    argbPixels[yRow + x + 1] = yuvToAbgrInt(y1, u, v)
                    x += 2
                }
            }
        } else {
            // YUY2 (Y0 U Y1 V) or UYVY (U Y0 V Y1)
            val isUyvy = (format == VideoFormat.UYVY)
            val maxBytes = min(length - 3, totalPixels * 2 - 3)
            var bIdx = 0
            while (bIdx < maxBytes && pixIdx + 1 < totalPixels) {
                val b0 = frameBuffer[bIdx].toInt() and 0xFF
                val b1 = frameBuffer[bIdx + 1].toInt() and 0xFF
                val b2 = frameBuffer[bIdx + 2].toInt() and 0xFF
                val b3 = frameBuffer[bIdx + 3].toInt() and 0xFF

                val y0: Int
                val u: Int
                val y1: Int
                val v: Int
                if (isUyvy) {
                    u = b0 - 128
                    y0 = b1
                    v = b2 - 128
                    y1 = b3
                } else {
                    y0 = b0
                    u = b1 - 128
                    y1 = b2
                    v = b3 - 128
                }
                argbPixels[pixIdx] = yuvToAbgrInt(y0, u, v)
                argbPixels[pixIdx + 1] = yuvToAbgrInt(y1, u, v)
                pixIdx += 2
                bIdx += 4
            }
        }

        outBuf.clear()
        outBuf.asIntBuffer().put(argbPixels, 0, totalPixels)
        outBuf.position(0)
        val isBlank = checkIfFrameIsFlatNoSignal(outBuf, width, height)
        onCompleteFrame(outBuf, width, height, isBlank)
    }

    /**
     * Converts (Y, U, V) to little-endian RGBA_8888 (0xAABBGGRR) for direct GL_RGBA upload.
     */
    private fun yuvToAbgrInt(y: Int, u: Int, v: Int): Int {
        val c = max(0, y - 16) * 298
        val r = ((c + 409 * v + 128) shr 8).coerceIn(0, 255)
        val g = ((c - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
        val b = ((c + 516 * u + 128) shr 8).coerceIn(0, 255)
        return (-0x1000000) or (b shl 16) or (g shl 8) or r
    }

    private fun ensureRgbaBuffer(w: Int, h: Int): ByteBuffer {
        activeRgbaSlot = 1 - activeRgbaSlot
        val needed = w * h * 4
        var buf = rgbaBuffers[activeRgbaSlot]
        if (buf.capacity() < needed) {
            buf = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
            rgbaBuffers[activeRgbaSlot] = buf
        }
        return buf
    }

    /**
     * Checks 32 evenly-spaced pixels in CPU RAM (zero GPU stall) to detect if the capture card
     * is outputting a 100% flat solid-black or solid-blue "no HDMI cable" frame.
     */
    private fun checkIfFrameIsFlatNoSignal(rgba: ByteBuffer, w: Int, h: Int): Boolean {
        val totalPixels = w * h
        if (totalPixels < 64) return false

        var minR = 255; var maxR = 0; var sumR = 0
        var minG = 255; var maxG = 0; var sumG = 0
        var minB = 255; var maxB = 0; var sumB = 0

        val samples = 32
        val step = totalPixels / (samples + 1)
        for (s in 1..samples) {
            val byteOff = (s * step) * 4
            if (byteOff + 3 >= rgba.limit()) break
            val r = rgba.get(byteOff).toInt() and 0xFF
            val g = rgba.get(byteOff + 1).toInt() and 0xFF
            val b = rgba.get(byteOff + 2).toInt() and 0xFF
            if (r < minR) minR = r; if (r > maxR) maxR = r; sumR += r
            if (g < minG) minG = g; if (g > maxG) maxG = g; sumG += g
            if (b < minB) minB = b; if (b > maxB) maxB = b; sumB += b
        }

        val uniform = (maxR - minR) <= 3 && (maxG - minG) <= 3 && (maxB - minB) <= 3
        val avgR = sumR / samples
        val avgG = sumG / samples
        val avgB = sumB / samples
        val isPureBlack = avgR <= 16 && avgG <= 16 && avgB <= 16
        val isSolidBlue = avgB >= 100 && avgR <= 25 && avgG <= 40

        if (uniform && (isPureBlack || isSolidBlue)) {
            consecutiveBlankFrames++
        } else {
            consecutiveBlankFrames = 0
        }
        return consecutiveBlankFrames >= 45
    }
}
