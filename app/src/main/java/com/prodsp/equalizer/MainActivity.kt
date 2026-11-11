package com.prodsp.equalizer

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFB5F36B),
                secondary = Color(0xFF73D6C2),
                background = Color(0xFF0B0D10),
                surface = Color(0xFF171B20)
            )) {
                ProDspScreen()
            }
        }
    }
}

private data class UiBand(
    val index: Short,
    val frequencyHz: Int,
    val minDb: Float,
    val maxDb: Float,
    val valueDb: Float
)

@OptIn(UnstableApi::class)
@androidx.compose.runtime.Composable
private fun ProDspScreen() {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    val engine = remember { AudioEffectsEngine() }
    val bands = remember { mutableStateListOf<UiBand>() }
    var trackName by remember { mutableStateOf("No track selected") }
    var status by remember { mutableStateOf("Choose a music file to start") }
    var playing by remember { mutableStateOf(false) }
    var effectsOn by remember { mutableStateOf(true) }
    var bass by remember { mutableFloatStateOf(0f) }
    var lowTone by remember { mutableFloatStateOf(0f) }
    var highTone by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(0f) }
    var preamp by remember { mutableFloatStateOf(1f) }
    var durationMs by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableIntStateOf(0) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.play()
            trackName = uri.lastPathSegment?.substringAfterLast('/') ?: "Selected audio"
            status = "Loading audio…"
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (isPlaying) status = if (engine.isAvailable) "Playing · DSP active" else "Playing · device EQ unavailable"
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    durationMs = player.duration.coerceAtLeast(0).toInt()
                    status = if (engine.isAvailable) "Ready · DSP connected" else "Ready · DSP unavailable on this device"
                } else if (playbackState == Player.STATE_ENDED) {
                    playing = false
                } else if (playbackState == Player.STATE_BUFFERING) {
                    status = "Buffering…"
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                status = "Playback error: ${error.errorCodeName}"
                playing = false
            }
        }
        val analyticsListener = object : AnalyticsListener {
            override fun onAudioSessionIdChanged(
                eventTime: AnalyticsListener.EventTime,
                audioSessionId: Int
            ) {
                val nativeBands = engine.attach(audioSessionId)
                bands.clear()
                nativeBands.forEach { b ->
                    bands.add(UiBand(
                        index = b.index,
                        frequencyHz = b.frequencyHz,
                        minDb = b.minLevelMb / 100f,
                        maxDb = b.maxLevelMb / 100f,
                        valueDb = b.levelMb / 100f
                    ))
                }
                status = if (nativeBands.isNotEmpty()) {
                    "DSP connected · ${nativeBands.size}-band device EQ"
                } else {
                    "This device did not provide an audio EQ effect"
                }
            }
        }
        player.addListener(listener)
        player.addAnalyticsListener(analyticsListener)
        onDispose {
            player.removeListener(listener)
            player.removeAnalyticsListener(analyticsListener)
            player.release()
            engine.release()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B0D10)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(modifier = Modifier.padding(top = 22.dp)) {
                    Text("PRODSP", color = Color(0xFFB5F36B), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Text("Equalizer", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("Precision audio control", color = Color(0xFF9AA4AF), fontSize = 13.sp)
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171D22)), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("NOW PLAYING", color = Color(0xFFB5F36B), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Text(trackName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(status, color = Color(0xFF9AA4AF), fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { picker.launch(arrayOf("audio/*")) }) { Text("Choose music") }
                            Button(onClick = {
                                if (playing) player.pause() else if (player.mediaItemCount > 0) player.play()
                            }, enabled = player.mediaItemCount > 0) {
                                Text(if (playing) "Pause" else "Play")
                            }
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171D22)), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("EQUALIZER", color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Switch(checked = effectsOn, onCheckedChange = {
                                effectsOn = it
                                engine.setEnabled(it)
                            })
                        }
                        if (bands.isEmpty()) {
                            Text("Select and play a track. Available EQ bands depend on your phone's audio hardware.", color = Color(0xFF9AA4AF), fontSize = 13.sp)
                        } else {
                            bands.forEachIndexed { i, band ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        Text(formatFrequency(band.frequencyHz), color = Color(0xFFDCE3E8), fontSize = 12.sp)
                                        Text(String.format(Locale.US, "%+.1f dB", band.valueDb), color = Color(0xFFB5F36B), fontSize = 12.sp)
                                    }
                                    Slider(
                                        value = band.valueDb,
                                        onValueChange = { value ->
                                            bands[i] = band.copy(valueDb = value)
                                            if (effectsOn) engine.setBandLevel(band.index, (value * 100).toInt())
                                        },
                                        valueRange = band.minDb..band.maxDb
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Flat", "Bass", "Vocal", "Treble").forEach { preset ->
                                Button(onClick = {
                                    val targets = when (preset) {
                                        "Bass" -> listOf(6f, 4f, 1f, 0f, -1f, -2f)
                                        "Vocal" -> listOf(-2f, -1f, 3f, 4f, 2f, 0f)
                                        "Treble" -> listOf(-2f, -1f, 0f, 2f, 4f, 5f)
                                        else -> List(6) { 0f }
                                    }
                                    bands.indices.forEach { i ->
                                        val target = targets[(i.toFloat() / (bands.size.coerceAtLeast(1) - 1).coerceAtLeast(1) * (targets.size - 1)).toInt().coerceIn(0, targets.lastIndex)]
                                            .coerceIn(bands[i].minDb, bands[i].maxDb)
                                        bands[i] = bands[i].copy(valueDb = target)
                                        if (effectsOn) engine.setBandLevel(bands[i].index, (target * 100).toInt())
                                    }
                                }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                                    Text(preset, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171D22)), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("TONE & SPATIAL", color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        ValueSlider("Bass boost", bass, { bass = it; if (effectsOn) engine.setBassStrength((it * 10).toInt()) }, 0f..100f, "%.0f%%")
                        ValueSlider("Low tone gain", lowTone, { value ->
                            lowTone = value
                            bands.firstOrNull()?.let { first ->
                                val level = value.coerceIn(first.minDb, first.maxDb)
                                bands[0] = first.copy(valueDb = level)
                                if (effectsOn) engine.setBandLevel(first.index, (level * 100).toInt())
                            }
                        }, -12f..12f, "%+.1f dB")
                        ValueSlider("High tone gain", highTone, { value ->
                            highTone = value
                            bands.lastOrNull()?.let { last ->
                                val level = value.coerceIn(last.minDb, last.maxDb)
                                val lastIndex = bands.lastIndex
                                bands[lastIndex] = last.copy(valueDb = level)
                                if (effectsOn) engine.setBandLevel(last.index, (level * 100).toInt())
                            }
                        }, -12f..12f, "%+.1f dB")
                        ValueSlider("Stereo width", width, { width = it; if (effectsOn) engine.setVirtualizerStrength((it * 10).toInt()) }, 0f..100f, "%.0f%%")
                        Text("Preamp  ${(preamp * 100).toInt()}%", color = Color(0xFFDCE3E8), fontSize = 13.sp)
                        Slider(value = preamp, onValueChange = { preamp = it; player.volume = it }, valueRange = 0f..1f)
                        Text("Tone gains adjust the lowest and highest native EQ bands. Stereo width depends on device support.", color = Color(0xFF8E99A4), fontSize = 11.sp)
                    }
                }
            }
            item {
                Text("ENGINE NOTES", color = Color(0xFFB5F36B), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("This build processes playback inside ProDSP using Android audio effects. Other apps such as Spotify or YouTube Music may require device/OEM support for system-wide effects.", color = Color(0xFF9AA4AF), fontSize = 12.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ValueSlider(
    label: String,
    value: Float,
    onValue: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    format: String
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color(0xFFDCE3E8), fontSize = 13.sp)
            Text(String.format(Locale.US, format, value), color = Color(0xFFB5F36B), fontSize = 12.sp)
        }
        Slider(value = value, onValueChange = onValue, valueRange = range)
    }
}

private fun formatFrequency(hz: Int): String =
    if (hz >= 1000) String.format(Locale.US, "%.1f kHz", hz / 1000f)
    else "$hz Hz"
