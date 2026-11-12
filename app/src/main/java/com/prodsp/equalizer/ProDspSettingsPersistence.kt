package com.prodsp.equalizer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the active DSP state independently of the Activity.
 *
 * The playback service owns the processor; saving and restoring this state
 * lets it survive Activity recreation and process restarts without the UI
 * inventing a second set of default values.
 */
object ProDspSettingsPersistence {
    private const val PREFS = "prodsp_active_session"
    private const val KEY_STATE = "dsp_state_v1"

    fun save(context: Context) {
        val dsp = ProDspAudioEngine.processor.snapshot()
        val effects = ProDspAudioEngine.deviceEffects.snapshot()
        val state = JSONObject()
            .put("bandCount", dsp.bandCount)
            .put("gains", JSONArray(dsp.gainsDb.toList()))
            .put("frequencies", JSONArray(dsp.centerFrequencies.toList()))
            .put("qs", JSONArray(dsp.qValues.toList()))
            .put("types", JSONArray(dsp.bandTypes.map { it.name }))
            .put("bandEnabled", JSONArray(dsp.bandEnabled.toList()))
            .put("soloBand", dsp.soloBandIndex)
            .put("preamp", dsp.preampDb)
            .put("autoHeadroom", dsp.autoHeadroomEnabled)
            .put("enabled", dsp.enabled)
            .put("peakGuard", dsp.peakGuard)
            .put("lowTone", dsp.lowToneDb)
            .put("highTone", dsp.highToneDb)
            .put("lowToneFrequency", dsp.lowToneFrequencyHz)
            .put("highToneFrequency", dsp.highToneFrequencyHz)
            .put("compressorEnabled", dsp.compressorEnabled)
            .put("compressorThreshold", dsp.compressorThresholdDb)
            .put("compressorRatio", dsp.compressorRatio)
            .put("compressorAttack", dsp.compressorAttackMs)
            .put("compressorRelease", dsp.compressorReleaseMs)
            .put("compressorMakeup", dsp.compressorMakeupDb)
            .put("limiterEnabled", dsp.limiterEnabled)
            .put("limiterCeiling", dsp.limiterCeilingDb)
            .put("limiterRelease", dsp.limiterReleaseMs)
            .put("stereoWidth", dsp.stereoWidthPercent)
            .put("stereoBalance", dsp.stereoBalancePercent)
            .put("reverbEnabled", dsp.reverbEnabled)
            .put("reverbWet", dsp.reverbWetPercent)
            .put("reverbRoomSize", dsp.reverbRoomSizePercent)
            .put("reverbDamping", dsp.reverbDampingPercent)
            .put("deviceEffectsEnabled", effects.enabled)
            .put("bassStrength", effects.bassStrength)
            .put("virtualizerStrength", effects.virtualizerStrength)

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATE, state.toString())
            .apply()
    }

    fun restore(context: Context): Boolean {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATE, null) ?: return false

        return runCatching {
            val state = JSONObject(raw)
            val dsp = ProDspAudioEngine.processor
            val effects = ProDspAudioEngine.deviceEffects
            val bandCount = state.optInt("bandCount", 10).coerceIn(1, EqBandLayout.MAX_BANDS)
            val gains = state.optJSONArray("gains") ?: JSONArray()
            val frequencies = state.optJSONArray("frequencies") ?: JSONArray()
            val qs = state.optJSONArray("qs") ?: JSONArray()
            val types = state.optJSONArray("types") ?: JSONArray()
            val enabled = state.optJSONArray("bandEnabled") ?: JSONArray()

            dsp.configureBands(bandCount)
            for (index in 0 until bandCount) {
                val gain = gains.optDouble(index, 0.0).toFloat()
                val frequency = frequencies.optDouble(index, EqBandLayout.frequencyAt(index, bandCount).toDouble()).toFloat()
                val q = qs.optDouble(index, 1.0).toFloat()
                val type = runCatching {
                    BiquadFilterType.valueOf(types.optString(index, "PEAK"))
                }.getOrDefault(BiquadFilterType.PEAK)
                val bandIsEnabled = enabled.optBoolean(index, true)

                dsp.setBandGain(index, gain)
                dsp.setBandFrequency(index, frequency)
                dsp.setBandQ(index, q)
                dsp.setBandType(index, type)
                dsp.setBandEnabled(index, bandIsEnabled)
            }

            dsp.setPreamp(state.optDouble("preamp", 0.0).toFloat())
            dsp.setLowToneGain(state.optDouble("lowTone", 0.0).toFloat())
            dsp.setHighToneGain(state.optDouble("highTone", 0.0).toFloat())
            dsp.setLowToneFrequency(state.optDouble("lowToneFrequency", 120.0).toFloat())
            dsp.setHighToneFrequency(state.optDouble("highToneFrequency", 8_000.0).toFloat())
            dsp.setCompressorThreshold(state.optDouble("compressorThreshold", -18.0).toFloat())
            dsp.setCompressorRatio(state.optDouble("compressorRatio", 3.0).toFloat())
            dsp.setCompressorAttack(state.optDouble("compressorAttack", 10.0).toFloat())
            dsp.setCompressorRelease(state.optDouble("compressorRelease", 120.0).toFloat())
            dsp.setCompressorMakeup(state.optDouble("compressorMakeup", 0.0).toFloat())
            dsp.setLimiterCeiling(state.optDouble("limiterCeiling", -1.0).toFloat())
            dsp.setLimiterRelease(state.optDouble("limiterRelease", 80.0).toFloat())
            dsp.setStereoWidth(state.optDouble("stereoWidth", 100.0).toFloat())
            dsp.setStereoBalance(state.optDouble("stereoBalance", 0.0).toFloat())
            dsp.setReverbWetPercent(state.optDouble("reverbWet", 25.0).toFloat())
            dsp.setReverbRoomSizePercent(state.optDouble("reverbRoomSize", 55.0).toFloat())
            dsp.setReverbDampingPercent(state.optDouble("reverbDamping", 35.0).toFloat())
            dsp.setReverbEnabled(state.optBoolean("reverbEnabled", false))
            dsp.setSoloBand(state.optInt("soloBand", -1))
            dsp.setPeakGuard(state.optBoolean("peakGuard", true))
            dsp.setCompressorEnabled(state.optBoolean("compressorEnabled", false))
            dsp.setLimiterEnabled(state.optBoolean("limiterEnabled", true))
            dsp.setAutoHeadroom(state.optBoolean("autoHeadroom", false))
            dsp.setEnabled(state.optBoolean("enabled", true))

            effects.setBassStrength(state.optInt("bassStrength", 0))
            effects.setVirtualizerStrength(state.optInt("virtualizerStrength", 0))
            effects.setEnabled(state.optBoolean("deviceEffectsEnabled", true))
            true
        }.getOrElse { false }
    }
}
