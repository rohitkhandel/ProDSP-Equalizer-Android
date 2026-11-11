# ProDSP Equalizer

An Android audio player and equalizer built with Kotlin, Jetpack Compose and AndroidX Media3.

## Current implementation
- Local audio file selection through Android's document picker
- Playback using ExoPlayer
- Android native Equalizer attached to the app's playback audio session
- Native Bass Boost and Virtualizer when supported by the device
- Dynamic EQ bands based on the device's native audio effect
- Flat, Bass, Vocal and Treble presets
- Output/preamp level control
- GitHub Actions debug APK build

## Compatibility
Android audio effects are device- and audio-session-dependent. This app applies effects to its own playback session. It does **not** guarantee processing of Spotify, YouTube Music, Bluetooth routes, or every other app. System-wide effects depend on Android/OEM support and are not available through a universal public API.

The current EQ uses Android's native Equalizer, so the number of bands and frequency response are determined by the device. Parametric EQ, custom biquad processing, band-overlap controls, per-app profiles, AutoEQ, limiter/compressor and background media-session controls are not yet implemented.

## Build
Requirements: JDK 17, Android SDK 35, Gradle 8.9.

```bash
gradle assembleDebug
```

The debug APK is uploaded as a GitHub Actions artifact after a successful workflow run.
