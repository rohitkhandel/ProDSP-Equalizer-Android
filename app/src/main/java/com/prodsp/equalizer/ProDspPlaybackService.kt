package com.prodsp.equalizer

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns playback independently from the Activity so audio can continue while
 * the UI is backgrounded or the screen is locked.
 */
@OptIn(UnstableApi::class)
class ProDspPlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    private val analyticsListener = object : AnalyticsListener {
        override fun onAudioSessionIdChanged(
            eventTime: AnalyticsListener.EventTime,
            audioSessionId: Int
        ) {
            ProDspAudioEngine.deviceEffects.attach(audioSessionId)
        }
    }

    private val persistenceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val persistActiveSettings = Runnable {
        ProDspSettingsPersistence.save(this)
    }
    private val settingsChangedListener: () -> Unit = {
        persistenceHandler.removeCallbacks(persistActiveSettings)
        persistenceHandler.postDelayed(persistActiveSettings, 500L)
    }

    override fun onCreate() {
        super.onCreate()
        ProDspSettingsPersistence.restore(this)
        ProDspAudioEngine.settingsChangedListener = settingsChangedListener

        val exoPlayer = ExoPlayer.Builder(
            this,
            ProDspRenderersFactory(this, ProDspAudioEngine.processor)
        ).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            setHandleAudioBecomingNoisy(true)
            addAnalyticsListener(analyticsListener)
        }

        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        persistenceHandler.removeCallbacks(persistActiveSettings)
        persistActiveSettings.run()
        ProDspAudioEngine.settingsChangedListener = null

        mediaSession?.release()
        mediaSession = null

        player?.removeAnalyticsListener(analyticsListener)
        player?.release()
        player = null

        ProDspAudioEngine.deviceEffects.release()
        super.onDestroy()
    }
}
