# ProDSP Equalizer

ProDSP is an Android-local music player and parametric EQ project.

## Current engineering scope

- Local audio selection and playback through Media3/ExoPlayer.
- A custom PCM16 audio-processing stage on this app's playback path.
- A shared logarithmic EQ band layout used by the UI and DSP engine.
- Up to 31 active parametric peaking filters with adjustable gain, frequency and Q.
- Low- and high-shelf tone controls.
- Preamp, compressor, output limiter and peak guard.
- 48-band spectrum display using a 2048-point FFT.
- Preset storage on the device.

## Important limitations

This project is not yet feature-equivalent to Poweramp Equalizer. The current application is an in-app player/equalizer, not a universal system-wide effect. Processing of Spotify, YouTube Music and other third-party players is not guaranteed. Android audio effects vary by device and audio route.

The PCM processor currently accepts PCM16 mono/stereo output. A production-grade release still needs broader format/device validation, DSP response tests, a more complete player/media-session experience, robust preset import/export, advanced filter types, channel controls and device-level testing.

## Build and verification

Requirements: JDK 17, Android SDK 35, Gradle 8.9.

```bash
gradle testDebugUnitTest assembleDebug
```

GitHub Actions runs the local unit tests before packaging the debug APK.
