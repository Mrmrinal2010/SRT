package io.github.thibaultbee.streampack.app.managers

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

data class AudioSource(
    val id: Int,
    val name: String,
    val type: AudioSourceType,
    val isAvailable: Boolean
)

enum class AudioSourceType {
    BUILTIN_MIC,
    WIRED_HEADSET,
    USB_AUDIO,
    BLUETOOTH,
    UNKNOWN
}

class AudioSourceManager(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun getAvailableAudioSources(): List<AudioSource> {
        val sources = mutableListOf<AudioSource>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            for (device in devices) {
                val type = when (device.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioSourceType.BUILTIN_MIC
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioSourceType.WIRED_HEADSET
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> AudioSourceType.USB_AUDIO
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioSourceType.BLUETOOTH
                    else -> AudioSourceType.UNKNOWN
                }

                val name = when (type) {
                    AudioSourceType.BUILTIN_MIC -> "Phone Microphone"
                    AudioSourceType.WIRED_HEADSET -> "Headset Microphone"
                    AudioSourceType.USB_AUDIO -> device.productName?.toString() ?: "USB Audio Interface"
                    AudioSourceType.BLUETOOTH -> "Bluetooth Microphone"
                    else -> device.productName?.toString() ?: "Unknown Input"
                }

                sources.add(AudioSource(device.id, name, type, true))
            }
        } else {
            // Fallback for older Android versions
            sources.add(AudioSource(0, "Default Microphone", AudioSourceType.BUILTIN_MIC, true))
        }

        return sources
    }
}
