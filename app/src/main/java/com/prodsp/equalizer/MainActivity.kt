package com.prodsp.equalizer

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.delay

private val Accent = Color(0xFFFF2028)
private val SpectrumGreen = Color(0xFF72E52D)
private val BandPalette = listOf(Color(0xFF9B9A32), Color(0xFF173A9D), Color(0xFF079A3B), Color(0xFF8B347F), Color(0xFFB45A1D), Color(0xFF236D88))
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
    var spectrum by remember { mutableStateOf(FloatArray(48)) }
    var playing by remember { mutableStateOf(false) }
    var dspOn by remember { mutableStateOf(true) }
    var peakGuard by remember { mutableStateOf(true) }
    var compressorOn by remember { mutableStateOf(false) }
    var compressorThreshold by remember { mutableFloatStateOf(-18f) }
    var compressorRatio by remember { mutableFloatStateOf(3f) }
    var compressorAttack by remember { mutableFloatStateOf(10f) }
    var compressorRelease by remember { mutableFloatStateOf(120f) }
    var compressorMakeup by remember { mutableFloatStateOf(0f) }
    var limiterOn by remember { mutableStateOf(true) }
    var limiterCeiling by remember { mutableFloatStateOf(-1f) }
    var bandCount by remember { mutableIntStateOf(10) }
    var selectedBand by remember { mutableIntStateOf(0) }
    val frequencies = remember { mutableStateListOf<Float>().apply { repeat(31) { add(bandFrequency(it, 31)) } } }
    val bandQs = remember { mutableStateListOf<Float>().apply { repeat(31) { add(1f) } } }
    var preamp by remember { mutableFloatStateOf(0f) }
    var lowTone by remember { mutableFloatStateOf(0f) }
    var highTone by remember { mutableFloatStateOf(0f) }
    var bass by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(0f) }
    val gains = remember { mutableStateListOf<Float>().apply { repeat(31) { add(0f) } } }
    val preferences = remember { context.getSharedPreferences("prodsp_presets", android.content.Context.MODE_PRIVATE) }
    val savedPresets = remember { mutableStateListOf<String>().apply {
        addAll(preferences.getStringSet("names", emptySet())?.sorted() ?: emptyList())
    } }
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

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

    LaunchedEffect(dsp) {
        while (true) {
            spectrum = dsp.spectrumSnapshot()
            delay(33)
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

    fun savePreset(nameInput: String) {
        val name = nameInput.trim().take(24)
        if (name.isBlank()) return
        val data = JSONObject()
            .put("bandCount", bandCount)
            .put("gains", JSONArray(gains.toList()))
            .put("frequencies", JSONArray(frequencies.toList()))
            .put("qs", JSONArray(bandQs.toList()))
            .put("preamp", preamp)
            .put("lowTone", lowTone)
            .put("highTone", highTone)
            .put("peakGuard", peakGuard)
            .put("compressorOn", compressorOn)
            .put("compressorThreshold", compressorThreshold)
            .put("compressorRatio", compressorRatio)
            .put("compressorAttack", compressorAttack)
            .put("compressorRelease", compressorRelease)
            .put("compressorMakeup", compressorMakeup)
            .put("limiterOn", limiterOn)
            .put("limiterCeiling", limiterCeiling)
            .put("dspOn", dspOn)
        preferences.edit().putString("preset_$name", data.toString())
            .putStringSet("names", (savedPresets.toSet() + name)).apply()
        if (name !in savedPresets) savedPresets.add(name)
        showSaveDialog = false
        presetName = ""
    }

    fun loadPreset(name: String) {
        val raw = preferences.getString("preset_$name", null) ?: return
        try {
            val data = JSONObject(raw)
            bandCount = data.optInt("bandCount", 10).coerceIn(1, 31)
            dsp.configureBands(bandCount)
            val savedGains = data.optJSONArray("gains") ?: JSONArray()
            val savedFrequencies = data.optJSONArray("frequencies") ?: JSONArray()
            val savedQs = data.optJSONArray("qs") ?: JSONArray()
            for (i in 0 until 31) {
                gains[i] = savedGains.optDouble(i, 0.0).toFloat()
                frequencies[i] = savedFrequencies.optDouble(i, bandFrequency(i, bandCount).toDouble()).toFloat()
                bandQs[i] = savedQs.optDouble(i, 1.0).toFloat()
                dsp.setBandFrequency(i, frequencies[i])
                dsp.setBandQ(i, bandQs[i])
            }
            preamp = data.optDouble("preamp", 0.0).toFloat()
            lowTone = data.optDouble("lowTone", 0.0).toFloat()
            highTone = data.optDouble("highTone", 0.0).toFloat()
            dsp.setLowToneGain(lowTone)
            dsp.setHighToneGain(highTone)
            peakGuard = data.optBoolean("peakGuard", true)
            compressorOn = data.optBoolean("compressorOn", false)
            compressorThreshold = data.optDouble("compressorThreshold", -18.0).toFloat()
            compressorRatio = data.optDouble("compressorRatio", 3.0).toFloat()
            compressorAttack = data.optDouble("compressorAttack", 10.0).toFloat()
            compressorRelease = data.optDouble("compressorRelease", 120.0).toFloat()
            compressorMakeup = data.optDouble("compressorMakeup", 0.0).toFloat()
            limiterOn = data.optBoolean("limiterOn", true)
            limiterCeiling = data.optDouble("limiterCeiling", -1.0).toFloat()
            dsp.setCompressorEnabled(compressorOn)
            dsp.setCompressorThreshold(compressorThreshold)
            dsp.setCompressorRatio(compressorRatio)
            dsp.setCompressorAttack(compressorAttack)
            dsp.setCompressorRelease(compressorRelease)
            dsp.setCompressorMakeup(compressorMakeup)
            dsp.setLimiterEnabled(limiterOn)
            dsp.setLimiterCeiling(limiterCeiling)
            dspOn = data.optBoolean("dspOn", true)
            dsp.setPreamp(preamp)
            dsp.setPeakGuard(peakGuard)
            dsp.setEnabled(dspOn)
            syncGains()
            selectedBand = 0
        } catch (_: Exception) {
            status = "Could not load preset"
        }
    }
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

    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("PRODSP", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Text("EQUALIZER", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Black)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (dspOn) "ON" else "OFF", color = if (dspOn) SpectrumGreen else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Switch(checked = dspOn, onCheckedChange = { dspOn = it; dsp.setEnabled(it) })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("EQU", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("TONE", color = Muted, fontSize = 12.sp)
                    Text("LIMIT", color = Muted, fontSize = 12.sp)
                    Text("DYNAMICS", color = Muted, fontSize = 12.sp)
                }
            }
            item {
                Row(Modifier.fillMaxWidth().height(560.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Column(Modifier.width(66.dp).fillMaxHeight().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                        Text("PRE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Box(Modifier.weight(1f).width(48.dp).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Slider(modifier = Modifier.width(500.dp).height(38.dp).rotate(270f), value = preamp, onValueChange = { preamp = it; dsp.setPreamp(it) }, valueRange = -12f..6f, colors = SliderDefaults.colors(thumbColor = Color.LightGray, activeTrackColor = Accent, inactiveTrackColor = Color.DarkGray))
                        }
                        Text(String.format(Locale.US, "%+.1f dB", preamp), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    for (i in 0 until bandCount) {
                        Column(
                            Modifier.width(184.dp).fillMaxHeight().padding(vertical = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(color = BandPalette[i % BandPalette.size], shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxSize()) {
                                Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                                    Text("BAND ${i + 1}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        Slider(
                                            modifier = Modifier.width(420.dp).height(38.dp).rotate(270f),
                                            value = gains[i],
                                            onValueChange = { value -> gains[i] = value; dsp.setBandGain(i, value); selectedBand = i },
                                            valueRange = -15f..15f,
                                            colors = SliderDefaults.colors(thumbColor = Color(0xFFE4E4E4), activeTrackColor = Accent, inactiveTrackColor = Color(0xFF4C4C4C))
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Surface(color = Color.Black, shape = RoundedCornerShape(18.dp)) {
                                            Text("L+R", Modifier.padding(horizontal = 13.dp, vertical = 5.dp), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Surface(color = Color.Black, shape = RoundedCornerShape(18.dp)) {
                                            Text("PEAK", Modifier.padding(horizontal = 13.dp, vertical = 5.dp), color = Color.White, fontSize = 10.sp)
                                        }
                                    }
                                    Text("Q  ${String.format(Locale.US, "%.2f", bandQs[i])}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Slider(value = bandQs[i], onValueChange = { bandQs[i] = it; dsp.setBandQ(i, it); selectedBand = i }, valueRange = 0.3f..8f, colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent, inactiveTrackColor = Color.DarkGray))
                                    Text("FREQ  ${formatFrequency(frequencies[i])}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Slider(value = ln(frequencies[i] / 20f) / ln(1000f), onValueChange = { v -> val hz = 20f * 1000f.pow(v); frequencies[i] = hz; dsp.setBandFrequency(i, hz); selectedBand = i }, valueRange = 0f..1f, colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent, inactiveTrackColor = Color.DarkGray))
                                    Text(String.format(Locale.US, "GAIN  %+.1f dB", gains[i]), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    TextButton(onClick = { selectedBand = i }) { Text(if (selectedBand == i) "SELECTED" else "SELECT", color = Color.White, fontSize = 10.sp) }
                                }
                            }
                        }
                    }
                    Column(Modifier.width(58.dp).fillMaxHeight().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                        Text("ADD", color = Muted, fontSize = 10.sp)
                        Button(onClick = { if (bandCount < 31) { bandCount++; dsp.configureBands(bandCount); frequencies[bandCount - 1] = bandFrequency(bandCount - 1, bandCount) } }) { Text("+") }
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("FREQUENCY RESPONSE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("LIVE", color = SpectrumGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    FrequencyResponseGraph(frequencies, gains, bandQs, bandCount, dspOn, lowTone, highTone)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("20 Hz", color = Muted, fontSize = 9.sp)
                        Text("1 kHz", color = Muted, fontSize = 9.sp)
                        Text("20 kHz", color = Muted, fontSize = 9.sp)
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("SPECTRUM ANALYZER", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(if (playing) "LIVE" else "WAITING", color = SpectrumGreen, fontSize = 10.sp)
                    }
                    SpectrumAnalyzer(spectrum, playing)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("PRESET", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("Flat", "Bass", "Vocal", "Treble", "Rock").forEach { name ->
                            TextButton(onClick = { applyPreset(name) }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) { Text(name, color = Color.White, fontSize = 10.sp) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { showSaveDialog = true }) { Text("Save") }
                    savedPresets.take(4).forEach { name -> TextButton(onClick = { loadPreset(name) }) { Text(name, color = Muted, fontSize = 10.sp) } }
                    Button(onClick = { picker.launch(arrayOf("audio/*")) }) { Text("Choose audio") }
                }
                Text(trackName, color = Color.White, fontSize = 12.sp)
                Text(status, color = Muted, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (playing) player.pause() else if (player.mediaItemCount > 0) player.play() }, enabled = player.mediaItemCount > 0) { Text(if (playing) "Pause" else "Play") }
                    TextButton(onClick = { for (i in 0 until 31) gains[i] = 0f; syncGains() }) { Text("Reset EQ", color = Color.White) }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("TONE CONTROL", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    ValueSlider("Bass", lowTone, { lowTone = it; dsp.setLowToneGain(it) }, -12f..12f, "%+.1f dB")
                    ValueSlider("Treble", highTone, { highTone = it; dsp.setHighToneGain(it) }, -12f..12f, "%+.1f dB")
                    ValueSlider("Bass boost (device)", bass, { bass = it; deviceEffects.setBassStrength((it * 10).toInt()) }, 0f..100f, "%.0f%%")
                    ValueSlider("Stereo width", width, { width = it; deviceEffects.setVirtualizerStrength((it * 10).toInt()) }, 0f..100f, "%.0f%%")
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DYNAMICS / LIMITER", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Compressor", color = Color.White)
                        Switch(checked = compressorOn, onCheckedChange = { compressorOn = it; dsp.setCompressorEnabled(it) })
                    }
                    ValueSlider("Attack", compressorAttack, { compressorAttack = it; dsp.setCompressorAttack(it) }, 1f..100f, "%.0f ms")
                    ValueSlider("Release", compressorRelease, { compressorRelease = it; dsp.setCompressorRelease(it) }, 10f..1000f, "%.0f ms")
                    ValueSlider("Ratio", compressorRatio, { compressorRatio = it; dsp.setCompressorRatio(it) }, 1f..20f, "%.1f:1")
                    ValueSlider("Threshold", compressorThreshold, { compressorThreshold = it; dsp.setCompressorThreshold(it) }, -36f..0f, "%+.1f dB")
                    ValueSlider("Makeup gain", compressorMakeup, { compressorMakeup = it; dsp.setCompressorMakeup(it) }, 0f..12f, "%+.1f dB")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Output limiter", color = Color.White)
                        Switch(checked = limiterOn, onCheckedChange = { limiterOn = it; dsp.setLimiterEnabled(it) })
                    }
                    ValueSlider("Limiter ceiling", limiterCeiling, { limiterCeiling = it; dsp.setLimiterCeiling(it) }, -12f..0f, "%+.1f dBFS")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Peak guard", color = Color.White)
                        Switch(checked = peakGuard, onCheckedChange = { peakGuard = it; dsp.setPeakGuard(it) })
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("PLAYBACK / SETTINGS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Current playback: this app's local player. System-wide processing of YouTube Music or other apps is not yet supported.", color = Muted, fontSize = 11.sp)
                    Text("31-band parametric DSP · tone shelves · compressor · limiter · FFT spectrum", color = Muted, fontSize = 11.sp)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        if (showSaveDialog) {
            AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                title = { Text("Save custom preset") },
                text = {
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it.take(24) },
                        label = { Text("Preset name") },
                        singleLine = true
                    )
                },
                confirmButton = { TextButton(onClick = { savePreset(presetName) }, enabled = presetName.isNotBlank()) { Text("Save") } },
                dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") } }
            )
        }
    }
}

@Composable
private fun SpectrumAnalyzer(levels: FloatArray, active: Boolean) {
    Canvas(Modifier.fillMaxWidth().height(112.dp)) {
        val count = levels.size.coerceAtLeast(1)
        val gap = 2.dp.toPx()
        val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
        val minHeight = 3.dp.toPx()
        val maxHeight = size.height - 4.dp.toPx()
        for (i in 0 until count) {
            val level = if (active) levels.getOrElse(i) { 0f }.coerceIn(0f, 1f) else 0f
            val height = minHeight + (maxHeight - minHeight) * level
            val left = i * (barWidth + gap)
            drawRoundRect(
                color = if (active) Accent.copy(alpha = 0.45f + 0.55f * level) else Muted.copy(alpha = 0.22f),
                topLeft = androidx.compose.ui.geometry.Offset(left, size.height - height),
                size = androidx.compose.ui.geometry.Size(barWidth, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f)
            )
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

@Composable
private fun FrequencyResponseGraph(
    frequencies: List<Float>,
    gains: List<Float>,
    qs: List<Float>,
    bandCount: Int,
    enabled: Boolean,
    lowToneDb: Float,
    highToneDb: Float
) {
    Canvas(Modifier.fillMaxWidth().height(154.dp)) {
        val left = 8f
        val right = size.width - 8f
        val top = 8f
        val bottom = size.height - 8f
        val graphWidth = right - left
        val graphHeight = bottom - top
        fun yFor(db: Float): Float = top + ((18f - db.coerceIn(-18f, 18f)) / 36f) * graphHeight
        drawRoundRect(color = Color(0xFF10161B), size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()))
        listOf(-15f, 0f, 15f).forEach { db ->
            val y = yFor(db)
            drawLine(Color(0xFF34414A), androidx.compose.ui.geometry.Offset(left, y), androidx.compose.ui.geometry.Offset(right, y), strokeWidth = if (db == 0f) 1.5f else 1f)
        }
        listOf(20f, 100f, 1000f, 10_000f, 20_000f).forEach { hz ->
            val x = left + (ln(hz) - ln(20f)) / ln(1000f) * graphWidth
            drawLine(Color(0xFF263139), androidx.compose.ui.geometry.Offset(x, top), androidx.compose.ui.geometry.Offset(x, bottom), strokeWidth = 1f)
        }
        val curve = Path()
        val points = 220
        for (point in 0..points) {
            val fraction = point.toFloat() / points
            val hz = 20f * 1000f.pow(fraction)
            var totalDb = 0f
            if (enabled) {
                for (i in 0 until bandCount.coerceAtMost(minOf(frequencies.size, gains.size, qs.size))) {
                    val gain = gains[i].coerceIn(-15f, 15f)
                    if (kotlin.math.abs(gain) < 0.001f) continue
                    totalDb += peakingMagnitudeDb(hz, frequencies[i], qs[i], gain)
                }
                totalDb += shelfMagnitudeDb(hz, 120f, lowToneDb, true)
                totalDb += shelfMagnitudeDb(hz, 8_000f, highToneDb, false)
            }
            val x = left + fraction * graphWidth
            val y = yFor(totalDb)
            if (point == 0) curve.moveTo(x, y) else curve.lineTo(x, y)
        }
        drawPath(curve, color = if (enabled) Accent else Muted, style = Stroke(width = 2.5.dp.toPx()))
    }
}

private fun peakingMagnitudeDb(frequency: Float, center: Float, q: Float, gainDb: Float): Float {
    val rate = 48_000f
    val a = 10.0.pow((gainDb / 40.0).toDouble()).toFloat()
    val w0 = 2f * Math.PI.toFloat() * center.coerceIn(20f, 20_000f) / rate
    val alpha = sin(w0) / (2f * q.coerceIn(0.3f, 8f))
    val cosine = cos(w0)
    val a0 = 1f + alpha / a
    val b0 = (1f + alpha * a) / a0
    val b1 = -2f * cosine / a0
    val b2 = (1f - alpha * a) / a0
    val a1 = b1
    val a2 = (1f - alpha / a) / a0
    val omega = 2f * Math.PI.toFloat() * frequency / rate
    val c1 = cos(omega)
    val s1 = sin(omega)
    val c2 = cos(2f * omega)
    val s2 = sin(2f * omega)
    val nr = b0 + b1 * c1 + b2 * c2
    val ni = -b1 * s1 - b2 * s2
    val dr = 1f + a1 * c1 + a2 * c2
    val di = -a1 * s1 - a2 * s2
    val magnitude = sqrt((nr * nr + ni * ni) / (dr * dr + di * di).coerceAtLeast(1e-12f))
    return 20f * kotlin.math.log10(magnitude.coerceAtLeast(1e-6f))
}

private fun shelfMagnitudeDb(frequency: Float, cutoff: Float, gainDb: Float, lowShelf: Boolean): Float {
    if (kotlin.math.abs(gainDb) < 0.001f) return 0f
    val rate = 48_000f
    val a = 10.0.pow((gainDb / 40.0).toDouble()).toFloat()
    val w0 = 2f * Math.PI.toFloat() * cutoff / rate
    val cosine = cos(w0)
    val alpha = sin(w0) * 0.5f * sqrt(2f)
    val beta = 2f * sqrt(a) * alpha
    val a0: Float
    val b0: Float
    val b1: Float
    val b2: Float
    val a1: Float
    val a2: Float
    if (lowShelf) {
        b0 = a * ((a + 1f) - (a - 1f) * cosine + beta)
        b1 = 2f * a * ((a - 1f) - (a + 1f) * cosine)
        b2 = a * ((a + 1f) - (a - 1f) * cosine - beta)
        a0 = (a + 1f) + (a - 1f) * cosine + beta
        a1 = -2f * ((a - 1f) + (a + 1f) * cosine)
        a2 = (a + 1f) + (a - 1f) * cosine - beta
    } else {
        b0 = a * ((a + 1f) + (a - 1f) * cosine + beta)
        b1 = -2f * a * ((a - 1f) + (a + 1f) * cosine)
        b2 = a * ((a + 1f) + (a - 1f) * cosine - beta)
        a0 = (a + 1f) - (a - 1f) * cosine + beta
        a1 = 2f * ((a - 1f) - (a + 1f) * cosine)
        a2 = (a + 1f) - (a - 1f) * cosine - beta
    }
    val omega = 2f * Math.PI.toFloat() * frequency / rate
    val c1 = cos(omega)
    val s1 = sin(omega)
    val c2 = cos(2f * omega)
    val s2 = sin(2f * omega)
    val nr = b0 + b1 * c1 + b2 * c2
    val ni = -b1 * s1 - b2 * s2
    val dr = a0 + a1 * c1 + a2 * c2
    val di = -a1 * s1 - a2 * s2
    val magnitude = sqrt((nr * nr + ni * ni) / (dr * dr + di * di).coerceAtLeast(1e-12f))
    return 20f * kotlin.math.log10(magnitude.coerceAtLeast(1e-6f))
}
