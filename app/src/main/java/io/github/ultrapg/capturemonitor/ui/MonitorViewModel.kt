package io.github.ultrapg.capturemonitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ultrapg.capturemonitor.audio.UsbAudioLoopbackEngine
import io.github.ultrapg.capturemonitor.model.AspectRatioPreset
import io.github.ultrapg.capturemonitor.model.CaptureDeviceInfo
import io.github.ultrapg.capturemonitor.model.ColorSpaceMode
import io.github.ultrapg.capturemonitor.model.RenderConfig
import io.github.ultrapg.capturemonitor.model.StreamTelemetry
import io.github.ultrapg.capturemonitor.model.UsbStandard
import io.github.ultrapg.capturemonitor.model.VideoFormat
import io.github.ultrapg.capturemonitor.model.VideoMode
import io.github.ultrapg.capturemonitor.usb.UsbCaptureDiscovery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MonitorUiState(
    val devices: List<CaptureDeviceInfo> = emptyList(),
    val selectedDevice: CaptureDeviceInfo? = null,
    val selectedMode: VideoMode = VideoMode(1920, 1080, 60, VideoFormat.MJPEG),
    val simulatedUsbPortStandard: UsbStandard? = null,
    val forceBypassUsbLimits: Boolean = false,
    val renderConfig: RenderConfig = RenderConfig(),
    val telemetry: StreamTelemetry = StreamTelemetry(),
    val isPanelOpen: Boolean = true,
    val showHudOverlay: Boolean = true,
    val audioPassthroughEnabled: Boolean = true,
    val audioVolume: Float = 1.0f,
    val isAudioMuted: Boolean = false,
    val audioPeakLevel: Float = 0f,
    val hasCameraPermission: Boolean = false,
    val hasAudioPermission: Boolean = false,
    val bannerMessage: String? = null
) {
    val effectiveUsbStandard: UsbStandard
        get() = simulatedUsbPortStandard ?: selectedDevice?.usbStandard ?: UsbStandard.USB_3_0_GEN1

    val isModeExceedingUsbPort: Boolean
        get() = selectedMode.exceedsUsbBandwidth(effectiveUsbStandard)

    val bandwidthUsagePercent: Int
        get() = selectedMode.bandwidthUtilizationPercent(effectiveUsbStandard)
}

