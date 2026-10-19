package io.github.ultrapg.capturemonitor

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.ultrapg.capturemonitor.model.HostDisplayInfo
import io.github.ultrapg.capturemonitor.ui.CaptureMonitorScreen
import io.github.ultrapg.capturemonitor.ui.MonitorViewModel
import io.github.ultrapg.capturemonitor.ui.theme.CaptureMonitorTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val viewModel: MonitorViewModel by viewModels()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val camGranted = grants[Manifest.permission.CAMERA] == true || hasPermission(Manifest.permission.CAMERA)
        val audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true || hasPermission(Manifest.permission.RECORD_AUDIO)
        viewModel.onPermissionsResult(camGranted, audioGranted)
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            configureImmersiveLowLatencyWindowAndDetectDisplay()
        }

        override fun onDisplayRemoved(displayId: Int) {}

        override fun onDisplayChanged(displayId: Int) {
            configureImmersiveLowLatencyWindowAndDetectDisplay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dm = getSystemService(DISPLAY_SERVICE) as? DisplayManager
        dm?.registerDisplayListener(displayListener, mainHandler)

        configureImmersiveLowLatencyWindowAndDetectDisplay()
        checkAndUpdatePermissions(autoPrompt = true)

        setContent {
            CaptureMonitorTheme {
                CaptureMonitorScreen(
                    viewModel = viewModel,
                    onRequestPermissions = { requestCapturePermissions() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        configureImmersiveLowLatencyWindowAndDetectDisplay()
        // Re-verify after window attachment in case display was null during early onCreate
        window.decorView.post {
            configureImmersiveLowLatencyWindowAndDetectDisplay()
        }
        checkAndUpdatePermissions(autoPrompt = false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            configureImmersiveLowLatencyWindowAndDetectDisplay()
        }
    }

    override fun onDestroy() {
        val dm = getSystemService(DISPLAY_SERVICE) as? DisplayManager
        dm?.unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

    private fun hasPermission(perm: String): Boolean {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndUpdatePermissions(autoPrompt: Boolean) {
        val camGranted = hasPermission(Manifest.permission.CAMERA)
        val audioGranted = hasPermission(Manifest.permission.RECORD_AUDIO)
        viewModel.onPermissionsResult(camGranted, audioGranted)
        if (autoPrompt && (!camGranted || !audioGranted)) {
            requestCapturePermissions()
        }
    }

    private fun requestCapturePermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
        )
    }

    private fun resolveActiveDisplay(): Display? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                display?.let { return it }
            } catch (_: Exception) {
            }
        }
        val dm = getSystemService(DISPLAY_SERVICE) as? DisplayManager
        dm?.getDisplay(Display.DEFAULT_DISPLAY)?.let { return it }
        @Suppress("DEPRECATION")
        return windowManager.defaultDisplay
    }

    private fun configureImmersiveLowLatencyWindowAndDetectDisplay() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val activeDisplay = resolveActiveDisplay()
        var bestWidth = 1920
        var bestHeight = 1080
        var maxHz = 60
        val rates = mutableSetOf<Int>()

        if (activeDisplay != null) {
            val modes = activeDisplay.supportedModes
            if (!modes.isNullOrEmpty()) {
                // Find the highest physical resolution across all display modes
                val highestResMode = modes.maxByOrNull {
                    it.physicalWidth.toLong() * it.physicalHeight.toLong()
                }
                if (highestResMode != null) {
                    bestWidth = max(highestResMode.physicalWidth, highestResMode.physicalHeight)
                    bestHeight = min(highestResMode.physicalWidth, highestResMode.physicalHeight)
                }

                // Collect all supported refresh rates (60Hz, 90Hz, 120Hz, 144Hz, 165Hz, 240Hz)
                modes.forEach { m ->
                    rates.add(m.refreshRate.roundToInt())
                }

                // Prefer the mode that has BOTH the highest resolution AND highest refresh rate
                // (or the overall highest refresh rate mode if LTPO/QHD+ scales separately)
                val maxRefreshOverall = modes.maxOf { it.refreshRate }
                val bestHighRefreshMode = modes
                    .filter { it.refreshRate >= maxRefreshOverall - 1.0f }
                    .maxByOrNull { it.physicalWidth.toLong() * it.physicalHeight.toLong() }
                    ?: modes.maxByOrNull { it.refreshRate }

                if (bestHighRefreshMode != null) {
                    maxHz = bestHighRefreshMode.refreshRate.roundToInt().coerceAtLeast(60)
                    window.attributes = window.attributes.apply {
                        preferredDisplayModeId = bestHighRefreshMode.modeId
                        preferredRefreshRate = bestHighRefreshMode.refreshRate
                    }
                }
            } else {
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                activeDisplay.getRealMetrics(dm)
                bestWidth = max(dm.widthPixels, dm.heightPixels)
                bestHeight = min(dm.widthPixels, dm.heightPixels)
                @Suppress("DEPRECATION")
                maxHz = activeDisplay.refreshRate.roundToInt().coerceAtLeast(60)
                rates.add(maxHz)
            }
        }

        // Also check WindowMetrics bounds to ensure exact full-screen pixel dimensions on foldables/tablets
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            val winW = max(bounds.width(), bounds.height())
            val winH = min(bounds.width(), bounds.height())
            if (winW.toLong() * winH.toLong() > bestWidth.toLong() * bestHeight.toLong()) {
                bestWidth = winW
                bestHeight = winH
            }
        }

        if (rates.isEmpty()) rates.add(maxHz)

        viewModel.onHostDisplayDetected(
            HostDisplayInfo(
                physicalWidth = bestWidth,
                physicalHeight = bestHeight,
                maxRefreshRateHz = maxHz,
                supportedRefreshRatesHz = rates.sortedDescending()
            )
        )

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
