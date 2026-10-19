package io.github.ultrapg.capturemonitor.ui

import android.opengl.GLSurfaceView
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SettingsInputHdmi
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.ultrapg.capturemonitor.gl.HardwareVideoRenderer
import io.github.ultrapg.capturemonitor.model.AspectRatioPreset
import io.github.ultrapg.capturemonitor.model.CaptureCardVendor
import io.github.ultrapg.capturemonitor.model.CaptureDeviceInfo
import io.github.ultrapg.capturemonitor.model.ColorSpaceMode
import io.github.ultrapg.capturemonitor.model.UsbStandard
import io.github.ultrapg.capturemonitor.model.VideoFormat
import io.github.ultrapg.capturemonitor.ui.theme.StudioAmber
import io.github.ultrapg.capturemonitor.ui.theme.StudioCyan
import io.github.ultrapg.capturemonitor.ui.theme.StudioEmerald
import io.github.ultrapg.capturemonitor.ui.theme.StudioIndigo
import io.github.ultrapg.capturemonitor.ui.theme.StudioRose
import java.util.Locale

@Composable
fun CaptureMonitorScreen(
    viewModel: MonitorViewModel,
    onRequestPermissions: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var rendererRef by remember { mutableStateOf<HardwareVideoRenderer?>(null) }

    // Sync renderer configuration whenever state changes
    LaunchedEffect(state.renderConfig) {
        rendererRef?.updateRenderConfig(state.renderConfig)
    }

    LaunchedEffect(state.selectedDevice, state.selectedMode, state.hasCameraPermission) {
        rendererRef?.bindSource(
            cameraId = state.selectedDevice?.camera2Id,
            mode = state.selectedMode,
            hasCameraPermission = state.hasCameraPermission
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            rendererRef?.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Support pinch-to-zoom, double-tap to toggle side panel, and swipe from right/left edge
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
            .pointerInput(Unit) {
                var accumulatedDragX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { accumulatedDragX = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        accumulatedDragX += dragAmount
                        if (accumulatedDragX < -48f && !state.isPanelOpen) {
                            viewModel.setPanelOpen(true)
                            accumulatedDragX = 0f
                        } else if (accumulatedDragX > 48f && state.isPanelOpen) {
                            viewModel.setPanelOpen(false)
                            accumulatedDragX = 0f
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1f) {
                        viewModel.updateRenderConfig { cfg ->
                            cfg.copy(zoomScale = (cfg.zoomScale * zoom).coerceIn(0.5f, 3.0f))
                        }
                    }
                }
            }
    ) {
        // 1. Fullscreen Hardware-Accelerated OpenGL ES 2.0/3.0 Zero-Copy Monitor Viewport
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(2)
                    preserveEGLContextOnPause = true
                    val renderer = HardwareVideoRenderer(
                        context = ctx,
                        glSurfaceView = this,
                        onTelemetryTick = { fps, frameMs ->
                            viewModel.onRendererTelemetry(fps, frameMs)
                        }
                    )
                    renderer.updateRenderConfig(state.renderConfig)
                    renderer.bindSource(
                        cameraId = state.selectedDevice?.camera2Id,
                        mode = state.selectedMode,
                        hasCameraPermission = state.hasCameraPermission
                    )
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    rendererRef = renderer
                }
            }
        )

        // 2. Top-Left Compact Live Telemetry & Signal HUD Overlay (Can be hidden for pure clean monitor usage)
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

        // Minimal floating HUD restore button when HUD & Panel are both hidden
        AnimatedVisibility(
            visible = !state.showHudOverlay && !state.isPanelOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
        ) {
            Surface(
                color = Color(0x660B0F19),
                shape = CircleShape,
                modifier = Modifier.clickable { viewModel.setShowHudOverlay(true) }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "Show HUD",
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "HUD",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.75f),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // 3. Right-Edge Subtle Swipe Handle Pill (Always accessible to swipe or tap open the control drawer)
        AnimatedVisibility(
            visible = !state.isPanelOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .width(26.dp)
                    .height(104.dp)
                    .clip(RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp))
                    .background(Color(0xAA0F172A))
                    .border(
                        width = 1.dp,
                        color = StudioCyan.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)
                    )
                    .clickable { viewModel.setPanelOpen(true) }
            ) {
                Icon(
                    imageVector = Icons.Default.ChevronLeft,
                    contentDescription = "Open Monitor Drawer",
                    tint = StudioCyan,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // 4. Bottom Status / Negotiation Toast Banner (when USB port warning or UVC commit occurs)
        val banner = state.bannerMessage
        AnimatedVisibility(
            visible = banner != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 14.dp, end = if (state.isPanelOpen) 410.dp else 16.dp)
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

        // 5. Slide-Over Material Design 3 Control Drawer (Swipe from right edge or tap handle)
        AnimatedVisibility(
            visible = state.isPanelOpen,
            enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(260)) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(220)) + fadeOut(),
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

    Surface(
        color = Color(0xCC0B0F19),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = when {
                isExceeding && !state.forceBypassUsbLimits -> StudioAmber.copy(alpha = 0.8f)
                state.forceBypassUsbLimits && isExceeding -> StudioRose.copy(alpha = 0.8f)
                else -> Color.White.copy(alpha = 0.14f)
            }
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Live Status Pulse Dot
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(
                        when {
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
                        text = dev?.name ?: "No USB Capture Card",
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
                    Text(
                        text = "${mode.resolutionLabel} @ ${String.format(Locale.US, "%.0f", state.telemetry.currentFps.takeIf { it > 1f } ?: mode.fps.toFloat())}Hz",
                        color = StudioCyan,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = " • ${mode.format.shortName} • ${mode.requiredMbps} Mbps • ${usbStd.specName}",
                        color = Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    if (state.forceBypassUsbLimits && isExceeding) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "[FORCED OVERRIDE]",
                            color = StudioRose,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlideOverMonitorControlPanel(
    state: MonitorUiState,
    viewModel: MonitorViewModel,
    onRequestPermissions: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Signal & USB", "GPU & Aspect", "Audio & HUD")

    Surface(
        color = Color(0xF20B0F19),
        modifier = Modifier
            .width(396.dp)
            .fillMaxHeight()
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
            ),
        shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
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
                        text = "CaptureMonitor Studio",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Text(
                        text = "Hardware-Accelerated USB/HDMI Display • v0.1.0",
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
                                fontSize = 12.sp,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    )
                }
            }

            // Scrollable Tab Body
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
    // 1. Capture Card Source Selector (Magewell, Elgato, AVerMedia, MacroSilicon, Generic UVC)
    SectionCard(
        title = "Capture Card & USB Source",
        icon = Icons.Default.Usb
    ) {
        state.devices.forEach { device ->
            val isSelected = device.id == state.selectedDevice?.id
            CaptureDeviceRow(
                device = device,
                isSelected = isSelected,
                onClick = { viewModel.selectDevice(device) }
            )
            Spacer(Modifier.height(6.dp))
        }

        if (!state.hasCameraPermission) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.HighQuality, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Grant UVC Camera & USB Audio Permission", fontSize = 12.sp)
            }
        }
    }

    // 2. USB Port Standard & Bandwidth Ceiling Analyzer + Force Override
    UsbBandwidthAndForceCard(
        state = state,
        viewModel = viewModel
    )

    // 3. Resolution Selector (All Qualities: 4K UHD down to 480p)
    SectionCard(
        title = "Monitor Resolution",
        icon = Icons.Default.Hd
    ) {
        val resolutions = listOf(
            Triple(3840, 2160, "4K UHD (3840×2160)"),
            Triple(2560, 1440, "1440p QHD (2560×1440)"),
            Triple(1920, 1200, "1200p WUXGA (1920×1200)"),
            Triple(1920, 1080, "1080p FHD (1920×1080)"),
            Triple(1600, 900, "900p HD+ (1600×900)"),
            Triple(1280, 720, "720p HD (1280×720)"),
            Triple(720, 576, "576p PAL (720×576)"),
            Triple(720, 480, "480p NTSC (720×480)"),
            Triple(640, 480, "480p VGA (640×480)")
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            resolutions.forEach { (w, h, label) ->
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
                                    modifier = Modifier.size(13.dp)
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
        val framerates = listOf(240, 144, 120, 60, 59, 50, 30, 25, 24)
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

    // 5. Capture Pixel Format / Compression (MJPEG, Uncompressed YUY2 4:2:2, NV12 4:2:0, H.264)
    SectionCard(
        title = "Capture Pixel Format & Encoding",
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

@OptIn(ExperimentalLayoutApi::class)
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
        // Detected or simulated USB port standard badge
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
            text = "Test / Inspect USB Port Standard:",
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
            listOf(UsbStandard.USB_2_0, UsbStandard.USB_3_0_GEN1, UsbStandard.USB_3_2_GEN2).forEach { std ->
                FilterChip(
                    selected = state.simulatedUsbPortStandard == std,
                    onClick = { viewModel.setSimulatedUsbPortStandard(std) },
                    label = { Text(std.specName, fontSize = 10.sp) }
                )
            }
        }

        // Bandwidth warning box when current mode exceeds USB port capability
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
                            text = "USB Port Bandwidth Bottleneck Detected",
                            color = StudioAmber,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${mode.resolutionLabel} @ ${mode.fps}Hz (${mode.format.shortName}) needs ~$requiredMbps Mbps, which exceeds ${usbStd.specName}'s practical limit ($ceilingMbps Mbps). You can either switch to MJPEG or force-negotiate the mode anyway.",
                        color = Color(0xFFFDE68A),
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        // Force Mode Switch + Direct UVC VS_COMMIT_CONTROL Trigger Button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Force Unsupported Resolution / FPS",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                Text(
                    text = "Bypass USB speed checks & send raw UVC VS_COMMIT_CONTROL",
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
        title = "Aspect Ratio & Geometry",
        icon = Icons.Default.CropFree
    ) {
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
                    Icon(Icons.Default.RotateRight, contentDescription = null, modifier = Modifier.size(16.dp))
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
    }

    SectionCard(
        title = "OpenGL ES 3.0 Hardware Shader Pipeline",
        icon = Icons.Default.DeveloperBoard
    ) {
        Text(
            text = "Color Space Matrix & Dynamic Range",
            color = Color(0xFFCBD5E1),
            fontSize = 11.sp
        )
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
                    text = if (cfg.fullRangeRgb) "PC / Gaming Full Range (0–255)" else "Broadcast Limited Range (16–235 expanded)",
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
            label = "CAS GPU Sharpness",
            valueText = "${(cfg.sharpness * 100).toInt()}%",
            value = cfg.sharpness,
            valueRange = 0f..1f,
            onValueChange = { v -> viewModel.updateRenderConfig { it.copy(sharpness = v) } }
        )
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

        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = { viewModel.resetColorAndTransform() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Reset Shader & Geometry to Reference Defaults", fontSize = 12.sp)
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
        title = "HDMI / USB Capture Digital Audio Passthrough",
        icon = if (state.isAudioMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Low-Latency 48kHz PCM Audio Loopback",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                Text(
                    text = "Routes USB Capture Card digital audio directly to tablet/phone speakers",
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

        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Live VU Peak Meter",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp
            )
            AssistChip(
                onClick = { viewModel.toggleAudioMute() },
                leadingIcon = {
                    Icon(
                        imageVector = if (state.isAudioMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
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
                Text("Grant Audio Capture Permission", fontSize = 12.sp)
            }
        }
    }

    SectionCard(
        title = "Monitor Overlays & Guides",
        icon = Icons.Default.GridOn
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Show Top-Left Signal Telemetry HUD", color = Color.White, fontSize = 12.sp)
                Text("Displays resolution, FPS, bitrate, and USB standard", color = Color(0xFF94A3B8), fontSize = 11.sp)
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
                Text("Hardware shader overlay for framing & camera monitoring", color = Color(0xFF94A3B8), fontSize = 11.sp)
            }
            Switch(
                checked = state.renderConfig.showSafeAreaGrid,
                onCheckedChange = { show ->
                    viewModel.updateRenderConfig { it.copy(showSafeAreaGrid = show) }
                }
            )
        }
    }

    SectionCard(
        title = "Hardware Pipeline Telemetry",
        icon = Icons.Default.Info
    ) {
        TelemetryKeyValueRow("Render Engine", state.telemetry.activeEngineName)
        TelemetryKeyValueRow("Measured Frame Time", String.format(Locale.US, "%.2f ms", state.telemetry.frameTimeMs))
        TelemetryKeyValueRow("USB Host Link", state.selectedDevice?.usbVersionString ?: state.effectiveUsbStandard.label)
        TelemetryKeyValueRow("USB VID:PID", state.selectedDevice?.hexVidPid ?: "2935:0002")
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
