# Phantom

A dark, turntable-style audio recorder for Android (Kotlin + Jetpack Compose).

## Features
- **Record button** (yellow equalizer, top centre): start/stop recording with AAC/M4A output
- **Twin turntable reels** that spin up with your voice level, plus pulsing rings and a reactive glow
- **Live scrolling waveform** with a playhead; **bookmark** (top right of card) drops markers while recording
- **Library** (waveform button, top left of card): browse, play, delete recordings
- **Volume** (bottom left): cycles 100% / 50% / mute for playback
- **Play / pause** (bottom right) the current recording; waveform follows playback
- **Output indicator** shows the active route (speaker, headphones, or Bluetooth device name)
- **Quality menu** (chevron, top right): High 256 kbps / Standard 96 kbps
- **Haptics**: start/stop, bookmark, button ticks, and level-reactive pulses
- **Immersive mode** while recording (system bars hidden, screen kept awake)

Recordings are saved in `Android/data/com.phantom.recorder/files/Music/`.

## Build
**GitHub:** push to `main` and download `Phantom.apk` from the Actions artifact. Tag `v1.0.0` to publish a Release.

**Local:** `gradle assembleDebug` (Gradle 8.7, JDK 17) or open in Android Studio.
APK: `app/build/outputs/apk/debug/app-debug.apk`
