package io.github.ultrapg.capturemonitor.ui

import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SettingsInputHdmi
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.ultrapg.capturemonitor.gl.HardwareVideoRenderer
import io.github.ultrapg.capturemonitor.gl.VulkanHardwarePipeline
import io.github.ultrapg.capturemonitor.model.AspectRatioPreset
import io.github.ultrapg.capturemonitor.model.CaptureCardVendor
import io.github.ultrapg.capturemonitor.model.CaptureDeviceInfo
import io.github.ultrapg.capturemonitor.model.ColorSpaceMode
import io.github.ultrapg.capturemonitor.model.FrameGenMode
import io.github.ultrapg.capturemonitor.model.FrameGenMultiplier
import io.github.ultrapg.capturemonitor.model.GraphicsBackend
import io.github.ultrapg.capturemonitor.model.TextureFilteringMode
import io.github.ultrapg.capturemonitor.model.UsbStandard
import io.github.ultrapg.capturemonitor.model.VideoFormat
import io.github.ultrapg.capturemonitor.ui.theme.StudioAmber
import io.github.ultrapg.capturemonitor.ui.theme.StudioCyan
import io.github.ultrapg.capturemonitor.ui.theme.StudioEmerald
import io.github.ultrapg.capturemonitor.ui.theme.StudioIndigo
import io.github.ultrapg.capturemonitor.ui.theme.StudioRose
import io.github.ultrapg.capturemonitor.usb.UsbCaptureDiscovery
import java.util.Locale

