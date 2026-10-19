package io.github.ultrapg.capturemonitor.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Low-latency USB digital audio passthrough engine for HDMI -> USB Capture Cards
 * (e.g., Magewell USB Capture HDMI, Elgato, MacroSilicon MS2130).
 */
class UsbAudioLoopbackEngine(
    private val context: Context,
    private val onAudioLevelUpdate: (Float) -> Unit
) {
    companion object {
        private const val TAG = "UsbAudioLoopback"
        private const val SAMPLE_RATE = 48000
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val isRunning = AtomicBoolean(false)
    private var workerThread: Thread? = null

    @Volatile
    var volumeGain: Float = 1.0f

    @Volatile
    var isMuted: Boolean = false

    fun findUsbAudioInputDevice(): AudioDeviceInfo? {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return inputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_HDMI
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
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
                return@Thread
            }

            val bufferSize = maxOf(minInBuf, minOutBuf, 2048)

            var recorder: AudioRecord? = null
            var player: AudioTrack? = null

            try {
                recorder = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(channelIn)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()

                val usbInput = findUsbAudioInputDevice()
                if (usbInput != null) {
                    recorder.preferredDevice = usbInput
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
                    Log.w(TAG, "AudioRecord failed to initialize")
                    isRunning.set(false)
                    return@Thread
                }

                recorder.startRecording()
                player.play()

                val pcmBuffer = ShortArray(bufferSize / 2)
                while (isRunning.get()) {
                    val readCount = recorder.read(pcmBuffer, 0, pcmBuffer.size)
                    if (readCount > 0) {
                        var sumSquares = 0.0
                        val gain = if (isMuted) 0f else volumeGain
                        for (i in 0 until readCount) {
                            val sample = (pcmBuffer[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                            pcmBuffer[i] = sample
                            sumSquares += (sample.toDouble() * sample.toDouble())
                        }
                        val rms = sqrt(sumSquares / readCount) / 32768.0
                        onAudioLevelUpdate(rms.toFloat().coerceIn(0f, 1f))
                        if (!isMuted) {
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
                onAudioLevelUpdate(0f)
                isRunning.set(false)
            }
        }, "UsbAudioLoopbackThread").also { it.start() }
    }

    fun stop() {
        isRunning.set(false)
        try {
            workerThread?.join(250)
        } catch (_: Exception) {
        }
        workerThread = null
        onAudioLevelUpdate(0f)
    }
}
