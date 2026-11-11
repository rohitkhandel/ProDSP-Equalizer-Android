package com.prodsp.equalizer

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.launch

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
    var parametricMode by remember { mutableStateOf(false) }
    var selectedBand by remember { mutableIntStateOf(0) }
    val frequencies = remember { mutableStateListOf<Float>().apply {
        val initialLayout = EqBandLayout.frequencies(10)
        val expandedLayout = EqBandLayout.frequencies(31)
        repeat(31) { index -> add(if (index < initialLayout.size) initialLayout[index] else expandedLayout[index]) }
    } }
    val bandQs = remember { mutableStateListOf<Float>().apply { repeat(31) { add(1f) } } }
    val bandTypes = remember { mutableStateListOf<BiquadFilterType>().apply { repeat(31) { add(BiquadFilterType.PEAK) } } }
    var showFilterMenu by remember { mutableStateOf(false) }
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
    val pageTitles = listOf("EQUALIZER", "TONE", "DYNAMICS", "PRESETS")
    val pagerState = rememberPagerState(pageCount = { pageTitles.size })
    val pageScope = rememberCoroutineScope()

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

    /**
     * Changes active EQ density while keeping the existing tonal shape.
     * Gain is interpolated on a logarithmic frequency axis.
     */
    fun changeBandCount(nextCount: Int) {
        val safeCount = nextCount.coerceIn(1, EqBandLayout.MAX_BANDS)
        if (safeCount == bandCount) return

        val oldCount = bandCount
        val oldFrequencies = FloatArray(oldCount) { frequencies[it] }
        val oldGains = FloatArray(oldCount) { gains[it] }
        val oldQs = FloatArray(oldCount) { bandQs[it] }
        val oldTypes = (0 until oldCount).map { bandTypes[it] }
        val nextFrequencies = EqBandLayout.frequencies(safeCount)
        val nextGains = EqBandLayout.resampleGains(oldFrequencies, oldGains, nextFrequencies)
        val nextQs = EqBandLayout.resampleNearest(oldFrequencies, oldQs, nextFrequencies)
        val nearestOldIndices = EqBandLayout.nearestSourceIndices(oldFrequencies, nextFrequencies)

        bandCount = safeCount
        dsp.configureBands(safeCount)
        for (index in 0 until EqBandLayout.MAX_BANDS) {
            if (index < safeCount) {
                frequencies[index] = nextFrequencies[index]
                gains[index] = nextGains[index].coerceIn(-15f, 15f)
                bandQs[index] = nextQs[index].coerceIn(0.3f, 8f)
                bandTypes[index] = oldTypes[nearestOldIndices[index]]
                dsp.setBandFrequency(index, frequencies[index])
                dsp.setBandQ(index, bandQs[index])
                dsp.setBandType(index, bandTypes[index])
            } else {
                gains[index] = 0f
            }
        }
        syncGains()
        selectedBand = selectedBand.coerceIn(0, safeCount - 1)
    }

    fun setParametricMode(enabled: Boolean) {
        parametricMode = enabled
        if (!enabled) {
            // Graphic mode uses evenly spaced logarithmic centres and peak
            // filters. Parametric values remain available after switching back.
            val graphicFrequencies = EqBandLayout.frequencies(bandCount)
            for (index in 0 until bandCount) {
                frequencies[index] = graphicFrequencies[index]
                bandQs[index] = 1f
                bandTypes[index] = BiquadFilterType.PEAK
                dsp.setBandFrequency(index, frequencies[index])
                dsp.setBandQ(index, bandQs[index])
                dsp.setBandType(index, BiquadFilterType.PEAK)
            }
        }
    }

    fun savePreset(nameInput: String) {
        val name = nameInput.trim().take(24)
        if (name.isBlank()) return
        val data = JSONObject()
            .put("bandCount", bandCount)
            .put("parametricMode", parametricMode)
            .put("gains", JSONArray(gains.toList()))
            .put("frequencies", JSONArray(frequencies.toList()))
            .put("qs", JSONArray(bandQs.toList()))
            .put("types", JSONArray(bandTypes.map { it.name }))
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
            parametricMode = data.optBoolean("parametricMode", false)
            dsp.configureBands(bandCount)
            val savedGains = data.optJSONArray("gains") ?: JSONArray()
            val savedFrequencies = data.optJSONArray("frequencies") ?: JSONArray()
            val savedQs = data.optJSONArray("qs") ?: JSONArray()
            val savedTypes = data.optJSONArray("types") ?: JSONArray()
            for (i in 0 until 31) {
                gains[i] = savedGains.optDouble(i, 0.0).toFloat()
                frequencies[i] = savedFrequencies.optDouble(i, bandFrequency(i, bandCount).toDouble()).toFloat()
                bandQs[i] = savedQs.optDouble(i, 1.0).toFloat()
                bandTypes[i] = runCatching {
                    BiquadFilterType.valueOf(savedTypes.optString(i, "PEAK"))
                }.getOrDefault(BiquadFilterType.PEAK)
                dsp.setBandFrequency(i, frequencies[i])
                dsp.setBandQ(i, bandQs[i])
                dsp.setBandType(i, bandTypes[i])
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

    Surface(Modifier.fillMaxSize(), color = Color(0xFF050607)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("PRODSP", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                    Text("EQUALIZER", color = Color(0xFFB7BEC5), fontSize = 10.sp, letterSpacing = 2.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (dspOn) "DSP ACTIVE" else "DSP BYPASS", color = if (dspOn) Accent else Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = dspOn, onCheckedChange = { dspOn = it; dsp.setEnabled(it) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                pageTitles.forEachIndexed { index, title ->
                    val active = pagerState.currentPage == index
                    TextButton(onClick = { pageScope.launch { pagerState.animateScrollToPage(index) } }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(title, color = if (active) Color.White else Color(0xFF68727B), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
                            Spacer(Modifier.height(5.dp))
                            Canvas(Modifier.fillMaxWidth().height(2.dp)) { if (active) drawLine(Accent, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx()) }
                        }
                    }
                }
            }
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                when (page) {
                    0 -> {
                        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text("PARAMETRIC EQ", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    Text("20 Hz — 20 kHz · ${bandCount} active bands", color = Muted, fontSize = 8.sp)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    listOf(10, 15, 20, 31).forEach { count ->
                                        FilterChip(
                                            selected = bandCount == count,
                                            onClick = { changeBandCount(count) },
                                            label = { Text(count.toString(), fontSize = 9.sp) },
                                            modifier = Modifier.height(30.dp),
                                            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)
                                        )
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("EDITOR", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    FilterChip(
                                        selected = !parametricMode,
                                        onClick = { setParametricMode(false) },
                                        label = { Text("GRAPHIC", fontSize = 9.sp) },
                                        modifier = Modifier.height(29.dp),
                                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)
                                    )
                                    FilterChip(
                                        selected = parametricMode,
                                        onClick = { setParametricMode(true) },
                                        label = { Text("PARAMETRIC", fontSize = 9.sp) },
                                        modifier = Modifier.height(29.dp),
                                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)
                                    )
                                }
                            }
                            Row(Modifier.fillMaxWidth().height(226.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Column(Modifier.width(48.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("PRE", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    ProDspFader(preamp, -12f, 6f, Modifier.weight(1f).width(42.dp)) { preamp = it; dsp.setPreamp(it) }
                                    Text(String.format(Locale.US, "%+.1f", preamp), color = Color.White, fontSize = 9.sp)
                                }
                                for (i in 0 until bandCount) {
                                    Column(Modifier.width(62.dp).fillMaxHeight().clickable { selectedBand = i }, horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("${i + 1}", color = if (selectedBand == i) Color.White else Muted, fontSize = 11.sp, fontWeight = FontWeight.Black)
                                        Text(formatFrequency(frequencies[i]), color = if (selectedBand == i) Accent else Muted, fontSize = 8.sp)
                                        ProDspFader(gains[i], -15f, 15f, Modifier.weight(1f).fillMaxWidth()) { gains[i] = it; dsp.setBandGain(i, it); selectedBand = i }
                                        Text(String.format(Locale.US, "%+.1f", gains[i]), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        Canvas(Modifier.fillMaxWidth().height(3.dp)) { if (selectedBand == i) drawLine(Accent, Offset(0f, size.height/2), Offset(size.width, size.height/2), 2.dp.toPx()) }
                                    }
                                }

                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("BAND ${selectedBand + 1}  ·  ${formatFrequency(frequencies[selectedBand])}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                if (parametricMode) {
                                    Box {
                                        TextButton(onClick = { showFilterMenu = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                            Text(bandTypes[selectedBand].title.uppercase(Locale.US), color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                            Text(" ▾", color = Accent, fontSize = 9.sp)
                                        }
                                        DropdownMenu(expanded = showFilterMenu, onDismissRequest = { showFilterMenu = false }) {
                                            BiquadFilterType.entries.forEach { type ->
                                                DropdownMenuItem(
                                                    text = { Text(type.title) },
                                                    onClick = {
                                                        bandTypes[selectedBand] = type
                                                        dsp.setBandType(selectedBand, type)
                                                        showFilterMenu = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Text("GRAPHIC BAND", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                if (bandTypes[selectedBand] in setOf(BiquadFilterType.PEAK, BiquadFilterType.LOW_SHELF, BiquadFilterType.HIGH_SHELF) || !parametricMode) {
                                    ProDspKnob("GAIN", gains[selectedBand], -15f, 15f, "%+.1f dB") { gains[selectedBand] = it; dsp.setBandGain(selectedBand, it) }
                                } else {
                                    Column(Modifier.width(94.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("GAIN", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                        Text("Not used", color = Muted, fontSize = 10.sp)
                                    }
                                }
                                if (parametricMode) {
                                    ProDspKnob("FREQ", frequencies[selectedBand], 20f, 20000f, "") { frequencies[selectedBand] = it; dsp.setBandFrequency(selectedBand, it) }
                                    ProDspKnob("Q FACTOR", bandQs[selectedBand], 0.3f, 8f, "%.2f") { bandQs[selectedBand] = it; dsp.setBandQ(selectedBand, it) }
                                } else {
                                    Column(Modifier.width(190.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("CENTRE FREQUENCY", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                        Text(formatFrequency(frequencies[selectedBand]), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Text("Fixed logarithmic spacing", color = Muted, fontSize = 8.sp)
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("FREQUENCY RESPONSE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text("LIVE", color = SpectrumGreen, fontSize = 9.sp)
                            }
                            FrequencyResponseGraph(frequencies, gains, bandQs, bandTypes, bandCount, dspOn, lowTone, highTone)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("20 Hz", color = Muted, fontSize = 8.sp); Text("1 kHz", color = Muted, fontSize = 8.sp); Text("20 kHz", color = Muted, fontSize = 8.sp)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("LIVE SPECTRUM", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Text(if (playing) "● LIVE" else "STANDBY", color = if (playing) Accent else Muted, fontSize = 8.sp)
                                }
                                SpectrumAnalyzer(spectrum, playing)
                            }
                        }
                    }
                    1 -> {
                        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            Text("TONE CONTROL", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                            Text("SHELF FILTERS", color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                ProDspKnob("LOW TONE", lowTone, -12f, 12f, "%+.1f dB") { lowTone = it; dsp.setLowToneGain(it) }
                                ProDspKnob("HIGH TONE", highTone, -12f, 12f, "%+.1f dB") { highTone = it; dsp.setHighToneGain(it) }
                            }
                            Divider(color = Color(0xFF252B30))
                            Text("DEVICE EFFECTS", color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
                            ValueSlider("Bass enhancement", bass, { bass = it; deviceEffects.setBassStrength((it * 10).toInt()) }, 0f..100f, "%.0f%%")
                            ValueSlider("Stereo width", width, { width = it; deviceEffects.setVirtualizerStrength((it * 10).toInt()) }, 0f..100f, "%.0f%%")
                            Text("Device effects depend on the Android audio implementation.", color = Muted, fontSize = 10.sp)
                        }
                    }
                    2 -> {
                        Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("DYNAMICS PROCESSOR", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Compressor", color = Color.White, fontSize = 13.sp)
                                Switch(checked = compressorOn, onCheckedChange = { compressorOn = it; dsp.setCompressorEnabled(it) })
                            }
                            ValueSlider("Threshold", compressorThreshold, { compressorThreshold = it; dsp.setCompressorThreshold(it) }, -36f..0f, "%+.1f dB")
                            ValueSlider("Ratio", compressorRatio, { compressorRatio = it; dsp.setCompressorRatio(it) }, 1f..20f, "%.1f:1")
                            ValueSlider("Attack", compressorAttack, { compressorAttack = it; dsp.setCompressorAttack(it) }, 1f..100f, "%.0f ms")
                            ValueSlider("Release", compressorRelease, { compressorRelease = it; dsp.setCompressorRelease(it) }, 10f..1000f, "%.0f ms")
                            ValueSlider("Makeup gain", compressorMakeup, { compressorMakeup = it; dsp.setCompressorMakeup(it) }, 0f..12f, "%+.1f dB")
                            Divider(color = Color(0xFF252B30))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("OUTPUT LIMITER", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Switch(checked = limiterOn, onCheckedChange = { limiterOn = it; dsp.setLimiterEnabled(it) })
                            }
                            ValueSlider("Ceiling", limiterCeiling, { limiterCeiling = it; dsp.setLimiterCeiling(it) }, -12f..0f, "%+.1f dBFS")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Peak guard", color = Color.White, fontSize = 12.sp)
                                Switch(checked = peakGuard, onCheckedChange = { peakGuard = it; dsp.setPeakGuard(it) })
                            }
                        }
                    }
                    else -> {
                        Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("PRESETS & PLAYER", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                            Text("QUICK PRESETS", color = Muted, fontSize = 9.sp, letterSpacing = 1.4.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("Flat", "Bass", "Vocal", "Treble", "Rock").forEach { name -> OutlinedButton(onClick = { applyPreset(name) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { Text(name, fontSize = 10.sp) } }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("CUSTOM PRESETS", color = Muted, fontSize = 9.sp, letterSpacing = 1.4.sp)
                                TextButton(onClick = { showSaveDialog = true }) { Text("+ SAVE", color = Accent) }
                            }
                            savedPresets.forEach { name -> TextButton(onClick = { loadPreset(name) }, modifier = Modifier.fillMaxWidth()) { Text(name, color = Color.White) } }
                            Divider(color = Color(0xFF252B30))
                            Text("LOCAL PLAYER", color = Muted, fontSize = 9.sp, letterSpacing = 1.4.sp)
                            Text(trackName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(status, color = Muted, fontSize = 10.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = { picker.launch(arrayOf("audio/*")) }) { Text("CHOOSE AUDIO") }
                                OutlinedButton(onClick = { if (playing) player.pause() else if (player.mediaItemCount > 0) player.play() }, enabled = player.mediaItemCount > 0) { Text(if (playing) "PAUSE" else "PLAY") }
                            }
                            OutlinedButton(onClick = { for (i in 0 until 31) gains[i] = 0f; syncGains() }) { Text("RESET EQUALIZER") }
                            Text("Processing is currently applied to this app's local player, not system-wide audio.", color = Muted, fontSize = 10.sp)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                listOf("EQ", "TONE", "DYN", "LIB").forEachIndexed { index, label ->
                    TextButton(onClick = { pageScope.launch { pagerState.animateScrollToPage(index) } }) {
                        Text(label, color = if (pagerState.currentPage == index) Accent else Muted, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    }
                }
            }
        }
        if (showSaveDialog) {
            AlertDialog(onDismissRequest = { showSaveDialog = false }, title = { Text("Save custom preset") },
                text = { OutlinedTextField(value = presetName, onValueChange = { presetName = it.take(24) }, label = { Text("Preset name") }, singleLine = true) },
                confirmButton = { TextButton(onClick = { savePreset(presetName) }, enabled = presetName.isNotBlank()) { Text("Save") } },
                dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") } })
        }
    }
}


@Composable
private fun ProDspFader(value: Float, min: Float, max: Float, modifier: Modifier = Modifier, onValue: (Float) -> Unit) {
    var heightPx by remember { mutableFloatStateOf(1f) }
    Canvas(modifier.pointerInput(min, max, heightPx) {
        detectDragGestures(onDragStart = { point ->
            val fraction = (1f - point.y / heightPx).coerceIn(0f, 1f)
            onValue(min + fraction * (max - min))
        }, onDrag = { change, _ ->
            val fraction = (1f - change.position.y / heightPx).coerceIn(0f, 1f)
            onValue(min + fraction * (max - min))
        })
    }) {
        heightPx = size.height
        val centerX = size.width / 2f
        val top = 8.dp.toPx()
        val bottom = size.height - 8.dp.toPx()
        val zeroY = bottom - ((0f - min) / (max - min)) * (bottom - top)
        val y = bottom - ((value - min) / (max - min)) * (bottom - top)
        drawLine(Color(0xFF343A40), Offset(centerX, top), Offset(centerX, bottom), 3.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Accent.copy(alpha = 0.18f), Offset(centerX, zeroY), Offset(centerX, y), 10.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Accent, Offset(centerX, zeroY), Offset(centerX, y), 3.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(Color.White, 7.dp.toPx(), Offset(centerX, y))
        drawCircle(Accent, 3.dp.toPx(), Offset(centerX, y))
        drawLine(Color(0xFF69737C), Offset(centerX - 8.dp.toPx(), zeroY), Offset(centerX + 8.dp.toPx(), zeroY), 1.dp.toPx())
    }
}

@Composable
private fun ProDspKnob(label: String, value: Float, min: Float, max: Float, format: String, onValue: (Float) -> Unit) {
    val display = if (label == "FREQ") formatFrequency(value) else String.format(Locale.US, format, value)
    var dragStartValue by remember(label) { mutableFloatStateOf(value) }
    var dragStartNorm by remember(label) { mutableFloatStateOf(0.5f) }
    val latestValue by rememberUpdatedState(value)
    val logarithmic = label == "FREQ"
    fun normalized(value: Float): Float {
        return if (logarithmic) {
            ((ln(value.coerceIn(min, max)) - ln(min)) / (ln(max) - ln(min))).coerceIn(0f, 1f)
        } else {
            ((value - min) / (max - min)).coerceIn(0f, 1f)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(94.dp)) {
        Text(label, color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Canvas(Modifier.size(66.dp).pointerInput(label, min, max) {
            detectDragGestures(onDragStart = {
                dragStartValue = latestValue
                dragStartNorm = normalized(latestValue)
            }, onDrag = { _, amount ->
                if (logarithmic) {
                    dragStartNorm = (dragStartNorm - amount.y / 260f).coerceIn(0f, 1f)
                    val hz = exp(ln(min) + dragStartNorm * (ln(max) - ln(min)))
                    onValue(hz.coerceIn(min, max))
                } else {
                    dragStartValue = (dragStartValue - amount.y * (max - min) / 260f).coerceIn(min, max)
                    onValue(dragStartValue)
                }
            })
        }) {
            val stroke = 5.dp.toPx()
            val radius = size.minDimension / 2f - stroke
            val sweep = normalized(value) * 270f
            drawArc(Color(0xFF30363C), 135f, 270f, false, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(Accent, 135f, sweep, false, style = Stroke(stroke, cap = StrokeCap.Round))
            drawCircle(Color(0xFF171B20), radius - 4.dp.toPx())
            val angle = Math.toRadians((135f + sweep).toDouble())
            val dot = Offset(size.width / 2f + kotlin.math.cos(angle).toFloat() * radius, size.height / 2f + kotlin.math.sin(angle).toFloat() * radius)
            drawCircle(Accent.copy(alpha = 0.25f), 8.dp.toPx(), dot)
            drawCircle(Color.White, 3.dp.toPx(), dot)
        }
        Text(display, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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

private fun bandFrequency(index: Int, count: Int): Float = EqBandLayout.frequencyAt(index, count)

private fun formatFrequency(hz: Float): String =
    if (hz >= 1000f) String.format(Locale.US, "%.2f kHz", hz / 1000f) else String.format(Locale.US, "%.0f Hz", hz)

@Composable
private fun FrequencyResponseGraph(
    frequencies: List<Float>,
    gains: List<Float>,
    qs: List<Float>,
    types: List<BiquadFilterType>,
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
                val activeCount = bandCount.coerceAtMost(minOf(frequencies.size, gains.size, qs.size, types.size))
                for (i in 0 until activeCount) {
                    val gain = gains[i].coerceIn(-15f, 15f)
                    val coefficients = BiquadMath.coefficients(
                        48_000, frequencies[i], qs[i], gain, types[i]
                    )
                    totalDb += BiquadMath.magnitudeDb(coefficients, hz, 48_000)
                }
                val lowShelf = BiquadMath.coefficients(48_000, 120f, 0.707f, lowToneDb, BiquadFilterType.LOW_SHELF)
                val highShelf = BiquadMath.coefficients(48_000, 8_000f, 0.707f, highToneDb, BiquadFilterType.HIGH_SHELF)
                totalDb += BiquadMath.magnitudeDb(lowShelf, hz, 48_000)
                totalDb += BiquadMath.magnitudeDb(highShelf, hz, 48_000)
            }
            val x = left + fraction * graphWidth
            val y = yFor(totalDb)
            if (point == 0) curve.moveTo(x, y) else curve.lineTo(x, y)
        }
        drawPath(curve, color = if (enabled) Accent else Muted, style = Stroke(width = 2.5.dp.toPx()))
    }
}

