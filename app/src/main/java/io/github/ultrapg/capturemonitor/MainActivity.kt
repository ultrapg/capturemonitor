package io.github.ultrapg.capturemonitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val camGranted = grants[Manifest.permission.CAMERA] == true || hasPermission(Manifest.permission.CAMERA)
        val audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true || hasPermission(Manifest.permission.RECORD_AUDIO)
        viewModel.onPermissionsResult(camGranted, audioGranted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
        checkAndUpdatePermissions(autoPrompt = false)
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

    private fun configureImmersiveLowLatencyWindowAndDetectDisplay() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // Query highest physical resolution and refresh rate supported by the Android device display
        var bestWidth = 1920
        var bestHeight = 1080
        var maxHz = 60
        val rates = mutableSetOf(60)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val modes = display?.supportedModes
            if (!modes.isNullOrEmpty()) {
                modes.forEach { m -> rates.add(m.refreshRate.roundToInt()) }
                val bestMode = modes.maxWithOrNull(
                    compareBy<android.view.Display.Mode> { it.refreshRate.roundToInt() }
                        .thenBy { it.physicalWidth.toLong() * it.physicalHeight.toLong() }
                )
                if (bestMode != null) {
                    window.attributes = window.attributes.apply {
                        preferredDisplayModeId = bestMode.modeId
                        preferredRefreshRate = bestMode.refreshRate
                    }
                    bestWidth = max(bestMode.physicalWidth, bestMode.physicalHeight)
                    bestHeight = min(bestMode.physicalWidth, bestMode.physicalHeight)
                    maxHz = bestMode.refreshRate.roundToInt().coerceAtLeast(60)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val d = windowManager.defaultDisplay
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            d.getRealMetrics(dm)
            bestWidth = max(dm.widthPixels, dm.heightPixels)
            bestHeight = min(dm.widthPixels, dm.heightPixels)
            @Suppress("DEPRECATION")
            maxHz = d.refreshRate.roundToInt().coerceAtLeast(60)
            rates.add(maxHz)
        }

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
