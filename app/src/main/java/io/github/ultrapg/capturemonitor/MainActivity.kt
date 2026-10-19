package io.github.ultrapg.capturemonitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import io.github.ultrapg.capturemonitor.ui.CaptureMonitorScreen
import io.github.ultrapg.capturemonitor.ui.MonitorViewModel
import io.github.ultrapg.capturemonitor.ui.theme.CaptureMonitorTheme

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
        configureImmersiveLowLatencyWindow()
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
        configureImmersiveLowLatencyWindow()
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

    private fun configureImmersiveLowLatencyWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // Request highest supported display refresh rate (e.g., 90Hz / 120Hz / 144Hz on tablets & phones)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.supportedModes?.maxByOrNull { it.refreshRate }?.let { bestMode ->
                window.attributes = window.attributes.apply {
                    preferredDisplayModeId = bestMode.modeId
                }
            }
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
