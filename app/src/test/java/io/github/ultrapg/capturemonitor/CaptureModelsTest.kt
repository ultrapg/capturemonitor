package io.github.ultrapg.capturemonitor

import io.github.ultrapg.capturemonitor.model.AspectRatioPreset
import io.github.ultrapg.capturemonitor.model.CaptureCardVendor
import io.github.ultrapg.capturemonitor.model.GraphicsBackend
import io.github.ultrapg.capturemonitor.model.RenderConfig
import io.github.ultrapg.capturemonitor.model.TextureFilteringMode
import io.github.ultrapg.capturemonitor.model.UsbStandard
import io.github.ultrapg.capturemonitor.model.VideoFormat
import io.github.ultrapg.capturemonitor.model.VideoMode
import io.github.ultrapg.capturemonitor.ui.MonitorUiState
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
    fun defaultUiStateUsesVulkanBackendLinearFilterFullScreenAndHiddenOverlays() {
        val state = MonitorUiState()
        assertFalse(state.isPanelOpen)
        assertFalse(state.showHudOverlay)
        assertFalse(state.isCaptureCardConnected)
        val cfg = RenderConfig()
        assertEquals(GraphicsBackend.VULKAN, cfg.graphicsBackend)
        assertEquals(AspectRatioPreset.STRETCH_FILL, cfg.aspectRatioPreset)
        assertEquals(TextureFilteringMode.LINEAR, cfg.filteringMode)
        assertEquals("Linear", cfg.filteringMode.label)
        assertTrue(TextureFilteringMode.entries.contains(TextureFilteringMode.AMD_FSR_1_0))
        assertTrue(TextureFilteringMode.entries.contains(TextureFilteringMode.AMD_FSR_ULTRA))
        assertTrue(TextureFilteringMode.entries.contains(TextureFilteringMode.NVIDIA_NIS))
        assertTrue(TextureFilteringMode.entries.contains(TextureFilteringMode.SNAPDRAGON_GSR))
    }

    @Test
    fun usbBandwidthDetectionAccuratelyFlagsUsb2BottleneckForUncompressed1080p60() {
        val modeYuy2 = VideoMode(1920, 1080, 60, VideoFormat.YUY2)
        assertTrue(modeYuy2.exceedsUsbBandwidth(UsbStandard.USB_2_0))
        assertFalse(modeYuy2.exceedsUsbBandwidth(UsbStandard.USB_3_0_GEN1))

        val modeMjpeg = VideoMode(1920, 1080, 60, VideoFormat.MJPEG)
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
