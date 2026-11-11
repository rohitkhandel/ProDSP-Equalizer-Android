package com.prodsp.equalizer

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
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
private val BandPalette = listOf(Color(0xFFFF654A), Color(0xFFFFA23F), Color(0xFFFFD34E), Color(0xFF9BCB4B), Color(0xFF39C7A2), Color(0xFF28ABD7), Color(0xFF6679E8), Color(0xFFB76CE0))
private val Muted = Color(0xFF9AA4AF)
private val Panel = Color(0xFF171D22)

private data class ParametricBackup(
    val gainsDb: FloatArray,
    val frequencies: FloatArray,
    val qValues: FloatArray,
    val filterTypes: Array<BiquadFilterType>,
    val bandEnabled: BooleanArray
)

private data class EqCompareSlot(
    val bandCount: Int,
    val parametricMode: Boolean,
    val gainsDb: FloatArray,
    val frequenciesHz: FloatArray,
    val qValues: FloatArray,
    val filterTypes: Array<BiquadFilterType>,
    val bandEnabled: BooleanArray,
    val preampDb: Float,
    val lowToneDb: Float,
    val highToneDb: Float,
    val lowToneFrequencyHz: Float,
    val highToneFrequencyHz: Float,
    val autoHeadroomEnabled: Boolean,
    val soloBandIndex: Int
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1201)
        }
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
    val sessionToken = remember(context) {
        SessionToken(context, ComponentName(context, ProDspPlaybackService::class.java))
    }
    val controllerFuture = remember(sessionToken) {
        MediaController.Builder(context, sessionToken).buildAsync()
    }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var connectionFailed by remember { mutableStateOf(false) }

    DisposableEffect(controllerFuture) {
        val listener = Runnable {
            try {
                controller = controllerFuture.get()
            } catch (_: Exception) {
                connectionFailed = true
            }
        }
        controllerFuture.addListener(listener, MoreExecutors.directExecutor())
        onDispose { MediaController.releaseFuture(controllerFuture) }
    }

    val connectedController = controller
    if (connectedController != null) {
        ProDspContent(connectedController)
    } else {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF050607)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!connectionFailed) CircularProgressIndicator(color = Accent)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        if (connectionFailed) "AUDIO SERVICE CONNECTION FAILED" else "CONNECTING AUDIO ENGINE",
                        color = if (connectionFailed) Accent else Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ProDspContent(player: MediaController) {
    val context = LocalContext.current
    val dsp = ProDspAudioEngine.processor
    val deviceEffects = ProDspAudioEngine.deviceEffects
    var trackName by remember(player) { mutableStateOf(player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "No track selected") }
    var queueCount by remember(player) { mutableIntStateOf(player.mediaItemCount) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf(false) }
    var replaceQueueOnPick by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Choose a music file to start") }
    var spectrum by remember { mutableStateOf(FloatArray(48)) }
    var sampleRateHz by remember { mutableIntStateOf(48_000) }
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    var shuffleEnabled by remember(player) { mutableStateOf(player.shuffleModeEnabled) }
    var repeatMode by remember(player) { mutableIntStateOf(player.repeatMode) }
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
    var limiterRelease by remember { mutableFloatStateOf(80f) }
    var bandCount by remember { mutableIntStateOf(10) }
    var parametricMode by remember { mutableStateOf(false) }
    var parametricBackup by remember { mutableStateOf<ParametricBackup?>(null) }
    var selectedBand by remember { mutableIntStateOf(0) }
    var soloBandIndex by remember { mutableIntStateOf(-1) }
    var activeAbSlot by remember { mutableIntStateOf(0) }
    var abSlotA by remember { mutableStateOf<EqCompareSlot?>(null) }
    var abSlotB by remember { mutableStateOf<EqCompareSlot?>(null) }
    val frequencies = remember { mutableStateListOf<Float>().apply {
        val initialLayout = EqBandLayout.frequencies(10)
        val expandedLayout = EqBandLayout.frequencies(31)
        repeat(31) { index -> add(if (index < initialLayout.size) initialLayout[index] else expandedLayout[index]) }
    } }
    val bandQs = remember { mutableStateListOf<Float>().apply { repeat(31) { add(1f) } } }
    val bandTypes = remember { mutableStateListOf<BiquadFilterType>().apply { repeat(31) { add(BiquadFilterType.PEAK) } } }
    val bandEnabled = remember { mutableStateListOf<Boolean>().apply { repeat(31) { add(true) } } }
    var showFilterMenu by remember { mutableStateOf(false) }
    var preamp by remember { mutableFloatStateOf(0f) }
    var autoHeadroomOn by remember { mutableStateOf(false) }
    var autoHeadroomDb by remember { mutableFloatStateOf(0f) }
    var lowTone by remember { mutableFloatStateOf(0f) }
    var highTone by remember { mutableFloatStateOf(0f) }
    var lowToneFrequency by remember { mutableFloatStateOf(120f) }
    var highToneFrequency by remember { mutableFloatStateOf(8_000f) }
    var balance by remember { mutableFloatStateOf(0f) }
    var bass by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(100f) }
    var virtualizerStrength by remember { mutableFloatStateOf(0f) }
    val gains = remember { mutableStateListOf<Float>().apply { repeat(31) { add(0f) } } }
    val preferences = remember { context.getSharedPreferences("prodsp_presets", android.content.Context.MODE_PRIVATE) }
    val savedPresets = remember { mutableStateListOf<String>().apply {
        addAll(preferences.getStringSet("names", emptySet())?.sorted() ?: emptyList())
    } }
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    val pageTitles = listOf("EQUALIZER", "TONE", "DYNAMICS", "PRESETS")
    val pagerState = rememberPagerState(pageCount = { pageTitles.size })
    val pageScope = rememberCoroutineScope()
    val maxBandFrequencyHz = minOf(20_000f, sampleRateHz * 0.475f).coerceAtLeast(20f)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            }
            val mediaItems = uris.map { uri ->
                MediaItem.Builder()
                    .setUri(uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(queryDisplayName(context, uri))
                            .build()
                    )
                    .build()
            }
            val replaceQueue = replaceQueueOnPick || player.mediaItemCount == 0
            if (replaceQueue) {
                player.setMediaItems(mediaItems)
                queueCount = mediaItems.size
                trackName = queryDisplayName(context, uris.first())
                currentPositionMs = 0L
                player.prepare()
                player.play()
                status = "Loading $queueCount tracks…"
            } else {
                player.addMediaItems(mediaItems)
                queueCount = player.mediaItemCount
                status = "Added ${mediaItems.size} tracks · ${queueCount} total"
            }
        }
    }

    val exportPresetLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val json = pendingExportJson
        if (uri != null && json != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(json.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("Could not open export destination")
                status = "Preset exported successfully"
            } catch (error: Exception) {
                status = "Preset export failed: ${error.message ?: "storage error"}"
            }
        }
        pendingExportJson = null
    }

    val importPresetLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException("Could not read preset file")
                val wrapper = JSONObject(raw)
                val schemaVersion = wrapper.optInt("schemaVersion", 0)
                require(schemaVersion in 0..1) { "Unsupported preset version" }
                val settings = wrapper.optJSONObject("settings") ?: wrapper
                require(settings.has("bandCount") && settings.has("gains") && settings.has("frequencies")) {
                    "This file is not a ProDSP preset"
                }
                val name = wrapper.optString("name", "Imported preset")
                    .replace(Regex("[\\p{Cntrl}]"), "_")
                    .trim().take(24).ifBlank { "Imported preset" }
                preferences.edit()
                    .putString("preset_$name", settings.toString())
                    .putStringSet("names", savedPresets.toSet() + name)
                    .apply()
                if (name !in savedPresets) savedPresets.add(name)
                status = "Imported preset: $name"
            } catch (error: Exception) {
                status = "Preset import failed: ${error.message ?: "invalid file"}"
            }
        }
    }

    LaunchedEffect(dsp) {
        while (true) {
            spectrum = dsp.spectrumSnapshot()
            val activeRate = dsp.currentSampleRate()
            if (sampleRateHz != activeRate) sampleRateHz = activeRate
            val headroom = dsp.currentAutoHeadroomDb()
            if (kotlin.math.abs(autoHeadroomDb - headroom) >= 0.02f) autoHeadroomDb = headroom
            delay(33)
        }
    }

    LaunchedEffect(sampleRateHz) {
        val safeMaximum = minOf(20_000f, sampleRateHz * 0.475f).coerceAtLeast(20f)
        for (index in 0 until bandCount) {
            if (frequencies[index] > safeMaximum) {
                frequencies[index] = safeMaximum
                dsp.setBandFrequency(index, safeMaximum)
            }
        }
        lowToneFrequency = lowToneFrequency.coerceAtMost(minOf(800f, safeMaximum))
        highToneFrequency = highToneFrequency.coerceAtMost(safeMaximum)
        dsp.setLowToneFrequency(lowToneFrequency)
        dsp.setHighToneFrequency(highToneFrequency)
    }

    LaunchedEffect(player) {
        while (true) {
            val playerDuration = player.duration
            durationMs = if (playerDuration > 0L && playerDuration != androidx.media3.common.C.TIME_UNSET) playerDuration else 0L
            if (!seeking) currentPositionMs = player.currentPosition.coerceAtLeast(0L)
            delay(250)
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
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val uri = mediaItem?.localConfiguration?.uri
                if (uri != null) trackName = queryDisplayName(context, uri)
                currentPositionMs = 0L
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                shuffleEnabled = shuffleModeEnabled
            }
            override fun onRepeatModeChanged(mode: Int) {
                repeatMode = mode
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                status = "Playback error: ${error.errorCodeName}"
                playing = false
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
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
        val oldEnabled = (0 until oldCount).map { bandEnabled[it] }
        val nextFrequencies = EqBandLayout.frequencies(safeCount, maxHz = maxBandFrequencyHz)
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
                bandEnabled[index] = oldEnabled[nearestOldIndices[index]]
                dsp.setBandEnabled(index, bandEnabled[index])
                dsp.setBandFrequency(index, frequencies[index])
                dsp.setBandQ(index, bandQs[index])
                dsp.setBandType(index, bandTypes[index])
            } else {
                gains[index] = 0f
            }
        }
        syncGains()
        selectedBand = selectedBand.coerceIn(0, safeCount - 1)
        if (soloBandIndex >= safeCount) {
            soloBandIndex = -1
            dsp.setSoloBand(-1)
        }
    }

    fun setParametricMode(enabled: Boolean) {
        if (enabled == parametricMode) return
        if (!enabled) {
            // Keep the user's parametric curve while showing the fixed-band
            // graphic editor. Switching back restores these filter controls.
            parametricBackup = ParametricBackup(
                FloatArray(bandCount) { gains[it] },
                FloatArray(bandCount) { frequencies[it] },
                FloatArray(bandCount) { bandQs[it] },
                Array(bandCount) { bandTypes[it] },
                BooleanArray(bandCount) { bandEnabled[it] }
            )
            val graphicFrequencies = EqBandLayout.frequencies(bandCount, maxHz = maxBandFrequencyHz)
            for (index in 0 until bandCount) {
                frequencies[index] = graphicFrequencies[index]
                bandQs[index] = 1f
                bandTypes[index] = BiquadFilterType.PEAK
                dsp.setBandFrequency(index, frequencies[index])
                dsp.setBandQ(index, bandQs[index])
                dsp.setBandType(index, BiquadFilterType.PEAK)
            }
        } else {
            val saved = parametricBackup
            if (saved != null && saved.frequencies.isNotEmpty()) {
                val sameBandCount = saved.frequencies.size == bandCount
                val targetFrequencies = if (sameBandCount) saved.frequencies else EqBandLayout.frequencies(bandCount)
                val nearest = EqBandLayout.nearestSourceIndices(saved.frequencies, targetFrequencies)
                val restoredGains = if (sameBandCount) saved.gainsDb else
                    EqBandLayout.resampleGains(saved.frequencies, saved.gainsDb, targetFrequencies)
                for (index in 0 until bandCount) {
                    gains[index] = restoredGains[index].coerceIn(-15f, 15f)
                    frequencies[index] = targetFrequencies[index]
                    bandQs[index] = saved.qValues[nearest[index]].coerceIn(0.3f, 8f)
                    bandTypes[index] = saved.filterTypes[nearest[index]]
                    bandEnabled[index] = saved.bandEnabled[nearest[index]]
                    dsp.setBandEnabled(index, bandEnabled[index])
                    dsp.setBandGain(index, gains[index])
                    dsp.setBandFrequency(index, frequencies[index])
                    dsp.setBandQ(index, bandQs[index])
                    dsp.setBandType(index, bandTypes[index])
                }
            }
        }
        parametricMode = enabled
    }

    fun deletePreset(name: String) {
        val remaining = savedPresets.filterNot { it == name }.toSet()
        preferences.edit()
            .remove("preset_$name")
            .putStringSet("names", remaining)
            .apply()
        savedPresets.remove(name)
    }

    fun exportPreset(name: String) {
        val raw = preferences.getString("preset_$name", null) ?: return
        pendingExportJson = JSONObject()
            .put("schemaVersion", 1)
            .put("app", "ProDSP Equalizer")
            .put("name", name)
            .put("settings", JSONObject(raw))
            .toString(2)
        val safeFileName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "preset" }
        exportPresetLauncher.launch("$safeFileName.prodsp.json")
    }

    fun captureEqCompareSlot(): EqCompareSlot {
        return EqCompareSlot(
            bandCount = bandCount,
            parametricMode = parametricMode,
            gainsDb = FloatArray(bandCount) { gains[it] },
            frequenciesHz = FloatArray(bandCount) { frequencies[it] },
            qValues = FloatArray(bandCount) { bandQs[it] },
            filterTypes = Array(bandCount) { bandTypes[it] },
            bandEnabled = BooleanArray(bandCount) { bandEnabled[it] },
            preampDb = preamp,
            lowToneDb = lowTone,
            highToneDb = highTone,
            lowToneFrequencyHz = lowToneFrequency,
            highToneFrequencyHz = highToneFrequency,
            autoHeadroomEnabled = autoHeadroomOn,
            soloBandIndex = soloBandIndex
        )
    }

    fun restoreEqCompareSlot(slot: EqCompareSlot) {
        bandCount = slot.bandCount.coerceIn(1, EqBandLayout.MAX_BANDS)
        parametricMode = slot.parametricMode
        parametricBackup = null
        dsp.configureBands(bandCount)

        for (index in 0 until EqBandLayout.MAX_BANDS) {
            if (index < bandCount) {
                gains[index] = slot.gainsDb.getOrElse(index) { 0f }.coerceIn(-15f, 15f)
                frequencies[index] = slot.frequenciesHz.getOrElse(index) {
                    bandFrequency(index, bandCount)
                }.coerceIn(20f, maxBandFrequencyHz)
                bandQs[index] = slot.qValues.getOrElse(index) { 1f }.coerceIn(0.3f, 8f)
                bandTypes[index] = slot.filterTypes.getOrElse(index) { BiquadFilterType.PEAK }
                bandEnabled[index] = slot.bandEnabled.getOrElse(index) { true }
                dsp.setBandGain(index, gains[index])
                dsp.setBandFrequency(index, frequencies[index])
                dsp.setBandQ(index, bandQs[index])
                dsp.setBandType(index, bandTypes[index])
                dsp.setBandEnabled(index, bandEnabled[index])
            } else {
                gains[index] = 0f
                bandEnabled[index] = true
            }
        }

        preamp = slot.preampDb
        lowTone = slot.lowToneDb
        highTone = slot.highToneDb
        lowToneFrequency = slot.lowToneFrequencyHz
        highToneFrequency = slot.highToneFrequencyHz
        autoHeadroomOn = slot.autoHeadroomEnabled
        dsp.setPreamp(preamp)
        dsp.setLowToneGain(lowTone)
        dsp.setHighToneGain(highTone)
        dsp.setLowToneFrequency(lowToneFrequency)
        dsp.setHighToneFrequency(highToneFrequency)
        dsp.setAutoHeadroom(autoHeadroomOn)
        soloBandIndex = slot.soloBandIndex.takeIf { it in 0 until bandCount } ?: -1
        dsp.setSoloBand(soloBandIndex)
        selectedBand = selectedBand.coerceIn(0, bandCount - 1)
        showFilterMenu = false
        syncGains()
    }

    fun switchEqCompareSlot(targetSlot: Int) {
        val target = targetSlot.coerceIn(0, 1)
        val current = captureEqCompareSlot()

        if (abSlotA == null || abSlotB == null) {
            abSlotA = current
            abSlotB = current
            activeAbSlot = 0
        }

        if (activeAbSlot == 0) abSlotA = current else abSlotB = current
        if (target == activeAbSlot) {
            if (target == 0) abSlotA = current else abSlotB = current
            return
        }

        val next = if (target == 0) abSlotA else abSlotB
        if (next != null) restoreEqCompareSlot(next)
        activeAbSlot = target
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
            .put("bandEnabled", JSONArray(bandEnabled.toList()))
            .put("preamp", preamp)
            .put("autoHeadroom", autoHeadroomOn)
            .put("lowTone", lowTone)
            .put("highTone", highTone)
            .put("lowToneFrequency", lowToneFrequency)
            .put("highToneFrequency", highToneFrequency)
            .put("stereoBalance", balance)
            .put("bass", bass)
            .put("stereoWidth", width)
            .put("virtualizerStrength", virtualizerStrength)
            .put("peakGuard", peakGuard)
            .put("compressorOn", compressorOn)
            .put("compressorThreshold", compressorThreshold)
            .put("compressorRatio", compressorRatio)
            .put("compressorAttack", compressorAttack)
            .put("compressorRelease", compressorRelease)
            .put("compressorMakeup", compressorMakeup)
            .put("limiterOn", limiterOn)
            .put("limiterCeiling", limiterCeiling)
            .put("limiterRelease", limiterRelease)
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
            parametricBackup = null
            soloBandIndex = -1
            dsp.setSoloBand(-1)
            dsp.configureBands(bandCount)
            val savedGains = data.optJSONArray("gains") ?: JSONArray()
            val savedFrequencies = data.optJSONArray("frequencies") ?: JSONArray()
            val savedQs = data.optJSONArray("qs") ?: JSONArray()
            val savedTypes = data.optJSONArray("types") ?: JSONArray()
            val savedBandEnabled = data.optJSONArray("bandEnabled") ?: JSONArray()
            for (i in 0 until 31) {
                gains[i] = savedGains.optDouble(i, 0.0).toFloat()
                frequencies[i] = savedFrequencies.optDouble(i, bandFrequency(i, bandCount).toDouble()).toFloat()
                bandQs[i] = savedQs.optDouble(i, 1.0).toFloat()
                bandTypes[i] = runCatching {
                    BiquadFilterType.valueOf(savedTypes.optString(i, "PEAK"))
                }.getOrDefault(BiquadFilterType.PEAK)
                bandEnabled[i] = savedBandEnabled.optBoolean(i, true)
                dsp.setBandEnabled(i, bandEnabled[i])
                dsp.setBandFrequency(i, frequencies[i])
                dsp.setBandQ(i, bandQs[i])
                dsp.setBandType(i, bandTypes[i])
            }
            preamp = data.optDouble("preamp", 0.0).toFloat()
            autoHeadroomOn = data.optBoolean("autoHeadroom", false)
            lowTone = data.optDouble("lowTone", 0.0).toFloat()
            highTone = data.optDouble("highTone", 0.0).toFloat()
            lowToneFrequency = data.optDouble("lowToneFrequency", 120.0).toFloat().coerceIn(30f, 800f)
            highToneFrequency = data.optDouble("highToneFrequency", 8_000.0).toFloat().coerceIn(1_000f, 18_000f)
            balance = data.optDouble("stereoBalance", 0.0).toFloat().coerceIn(-100f, 100f)
            bass = data.optDouble("bass", 0.0).toFloat()
            width = data.optDouble("stereoWidth", 100.0).toFloat().coerceIn(0f, 200f)
            virtualizerStrength = data.optDouble("virtualizerStrength", 0.0).toFloat().coerceIn(0f, 100f)
            dsp.setLowToneGain(lowTone)
            dsp.setHighToneGain(highTone)
            dsp.setLowToneFrequency(lowToneFrequency)
            dsp.setHighToneFrequency(highToneFrequency)
            dsp.setAutoHeadroom(autoHeadroomOn)
            dsp.setStereoBalance(balance)
            dsp.setStereoWidth(width)
            deviceEffects.setBassStrength((bass * 10).toInt())
            deviceEffects.setVirtualizerStrength((virtualizerStrength * 10).toInt())
            peakGuard = data.optBoolean("peakGuard", true)
            compressorOn = data.optBoolean("compressorOn", false)
            compressorThreshold = data.optDouble("compressorThreshold", -18.0).toFloat()
            compressorRatio = data.optDouble("compressorRatio", 3.0).toFloat()
            compressorAttack = data.optDouble("compressorAttack", 10.0).toFloat()
            compressorRelease = data.optDouble("compressorRelease", 120.0).toFloat()
            compressorMakeup = data.optDouble("compressorMakeup", 0.0).toFloat()
            limiterOn = data.optBoolean("limiterOn", true)
            limiterCeiling = data.optDouble("limiterCeiling", -1.0).toFloat()
            limiterRelease = data.optDouble("limiterRelease", 80.0).toFloat().coerceIn(10f, 500f)
            dsp.setCompressorEnabled(compressorOn)
            dsp.setCompressorThreshold(compressorThreshold)
            dsp.setCompressorRatio(compressorRatio)
            dsp.setCompressorAttack(compressorAttack)
            dsp.setCompressorRelease(compressorRelease)
            dsp.setCompressorMakeup(compressorMakeup)
            dsp.setLimiterEnabled(limiterOn)
            dsp.setLimiterCeiling(limiterCeiling)
            dsp.setLimiterRelease(limiterRelease)
            dspOn = data.optBoolean("dspOn", true)
            dsp.setPreamp(preamp)
            dsp.setPeakGuard(peakGuard)
            dsp.setEnabled(dspOn)
            deviceEffects.setEnabled(dspOn)
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
                "Classical" -> 2.5f * exp(-(((x - 0.08f) / 0.22f).let { it * it })) +
                    2f * exp(-(((x - 0.92f) / 0.18f).let { it * it })) -
                    1.5f * exp(-(((x - 0.52f) / 0.22f).let { it * it }))
                "Dance" -> 8f * exp(-(((x - 0.05f) / 0.17f).let { it * it })) -
                    2f * exp(-(((x - 0.48f) / 0.22f).let { it * it })) +
                    5f * exp(-(((x - 0.94f) / 0.18f).let { it * it }))
                "Jazz" -> 3f * exp(-(((x - 0.18f) / 0.2f).let { it * it })) +
                    2f * exp(-(((x - 0.78f) / 0.22f).let { it * it })) -
                    1.5f * exp(-(((x - 0.5f) / 0.24f).let { it * it }))
                "Acoustic" -> 2f * (1f - x) +
                    4f * exp(-(((x - 0.62f) / 0.18f).let { it * it })) - 1f
                "Podcast" -> -6f * exp(-(((x - 0.04f) / 0.19f).let { it * it })) +
                    4f * exp(-(((x - 0.55f) / 0.2f).let { it * it })) -
                    4f * exp(-(((x - 0.98f) / 0.12f).let { it * it }))
                "Electronic" -> 5f * exp(-(((x - 0.08f) / 0.2f).let { it * it })) +
                    3f * exp(-(((x - 0.9f) / 0.2f).let { it * it })) -
                    1f * exp(-(((x - 0.5f) / 0.24f).let { it * it }))
                "Pop" -> 3f * exp(-(((x - 0.12f) / 0.2f).let { it * it })) +
                    2.5f * exp(-(((x - 0.68f) / 0.24f).let { it * it }))
                else -> 0f
            }.coerceIn(-15f, 15f)
        }
        for (i in 0 until 31) gains[i] = values[i]
        for (i in 0 until bandCount) {
            bandEnabled[i] = true
            dsp.setBandEnabled(i, true)
        }
        soloBandIndex = -1
        dsp.setSoloBand(-1)
        syncGains()
    }

    fun resetEqualizer() {
        val defaultFrequencies = EqBandLayout.frequencies(bandCount, maxHz = maxBandFrequencyHz)
        for (index in 0 until EqBandLayout.MAX_BANDS) {
            gains[index] = 0f
            bandQs[index] = 1f
            bandTypes[index] = BiquadFilterType.PEAK
            bandEnabled[index] = true
            if (index < bandCount) frequencies[index] = defaultFrequencies[index]
        }
        dsp.configureBands(bandCount)
        for (index in 0 until bandCount) {
            dsp.setBandGain(index, 0f)
            dsp.setBandFrequency(index, frequencies[index])
            dsp.setBandQ(index, 1f)
            dsp.setBandType(index, BiquadFilterType.PEAK)
            dsp.setBandEnabled(index, true)
        }
        preamp = 0f
        dsp.setPreamp(0f)
        soloBandIndex = -1
        dsp.setSoloBand(-1)
        parametricBackup = null
        selectedBand = 0
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
                    Switch(checked = dspOn, onCheckedChange = {
                        dspOn = it
                        dsp.setEnabled(it)
                        deviceEffects.setEnabled(it)
                    })
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
                        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text(if (parametricMode) "PARAMETRIC EQ" else "GRAPHIC EQ", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    Text("20 Hz — ${formatFrequency(maxBandFrequencyHz)} · ${bandCount} bands · ${sampleRateHz / 1000} kHz", color = Muted, fontSize = 8.sp)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    listOf(10, 15, 20, 31).forEach { count ->
                                        FilterChip(
                                            selected = bandCount == count,
                                            onClick = { changeBandCount(count) },
                                            label = { Text(count.toString(), fontSize = 9.sp) },
                                            modifier = Modifier.height(30.dp),
                                        )
                                    }
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("A/B EQ COMPARE", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    Text("Switch between two saved EQ tunings", color = Muted, fontSize = 7.sp)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    FilterChip(
                                        selected = activeAbSlot == 0,
                                        onClick = { switchEqCompareSlot(0) },
                                        label = { Text("A", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                                        modifier = Modifier.height(29.dp)
                                    )
                                    FilterChip(
                                        selected = activeAbSlot == 1,
                                        onClick = { switchEqCompareSlot(1) },
                                        label = { Text("B", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                                        modifier = Modifier.height(29.dp)
                                    )
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
                                    )
                                    FilterChip(
                                        selected = parametricMode,
                                        onClick = { setParametricMode(true) },
                                        label = { Text("PARAMETRIC", fontSize = 9.sp) },
                                        modifier = Modifier.height(29.dp),
                                    )
                                }
                            }
                            Row(Modifier.fillMaxWidth().height(226.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Column(Modifier.width(42.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("PRE", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    ProDspFader(preamp, -12f, 6f, Modifier.weight(1f).width(42.dp)) { preamp = it; dsp.setPreamp(it) }
                                    Text(String.format(Locale.US, "%+.1f", preamp), color = Color.White, fontSize = 9.sp)
                                }
                                for (i in 0 until bandCount) {
                                    Column(Modifier.width(46.dp).fillMaxHeight().clickable { selectedBand = i }, horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("${i + 1}", color = if (selectedBand == i) Color.White else Muted, fontSize = 11.sp, fontWeight = FontWeight.Black)
                                        Text(formatFrequency(frequencies[i]), color = if (selectedBand == i) Accent else Muted, fontSize = 8.sp)
                                        ProDspFader(gains[i], -15f, 15f, Modifier.weight(1f).fillMaxWidth(), accent = bandAccent(i, bandCount)) { gains[i] = it; dsp.setBandGain(i, it); selectedBand = i }
                                        Text(String.format(Locale.US, "%+.1f", gains[i]), color = if (bandEnabled[i]) bandAccent(i, bandCount) else Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        Canvas(Modifier.fillMaxWidth().height(3.dp)) { if (selectedBand == i) drawLine(Accent, Offset(0f, size.height/2), Offset(size.width, size.height/2), 2.dp.toPx()) }
                                    }
                                }

                            }
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(
                                        checked = autoHeadroomOn,
                                        onCheckedChange = {
                                            autoHeadroomOn = it
                                            dsp.setAutoHeadroom(it)
                                        },
                                        modifier = Modifier.height(32.dp)
                                    )
                                    Column {
                                        Text("AUTO HEADROOM", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        Text(
                                            if (autoHeadroomOn) "PREAMP COMPENSATION ACTIVE" else "PROTECT AGAINST EQ BOOST CLIPPING",
                                            color = Muted,
                                            fontSize = 7.sp
                                        )
                                    }
                                }
                                Text(
                                    if (autoHeadroomOn) String.format(Locale.US, "−%.1f dB", autoHeadroomDb) else "OFF",
                                    color = if (autoHeadroomOn) SpectrumGreen else Muted,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
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
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("BAND ACTIVE", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(6.dp))
                                    Switch(
                                        checked = bandEnabled[selectedBand],
                                        onCheckedChange = {
                                            bandEnabled[selectedBand] = it
                                            dsp.setBandEnabled(selectedBand, it)
                                        },
                                        modifier = Modifier.height(32.dp)
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        soloBandIndex = if (soloBandIndex == selectedBand) -1 else selectedBand
                                        dsp.setSoloBand(soloBandIndex)
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                ) {
                                    Text(
                                        if (soloBandIndex == selectedBand) "UNSOLO" else "SOLO",
                                        color = if (soloBandIndex == selectedBand) Accent else Muted,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        gains[selectedBand] = 0f
                                        bandQs[selectedBand] = 1f
                                        bandTypes[selectedBand] = BiquadFilterType.PEAK
                                        bandEnabled[selectedBand] = true
                                        if (parametricMode) {
                                            frequencies[selectedBand] = EqBandLayout.frequencyAt(selectedBand, bandCount, maxBandFrequencyHz)
                                        }
                                        dsp.setBandGain(selectedBand, 0f)
                                        dsp.setBandQ(selectedBand, 1f)
                                        dsp.setBandType(selectedBand, BiquadFilterType.PEAK)
                                        dsp.setBandEnabled(selectedBand, true)
                                        if (parametricMode) dsp.setBandFrequency(selectedBand, frequencies[selectedBand])
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text("RESET BAND", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
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
                                    ProDspKnob("FREQ", frequencies[selectedBand], 20f, maxBandFrequencyHz, "") { frequencies[selectedBand] = it; dsp.setBandFrequency(selectedBand, it) }
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
                                Text("DRAG NODES TO EDIT", color = SpectrumGreen, fontSize = 8.sp)
                            }
                            FrequencyResponseGraph(
                                frequencies = frequencies,
                                gains = gains,
                                qs = bandQs,
                                types = bandTypes,
                                bandEnabled = bandEnabled,
                                bandCount = bandCount,
                                enabled = dspOn,
                                lowToneDb = lowTone,
                                highToneDb = highTone,
                                lowToneFrequencyHz = lowToneFrequency,
                                highToneFrequencyHz = highToneFrequency,
                                sampleRateHz = sampleRateHz,
                                autoHeadroomDb = if (autoHeadroomOn) autoHeadroomDb else 0f,
                                selectedBand = selectedBand,
                                soloBandIndex = soloBandIndex,
                                editableFrequency = parametricMode,
                                onBandSelected = { selectedBand = it },
                                onBandChanged = { index, frequency, gain ->
                                    if (parametricMode) {
                                        frequencies[index] = frequency
                                        dsp.setBandFrequency(index, frequency)
                                    }
                                    if (bandTypes[index] in setOf(BiquadFilterType.PEAK, BiquadFilterType.LOW_SHELF, BiquadFilterType.HIGH_SHELF)) {
                                        gains[index] = gain
                                        dsp.setBandGain(index, gain)
                                    }
                                }
                            )
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
                        Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            Text("TONE CONTROL", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                            Text("SHELF FILTERS", color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                ProDspKnob("LOW TONE", lowTone, -12f, 12f, "%+.1f dB") { lowTone = it; dsp.setLowToneGain(it) }
                                ProDspKnob("HIGH TONE", highTone, -12f, 12f, "%+.1f dB") { highTone = it; dsp.setHighToneGain(it) }
                            }
                            ValueSlider("Low shelf frequency", lowToneFrequency, { lowToneFrequency = it; dsp.setLowToneFrequency(it) }, 30f..800f, "%.0f Hz")
                            ValueSlider("High shelf frequency", highToneFrequency, { highToneFrequency = it; dsp.setHighToneFrequency(it) }, 1_000f..18_000f, "%.0f Hz")
                            Divider(color = Color(0xFF252B30))
                            Text("DEVICE EFFECTS", color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
                            ValueSlider("Bass enhancement", bass, { bass = it; deviceEffects.setBassStrength((it * 10).toInt()) }, 0f..100f, "%.0f%%")
                            ValueSlider("Device virtualizer", virtualizerStrength, { virtualizerStrength = it; deviceEffects.setVirtualizerStrength((it * 10).toInt()) }, 0f..100f, "%.0f%%")
                            Divider(color = Color(0xFF252B30))
                            Text("STEREO IMAGE · PCM DSP", color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
                            ValueSlider("Stereo width", width, { width = it; dsp.setStereoWidth(it) }, 0f..200f, "%.0f%%")
                            ValueSlider("Left / right balance", balance, { balance = it; dsp.setStereoBalance(it) }, -100f..100f, "%+.0f")
                            Text("100% width = original stereo · 0% = mono · above 100% = wider image", color = Muted, fontSize = 10.sp)
                            Text("Device virtualizer availability depends on Android and the audio route.", color = Muted, fontSize = 10.sp)
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
                            ValueSlider("Limiter release", limiterRelease, { limiterRelease = it; dsp.setLimiterRelease(it) }, 10f..500f, "%.0f ms")
                            Text("Stereo-linked peak control · instant attack · smooth release", color = Muted, fontSize = 10.sp)
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
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("Flat", "Bass", "Vocal", "Treble", "Rock", "Classical", "Dance", "Jazz", "Acoustic", "Podcast", "Electronic", "Pop").forEach { name ->
                                    OutlinedButton(onClick = { applyPreset(name) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                                        Text(name, fontSize = 10.sp, maxLines = 1)
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("CUSTOM PRESETS", color = Muted, fontSize = 9.sp, letterSpacing = 1.4.sp)
                                Row {
                                    TextButton(onClick = { importPresetLauncher.launch(arrayOf("application/json", "text/json", "*/*")) }) { Text("IMPORT", color = Muted, fontSize = 10.sp) }
                                    TextButton(onClick = { showSaveDialog = true }) { Text("+ SAVE", color = Accent, fontSize = 10.sp) }
                                }
                            }
                            if (savedPresets.isEmpty()) {
                                Text("No custom presets saved yet.", color = Muted, fontSize = 11.sp)
                            }
                            savedPresets.forEach { name ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { loadPreset(name) }, modifier = Modifier.weight(1f)) {
                                        Text(name, color = Color.White, maxLines = 1)
                                    }
                                    TextButton(onClick = { exportPreset(name) }) { Text("EXPORT", color = Muted, fontSize = 9.sp) }
                                    TextButton(onClick = { deletePreset(name) }) { Text("DELETE", color = Accent, fontSize = 9.sp) }
                                }
                            }
                            Divider(color = Color(0xFF252B30))
                            Text("LOCAL MUSIC PLAYER", color = Muted, fontSize = 9.sp, letterSpacing = 1.4.sp)
                            Text(trackName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("$queueCount tracks in queue · $status", color = Muted, fontSize = 10.sp, maxLines = 2)
                            Slider(
                                value = if (durationMs > 0L) currentPositionMs.coerceIn(0L, durationMs).toFloat() else 0f,
                                onValueChange = { currentPositionMs = it.toLong(); seeking = true },
                                onValueChangeFinished = { player.seekTo(currentPositionMs); seeking = false },
                                enabled = durationMs > 0L,
                                valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f)
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatDuration(currentPositionMs), color = Muted, fontSize = 9.sp)
                                Text(formatDuration(durationMs), color = Muted, fontSize = 9.sp)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    onClick = { if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() else player.seekTo(0L) },
                                    enabled = player.mediaItemCount > 0
                                ) { Text("PREV", fontSize = 9.sp) }
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = { if (playing) player.pause() else if (player.mediaItemCount > 0) player.play() },
                                    enabled = player.mediaItemCount > 0
                                ) { Text(if (playing) "PAUSE" else "PLAY") }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(
                                    onClick = { player.seekToNextMediaItem() },
                                    enabled = player.hasNextMediaItem()
                                ) { Text("NEXT", fontSize = 9.sp) }
                            }
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { player.shuffleModeEnabled = !shuffleEnabled }) {
                                    Text(if (shuffleEnabled) "SHUFFLE ON" else "SHUFFLE", color = if (shuffleEnabled) Accent else Muted, fontSize = 8.sp)
                                }
                                TextButton(onClick = {
                                    player.repeatMode = when (repeatMode) {
                                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                        else -> Player.REPEAT_MODE_OFF
                                    }
                                }) {
                                    Text(
                                        when (repeatMode) {
                                            Player.REPEAT_MODE_ALL -> "REPEAT ALL"
                                            Player.REPEAT_MODE_ONE -> "REPEAT ONE"
                                            else -> "REPEAT OFF"
                                        },
                                        color = if (repeatMode == Player.REPEAT_MODE_OFF) Muted else Accent,
                                        fontSize = 8.sp
                                    )
                                }
                                OutlinedButton(onClick = { replaceQueueOnPick = false; picker.launch(arrayOf("audio/*")) }) { Text("ADD TRACKS", fontSize = 8.sp) }
                                OutlinedButton(onClick = { replaceQueueOnPick = true; picker.launch(arrayOf("audio/*")) }) { Text("NEW QUEUE", fontSize = 8.sp) }
                            }
                            OutlinedButton(onClick = { resetEqualizer() }) { Text("RESET EQUALIZER") }
                            Text("Processing is currently applied to this app's local player, not system-wide audio.", color = Muted, fontSize = 10.sp)
                        }
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
private fun ProDspFader(value: Float, min: Float, max: Float, modifier: Modifier = Modifier, accent: Color = Accent, onValue: (Float) -> Unit) {
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
        for (tick in 0..12) {
            val tickY = top + (bottom - top) * tick / 12f
            val isZero = kotlin.math.abs(tickY - zeroY) < 1f
            val tickHalfWidth = if (isZero) 7.dp.toPx() else 3.dp.toPx()
            val tickColor = if (isZero) Color(0xFF9AA4AF) else Color(0xFF48515B).copy(alpha = 0.7f)
            drawLine(tickColor, Offset(centerX - tickHalfWidth - 3.dp.toPx(), tickY), Offset(centerX - 3.dp.toPx(), tickY), 1.dp.toPx())
            drawLine(tickColor, Offset(centerX + 3.dp.toPx(), tickY), Offset(centerX + tickHalfWidth + 3.dp.toPx(), tickY), 1.dp.toPx())
        }
        drawLine(Color(0xFF343A40), Offset(centerX, top), Offset(centerX, bottom), 3.dp.toPx(), cap = StrokeCap.Round)
        drawLine(accent.copy(alpha = 0.22f), Offset(centerX, zeroY), Offset(centerX, y), 10.dp.toPx(), cap = StrokeCap.Round)
        drawLine(accent, Offset(centerX, zeroY), Offset(centerX, y), 3.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(Color.White, 7.dp.toPx(), Offset(centerX, y))
        drawCircle(accent, 3.dp.toPx(), Offset(centerX, y))
        drawLine(Color(0xFF69737C), Offset(centerX - 8.dp.toPx(), zeroY), Offset(centerX + 8.dp.toPx(), zeroY), 1.dp.toPx())
    }
}

@Composable
private fun ProDspKnob(label: String, value: Float, min: Float, max: Float, format: String, onValue: (Float) -> Unit) {
    val display = if (label == "FREQ") formatFrequency(value) else String.format(Locale.US, format, value)
    var dragStartValue by remember(label) { mutableFloatStateOf(value) }
    var dragStartNorm by remember(label) { mutableFloatStateOf(0.5f) }
    val latestValue by rememberUpdatedState(value)
    val latestOnValue by rememberUpdatedState(onValue)
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
                    latestOnValue(hz.coerceIn(min, max))
                } else {
                    dragStartValue = (dragStartValue - amount.y * (max - min) / 260f).coerceIn(min, max)
                    latestOnValue(dragStartValue)
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


private fun queryDisplayName(context: android.content.Context, uri: Uri): String {
    val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
    return try {
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index)?.takeIf { it.isNotBlank() } else null
            } else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Audio track"
    } catch (_: Exception) {
        uri.lastPathSegment?.substringAfterLast('/') ?: "Audio track"
    }
}

private fun formatDuration(milliseconds: Long): String {
    if (milliseconds <= 0L) return "0:00"
    val totalSeconds = milliseconds / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val hours = minutes / 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes % 60L, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

private fun bandFrequency(index: Int, count: Int): Float = EqBandLayout.frequencyAt(index, count)

private fun formatFrequency(hz: Float): String =
    if (hz >= 1000f) String.format(Locale.US, "%.2f kHz", hz / 1000f) else String.format(Locale.US, "%.0f Hz", hz)

private fun bandAccent(index: Int, count: Int): Color {
    val paletteIndex = (index * (BandPalette.size - 1) / (count - 1).coerceAtLeast(1))
        .coerceIn(0, BandPalette.lastIndex)
    return BandPalette[paletteIndex]
}

@Composable
private fun FrequencyResponseGraph(
    frequencies: List<Float>,
    gains: List<Float>,
    qs: List<Float>,
    types: List<BiquadFilterType>,
    bandEnabled: List<Boolean>,
    bandCount: Int,
    enabled: Boolean,
    lowToneDb: Float,
    highToneDb: Float,
    lowToneFrequencyHz: Float,
    highToneFrequencyHz: Float,
    sampleRateHz: Int,
    autoHeadroomDb: Float,
    selectedBand: Int,
    soloBandIndex: Int,
    editableFrequency: Boolean,
    onBandSelected: (Int) -> Unit,
    onBandChanged: (Int, Float, Float) -> Unit
) {
    // Snapshot the EQ controls and cache the curve. The live spectrum updates
    // frequently; they should not force expensive biquad/trigonometric work.
    val activeCount = bandCount.coerceAtMost(minOf(frequencies.size, gains.size, qs.size, types.size, bandEnabled.size))
    val enabledValues = bandEnabled.take(activeCount)
    val frequencyValues = frequencies.take(activeCount)
    val gainValues = gains.take(activeCount)
    val qValues = qs.take(activeCount)
    val typeValues = types.take(activeCount)
    val curveValues = remember(
        frequencyValues, gainValues, qValues, typeValues, enabledValues, enabled, soloBandIndex,
        lowToneDb, highToneDb, lowToneFrequencyHz, highToneFrequencyHz, sampleRateHz, autoHeadroomDb
    ) {
        if (!enabled) {
            FloatArray(221)
        } else {
            // Coefficients depend on the band controls, not on each graph
            // sample. Build once per edit, then evaluate the response curve.
            val bandCoefficients = (0 until activeCount)
                .filter { enabledValues[it] && (soloBandIndex < 0 || soloBandIndex == it) }
                .map { index ->
                BiquadMath.coefficients(
                    sampleRateHz,
                    frequencyValues[index],
                    qValues[index],
                    gainValues[index],
                    typeValues[index]
                )
            }
            val lowShelf = BiquadMath.coefficients(
                sampleRateHz, lowToneFrequencyHz, 0.707f, lowToneDb, BiquadFilterType.LOW_SHELF
            )
            val highShelf = BiquadMath.coefficients(
                sampleRateHz, highToneFrequencyHz, 0.707f, highToneDb, BiquadFilterType.HIGH_SHELF
            )
            val maxHz = minOf(20_000f, sampleRateHz * 0.475f).coerceAtLeast(20f)
            FloatArray(221) { point ->
                val fraction = point / 220f
                val hz = 20f * (maxHz / 20f).pow(fraction)
                var totalDb = 0f
                for (coefficients in bandCoefficients) {
                    totalDb += BiquadMath.magnitudeDb(coefficients, hz, sampleRateHz)
                }
                totalDb += BiquadMath.magnitudeDb(lowShelf, hz, sampleRateHz)
                totalDb += BiquadMath.magnitudeDb(highShelf, hz, sampleRateHz)
                totalDb - autoHeadroomDb
            }
        }
    }

    val latestBandSelected by rememberUpdatedState(onBandSelected)
    val latestBandChanged by rememberUpdatedState(onBandChanged)
    val latestFrequencyValues by rememberUpdatedState(frequencyValues)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(154.dp)
            .pointerInput(sampleRateHz, editableFrequency, activeCount) {
                var dragIndex = -1
                val left = 8.dp.toPx()
                val right = size.width - 8.dp.toPx()
                val top = 8.dp.toPx()
                val bottom = size.height - 8.dp.toPx()
                val graphWidth = (right - left).coerceAtLeast(1f)
                val graphHeight = (bottom - top).coerceAtLeast(1f)
                val maxHz = minOf(20_000f, sampleRateHz * 0.475f).coerceAtLeast(20f)

                fun nearestBandAt(x: Float): Int {
                    val tappedFrequency = 20f * (maxHz / 20f).pow(
                        ((x - left) / graphWidth).coerceIn(0f, 1f)
                    )
                    return (0 until activeCount).minByOrNull { index ->
                        kotlin.math.abs(ln(latestFrequencyValues[index]) - ln(tappedFrequency))
                    } ?: 0
                }

                fun updateBandAt(index: Int, x: Float, y: Float) {
                    if (index !in 0 until activeCount) return
                    val frequency = if (editableFrequency) {
                        20f * (maxHz / 20f).pow(((x - left) / graphWidth).coerceIn(0f, 1f))
                    } else latestFrequencyValues[index]
                    val gain = (18f - ((y - top) / graphHeight).coerceIn(0f, 1f) * 36f)
                        .coerceIn(-15f, 15f)
                    latestBandChanged(index, frequency, gain)
                }

                detectDragGestures(
                    onDragStart = { point ->
                        dragIndex = nearestBandAt(point.x)
                        latestBandSelected(dragIndex)
                        updateBandAt(dragIndex, point.x, point.y)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        updateBandAt(dragIndex, change.position.x, change.position.y)
                    },
                    onDragEnd = { dragIndex = -1 },
                    onDragCancel = { dragIndex = -1 }
                )
            }
    ) {
        val left = 8f
        val right = size.width - 8f
        val top = 8f
        val bottom = size.height - 8f
        val graphWidth = right - left
        val graphHeight = bottom - top
        fun yFor(db: Float): Float = top + ((18f - db.coerceIn(-18f, 18f)) / 36f) * graphHeight

        drawRoundRect(
            color = Color(0xFF10161B),
            size = size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx())
        )
        listOf(-15f, 0f, 15f).forEach { db ->
            val y = yFor(db)
            drawLine(
                Color(0xFF34414A),
                androidx.compose.ui.geometry.Offset(left, y),
                androidx.compose.ui.geometry.Offset(right, y),
                strokeWidth = if (db == 0f) 1.5f else 1f
            )
        }
        val graphMaxHz = minOf(20_000f, sampleRateHz * 0.475f).coerceAtLeast(20f)
        (listOf(20f, 100f, 1_000f, 10_000f, graphMaxHz).distinct().filter { it <= graphMaxHz }).forEach { hz ->
            val x = left + (ln(hz) - ln(20f)) / ln(graphMaxHz / 20f) * graphWidth
            drawLine(
                Color(0xFF263139),
                androidx.compose.ui.geometry.Offset(x, top),
                androidx.compose.ui.geometry.Offset(x, bottom),
                strokeWidth = 1f
            )
        }

        val curve = Path()
        val fill = Path()
        val zeroLine = yFor(0f)
        fill.moveTo(left, zeroLine)
        curveValues.forEachIndexed { point, db ->
            val x = left + (point / 220f) * graphWidth
            val y = yFor(db)
            if (point == 0) {
                curve.moveTo(x, y)
                fill.lineTo(x, y)
            } else {
                curve.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(right, zeroLine)
        fill.close()
        drawPath(
            fill,
            brush = Brush.verticalGradient(
                colors = listOf(Accent.copy(alpha = 0.23f), Accent.copy(alpha = 0.015f)),
                startY = top,
                endY = bottom
            )
        )
        drawPath(
            curve,
            color = if (enabled) Accent.copy(alpha = 0.28f) else Muted.copy(alpha = 0.22f),
            style = Stroke(width = 7.dp.toPx())
        )
        drawPath(
            curve,
            color = if (enabled) Accent else Muted,
            style = Stroke(width = 2.5.dp.toPx())
        )

        // Draggable band handles make the response graph a real editor.
        for (index in 0 until activeCount) {
            val frequency = frequencyValues[index].coerceIn(20f, graphMaxHz)
            val x = left + (ln(frequency) - ln(20f)) / ln(graphMaxHz / 20f) * graphWidth
            val handleGain = if (types[index] in setOf(
                    BiquadFilterType.PEAK,
                    BiquadFilterType.LOW_SHELF,
                    BiquadFilterType.HIGH_SHELF
                )
            ) gainValues[index] else 0f
            val y = yFor(handleGain)
            if (!enabledValues[index] || (soloBandIndex >= 0 && soloBandIndex != index)) {
                drawCircle(
                    color = Muted.copy(alpha = 0.45f),
                    radius = if (index == selectedBand) 5.dp.toPx() else 3.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(x, y)
                )
            } else if (index == selectedBand) {
                drawCircle(
                    color = Accent.copy(alpha = 0.2f),
                    radius = 11.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(x, y)
                )
                drawCircle(
                    color = Color.White,
                    radius = 5.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(x, y)
                )
                drawCircle(
                    color = Accent,
                    radius = 3.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(x, y)
                )
            } else {
                drawCircle(
                    color = Color(0xFFE6E9EC),
                    radius = 3.5.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(x, y)
                )
            }
        }
    }
}