@Composable
fun CaptureMonitorScreen(
    viewModel: MonitorViewModel,
    onRequestPermissions: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var rendererRef by remember { mutableStateOf<HardwareVideoRenderer?>(null) }

    // Pressing the Back button or system back gesture toggles the Monitor Control Menu
    // (just like Steam Link / Moonlight / Noir / console streaming apps)
    BackHandler(enabled = true) {
        viewModel.togglePanel()
    }

    LaunchedEffect(state.renderConfig) {
        rendererRef?.updateRenderConfig(state.renderConfig)
    }

    LaunchedEffect(state.selectedDevice, state.selectedMode, state.hasCameraPermission) {
        rendererRef?.bindSource(
            device = state.selectedDevice,
            mode = state.selectedMode,
            hasCameraPermission = state.hasCameraPermission
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            rendererRef?.release()
        }
    }

    // Release the camera + microphone whenever the app is not in the foreground
    // (this also clears Android's green privacy indicator while backgrounded) and resume on return.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, rendererRef) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                    rendererRef?.pauseCapture()
                    viewModel.onAppForeground(false)
                }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    rendererRef?.resumeCapture()
                    viewModel.onAppForeground(true)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05070B))
            // Tap or double-tap gestures
            .pointerInput(state.isPanelOpen) {
                detectTapGestures(
                    onDoubleTap = { viewModel.togglePanel() },
                    onTap = {
                        if (state.isPanelOpen) {
                            viewModel.setPanelOpen(false)
                        }
                    }
                )
            }
            // Swipe from either side edge (or horizontal swipe) to open/close the control drawer
            .pointerInput(state.isPanelOpen) {
                var startX = 0f
                var accumulatedDragX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        startX = offset.x
                        accumulatedDragX = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        accumulatedDragX += dragAmount
                        val screenW = size.width.toFloat()
                        val startedNearLeftEdge = startX <= screenW * 0.18f
                        val startedNearRightEdge = startX >= screenW * 0.82f

                        if (!state.isPanelOpen) {
                            // Swipe inward from right edge OR swipe inward from left edge opens the menu
                            if ((startedNearRightEdge && accumulatedDragX < -36f) ||
                                (startedNearLeftEdge && accumulatedDragX > 36f) ||
                                accumulatedDragX < -75f
                            ) {
                                viewModel.setPanelOpen(true)
                                accumulatedDragX = 0f
                            }
                        } else {
                            if (accumulatedDragX > 42f) {
                                viewModel.setPanelOpen(false)
                                accumulatedDragX = 0f
                            }
                        }
                    }
                )
            }
            // Pinch-to-zoom and 2-finger pan
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (zoom != 1f || pan.x != 0f || pan.y != 0f) {
                        viewModel.updateRenderConfig { cfg ->
                            val newZoom = (cfg.zoomScale * zoom).coerceIn(0.5f, 3.0f)
                            val newPanX = if (newZoom > 1.01f) {
                                (cfg.panX + pan.x / size.width.coerceAtLeast(1) * 2f).coerceIn(-1f, 1f)
                            } else {
                                0f
                            }
                            val newPanY = if (newZoom > 1.01f) {
                                (cfg.panY - pan.y / size.height.coerceAtLeast(1) * 2f).coerceIn(-1f, 1f)
                            } else {
                                0f
                            }
                            cfg.copy(zoomScale = newZoom, panX = newPanX, panY = newPanY)
                        }
                    }
                }
            }
    ) {
        // 1. 100% Fullscreen Edge-to-Edge OpenGL ES 2.0/3.0 Zero-Copy Viewport
        // No floating pills or HUD elements are rendered by default so 100% of the screen is utilized!
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(2)
                    preserveEGLContextOnPause = true
                    val renderer = HardwareVideoRenderer(
                        context = ctx,
                        glSurfaceView = this,
                        onTelemetryTick = { srcFps, outFps, genFps, frameMs, isLive, gpuName ->
                            viewModel.onRendererTelemetry(srcFps, outFps, genFps, frameMs, isLive, gpuName)
                        }
                    )
                    setRenderer(renderer)
                    renderer.updateRenderConfig(state.renderConfig)
                    renderer.bindSource(
                        device = state.selectedDevice,
                        mode = state.selectedMode,
                        hasCameraPermission = state.hasCameraPermission
                    )
                    VulkanHardwarePipeline.syncViewBackend(this, state.renderConfig)
                    rendererRef = renderer
                }
            },
            update = { glView ->
                VulkanHardwarePipeline.syncViewBackend(glView, state.renderConfig)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    val targetHz = maxOf(state.hostDisplay.maxRefreshRateHz, state.selectedMode.fps).toFloat()
                    try {
                        glView.holder.surface?.takeIf { it.isValid }?.setFrameRate(
                            targetHz,
                            android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        )

        // 2. Minimalistic Material Design 3 "No Signal" Empty State
        // Shown whenever no live video is arriving (no card, card without HDMI source, or still connecting)
        // and the calibration test pattern is off.
        val showMinimalNoSignal = !state.isReceivingLiveFrames &&
            !state.renderConfig.showTestPatternWhenNoSignal

        val noSignalMessage = when {
            !state.isCaptureCardConnected ->
                "Connect a USB-C or USB HDMI capture card.\nSwipe from edge or press Back for settings."
            state.selectedDevice?.hasUsbPermission == false ->
                "Capture card detected (${state.selectedDevice?.name}).\nAllow USB access when prompted, or tap Rescan."
            !state.hasCameraPermission && state.selectedDevice?.usbDeviceName == null ->
                "Capture card detected.\nGrant camera permission to start video."
            else ->
                "Capture card connected, but no HDMI signal.\nConnect and power on an HDMI source — it starts automatically."
        }

        AnimatedVisibility(
            visible = showMinimalNoSignal,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            MinimalNoSignalM3State(
                hostDisplayLabel = state.hostDisplay.label,
                selectedModeLabel = "${state.selectedMode.resolutionLabel} @ ${state.selectedMode.fps}Hz",
                message = noSignalMessage,
                onOpenMenu = { viewModel.setPanelOpen(true) },
                onRescanUsb = {
                    if (state.selectedDevice?.hasUsbPermission == false) {
                        viewModel.requestUsbPermissionForCurrentDevice()
                    }
                    viewModel.refreshDevices()
                }
            )
        }

        // 3. Optional Top-Left Live Signal Telemetry HUD (Hidden by default; user can enable in settings)
        AnimatedVisibility(
            visible = state.showHudOverlay,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp)
        ) {
            TopSignalTelemetryHud(
                state = state,
                onTogglePanel = { viewModel.togglePanel() },
                onHideHud = { viewModel.setShowHudOverlay(false) }
            )
        }

        // 4. Bottom Status / Negotiation Toast Banner (Only visible while the menu drawer is open)
        val banner = state.bannerMessage
        AnimatedVisibility(
            visible = banner != null && state.isPanelOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 14.dp, end = 430.dp)
        ) {
            if (banner != null) {
                Surface(
                    color = if (state.isModeExceedingUsbPort && !state.forceBypassUsbLimits) {
                        Color(0xEE451A03)
                    } else {
                        Color(0xEE0F172A)
                    },
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (state.isModeExceedingUsbPort && !state.forceBypassUsbLimits) StudioAmber else StudioCyan.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                    ) {
                        Icon(
                            imageVector = if (state.isModeExceedingUsbPort && !state.forceBypassUsbLimits) {
                                Icons.Default.WarningAmber
                            } else {
                                Icons.Default.Bolt
                            },
                            contentDescription = null,
                            tint = if (state.isModeExceedingUsbPort && !state.forceBypassUsbLimits) StudioAmber else StudioCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = banner,
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = { viewModel.clearBanner() },
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }

        // 5. Slide-Over Material Design 3 Control Drawer
        // Hidden by default; triggered by swiping from either screen edge, pressing Back, or double-tapping
        AnimatedVisibility(
            visible = state.isPanelOpen,
            enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(240)) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(200)) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            SlideOverMonitorControlPanel(
                state = state,
                viewModel = viewModel,
                onRequestPermissions = onRequestPermissions
            )
        }
    }
}

/**
 * Clean, minimalistic Material Design 3 "No Signal" card shown when no capture card is connected.
 */
