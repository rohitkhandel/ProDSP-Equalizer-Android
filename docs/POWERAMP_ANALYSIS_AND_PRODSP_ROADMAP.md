# Poweramp Equalizer analysis and ProDSP roadmap

## Scope and limits

The supplied `Poweramp Equalizer.zip` is an Android application package, not an original source project. It contains DEX bytecode, compiled Android resources, and native shared libraries. The native PEQ library is stripped. This allows package/resource inspection and high-level architectural inferences, but it does not expose the original source implementation or prove the exact DSP algorithms used internally.

This document records observations from that package and publicly documented Poweramp/Android behaviour. It is an engineering reference for an independent implementation. It does not copy Poweramp binaries, proprietary source, artwork, or its embedded AutoEQ database.

## What the supplied package reveals

### Native and app structure

The archive contains ARM64 and ARM32 builds of `libpeqcore.so`, plus `libffmpeg_min.so`. DEX class names and native strings identify components including:

- `PeqMainActivity`, `PeqService`, and `PeqStartupActivity`
- `PeqSessionReceiver` and `PeqNotificationListener`
- `PeqNativeUtils` and `PeqCoreConnectionBehavior`
- `EqPresetsActivity`, `EqPresetAssignActivity`, and preset-search widgets
- Native plugin registration entry points such as `internal_register_dsp` and `pa_register_dsp`
- Native EQ-related strings for PEQ, low-shelf, high-shelf, gain, centre frequency, Q, and frequency-response evaluation

This is evidence of a native DSP component connected to an Android app/session-management layer. Because the binary is stripped, the exact signal-flow internals, coefficient interpolation, oversampling, and precision choices of this particular Equalizer build cannot be established from strings alone.

### Session and output integration

The package references Android audio-effect/session APIs, media sessions, playback notifications, Bluetooth devices, and output routing. Android's public `AudioEffect` contract defines open/close audio-effect session intents carrying the player package name and audio-session ID. This is the standard integration point available to a separate effects-control app when a player exposes its session.

A separate effect app cannot assume that every player or ROM exposes an attachable session. Global output effects are device/firmware dependent; session-based effects are the more portable Android model. ProDSP must therefore report which path is active and must not claim universal system-wide processing until tested.

### AutoEQ asset

The APK embeds an SQLite database named `auto_eq.db`. Its `eq_presets` table stores a display name, measurement/source metadata, a graphic/parametric flag, and a compact binary payload.

Inspection of this copy found:

- 13,967 rows
- 4,900 distinct profile names
- 46 distinct measurement/source metadata labels
- 6,985 graphic rows and 6,982 parametric rows

Examples of source labels include Crinacle, Innerfidelity, Super Review, Oratory1990, and Rtings. These are package-specific observations, not a recommendation to copy the database. ProDSP will use its own profile schema and only data that can be lawfully sourced and redistributed.

## Poweramp interaction model to learn from

The provided UI reference and official product material point to a compact, control-first EQ screen rather than a settings dashboard:

1. A left-side action rail for EQ processing, limiting, and visualization-related controls.
2. The main EQ surface occupies most of the screen.
3. Graphic mode uses a horizontal run of vertical frequency faders plus a preamp fader.
4. A response/spectrum strip sits immediately below the faders.
5. Bass and treble are exposed as large rotary controls near the lower area.
6. Parametric mode presents each band as its own control group, with gain, frequency, Q, filter type, and channel selection.
7. Preset selection and output/device assignment are separate workflows, not one giant list mixed with playback controls.
8. A compact bottom navigation and player/status area keeps the primary controls visible.

The exact skin and button configuration can vary. ProDSP should copy the interaction principles and layout hierarchy, not use unrelated card-heavy screens or a web-dashboard structure.

## DSP behaviour and architecture reference

The public Poweramp site describes its player engine as a native pipeline with separate decoder, DSP, and output work, with high-precision EQ processing and dynamic output reconfiguration. That information describes the Poweramp player product and should not be silently attributed to every detail of the separate Equalizer APK.

For ProDSP's own player, the current signal path is:

`PCM input → low-shelf → parametric biquad cascade → high-shelf → preamp/headroom → stereo width/balance → reverb → compressor → peak guard → linked limiter → bypass crossfade → PCM output`

The engine already includes RBJ-style peaking/shelf/pass filters, per-channel filter state, smoothed coefficient updates, auto-headroom, stereo processing, reverb, compressor, and linked peak limiting. Before this update, the Media3 processor accepted only signed 16-bit PCM and used Float coefficients/state for EQ sections.

## ProDSP engineering priorities

### Implemented in this analysis milestone

- The biquad EQ calculation and per-channel delay state now use Double precision.
- The Media3 processor accepts PCM Float as well as PCM16.
- The local player requests Float output from Media3, while retaining PCM16 as a supported fallback.
- Unit tests exercise both PCM formats and verify a real parametric boost.

This is an original implementation choice informed by the high-precision processing described in Poweramp's public player documentation. It does not mean ProDSP uses Poweramp's DSP.

### Next milestones

1. **Poweramp-shaped native UI:** a compact EQ-first screen, left action rail, graphic faders, response display, Bass/Treble knobs, and a separately navigated parametric editor.
2. **Band model:** support a configurable 5–32 band layout for the Equalizer-app target, with independent parametric filter types, frequency, gain, Q, and per-band enable/lock state.
3. **Preset system:** separate built-in, user, imported, and headphone AutoEQ profiles; support search, measurement-source labels, favourites, locking, autosave, and safe schema versioning.
4. **Output/device assignment:** store independent profiles for wired, Bluetooth, USB, and speaker routes, keyed only by stable device identifiers available to Android.
5. **External-player compatibility:** build a distinct Android audio-session/effect integration layer, clearly distinguish supported session effects from the app's own PCM path, and test player/ROM compatibility.
6. **Real-time quality:** keep allocations and blocking work out of the audio callback, test filter stability at sample-rate changes, and validate clipping/headroom with deterministic signal tests.
7. **Physical-device testing:** validate on actual Android devices and audio routes. CI proves compilation and unit tests; it does not prove a phone's audio HAL or third-party player compatibility.

## Current limitations that remain

- ProDSP's custom PCM processor currently processes audio in its own Media3 player. It is not yet a universal system-wide DSP.
- The native Android BassBoost/Virtualizer controls are optional platform effects and may differ by device.
- Float output availability depends on the Media3/audio-device path; the processor keeps a PCM16 fallback.
- The APK-only reference does not reveal Poweramp's exact internal native algorithm or source-level UI implementation.
