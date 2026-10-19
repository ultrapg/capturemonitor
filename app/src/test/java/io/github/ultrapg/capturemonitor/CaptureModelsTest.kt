package io.github.ultrapg.capturemonitor

import io.github.ultrapg.capturemonitor.model.CaptureCardVendor
import io.github.ultrapg.capturemonitor.model.UsbStandard
import io.github.ultrapg.capturemonitor.model.VideoFormat
import io.github.ultrapg.capturemonitor.model.VideoMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureModelsTest {

    @Test
    fun magewellVendorIdIsRecognized() {
        val vendor = CaptureCardVendor.fromVendorId(0x2935)
        assertEquals(CaptureCardVendor.MAGEWELL, vendor)
        assertTrue(vendor.supportsHardwareScaler)
    }

    @Test
    fun usbBandwidthDetectionAccuratelyFlagsUsb2BottleneckForUncompressed1080p60() {
        val modeYuy2 = VideoMode(1920, 1080, 60, VideoFormat.YUY2)
        // 1920 * 1080 * 60 * 16bpp ~= 1990 Mbps -> exceeds USB 2.0 (340 Mbps) but fits USB 3.2 Gen 1 (3200 Mbps)
        assertTrue(modeYuy2.exceedsUsbBandwidth(UsbStandard.USB_2_0))
        assertFalse(modeYuy2.exceedsUsbBandwidth(UsbStandard.USB_3_0_GEN1))

        val modeMjpeg = VideoMode(1920, 1080, 60, VideoFormat.MJPEG)
        // MJPEG 1080p60 fits inside USB 2.0 High-Speed
        assertFalse(modeMjpeg.exceedsUsbBandwidth(UsbStandard.USB_2_0))
    }

    @Test
    fun usbVersionBcdMapsToCorrectUsbStandard() {
        assertEquals(UsbStandard.USB_2_0, UsbStandard.fromUsbVersionBcd(0x0200, 512, false))
        assertEquals(UsbStandard.USB_3_0_GEN1, UsbStandard.fromUsbVersionBcd(0x0300, 1024, true))
        assertEquals(UsbStandard.USB_3_2_GEN2, UsbStandard.fromUsbVersionBcd(0x0320, 1024, true))
        assertEquals(UsbStandard.USB_4_GEN3, UsbStandard.fromUsbVersionBcd(0x0400, 1024, true))
    }
}
