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
- Named preset storage with import/export.
- Automatic, debounced persistence of the active DSP settings.
- Foreground MediaSession playback service with lock-screen and notification controls.

## Important limitations

This project is not yet feature-equivalent to Poweramp Equalizer. The current application is an in-app player/equalizer, not a universal system-wide effect. Processing of Spotify, YouTube Music and other third-party players is not guaranteed. Android audio effects vary by device and audio route.

The PCM processor currently accepts PCM16 mono/stereo output. Playback is owned by a MediaSession service and can continue while the UI is backgrounded. The app still needs broader format/device validation, more extensive listening tests, independent channel EQ and device-level testing. Third-party player/system-wide processing is not currently guaranteed.

## Build and verification

Requirements: JDK 17, Android SDK 35, Gradle 8.9.

```bash
gradle testDebugUnitTest assembleDebug
```

GitHub Actions runs the local unit tests before packaging the debug APK.
