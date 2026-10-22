package io.github.ultrapg.capturemonitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ultrapg.capturemonitor.audio.UsbAudioLoopbackEngine
import io.github.ultrapg.capturemonitor.model.CaptureDeviceInfo
import io.github.ultrapg.capturemonitor.model.HostDisplayInfo
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
    val hostDisplay: HostDisplayInfo = HostDisplayInfo(3840, 2160, 120, listOf(60, 90, 120)),
    val selectedMode: VideoMode = VideoMode(1920, 1080, 30, VideoFormat.MJPEG),
    val customModes: List<VideoMode> = emptyList(),
    val simulatedUsbPortStandard: UsbStandard? = null,
    val forceBypassUsbLimits: Boolean = false,
    val renderConfig: RenderConfig = RenderConfig(),
    val telemetry: StreamTelemetry = StreamTelemetry(),
    // Both Menu Drawer and HUD are completely hidden by default so 100% of the screen is utilized!
    val isPanelOpen: Boolean = false,
    val showHudOverlay: Boolean = false,
    val isReceivingLiveFrames: Boolean = false,
    val audioPassthroughEnabled: Boolean = true,
    val audioVolume: Float = 1.0f,
    val audioNoiseGateDb: Float = -58f,
    val isAudioMuted: Boolean = false,
    val audioPeakLevel: Float = 0f,
    val hasCameraPermission: Boolean = false,
    val hasAudioPermission: Boolean = false,
    val bannerMessage: String? = null
) {
    /**
     * True only when a physical USB capture card or external UVC device is actually plugged in.
     */
    val isCaptureCardConnected: Boolean
        get() = selectedDevice != null && !selectedDevice.isSimulated

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

    private var hasUserManuallyChosenMode = false

    private val usbDiscovery = UsbCaptureDiscovery(application) {
        refreshDevices()
    }

    private val audioEngine = UsbAudioLoopbackEngine(application) { rmsLevel, usbAudioName ->
        _uiState.update { state ->
            state.copy(
                audioPeakLevel = rmsLevel,
                telemetry = state.telemetry.copy(usbAudioDeviceName = usbAudioName)
            )
        }
    }

    init {
        usbDiscovery.register()
        audioEngine.register()
        refreshDevices()
        startDevicePolling()
    }

    private var isAppForeground = true
    private var refreshJob: kotlinx.coroutines.Job? = null

    /**
     * Safety net for missed USB / Camera2 hot-plug broadcasts: while no usable capture card is
     * bound (none attached, camera node not yet enumerated, or USB permission pending) we
     * re-scan every 2 seconds so a freshly connected card is picked up reliably.
     */
    private fun startDevicePolling() {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(2000)
                val s = _uiState.value
                val dev = s.selectedDevice
                val needsRescan = isAppForeground && (
                    dev == null ||
                        dev.isSimulated ||
                        !dev.hasUsbPermission ||
                        (dev.usbDeviceName == null && dev.camera2Id == null)
                    )
                if (needsRescan) refreshDevices()
            }
        }
    }

    /**
     * Called when the app moves to/from the foreground. The microphone is released in the
     * background (clearing the privacy indicator) and everything is re-synced on return.
     */
    fun onAppForeground(foreground: Boolean) {
        isAppForeground = foreground
        if (foreground) {
            refreshDevices()
        } else {
            audioEngine.stop()
        }
    }

    /**
     * Called by MainActivity once the Android display's native resolution and maximum refresh rate
     * (e.g., 120Hz / 144Hz / 240Hz) are queried. Automatically selects the highest supported
     * resolution and refresh rate for the device and capture card by default.
     */
    fun onHostDisplayDetected(displayInfo: HostDisplayInfo) {
        _uiState.update { state ->
            val bestMode = if (!hasUserManuallyChosenMode) {
                usbDiscovery.selectBestDefaultMode(state.selectedDevice, displayInfo)
            } else {
                state.selectedMode
            }
            state.copy(
                hostDisplay = displayInfo,
                selectedMode = bestMode
            )
        }
    }

    fun refreshDevices() {
        // Debounce bursts of USB / Camera2 callbacks into a single discovery pass
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            kotlinx.coroutines.delay(150)
            val found = usbDiscovery.discoverCaptureDevices()
            val previouslyConnectedId = _uiState.value.selectedDevice?.id

            _uiState.update { current ->
                val chosen = found.firstOrNull { it.id == current.selectedDevice?.id }
                    ?: found.firstOrNull()

                val bestMode = if (!hasUserManuallyChosenMode && (chosen != null && chosen.id != previouslyConnectedId)) {
                    usbDiscovery.selectBestDefaultMode(chosen, current.hostDisplay)
                } else {
                    current.selectedMode
                }

                current.copy(
                    devices = found,
                    selectedDevice = chosen,
                    selectedMode = bestMode,
                    telemetry = current.telemetry.copy(
                        negotiateStatus = if (chosen != null) "Locked (${chosen.usbStandard.specName})" else "No Capture Card"
                    )
                )
            }

            // Immediately update the audio engine with whether a physical capture card is attached
            val isPhysicalConnected = _uiState.value.isCaptureCardConnected
            audioEngine.isCaptureCardPhysicallyConnected = isPhysicalConnected
            if (isAppForeground) audioEngine.syncAudioState()
        }
    }

    fun onPermissionsResult(cameraGranted: Boolean, audioGranted: Boolean) {
        _uiState.update {
            it.copy(
                hasCameraPermission = cameraGranted,
                hasAudioPermission = audioGranted
            )
        }
        audioEngine.hasAudioPermission = audioGranted
        refreshDevices()
    }

    fun requestUsbPermissionForCurrentDevice() {
        val dev = _uiState.value.selectedDevice ?: return
        usbDiscovery.requestPermissionForDeviceInfo(dev)
    }

    fun selectDevice(device: CaptureDeviceInfo) {
        val bestMode = if (!hasUserManuallyChosenMode) {
            usbDiscovery.selectBestDefaultMode(device, _uiState.value.hostDisplay)
        } else {
            _uiState.value.selectedMode
        }
        _uiState.update {
            it.copy(
                selectedDevice = device,
                selectedMode = bestMode,
                simulatedUsbPortStandard = null,
                bannerMessage = "Connected to ${device.name} (${device.usbVersionString})"
            )
        }
        audioEngine.isCaptureCardPhysicallyConnected = !device.isSimulated
        audioEngine.syncAudioState()
    }

    fun selectResolutionAndFps(
        width: Int = _uiState.value.selectedMode.width,
        height: Int = _uiState.value.selectedMode.height,
        fps: Int = _uiState.value.selectedMode.fps,
        format: VideoFormat = _uiState.value.selectedMode.format,
        isCustom: Boolean = false
    ) {
        hasUserManuallyChosenMode = true
        val candidate = VideoMode(width, height, fps, format, isNativeReported = !isCustom, isCustom = isCustom)
        val usbStd = _uiState.value.effectiveUsbStandard
        val exceeds = candidate.exceedsUsbBandwidth(usbStd)

        _uiState.update { state ->
            val updatedCustomModes = if (isCustom && state.customModes.none { it.width == width && it.height == height && it.fps == fps }) {
                listOf(candidate) + state.customModes
            } else {
                state.customModes
            }

            val msg = if (exceeds && !state.forceBypassUsbLimits) {
                "Port limit warning: ${candidate.resolutionLabel}@${candidate.fps}Hz (${candidate.format.shortName}) requires ~${candidate.requiredMbps} Mbps, exceeding ${usbStd.specName} (${usbStd.maxPracticalPayloadMbps} Mbps). Enable 'Force Mode' or use MJPEG."
            } else if (exceeds && state.forceBypassUsbLimits) {
                "Forcing ${candidate.resolutionLabel}@${candidate.fps}Hz (${candidate.format.shortName}) over ${usbStd.specName} via UVC VS_COMMIT_CONTROL override."
            } else {
                "Locked ${candidate.resolutionLabel} @ ${candidate.fps}Hz (${candidate.format.shortName}) • ${candidate.requiredMbps} Mbps"
            }

            state.copy(
                selectedMode = candidate,
                customModes = updatedCustomModes,
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

    /**
     * Matches the capture card target resolution and refresh rate directly to the Android device's
     * native physical screen resolution and maximum refresh rate.
     */
    fun matchHostScreenResolutionAndHz() {
        val host = _uiState.value.hostDisplay
        selectResolutionAndFps(
            width = host.physicalWidth,
            height = host.physicalHeight,
            fps = host.maxRefreshRateHz,
            format = _uiState.value.selectedMode.format,
            isCustom = true
        )
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
        val device = _uiState.value.selectedDevice
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
        _uiState.update { state ->
            val nextCfg = transform(state.renderConfig)
            state.copy(
                renderConfig = nextCfg,
                telemetry = state.telemetry.copy(
                    activeEngineName = "${nextCfg.graphicsBackend.engineTitle} • ${nextCfg.filteringMode.label}"
                )
            )
        }
    }

    fun resetColorAndTransform() {
        _uiState.update {
            val defaultCfg = RenderConfig()
            it.copy(
                renderConfig = defaultCfg,
                telemetry = it.telemetry.copy(
                    activeEngineName = "${defaultCfg.graphicsBackend.engineTitle} • ${defaultCfg.filteringMode.label}"
                ),
                bannerMessage = "Reset GPU shader grading & full-screen geometry to defaults"
            )
        }
    }

    fun setAudioPassthrough(enabled: Boolean) {
        audioEngine.userEnabled = enabled
        _uiState.update { it.copy(audioPassthroughEnabled = enabled) }
        audioEngine.syncAudioState()
    }

    fun setAudioVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 2.0f)
        audioEngine.volumeGain = clamped
        _uiState.update { it.copy(audioVolume = clamped) }
    }

    fun setAudioNoiseGateDb(db: Float) {
        val clamped = db.coerceIn(-90f, -20f)
        audioEngine.noiseGateDb = clamped
        _uiState.update { it.copy(audioNoiseGateDb = clamped) }
    }

    fun toggleAudioMute() {
        val nextMute = !_uiState.value.isAudioMuted
        audioEngine.isMuted = nextMute
        _uiState.update { it.copy(isAudioMuted = nextMute) }
    }

    fun onRendererTelemetry(
        sourceFps: Float,
        presentedFps: Float,
        generatedFps: Float,
        frameTimeMs: Float,
        isReceivingLiveFrames: Boolean,
        gpuHardwareName: String
    ) {
        // Silence the audio path whenever no real HDMI picture is arriving (prevents idle ADC hiss)
        audioEngine.isSignalPresent = isReceivingLiveFrames
        _uiState.update { state ->
            val bitrate = if (state.isCaptureCardConnected || state.renderConfig.showTestPatternWhenNoSignal) {
                state.selectedMode.requiredMbps.toFloat()
            } else {
                0f
            }
            val cfg = state.renderConfig
            val fgSuffix = if (cfg.frameGenMode != io.github.ultrapg.capturemonitor.model.FrameGenMode.OFF) {
                " • ${cfg.frameGenMode.label}"
            } else {
                ""
            }
            state.copy(
                isReceivingLiveFrames = isReceivingLiveFrames,
                telemetry = state.telemetry.copy(
                    currentFps = sourceFps,
                    outputPresentedFps = presentedFps,
                    generatedFramesPerSec = generatedFps,
                    frameTimeMs = frameTimeMs,
                    estimatedBitrateMbps = bitrate,
                    activeEngineName = "${cfg.graphicsBackend.label} • $gpuHardwareName • ${cfg.filteringMode.label}$fgSuffix"
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
        audioEngine.unregister()
    }
}
