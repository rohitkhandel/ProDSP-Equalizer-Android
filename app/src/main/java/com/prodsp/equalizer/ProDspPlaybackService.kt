package com.prodsp.equalizer

import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns playback independently from the Activity so audio can continue while
 * the UI is backgrounded or the screen is locked. The queue and last position
 * are restored after Android recreates the service/process.
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

    private val queuePreferences by lazy {
        getSharedPreferences("prodsp_playback_queue", MODE_PRIVATE)
    }
    private val persistQueueRunnable = Runnable { persistQueue() }
    private val positionSaveRunnable = object : Runnable {
        override fun run() {
            persistQueue()
            persistenceHandler.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
        }
    }

    private val queueListener = object : Player.Listener {
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            scheduleQueueSave()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            scheduleQueueSave()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            scheduleQueueSave()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            scheduleQueueSave()
        }
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

        restoreQueue(exoPlayer)
        exoPlayer.addListener(queueListener)
        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer).build()
        persistenceHandler.postDelayed(positionSaveRunnable, POSITION_SAVE_INTERVAL_MS)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    private fun restoreQueue(exoPlayer: ExoPlayer) {
        val saved = ProDspQueueStore.decode(queuePreferences.getString(KEY_QUEUE, null).orEmpty())
        if (saved.isEmpty()) return

        val grantedContentUris = contentResolver.persistedUriPermissions
            .asSequence()
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .toSet()

        val validEntries = saved.mapIndexedNotNull { originalIndex, entry ->
            val uri = runCatching { Uri.parse(entry.uri) }.getOrNull() ?: return@mapIndexedNotNull null
            val scheme = uri.scheme
            val permissionAvailable = scheme != "content" || entry.uri in grantedContentUris
            if (!permissionAvailable || scheme !in setOf("content", "file")) {
                return@mapIndexedNotNull null
            }
            Triple(originalIndex, entry, uri)
        }
        if (validEntries.isEmpty()) return

        val restoredItems = validEntries.map { (_, entry, uri) ->
            MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.title).build())
                .build()
        }
        val savedIndex = queuePreferences.getInt(KEY_CURRENT_INDEX, 0)
        val restoredIndex = validEntries.indexOfFirst { it.first == savedIndex }
            .takeIf { it >= 0 }
            ?: validEntries.indexOfLast { it.first < savedIndex }.coerceAtLeast(0)
        val savedPosition = queuePreferences.getLong(KEY_POSITION_MS, 0L).coerceAtLeast(0L)

        exoPlayer.setMediaItems(restoredItems, restoredIndex, savedPosition)
        exoPlayer.prepare()
        // Restore the queue and position without unexpectedly starting audio
        // after a process restart. The user can resume with the normal controls.
        exoPlayer.playWhenReady = false
    }

    private fun scheduleQueueSave() {
        persistenceHandler.removeCallbacks(persistQueueRunnable)
        persistenceHandler.postDelayed(persistQueueRunnable, QUEUE_SAVE_DEBOUNCE_MS)
    }

    private fun persistQueue() {
        val activePlayer = player ?: return
        if (activePlayer.mediaItemCount == 0 || activePlayer.currentTimeline.isEmpty) {
            queuePreferences.edit()
                .remove(KEY_QUEUE)
                .putInt(KEY_CURRENT_INDEX, 0)
                .putLong(KEY_POSITION_MS, 0L)
                .apply()
            return
        }

        val queueEntries = ArrayList<ProDspQueueStore.Entry>(activePlayer.mediaItemCount)
        var persistedCurrentIndex = -1
        for (index in 0 until activePlayer.mediaItemCount) {
            val item = activePlayer.getMediaItemAt(index)
            val uri = item.localConfiguration?.uri ?: continue
            if (uri.scheme !in setOf("content", "file")) continue
            if (index == activePlayer.currentMediaItemIndex) {
                persistedCurrentIndex = queueEntries.size
            }
            val title = item.mediaMetadata.title?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment
                ?: "Audio track"
            queueEntries += ProDspQueueStore.Entry(uri.toString(), title)
        }

        if (queueEntries.isEmpty()) {
            queuePreferences.edit().remove(KEY_QUEUE).apply()
            return
        }

        val safeIndex = persistedCurrentIndex.takeIf { it >= 0 }
            ?: queueEntries.lastIndex.coerceAtLeast(0)
        queuePreferences.edit()
            .putString(KEY_QUEUE, ProDspQueueStore.encode(queueEntries))
            .putInt(KEY_CURRENT_INDEX, safeIndex)
            .putLong(KEY_POSITION_MS, activePlayer.currentPosition.coerceAtLeast(0L))
            .apply()
    }

    override fun onDestroy() {
        persistenceHandler.removeCallbacks(persistActiveSettings)
        persistenceHandler.removeCallbacks(persistQueueRunnable)
        persistenceHandler.removeCallbacks(positionSaveRunnable)
        persistActiveSettings.run()
        persistQueue()
        ProDspAudioEngine.settingsChangedListener = null

        player?.removeListener(queueListener)
        mediaSession?.release()
        mediaSession = null

        player?.removeAnalyticsListener(analyticsListener)
        player?.release()
        player = null

        ProDspAudioEngine.deviceEffects.release()
        super.onDestroy()
    }

    private companion object {
        const val KEY_QUEUE = "queue_items"
        const val KEY_CURRENT_INDEX = "current_index"
        const val KEY_POSITION_MS = "position_ms"
        const val QUEUE_SAVE_DEBOUNCE_MS = 350L
        const val POSITION_SAVE_INTERVAL_MS = 3_000L
    }
}
