package io.github.ultrapg.capturemonitor.gl

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.SurfaceView
import android.view.View
import io.github.ultrapg.capturemonitor.model.GraphicsBackend
import io.github.ultrapg.capturemonitor.model.RenderConfig

/**
 * Hardware Vulkan 1.3 / 1.1 capability detection and pipeline synchronization.
 *
 * Important Android SurfaceFlinger note:
 * `GLSurfaceView` extends `SurfaceView`, which punches a transparent hole (`PorterDuff.Mode.CLEAR`)
 * in the Window View hierarchy so the hardware OpenGL/Vulkan surface behind the window is visible.
 * Attaching a `View.setRenderEffect(...)` to a `SurfaceView` intercepts the transparent hole-punch
 * and turns it opaque black, hiding the video surface! Therefore, on `SurfaceView` we always keep
 * the Window hole-punch unobstructed (`setRenderEffect(null)`) and execute the selected backend
 * (`GraphicsBackend.VULKAN` vs `GraphicsBackend.OPENGL_ES`) directly inside `HardwareVideoRenderer`.
 */
object VulkanHardwarePipeline {

    data class VulkanSupportInfo(
        val isSupported: Boolean,
        val apiVersionString: String,
        val hardwareLevel: Int,
        val supportsCompute: Boolean,
        val supportsRuntimeShader: Boolean
    )

    fun queryDeviceVulkanSupport(context: Context): VulkanSupportInfo {
        val pm = context.packageManager
        val hasVersion = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
        val hasLevel = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        val hasCompute = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE)
        val hasAgsl = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        val versionLabel = when {
            Build.VERSION.SDK_INT >= 33 && hasVersion -> "Vulkan 1.3 (Hardware GPU)"
            hasVersion && hasCompute -> "Vulkan 1.1+ (Hardware Compute)"
            hasVersion || hasLevel -> "Vulkan 1.1 (Hardware Graphics)"
            else -> "Vulkan / GLES 3.0 Hardware"
        }

        return VulkanSupportInfo(
            isSupported = hasVersion || hasLevel,
            apiVersionString = versionLabel,
            hardwareLevel = if (hasLevel) 1 else 0,
            supportsCompute = hasCompute,
            supportsRuntimeShader = hasAgsl
        )
    }

    /**
     * Ensures the `GLSurfaceView` hole-punch is never blocked by an offscreen View RenderEffect layer.
     */
    fun syncViewBackend(
        view: View,
        @Suppress("UNUSED_PARAMETER") config: RenderConfig
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                view.setRenderEffect(null)
            } catch (_: Exception) {
            }
        }
        if (view !is SurfaceView) {
            view.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }
}
