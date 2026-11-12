package com.prodsp.equalizer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.os.Build
import android.service.notification.NotificationListenerService

/**
 * Keeps a lightweight receiver available for the standard Android audio
 * effect session broadcasts while the user has enabled notification access.
 * Notification contents are not read or stored.
 */
class ExternalAudioSessionListenerService : NotificationListenerService() {
    private var receiverRegistered = false

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
            val playerPackage = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME).orEmpty()
            if (sessionId <= 0 || playerPackage.isBlank() || playerPackage == packageName) return

            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION ->
                    ProDspAudioEngine.externalSessions?.openSession(playerPackage, sessionId)

                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION ->
                    ProDspAudioEngine.externalSessions?.closeSession(playerPackage, sessionId)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ProDspAudioEngine.externalSessions = ExternalAudioSessionManager()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(sessionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(sessionReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onListenerDisconnected() {
        unregisterSessionReceiver()
        ProDspAudioEngine.externalSessions?.releaseAll()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        unregisterSessionReceiver()
        val manager = ProDspAudioEngine.externalSessions
        manager?.releaseAll()
        if (ProDspAudioEngine.externalSessions === manager) {
            ProDspAudioEngine.externalSessions = null
        }
        super.onDestroy()
    }

    private fun unregisterSessionReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(sessionReceiver) }
        receiverRegistered = false
    }
}
