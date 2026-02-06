# CNC Pendant - Native Android App

A native Kotlin Android app for controlling your CNC machine via ncSender's WebSocket API.

## Project Structure

```
app/
├── src/main/
│   ├── java/com/cncpendant/app/
│   │   ├── MainActivity.kt      # Main UI and logic
│   │   ├── WebSocketManager.kt  # WebSocket connection handling
│   │   └── JogDialView.kt       # Custom dial widget
│   ├── res/
│   │   ├── layout/              # XML layouts
│   │   ├── drawable/            # Button/input backgrounds
│   │   └── values/              # Strings, themes, arrays
│   └── AndroidManifest.xml
├── build.gradle.kts             # App dependencies
└── proguard-rules.pro
```

## Building

1. Open this folder in Android Studio
2. Wait for Gradle sync to complete
3. Build > Build APK or Run on device

## Features

- Native Kotlin implementation
- Custom JogDialView with haptic feedback
- WebSocket connection to ncSender
- Real-time position updates (Machine & Work coordinates)
- Workspace switching (G54-G59)
- Remembers last connected URL

## Requirements

- Android 8.0+ (API 26)
- ncSender running with WebSocket enabled
