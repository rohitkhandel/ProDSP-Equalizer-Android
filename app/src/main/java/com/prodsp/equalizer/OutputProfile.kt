package com.prodsp.equalizer

import android.media.AudioDeviceInfo

/**
 * Classifies audio output device types Android reports as connected.
 * This does not guarantee the active route when several devices are connected.
 */
internal data class OutputProfile(val id: String, val label: String)

internal fun detectConnectedOutputProfile(deviceTypes: Collection<Int>): OutputProfile {
    val connectedTypes = deviceTypes.toSet()
    fun has(vararg types: Int): Boolean = types.any { it in connectedTypes }
    return when {
        has(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER) ->
            OutputProfile("bluetooth", "Bluetooth")
        has(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET) ->
            OutputProfile("wired", "Wired headphones")
        has(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY) ->
            OutputProfile("usb", "USB audio")
        has(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC) ->
            OutputProfile("hdmi", "HDMI / Display audio")
        has(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) ->
            OutputProfile("speaker", "Phone speaker")
        else -> OutputProfile("default", "Default output")
    }
}