class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MonitorUiState())
    val uiState: StateFlow<MonitorUiState> = _uiState.asStateFlow()

    private val usbDiscovery = UsbCaptureDiscovery(application) {
        refreshDevices()
    }

    private val audioEngine = UsbAudioLoopbackEngine(application) { rmsLevel ->
        _uiState.update { it.copy(audioPeakLevel = rmsLevel) }
    }

    init {
        usbDiscovery.register()
        refreshDevices()
    }

    fun refreshDevices() {
        viewModelScope.launch {
            val found = usbDiscovery.discoverCaptureDevices()
            _uiState.update { current ->
                val chosen = found.firstOrNull { it.id == current.selectedDevice?.id }
                    ?: found.firstOrNull { !it.isSimulated }
                    ?: found.firstOrNull()
                current.copy(
                    devices = found,
                    selectedDevice = chosen
                )
            }
        }
    }

    fun onPermissionsResult(cameraGranted: Boolean, audioGranted: Boolean) {
        _uiState.update {
            it.copy(
                hasCameraPermission = cameraGranted,
                hasAudioPermission = audioGranted
            )
        }
        if (audioGranted && _uiState.value.audioPassthroughEnabled) {
            audioEngine.start()
        }
        refreshDevices()
    }

    fun selectDevice(device: CaptureDeviceInfo) {
        _uiState.update {
            it.copy(
                selectedDevice = device,
                simulatedUsbPortStandard = null,
                bannerMessage = "Switched input to ${device.name} (${device.usbVersionString})"
            )
        }
    }

    fun selectResolutionAndFps(
        width: Int = _uiState.value.selectedMode.width,
        height: Int = _uiState.value.selectedMode.height,
        fps: Int = _uiState.value.selectedMode.fps,
        format: VideoFormat = _uiState.value.selectedMode.format
    ) {
        val candidate = VideoMode(width, height, fps, format)
        val usbStd = _uiState.value.effectiveUsbStandard
        val exceeds = candidate.exceedsUsbBandwidth(usbStd)

        _uiState.update { state ->
            val msg = if (exceeds && !state.forceBypassUsbLimits) {
                "Port limit warning: ${candidate.resolutionLabel}@${candidate.fps}Hz (${candidate.format.shortName}) requires ~${candidate.requiredMbps} Mbps, exceeding ${usbStd.specName} practical ceiling (${usbStd.maxPracticalPayloadMbps} Mbps). Enable 'Force Mode' or switch to MJPEG."
            } else if (exceeds && state.forceBypassUsbLimits) {
                "Forcing ${candidate.resolutionLabel}@${candidate.fps}Hz (${candidate.format.shortName}) over ${usbStd.specName} via UVC VS_COMMIT_CONTROL override."
            } else {
                "Locked ${candidate.resolutionLabel} @ ${candidate.fps}Hz (${candidate.format.shortName}) • ${candidate.requiredMbps} Mbps"
            }

            state.copy(
                selectedMode = candidate,
                telemetry = state.telemetry.copy(
                    estimatedBitrateMbps = candidate.requiredMbps.toFloat(),
                    isForcedModeActive = exceeds && state.forceBypassUsbLimits,
                    negotiateStatus = if (exceeds && !state.forceBypassUsbLimits) "Bandwidth Warning" else "Locked"
                ),
                bannerMessage = msg
            )
        }

        if (_uiState.value.forceBypassUsbLimits || !exceeds) {
            applyUvcNegotiation(candidate, _uiState.value.forceBypassUsbLimits)
        }
    }

    fun setForceBypassUsbLimits(force: Boolean) {
        _uiState.update { state ->
            state.copy(
                forceBypassUsbLimits = force,
                telemetry = state.telemetry.copy(
                    isForcedModeActive = force,
                    negotiateStatus = if (force) "Forced UVC Override" else "Locked"
                )
            )
        }
        if (force) {
            applyUvcNegotiation(_uiState.value.selectedMode, ignoreBandwidthCeiling = true)
        }
    }

    fun triggerForceUvcCommitNow() {
        applyUvcNegotiation(_uiState.value.selectedMode, ignoreBandwidthCeiling = true)
    }

    private fun applyUvcNegotiation(mode: VideoMode, ignoreBandwidthCeiling: Boolean) {
        val device = _uiState.value.selectedDevice ?: return
        viewModelScope.launch {
            val result = usbDiscovery.forceUvcStreamNegotiation(
                deviceInfo = device,
                targetMode = mode,
                ignoreBandwidthCeiling = ignoreBandwidthCeiling
            )
            _uiState.update { state ->
                state.copy(
                    forceBypassUsbLimits = ignoreBandwidthCeiling || state.forceBypassUsbLimits,
                    telemetry = state.telemetry.copy(
                        isForcedModeActive = ignoreBandwidthCeiling,
                        negotiateStatus = if (ignoreBandwidthCeiling) "Forced Override" else "Negotiated"
                    ),
                    bannerMessage = result.statusMessage
                )
            }
        }
    }

    fun setSimulatedUsbPortStandard(standard: UsbStandard?) {
        _uiState.update {
            it.copy(
                simulatedUsbPortStandard = standard,
                bannerMessage = if (standard != null) {
                    "Inspecting port ceiling as ${standard.label} (${standard.maxPracticalPayloadMbps} Mbps max payload)"
                } else {
                    "Using hardware-detected USB link speed"
                }
            )
        }
    }

    fun setPanelOpen(open: Boolean) {
        _uiState.update { it.copy(isPanelOpen = open) }
    }

    fun togglePanel() {
        _uiState.update { it.copy(isPanelOpen = !it.isPanelOpen) }
    }

    fun setShowHudOverlay(show: Boolean) {
        _uiState.update { it.copy(showHudOverlay = show) }
    }

    fun updateRenderConfig(transform: (RenderConfig) -> RenderConfig) {
        _uiState.update { it.copy(renderConfig = transform(it.renderConfig)) }
    }

    fun resetColorAndTransform() {
        _uiState.update {
            it.copy(
                renderConfig = RenderConfig(),
                bannerMessage = "Reset GPU shader grading & viewport geometry to reference defaults"
            )
        }
    }

    fun setAudioPassthrough(enabled: Boolean) {
        _uiState.update { it.copy(audioPassthroughEnabled = enabled) }
        if (enabled && _uiState.value.hasAudioPermission) {
            audioEngine.start()
        } else {
            audioEngine.stop()
        }
    }

    fun setAudioVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 2.0f)
        audioEngine.volumeGain = clamped
        _uiState.update { it.copy(audioVolume = clamped) }
    }

    fun toggleAudioMute() {
        val nextMute = !_uiState.value.isAudioMuted
        audioEngine.isMuted = nextMute
        _uiState.update { it.copy(isAudioMuted = nextMute) }
    }

    fun onRendererTelemetry(fps: Float, frameTimeMs: Float) {
        _uiState.update { state ->
            val bitrate = state.selectedMode.requiredMbps.toFloat()
            state.copy(
                telemetry = state.telemetry.copy(
                    currentFps = fps,
                    frameTimeMs = frameTimeMs,
                    estimatedBitrateMbps = bitrate
                )
            )
        }
    }

    fun clearBanner() {
        _uiState.update { it.copy(bannerMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        usbDiscovery.unregister()
        audioEngine.stop()
    }
}
