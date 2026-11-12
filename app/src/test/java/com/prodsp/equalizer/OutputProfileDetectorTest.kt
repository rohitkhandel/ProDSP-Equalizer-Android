package com.prodsp.equalizer

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class OutputProfileDetectorTest {
    @Test fun classifiesBluetooth() {
        assertEquals("bluetooth", detectConnectedOutputProfile(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)).id)
        assertEquals("bluetooth", detectConnectedOutputProfile(listOf(AudioDeviceInfo.TYPE_BLE_HEADSET)).id)
    }
    @Test fun classifiesWiredUsbAndHdmi() {
        assertEquals("wired", detectConnectedOutputProfile(listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)).id)
        assertEquals("usb", detectConnectedOutputProfile(listOf(AudioDeviceInfo.TYPE_USB_DEVICE)).id)
        assertEquals("hdmi", detectConnectedOutputProfile(listOf(AudioDeviceInfo.TYPE_HDMI)).id)
    }
    @Test fun classifiesPhoneSpeaker() {
        assertEquals("speaker", detectConnectedOutputProfile(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)).id)
    }
    @Test fun emptyDeviceListUsesDefaultProfile() {
        assertEquals("default", detectConnectedOutputProfile(emptyList()).id)
    }
    @Test fun bluetoothHasStablePriorityWhenSeveralDevicesAreConnected() {
        val profile = detectConnectedOutputProfile(listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        ))
        assertEquals("bluetooth", profile.id)
    }
}
