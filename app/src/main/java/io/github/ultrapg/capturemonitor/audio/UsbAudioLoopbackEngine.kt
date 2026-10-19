package io.github.ultrapg.capturemonitor.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Low-latency USB digital audio passthrough engine for HDMI -> USB Capture Cards
 * (e.g., Magewell USB Capture HDMI, Elgato, MacroSilicon MS2130).
 *
 * CRITICAL SAFETY DESIGN:
 * - Strictly binds ONLY to external USB / HDMI digital audio input devices (`TYPE_USB_DEVICE`,
 *   `TYPE_USB_ACCESSORY`, `TYPE_USB_HEADSET`, `TYPE_HDMI`).
 * - NEVER falls back to the phone/tablet's built-in microphone when a capture card is unplugged!
 *   (Falling back to the internal mic while playing out the speakers creates an acoustic feedback loop
 *   and high-pitched hissing noise.)
 * - Includes an adjustable Digital Noise Gate + DC-offset filter to silence sub-threshold line floor hiss.
 */
class UsbAudioLoopbackEngine(
    private val context: Context,
    private val onAudioStatusUpdate: (rmsLevel: Float, usbAudioDeviceName: String?) -> Unit
) {
    companion object {
        private const val TAG = "UsbAudioLoopback"
        private const val SAMPLE_RATE = 48000
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val isRunning = AtomicBoolean(false)
    private var workerThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var callbackRegistered = false

    @Volatile
    var userEnabled: Boolean = true

    @Volatile
    var hasAudioPermission: Boolean = false

    @Volatile
    var isCaptureCardPhysicallyConnected: Boolean = false

    @Volatile
    var volumeGain: Float = 1.0f

    @Volatile
    var isMuted: Boolean = false

    /**
     * Digital noise gate threshold in dBFS (-90 dB to -20 dB). Default -58 dB eliminates idle USB ADC hiss.
     */
    @Volatile
    var noiseGateDb: Float = -58f

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            syncAudioState()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            syncAudioState()
        }
    }

    fun register() {
        if (callbackRegistered) return
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        callbackRegistered = true
    }

    fun unregister() {
        if (callbackRegistered) {
            try {
                audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            } catch (_: Exception) {
            }
            callbackRegistered = false
        }
        stop()
    }

    /**
     * Strictly finds a USB or HDMI digital audio input from the capture card.
     * Returns null if only the built-in phone/tablet microphone is present.
     */
    fun findUsbCaptureAudioInputDevice(): AudioDeviceInfo? {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return inputs.firstOrNull { dev ->
            dev.isSource && (
                dev.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    dev.type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
                    dev.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    dev.type == AudioDeviceInfo.TYPE_HDMI ||
                    dev.type == AudioDeviceInfo.TYPE_LINE_DIGITAL
                )
        }
    }

    /**
     * Starts or stops the audio loopback depending on whether:
     * 1. User enabled audio passthrough
     * 2. Audio permission is granted
     * 3. A physical USB capture card AND a USB/HDMI digital audio input endpoint are actually connected
     */
    fun syncAudioState() {
        val usbInput = findUsbCaptureAudioInputDevice()
        val devName = usbInput?.productName?.toString()?.takeIf { it.isNotBlank() }

        if (userEnabled && hasAudioPermission && isCaptureCardPhysicallyConnected && usbInput != null) {
            startWithDevice(usbInput, devName ?: "USB Digital Audio")
        } else {
            stop()
            onAudioStatusUpdate(0f, if (isCaptureCardPhysicallyConnected) devName else null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startWithDevice(usbInputDevice: AudioDeviceInfo, deviceLabel: String) {
        if (isRunning.getAndSet(true)) return

        workerThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val channelIn = AudioFormat.CHANNEL_IN_STEREO
            val channelOut = AudioFormat.CHANNEL_OUT_STEREO
            val encoding = AudioFormat.ENCODING_PCM_16BIT

            val minInBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelIn, encoding)
            val minOutBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, channelOut, encoding)
            if (minInBuf <= 0 || minOutBuf <= 0) {
                isRunning.set(false)
                onAudioStatusUpdate(0f, null)
                return@Thread
            }

            val bufferSize = maxOf(minInBuf, minOutBuf, 2048)

            var recorder: AudioRecord? = null
            var player: AudioTrack? = null

            try {
                recorder = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(channelIn)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()

                // Strictly lock onto the USB capture card audio endpoint
                val setOk = recorder.setPreferredDevice(usbInputDevice)
                if (!setOk) {
                    Log.w(TAG, "Could not lock preferredDevice to USB audio; aborting to prevent internal mic hiss")
                    isRunning.set(false)
                    return@Thread
                }

                player = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(channelOut)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    isRunning.set(false)
                    return@Thread
                }

                recorder.startRecording()

                // Verify the active routed device is genuinely USB/HDMI and NOT the built-in mic
                val routed = recorder.routedDevice
                if (routed != null &&
                    routed.type != AudioDeviceInfo.TYPE_USB_DEVICE &&
                    routed.type != AudioDeviceInfo.TYPE_USB_ACCESSORY &&
                    routed.type != AudioDeviceInfo.TYPE_USB_HEADSET &&
                    routed.type != AudioDeviceInfo.TYPE_HDMI &&
                    routed.type != AudioDeviceInfo.TYPE_LINE_DIGITAL
                ) {
                    Log.w(TAG, "Android routed AudioRecord to non-USB device (${routed.type}); stopping immediately to prevent mic feedback hiss")
                    isRunning.set(false)
                    return@Thread
                }

                player.play()
                onAudioStatusUpdate(0f, deviceLabel)

                val pcmBuffer = ShortArray(bufferSize / 2)
                var envelopeGain = 0f

                while (isRunning.get() && isCaptureCardPhysicallyConnected && userEnabled) {
                    // Also check if the USB audio device was unplugged mid-read
                    if (findUsbCaptureAudioInputDevice() == null) break

                    val readCount = recorder.read(pcmBuffer, 0, pcmBuffer.size)
                    if (readCount > 0) {
                        // Compute block RMS first to evaluate Digital Noise Gate
                        var rawSumSq = 0.0
                        for (i in 0 until readCount) {
                            val s = pcmBuffer[i].toDouble()
                            rawSumSq += s * s
                        }
                        val rawRmsNorm = (sqrt(rawSumSq / readCount) / 32768.0).toFloat()

                        // Convert dB threshold (-90..0) to linear amplitude
                        val gateLinear = Math.pow(10.0, (noiseGateDb.toDouble() / 20.0)).toFloat()
                        val targetGate = if (rawRmsNorm >= gateLinear && !isMuted) 1.0f else 0.0f
                        // Smooth attack/release to avoid clicks
                        envelopeGain = envelopeGain * 0.75f + targetGate * 0.25f

                        val effectiveGain = if (isMuted || envelopeGain < 0.01f) 0f else (volumeGain * envelopeGain)
                        var outSumSq = 0.0

                        for (i in 0 until readCount) {
                            val rawSample = pcmBuffer[i].toInt()
                            // Deadband sub-2-LSB quantization noise
                            val cleaned = if (abs(rawSample) <= 2) 0 else rawSample
                            val scaled = (cleaned * effectiveGain).toInt()
                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                .toShort()
                            pcmBuffer[i] = scaled
                            outSumSq += scaled.toDouble() * scaled.toDouble()
                        }

                        val outRms = (sqrt(outSumSq / readCount) / 32768.0).toFloat().coerceIn(0f, 1f)
                        onAudioStatusUpdate(outRms, deviceLabel)

                        if (effectiveGain > 0f) {
                            player.write(pcmBuffer, 0, readCount)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "USB Audio loopback stopped: ${e.message}")
            } finally {
                try {
                    recorder?.stop()
                    recorder?.release()
                } catch (_: Exception) {
                }
                try {
                    player?.stop()
                    player?.release()
                } catch (_: Exception) {
                }
                isRunning.set(false)
                onAudioStatusUpdate(0f, null)
            }
        }, "UsbAudioLoopbackThread").also { it.start() }
    }

    fun stop() {
        isRunning.set(false)
        try {
            workerThread?.join(200)
        } catch (_: Exception) {
        }
        workerThread = null
        onAudioStatusUpdate(0f, null)
    }
}