@Composable
private fun MinimalNoSignalM3State(
    hostDisplayLabel: String,
    selectedModeLabel: String,
    message: String,
    onOpenMenu: () -> Unit,
    onRescanUsb: () -> Unit
) {
    ElevatedCard(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = Color(0xFF0F172A).copy(alpha = 0.92f)
        ),
        modifier = Modifier
            .widthIn(max = 380.dp)
            .padding(24.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 26.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.SettingsInputHdmi,
                        contentDescription = null,
                        tint = StudioCyan,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "No Signal",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(14.dp))

            Surface(
                color = Color(0xFF1E293B).copy(alpha = 0.7f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = "Display: $hostDisplayLabel • Target: $selectedModeLabel",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = StudioCyan,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }

            Spacer(Modifier.height(18.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = onRescanUsb,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Scan USB", fontSize = 12.sp)
                }

                Button(
                    onClick = onOpenMenu,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioCyan,
                        contentColor = Color(0xFF041E2B)
                    )
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Settings", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun TopSignalTelemetryHud(
    state: MonitorUiState,
    onTogglePanel: () -> Unit,
    onHideHud: () -> Unit
) {
    val dev = state.selectedDevice
    val mode = state.selectedMode
    val usbStd = state.effectiveUsbStandard
    val isExceeding = state.isModeExceedingUsbPort
    val connected = state.isCaptureCardConnected

    Surface(
        color = Color(0xCC0B0F19),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = when {
                !connected -> Color.White.copy(alpha = 0.14f)
                isExceeding && !state.forceBypassUsbLimits -> StudioAmber.copy(alpha = 0.8f)
                state.forceBypassUsbLimits && isExceeding -> StudioRose.copy(alpha = 0.8f)
                else -> StudioEmerald.copy(alpha = 0.6f)
            }
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            !connected -> Color(0xFF64748B)
                            isExceeding && !state.forceBypassUsbLimits -> StudioAmber
                            state.forceBypassUsbLimits && isExceeding -> StudioRose
                            else -> StudioEmerald
                        }
                    )
            )
            Spacer(Modifier.width(8.dp))

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (connected) (dev?.name ?: "USB Capture Card") else "No Capture Card Connected",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (dev?.vendor == CaptureCardVendor.MAGEWELL) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(StudioCyan.copy(alpha = 0.2f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "MAGEWELL FPGA",
                                color = StudioCyan,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val srcFps = state.telemetry.currentFps.takeIf { it > 1f } ?: mode.fps.toFloat()
                    val outFps = state.telemetry.outputPresentedFps.takeIf { it > 1f } ?: srcFps
                    val fpsLabel = if (state.renderConfig.frameGenMode != FrameGenMode.OFF) {
                        "${String.format(Locale.US, "%.0f", srcFps)}→${String.format(Locale.US, "%.0f", outFps)}Hz (${state.renderConfig.frameGenMode.label})"
                    } else {
                        "${String.format(Locale.US, "%.0f", srcFps)}Hz"
                    }
                    Text(
                        text = "${mode.resolutionLabel} @ $fpsLabel",
                        color = StudioCyan,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = " • ${state.renderConfig.graphicsBackend.label} • ${state.renderConfig.filteringMode.label} • ${usbStd.specName}",
                        color = Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(Modifier.width(10.dp))
            IconButton(
                onClick = onTogglePanel,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "Configure Monitor",
                    tint = StudioCyan,
                    modifier = Modifier.size(17.dp)
                )
            }
            IconButton(
                onClick = onHideHud,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.VisibilityOff,
                    contentDescription = "Hide HUD",
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun SlideOverMonitorControlPanel(
    state: MonitorUiState,
    viewModel: MonitorViewModel,
    onRequestPermissions: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Signal & Custom Res", "Fullscreen & GPU", "Audio & HUD")

    Surface(
        color = Color(0xF50B0F19),
        modifier = Modifier
            .width(412.dp)
            .fillMaxHeight()
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp)
            ),
        shape = RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Drawer Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF111827), Color(0xFF1E293B))
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SettingsInputHdmi,
                    contentDescription = null,
                    tint = StudioCyan,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "CaptureMonitor",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Text(
                        text = "Swipe edge or press Back to hide • v0.5.0",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
                IconButton(onClick = { viewModel.refreshDevices() }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Rescan USB Bus",
                        tint = StudioCyan
                    )
                }
                IconButton(onClick = { viewModel.setPanelOpen(false) }) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Hide Drawer",
                        tint = Color.White
                    )
                }
            }

            // Tab Bar
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF0F172A),
                contentColor = StudioCyan
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                fontSize = 11.sp,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (selectedTab) {
                    0 -> SignalAndUsbTab(
                        state = state,
                        viewModel = viewModel,
                        onRequestPermissions = onRequestPermissions
                    )
                    1 -> GpuAndAspectTab(
                        state = state,
                        viewModel = viewModel
                    )
                    2 -> AudioAndHudTab(
                        state = state,
                        viewModel = viewModel,
                        onRequestPermissions = onRequestPermissions
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignalAndUsbTab(
    state: MonitorUiState,
    viewModel: MonitorViewModel,
    onRequestPermissions: () -> Unit
) {
    // 1. Live Capture Card Hardware Recognition Status Card
    SectionCard(
        title = "USB Capture Card Hardware Status",
        icon = if (state.isCaptureCardConnected) Icons.Default.Usb else Icons.Default.UsbOff
    ) {
        if (state.isCaptureCardConnected && state.devices.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(StudioEmerald.copy(alpha = 0.14f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = StudioEmerald,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${state.devices.size} Physical Capture Card(s) Detected on USB Bus",
                    color = StudioEmerald,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(8.dp))

            state.devices.forEach { device ->
                val isSelected = device.id == state.selectedDevice?.id
                CaptureDeviceRow(
                    device = device,
                    isSelected = isSelected,
                    onClick = { viewModel.selectDevice(device) }
                )
                Spacer(Modifier.height(6.dp))
            }

            if (state.selectedDevice?.hasUsbPermission == false) {
                Button(
                    onClick = { viewModel.requestUsbPermissionForCurrentDevice() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Authorize USB Capture Card Access", fontSize = 12.sp)
                }
            }
        } else {
            // Explicitly show that NO capture card is currently plugged in
            Surface(
                color = Color(0xFF0F172A),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.UsbOff,
                        contentDescription = null,
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "No USB Capture Card Connected",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "Plug in a Magewell USB Capture HDMI or UVC card via USB-C / OTG. Audio loopback is automatically silenced while disconnected.",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            FilledTonalButton(
                onClick = { viewModel.refreshDevices() },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("Rescan USB", fontSize = 11.sp)
            }
            if (!state.hasCameraPermission || !state.hasAudioPermission) {
                OutlinedButton(
                    onClick = onRequestPermissions,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.HighQuality, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Permissions", fontSize = 11.sp)
                }
            }
        }
    }

    // 2. Custom Resolution & Native Display Matcher Card
    CustomResolutionBuilderCard(
        state = state,
        viewModel = viewModel
    )

    // 3. All Capture Card Resolutions (4K, Ultrawide 21:9, 16:10 Tablet, 1440p, 1080p, 720p, Retro 4:3, PAL/NTSC)
    SectionCard(
        title = "All Capture Card Resolutions",
        icon = Icons.Default.Hd
    ) {
        val allPresets = buildList {
            // Always include the Android device's exact physical screen resolution at the top
            add(
                Triple(
                    state.hostDisplay.physicalWidth,
                    state.hostDisplay.physicalHeight,
                    "Device Screen (${state.hostDisplay.physicalWidth}×${state.hostDisplay.physicalHeight})"
                )
            )
            state.customModes.forEach { custom ->
                add(Triple(custom.width, custom.height, "Custom (${custom.width}×${custom.height})"))
            }
            UsbCaptureDiscovery.STANDARD_RESOLUTIONS.forEach { (w, h) ->
                val modeObj = io.github.ultrapg.capturemonitor.model.VideoMode(w, h, 60)
                add(Triple(w, h, "${modeObj.commonName} (${w}×${h})"))
            }
        }.distinctBy { it.first to it.second }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            allPresets.forEach { (w, h, label) ->
                val selected = state.selectedMode.width == w && state.selectedMode.height == h
                val candidateMbps = state.selectedMode.format.requiredBandwidthMbps(w, h, state.selectedMode.fps)
                val exceedsPort = candidateMbps > state.effectiveUsbStandard.maxPracticalPayloadMbps

                FilterChip(
                    selected = selected,
                    onClick = { viewModel.selectResolutionAndFps(width = w, height = h) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, fontSize = 11.sp)
                            if (exceedsPort) {
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.WarningAmber,
                                    contentDescription = "Exceeds USB Port Bandwidth",
                                    tint = StudioAmber,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = if (exceedsPort) StudioAmber.copy(alpha = 0.24f) else StudioCyan.copy(alpha = 0.22f),
                        selectedLabelColor = if (exceedsPort) StudioAmber else StudioCyan
                    )
                )
            }
        }
    }

    // 4. Refresh Rate Selector (24Hz up to 240Hz)
    SectionCard(
        title = "Refresh Rate (Hz / FPS)",
        icon = Icons.Default.Speed
    ) {
        Text(
            text = "Device Screen Max Refresh: ${state.hostDisplay.maxRefreshRateHz}Hz (${state.hostDisplay.physicalWidth}×${state.hostDisplay.physicalHeight})",
            color = StudioCyan,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(6.dp))

        val framerates = (listOf(state.selectedMode.fps) + UsbCaptureDiscovery.STANDARD_REFRESH_RATES)
            .distinct()
            .sortedDescending()

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            framerates.forEach { fps ->
                val selected = state.selectedMode.fps == fps
                val candidateMbps = state.selectedMode.format.requiredBandwidthMbps(
                    state.selectedMode.width,
                    state.selectedMode.height,
                    fps
                )
                val exceedsPort = candidateMbps > state.effectiveUsbStandard.maxPracticalPayloadMbps

                FilterChip(
                    selected = selected,
                    onClick = { viewModel.selectResolutionAndFps(fps = fps) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${fps}Hz", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            if (exceedsPort) {
                                Spacer(Modifier.width(3.dp))
                                Icon(
                                    imageVector = Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = StudioAmber,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = if (exceedsPort) StudioAmber.copy(alpha = 0.24f) else StudioCyan.copy(alpha = 0.22f),
                        selectedLabelColor = if (exceedsPort) StudioAmber else StudioCyan
                    )
                )
            }
        }
    }

    // 5. USB Port Link Speed Analyzer & Force-Mode Engine
    UsbBandwidthAndForceCard(
        state = state,
        viewModel = viewModel
    )

    // 6. Capture Pixel Format & Encoding
    SectionCard(
        title = "Capture Pixel Format & Chroma Subsampling",
        icon = Icons.Default.Memory
    ) {
        VideoFormat.entries.forEach { fmt ->
            val selected = state.selectedMode.format == fmt
            val reqMbps = fmt.requiredBandwidthMbps(
                state.selectedMode.width,
                state.selectedMode.height,
                state.selectedMode.fps
            )
            val exceeds = reqMbps > state.effectiveUsbStandard.maxPracticalPayloadMbps

            Surface(
                color = if (selected) Color(0xFF1E293B) else Color(0xFF0F172A),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = when {
                        selected && exceeds -> StudioAmber
                        selected -> StudioCyan
                        else -> Color.White.copy(alpha = 0.08f)
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.selectResolutionAndFps(format = fmt) }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = fmt.fullName,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "~$reqMbps Mbps",
                                color = if (exceeds) StudioAmber else StudioEmerald,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = fmt.description,
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun CustomResolutionBuilderCard(
    state: MonitorUiState,
    viewModel: MonitorViewModel
) {
    var widthInput by remember(state.selectedMode.width) { mutableStateOf(state.selectedMode.width.toString()) }
    var heightInput by remember(state.selectedMode.height) { mutableStateOf(state.selectedMode.height.toString()) }
    var fpsInput by remember(state.selectedMode.fps) { mutableStateOf(state.selectedMode.fps.toString()) }

    SectionCard(
        title = "Custom Resolution & Native Screen Match",
        icon = Icons.Default.Add
    ) {
        Text(
            text = "Enter any custom resolution (320×240 up to 7680×4320) and refresh rate (1–240Hz) to negotiate or force on the capture card:",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = widthInput,
                onValueChange = { widthInput = it.filter { ch -> ch.isDigit() }.take(4) },
                label = { Text("Width", fontSize = 10.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = heightInput,
                onValueChange = { heightInput = it.filter { ch -> ch.isDigit() }.take(4) },
                label = { Text("Height", fontSize = 10.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = fpsInput,
                onValueChange = { fpsInput = it.filter { ch -> ch.isDigit() }.take(3) },
                label = { Text("Hz / FPS", fontSize = 10.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(0.85f)
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = {
                    val w = widthInput.toIntOrNull()?.coerceIn(320, 7680) ?: 1920
                    val h = heightInput.toIntOrNull()?.coerceIn(240, 4320) ?: 1080
                    val hz = fpsInput.toIntOrNull()?.coerceIn(1, 240) ?: 60
                    viewModel.selectResolutionAndFps(width = w, height = h, fps = hz, isCustom = true)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = StudioCyan,
                    contentColor = Color(0xFF041E2B)
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("Apply Custom Mode", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            FilledTonalButton(
                onClick = { viewModel.matchHostScreenResolutionAndHz() },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Fullscreen, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Match Screen (${state.hostDisplay.maxRefreshRateHz}Hz)",
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun UsbBandwidthAndForceCard(
    state: MonitorUiState,
    viewModel: MonitorViewModel
) {
    val usbStd = state.effectiveUsbStandard
    val mode = state.selectedMode
    val requiredMbps = mode.requiredMbps
    val ceilingMbps = usbStd.maxPracticalPayloadMbps
    val exceeds = state.isModeExceedingUsbPort
    val usageFraction = (requiredMbps.toFloat() / ceilingMbps.toFloat()).coerceIn(0f, 1f)

    SectionCard(
        title = "USB Port Link Speed & Force-Mode Engine",
        icon = Icons.Default.Bolt
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Text(
                    text = "Active Port Ceiling: ${usbStd.label}",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                Text(
                    text = "Required: $requiredMbps Mbps / Max Payload: $ceilingMbps Mbps (${state.bandwidthUsagePercent}%)",
                    color = if (exceeds) StudioAmber else StudioEmerald,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { usageFraction },
            color = when {
                exceeds && state.forceBypassUsbLimits -> StudioRose
                exceeds -> StudioAmber
                else -> StudioEmerald
            },
            trackColor = Color(0xFF1E293B),
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Inspect / Override USB Port Standard:",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            FilterChip(
                selected = state.simulatedUsbPortStandard == null,
                onClick = { viewModel.setSimulatedUsbPortStandard(null) },
                label = { Text("Auto Detect", fontSize = 10.sp) }
            )
            listOf(
                UsbStandard.USB_2_0,
                UsbStandard.USB_3_0_GEN1,
                UsbStandard.USB_3_2_GEN2,
                UsbStandard.USB_4_GEN3
            ).forEach { std ->
                FilterChip(
                    selected = state.simulatedUsbPortStandard == std,
                    onClick = { viewModel.setSimulatedUsbPortStandard(std) },
                    label = { Text(std.specName, fontSize = 10.sp) }
                )
            }
        }

        if (exceeds) {
            Spacer(Modifier.height(8.dp))
            Surface(
                color = Color(0xFF451A03).copy(alpha = 0.75f),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, StudioAmber)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = StudioAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "USB Port Bandwidth Limit Warning",
                            color = StudioAmber,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${mode.resolutionLabel} @ ${mode.fps}Hz (${mode.format.shortName}) requires ~$requiredMbps Mbps, exceeding ${usbStd.specName}'s $ceilingMbps Mbps limit. Switch to MJPEG or enable Force Mode below.",
                        color = Color(0xFFFDE68A),
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Force Unsupported Resolution / Refresh Rate",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                Text(
                    text = "Bypass USB speed limits & send raw UVC VS_COMMIT_CONTROL",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
            }
            Switch(
                checked = state.forceBypassUsbLimits,
                onCheckedChange = { viewModel.setForceBypassUsbLimits(it) }
            )
        }

        Spacer(Modifier.height(6.dp))
        Button(
            onClick = { viewModel.triggerForceUvcCommitNow() },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (exceeds) StudioAmber else StudioCyan,
                contentColor = Color(0xFF070A12)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Force UVC Probe & Commit (${mode.resolutionLabel}@${mode.fps}Hz)",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GpuAndAspectTab(
    state: MonitorUiState,
    viewModel: MonitorViewModel
) {
    val cfg = state.renderConfig

    SectionCard(
        title = "Full Screen Scaling & Aspect Ratio",
        icon = Icons.Default.CropFree
    ) {
        Text(
            text = "Defaults to Full Screen (Stretch Fill) so the entire phone/tablet screen is always used edge-to-edge:",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AspectRatioPreset.entries.forEach { preset ->
                FilterChip(
                    selected = cfg.aspectRatioPreset == preset,
                    onClick = {
                        viewModel.updateRenderConfig { it.copy(aspectRatioPreset = preset) }
                    },
                    label = { Text(preset.label, fontSize = 11.sp) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            AssistChip(
                onClick = {
                    viewModel.updateRenderConfig {
                        it.copy(rotationDegrees = (it.rotationDegrees + 90) % 360)
                    }
                },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                label = { Text("Rotate (${cfg.rotationDegrees}°)", fontSize = 11.sp) }
            )
            AssistChip(
                onClick = {
                    viewModel.updateRenderConfig { it.copy(mirrorHorizontal = !it.mirrorHorizontal) }
                },
                leadingIcon = {
                    Icon(Icons.Default.Flip, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                label = { Text(if (cfg.mirrorHorizontal) "Mirrored H" else "Mirror H", fontSize = 11.sp) }
            )
            AssistChip(
                onClick = {
                    viewModel.updateRenderConfig { it.copy(flipVertical = !it.flipVertical) }
                },
                label = { Text(if (cfg.flipVertical) "Flipped V" else "Flip V", fontSize = 11.sp) }
            )
        }

        Spacer(Modifier.height(6.dp))
        LabeledSlider(
            label = "Digital Zoom",
            valueText = "${String.format(Locale.US, "%.2f", cfg.zoomScale)}×",
            value = cfg.zoomScale,
            valueRange = 0.5f..3.0f,
            onValueChange = { v -> viewModel.updateRenderConfig { it.copy(zoomScale = v) } }
        )
        LabeledSlider(
            label = "HDMI Overscan Edge Crop",
            valueText = "${(cfg.overscanCropPercent * 100).toInt()}%",
            value = cfg.overscanCropPercent,
            valueRange = 0.0f..0.10f,
            onValueChange = { v -> viewModel.updateRenderConfig { it.copy(overscanCropPercent = v) } }
        )
    }

    SectionCard(
        title = "Hardware Graphics Backend (Vulkan / OpenGL ES)",
        icon = Icons.Default.DeveloperBoard
    ) {
        Text(
            text = "Defaults to Vulkan 1.3 for low-overhead hardware acceleration across all GPUs. Switch between Vulkan and OpenGL ES anytime:",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            GraphicsBackend.entries.forEach { backend ->
                val selected = cfg.graphicsBackend == backend
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.updateRenderConfig { it.copy(graphicsBackend = backend) } },
                    label = {
                        Text(
                            text = backend.label,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StudioCyan.copy(alpha = 0.24f),
                        selectedLabelColor = StudioCyan
                    )
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Surface(
            color = Color(0xFF0F172A),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = cfg.graphicsBackend.engineTitle,
                    color = StudioCyan,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = cfg.graphicsBackend.description,
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
            }
        }
    }

    SectionCard(
        title = "Hardware Upscaling & Super Resolution Filters",
        icon = Icons.Default.HighQuality
    ) {
        Text(
            text = "Active Filter: ${cfg.filteringMode.label}",
            color = StudioCyan,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp
        )
        Text(
            text = cfg.filteringMode.description,
            color = Color(0xFF94A3B8),
            fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TextureFilteringMode.entries.forEach { mode ->
                val selected = cfg.filteringMode == mode
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.updateRenderConfig { it.copy(filteringMode = mode) } },
                    label = { Text(mode.label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StudioCyan.copy(alpha = 0.22f),
                        selectedLabelColor = StudioCyan
                    )
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        LabeledSlider(
            label = "Upscaler / FSR RCAS / NIS / GSR Sharpness",
            valueText = "${(cfg.sharpness * 100).toInt()}%",
            value = cfg.sharpness,
            valueRange = 0f..1f,
            onValueChange = { v -> viewModel.updateRenderConfig { it.copy(sharpness = v) } }
        )
    }

    SectionCard(
        title = "Open Frame Generation & Motion Interpolation (FG)",
        icon = Icons.Default.Speed
    ) {
        Text(
            text = "Active FG Model: ${cfg.frameGenMode.label}",
            color = if (cfg.frameGenMode != FrameGenMode.OFF) StudioEmerald else StudioCyan,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp
        )
        Text(
            text = cfg.frameGenMode.description,
            color = Color(0xFF94A3B8),
            fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FrameGenMode.entries.forEach { fg ->
                val selected = cfg.frameGenMode == fg
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.updateRenderConfig { it.copy(frameGenMode = fg) } },
                    label = { Text(fg.label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = if (fg != FrameGenMode.OFF) StudioEmerald.copy(alpha = 0.22f) else StudioCyan.copy(alpha = 0.22f),
                        selectedLabelColor = if (fg != FrameGenMode.OFF) StudioEmerald else StudioCyan
                    )
                )
            }
        }

        if (cfg.frameGenMode != FrameGenMode.OFF) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Frame Generation Target Multiplier:",
                color = Color(0xFFCBD5E1),
                fontSize = 11.sp
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FrameGenMultiplier.entries.forEach { mult ->
                    FilterChip(
                        selected = cfg.frameGenMultiplier == mult,
                        onClick = { viewModel.updateRenderConfig { it.copy(frameGenMultiplier = mult) } },
                        label = { Text(mult.label, fontSize = 11.sp) }
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            LabeledSlider(
                label = "Optical Flow Motion Vector Scale",
                valueText = "${String.format(Locale.US, "%.2f", cfg.frameGenMotionScale)}×",
                value = cfg.frameGenMotionScale,
                valueRange = 0.25f..2.0f,
                onValueChange = { v -> viewModel.updateRenderConfig { it.copy(frameGenMotionScale = v) } }
            )
            LabeledSlider(
                label = "Disocclusion & Anti-Ghosting Clamp",
                valueText = "${(cfg.frameGenAntiGhosting * 100).toInt()}%",
                value = cfg.frameGenAntiGhosting,
                valueRange = 0f..1f,
                onValueChange = { v -> viewModel.updateRenderConfig { it.copy(frameGenAntiGhosting = v) } }
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Static HUD / Crosshair / Text Protection", color = Color.White, fontSize = 12.sp)
                    Text("Prevents UI overlays and text from warping during fast camera pans", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
                Switch(
                    checked = cfg.frameGenUiProtection,
                    onCheckedChange = { prot -> viewModel.updateRenderConfig { it.copy(frameGenUiProtection = prot) } }
                )
            }
        }
    }

    SectionCard(
        title = "Color Space Matrix & Dynamic Range Grading",
        icon = Icons.Default.Tune
    ) {
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ColorSpaceMode.entries.forEach { cs ->
                FilterChip(
                    selected = cfg.colorSpace == cs,
                    onClick = { viewModel.updateRenderConfig { it.copy(colorSpace = cs) } },
                    label = { Text(cs.label, fontSize = 11.sp) }
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Full RGB Range (0–255)", color = Color.White, fontSize = 12.sp)
                Text(
                    text = if (cfg.fullRangeRgb) "PC / Console Full Range (0–255)" else "Broadcast Limited Range (16–235 expanded)",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
            }
            Switch(
                checked = cfg.fullRangeRgb,
                onCheckedChange = { full -> viewModel.updateRenderConfig { it.copy(fullRangeRgb = full) } }
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.White.copy(alpha = 0.08f))

        LabeledSlider(
            label = "Brightness",
            valueText = String.format(Locale.US, "%+.0f%%", cfg.brightness * 100f),
            value = cfg.brightness,
            valueRange = -0.4f..0.4f,
            onValueChange = { v -> viewModel.updateRenderConfig { it.copy(brightness = v) } }
        )
        LabeledSlider(
            label = "Contrast",
            valueText = "${(cfg.contrast * 100).toInt()}%",
            value = cfg.contrast,
            valueRange = 0.5f..1.8f,
            onValueChange = { v -> viewModel.updateRenderConfig { it.copy(contrast = v) } }
        )
        LabeledSlider(
            label = "Saturation",
            valueText = "${(cfg.saturation * 100).toInt()}%",
            value = cfg.saturation,
            valueRange = 0.0f..2.0f,
            onValueChange = { v -> viewModel.updateRenderConfig { it.copy(saturation = v) } }
        )
        LabeledSlider(
            label = "Gamma",
            valueText = String.format(Locale.US, "%.2f", cfg.gamma),
            value = cfg.gamma,
            valueRange = 0.6f..2.2f,
            onValueChange = { v -> viewModel.updateRenderConfig { it.copy(gamma = v) } }
        )

        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = { viewModel.resetColorAndTransform() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Reset Backend, Upscaler & Fullscreen Geometry", fontSize = 12.sp)
        }
    }
}

@Composable
private fun AudioAndHudTab(
    state: MonitorUiState,
    viewModel: MonitorViewModel,
    onRequestPermissions: () -> Unit
) {
    SectionCard(
        title = "Hiss-Free USB Digital Audio Passthrough",
        icon = if (state.isAudioMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp
    ) {
        Surface(
            color = if (state.telemetry.usbAudioDeviceName != null) {
                StudioEmerald.copy(alpha = 0.14f)
            } else {
                Color(0xFF0F172A)
            },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (state.telemetry.usbAudioDeviceName != null) {
                    "Active USB Audio Source: ${state.telemetry.usbAudioDeviceName}"
                } else {
                    "USB Audio Standby: Automatically muted until a USB capture card audio endpoint is connected (prevents internal mic feedback hiss)"
                },
                color = if (state.telemetry.usbAudioDeviceName != null) StudioEmerald else Color(0xFF94A3B8),
                fontSize = 11.sp,
                modifier = Modifier.padding(10.dp)
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Low-Latency 48kHz PCM USB Audio Loopback",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                Text(
                    text = "Routes only external USB/HDMI capture audio to device speakers",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
            }
            Switch(
                checked = state.audioPassthroughEnabled,
                onCheckedChange = { viewModel.setAudioPassthrough(it) }
            )
        }

        Spacer(Modifier.height(8.dp))
        LabeledSlider(
            label = "Preamp Gain",
            valueText = "${(state.audioVolume * 100).toInt()}%",
            value = state.audioVolume,
            valueRange = 0f..2.0f,
            onValueChange = { viewModel.setAudioVolume(it) }
        )

        LabeledSlider(
            label = "Anti-Hiss Digital Noise Gate Threshold",
            valueText = "${state.audioNoiseGateDb.toInt()} dBFS",
            value = state.audioNoiseGateDb,
            valueRange = -90f..-25f,
            onValueChange = { viewModel.setAudioNoiseGateDb(it) }
        )

        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Live USB Audio Peak Meter",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp
            )
            AssistChip(
                onClick = { viewModel.toggleAudioMute() },
                leadingIcon = {
                    Icon(
                        imageVector = if (state.isAudioMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                },
                label = { Text(if (state.isAudioMuted) "Unmute" else "Mute", fontSize = 11.sp) }
            )
        }
        LinearProgressIndicator(
            progress = { state.audioPeakLevel.coerceIn(0f, 1f) },
            color = StudioEmerald,
            trackColor = Color(0xFF1E293B),
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
        )

        if (!state.hasAudioPermission) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Grant USB Audio Capture Permission", fontSize = 12.sp)
            }
        }
    }

    SectionCard(
        title = "Monitor Overlays, Guides & Calibration",
        icon = Icons.Default.GridOn
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Show Top-Left Signal Telemetry HUD", color = Color.White, fontSize = 12.sp)
                Text("Hidden by default so 100% of the screen is clean", color = Color(0xFF94A3B8), fontSize = 11.sp)
            }
            Switch(
                checked = state.showHudOverlay,
                onCheckedChange = { viewModel.setShowHudOverlay(it) }
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Rule-of-Thirds & Broadcast Safe Area Grid", color = Color.White, fontSize = 12.sp)
                Text("Hardware shader framing overlay", color = Color(0xFF94A3B8), fontSize = 11.sp)
            }
            Switch(
                checked = state.renderConfig.showSafeAreaGrid,
                onCheckedChange = { show ->
                    viewModel.updateRenderConfig { it.copy(showSafeAreaGrid = show) }
                }
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("SMPTE Calibration Test Pattern (When Disconnected)", color = Color.White, fontSize = 12.sp)
                Text("Preview shader color grading & refresh motion bar without a capture card", color = Color(0xFF94A3B8), fontSize = 11.sp)
            }
            Switch(
                checked = state.renderConfig.showTestPatternWhenNoSignal,
                onCheckedChange = { show ->
                    viewModel.updateRenderConfig { it.copy(showTestPatternWhenNoSignal = show) }
                }
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Detect Blank HDMI Feed as No Signal", color = Color.White, fontSize = 12.sp)
                Text("Cards that stream a black / solid-blue frame with no HDMI source show No Signal", color = Color(0xFF94A3B8), fontSize = 11.sp)
            }
            Switch(
                checked = state.renderConfig.autoDetectBlankSignal,
                onCheckedChange = { on ->
                    viewModel.updateRenderConfig { it.copy(autoDetectBlankSignal = on) }
                }
            )
        }
    }

    SectionCard(
        title = "Hardware Pipeline Telemetry",
        icon = Icons.Default.Info
    ) {
        TelemetryKeyValueRow("Capture Card Connected", if (state.isCaptureCardConnected) "Yes (${state.selectedDevice?.name})" else "No (Standby)")
        TelemetryKeyValueRow("Host Screen Native Mode", state.hostDisplay.label)
        TelemetryKeyValueRow("Render Engine", state.telemetry.activeEngineName)
        TelemetryKeyValueRow("Source Input FPS", String.format(Locale.US, "%.1f FPS", state.telemetry.currentFps))
        TelemetryKeyValueRow(
            "Output Presented FPS (with FG)",
            String.format(
                Locale.US,
                "%.1f FPS (+%.1f FG)",
                state.telemetry.outputPresentedFps,
                state.telemetry.generatedFramesPerSec
            )
        )
        TelemetryKeyValueRow("Measured Frame Time", String.format(Locale.US, "%.2f ms", state.telemetry.frameTimeMs))
        TelemetryKeyValueRow("USB Host Link", state.selectedDevice?.usbVersionString ?: "Awaiting USB Device")
        TelemetryKeyValueRow("Negotiation State", state.telemetry.negotiateStatus)
    }
}

@Composable
private fun CaptureDeviceRow(
    device: CaptureDeviceInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (isSelected) Color(0xFF1E293B) else Color(0xFF0F172A),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (isSelected) StudioCyan else Color.White.copy(alpha = 0.08f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = device.name,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (device.vendor == CaptureCardVendor.MAGEWELL) {
                                StudioCyan.copy(alpha = 0.2f)
                            } else {
                                StudioIndigo.copy(alpha = 0.2f)
                            }
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = device.vendor.badgeText,
                        color = if (device.vendor == CaptureCardVendor.MAGEWELL) StudioCyan else StudioIndigo,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = "VID:PID ${device.hexVidPid} • ${device.usbVersionString}",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = StudioCyan,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = label, color = Color(0xFFCBD5E1), fontSize = 11.sp)
            Text(
                text = valueText,
                color = StudioCyan,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

@Composable
private fun TelemetryKeyValueRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(text = label, color = Color(0xFF94A3B8), fontSize = 11.sp)
        Text(
            text = value,
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
