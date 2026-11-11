package com.prodsp.equalizer

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

private val Accent = Color(0xFFB5F36B)
private val Muted = Color(0xFF9AA4AF)
private val Panel = Color(0xFF171D22)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, secondary = Color(0xFF73D6C2), background = Color(0xFF0B0D10), surface = Panel)) {
                ProDspScreen()
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ProDspScreen() {
    val context = LocalContext.current
    val dsp = remember { ParametricDspProcessor() }
    val player = remember { ExoPlayer.Builder(context, ProDspRenderersFactory(context, dsp)).build() }
    val deviceEffects = remember { AudioEffectsEngine() }
    var trackName by remember { mutableStateOf("No track selected") }
    var status by remember { mutableStateOf("Choose a music file to start") }
    var playing by remember { mutableStateOf(false) }
    var dspOn by remember { mutableStateOf(true) }
    var peakGuard by remember { mutableStateOf(true) }
    var bandCount by remember { mutableIntStateOf(10) }
    var selectedBand by remember { mutableIntStateOf(0) }
    val frequencies = remember { mutableStateListOf<Float>().apply { repeat(31) { add(bandFrequency(it, 31)) } } }
    val bandQs = remember { mutableStateListOf<Float>().apply { repeat(31) { add(1f) } } }
    var preamp by remember { mutableFloatStateOf(0f) }
    var bass by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(0f) }
    val gains = remember { mutableStateListOf<Float>().apply { repeat(31) { add(0f) } } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
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
                if (isPlaying) status = "Playing · custom PCM DSP active"
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) status = "Ready · custom DSP chain connected"
                else if (playbackState == Player.STATE_BUFFERING) status = "Buffering…"
                else if (playbackState == Player.STATE_ENDED) playing = false
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                status = "Playback error: ${error.errorCodeName}"
                playing = false
            }
        }
        val analytics = object : AnalyticsListener {
            override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
                deviceEffects.attach(audioSessionId)
                deviceEffects.setBassStrength((bass * 10).toInt())
                deviceEffects.setVirtualizerStrength((width * 10).toInt())
            }
        }
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        onDispose {
            player.removeListener(listener)
            player.removeAnalyticsListener(analytics)
            player.release()
            deviceEffects.release()
        }
    }

    fun syncGains() { dsp.setAllGains(FloatArray(31) { gains[it] }) }
    fun applyPreset(name: String) {
        val values = FloatArray(31)
        for (i in 0 until bandCount) {
            val x = i.toFloat() / (bandCount - 1).coerceAtLeast(1)
            values[i] = when (name) {
                "Bass" -> 7f * (1f - x) - 1.5f * x
                "Vocal" -> -1.5f + 5.5f * exp(-(((x - 0.58f) / 0.19f).let { it * it }))
                "Treble" -> -1f + 6f * x
                "Rock" -> 5f * sin((x * Math.PI * 2).toFloat())
                else -> 0f
            }.coerceIn(-15f, 15f)
        }
        for (i in 0 until 31) gains[i] = values[i]
        syncGains()
    }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0D10)) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            item {
                Column(Modifier.padding(top = 22.dp)) {
                    Text("PRODSP  /  AUDIO LAB", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
                    Text("Equalizer", color = Color.White, fontSize = 31.sp, fontWeight = FontWeight.Bold)
                    Text("Custom parametric sound engine", color = Muted, fontSize = 13.sp)
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("NOW PLAYING", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Text(trackName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(status, color = Muted, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { picker.launch(arrayOf("audio/*")) }) { Text("Choose audio") }
                            Button(onClick = { if (playing) player.pause() else if (player.mediaItemCount > 0) player.play() }, enabled = player.mediaItemCount > 0) {
                                Text(if (playing) "Pause" else "Play")
                            }
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("PARAMETRIC EQUALIZER", color = Color.White, fontWeight = FontWeight.Bold)
                            Switch(checked = dspOn, onCheckedChange = { dspOn = it; dsp.setEnabled(it) })
                        }
                        Text("Custom peaking-biquad filter bank. Select band count, then edit each band frequency, gain and Q independently.", color = Muted, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf(10, 15, 20, 31).forEach { count ->
                                Button(onClick = { bandCount = count; dsp.configureBands(count); for (i in 0 until 31) { frequencies[i] = bandFrequency(i, count); bandQs[i] = 1f }; syncGains() }) {
                                    Text(if (bandCount == count) "$count ✓" else "$count", fontSize = 11.sp)
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("Flat", "Bass", "Vocal", "Treble", "Rock").forEach { preset ->
                                Button(onClick = { applyPreset(preset) }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 5.dp)) {
                                    Text(preset, fontSize = 10.sp)
                                }
                            }
                        }
                        Text("SELECTED BAND  ·  ${selectedBand + 1}", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Text("Center frequency and Q are independently adjustable for each band.", color = Muted, fontSize = 11.sp)
                        val selectedFrequency = frequencies[selectedBand]
                        val frequencyProgress = (ln(selectedFrequency / 20f) / ln(1000f)).coerceIn(0f, 1f)
                        ValueSlider("Center frequency", frequencyProgress, { progress ->
                            val hz = 20f * 1000f.pow(progress)
                            frequencies[selectedBand] = hz
                            dsp.setBandFrequency(selectedBand, hz)
                        }, 0f..1f, "%.0f%%")
                        Text(formatFrequency(selectedFrequency), color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        ValueSlider("Selected band Q", bandQs[selectedBand], { q ->
                            bandQs[selectedBand] = q
                            dsp.setBandQ(selectedBand, q)
                        }, 0.3f..8f, "%.2f")
                        Text("BAND GAIN", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        for (i in 0 until bandCount) {
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(formatFrequency(frequencies[i]), color = if (selectedBand == i) Accent else Color(0xFFDCE3E8), fontSize = 12.sp)
                                    Text(String.format(Locale.US, "%+.1f dB", gains[i]), color = Accent, fontSize = 12.sp)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Slider(modifier = Modifier.weight(1f), value = gains[i], onValueChange = { value -> gains[i] = value; dsp.setBandGain(i, value) }, valueRange = -15f..15f)
                                    TextButton(onClick = { selectedBand = i }) { Text(if (selectedBand == i) "EDITING" else "EDIT", fontSize = 9.sp) }
                                }
                            }
                        }
                        Button(onClick = { for (i in 0 until 31) gains[i] = 0f; syncGains() }) { Text("Reset all bands") }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("MASTER & SPATIAL", color = Color.White, fontWeight = FontWeight.Bold)
                        ValueSlider("Preamp", preamp, { preamp = it; dsp.setPreamp(it) }, -12f..6f, "%+.1f dB")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Peak protection", color = Color(0xFFDCE3E8), fontSize = 13.sp)
                            Switch(checked = peakGuard, onCheckedChange = { peakGuard = it; dsp.setPeakGuard(it) })
                        }
                        ValueSlider("Bass boost (device)", bass, { bass = it; deviceEffects.setBassStrength((it * 10).toInt()) }, 0f..100f, "%.0f%%")
                        ValueSlider("Stereo virtualizer (device)", width, { width = it; deviceEffects.setVirtualizerStrength((it * 10).toInt()) }, 0f..100f, "%.0f%%")
                        Text("The custom EQ, Q and preamp process this player's PCM audio. Bass boost and virtualizer use optional Android device effects.", color = Muted, fontSize = 11.sp)
                    }
                }
            }
            item {
                Text("ENGINE", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("31-band-capable biquad bank · coefficient smoothing · per-channel filter state · PCM peak protection. App-local playback processing; other apps are not automatically routed through it.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ValueSlider(label: String, value: Float, onValue: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, format: String) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color(0xFFDCE3E8), fontSize = 13.sp)
            Text(String.format(Locale.US, format, value), color = Accent, fontSize = 12.sp)
        }
        Slider(value = value, onValueChange = onValue, valueRange = range)
    }
}

private fun bandFrequency(index: Int, count: Int): Float =
    20f * (20000f / 20f).pow(index.toFloat() / (count - 1).coerceAtLeast(1))

private fun formatFrequency(hz: Float): String =
    if (hz >= 1000f) String.format(Locale.US, "%.2f kHz", hz / 1000f) else String.format(Locale.US, "%.0f Hz", hz)
