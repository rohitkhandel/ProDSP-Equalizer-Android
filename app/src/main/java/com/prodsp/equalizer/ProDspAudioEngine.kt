package com.prodsp.equalizer

/**
 * Process-wide audio engine shared by the playback service and the UI.
 *
 * The service owns the lifetime of the player; the processor and optional
 * platform effects remain the single source of truth for live DSP controls.
 */
object ProDspAudioEngine {
    val processor: ParametricDspProcessor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ParametricDspProcessor()
    }

    val deviceEffects: AudioEffectsEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AudioEffectsEngine()
    }
}
