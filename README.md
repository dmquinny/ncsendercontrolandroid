# CNC Pendant - Native Android App

A native Kotlin Android app for controlling your CNC machine via ncSender's WebSocket API.

## Project Structure

```
app/
 src/main/
    java/com/cncpendant/app/
       MainActivity.kt      # Main UI and logic
       WebSocketManager.kt  # WebSocket connection handling
       JogDialView.kt       # Custom dial widget
       UsbEncoderManager.kt # USB encoder support
    res/
       layout/              # XML layouts
       drawable/            # Button/input backgrounds
       xml/                 # USB device filter
       values/              # Strings, themes, arrays
    AndroidManifest.xml
 build.gradle.kts             # App dependencies
 proguard-rules.pro

rp2040-encoder/                  # RP2040 Zero encoder firmware
 code.py                      # CircuitPython firmware
 boot.py                      # USB serial config
 encoder.ino                  # Arduino/C++ alternative
 README.md                    # Setup instructions
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
- **USB Hardware Encoder Support** - Connect an RP2040 Zero with a 100 PPR rotary encoder for physical jog control

## USB Encoder Setup

### Hardware Required
- Waveshare RP2040 Zero (or similar RP2040 board)
- 100 PPR rotary encoder (quadrature output)
- USB-C to USB-A/C OTG cable for Android

### Wiring
| Encoder | RP2040 Zero |
|---------|-------------|
| A       | GP0         |
| B       | GP1         |
| GND     | GND         |
| VCC     | 3.3V        |

### Firmware Installation
1. Install CircuitPython on the RP2040 Zero
2. Copy `rp2040-encoder/boot.py` and `rp2040-encoder/code.py` to CIRCUITPY drive
3. The board will auto-run and appear as a USB serial device

### Usage
1. Connect the RP2040 encoder to your Android device via USB OTG
2. Grant USB permission when prompted
3. Select an axis (X/Y/Z) in the app
4. Rotate the encoder to jog the selected axis
5. Step size and feed rate are controlled by the app settings

## Requirements

- Android 8.0+ (API 26)
- ncSender running with WebSocket enabled
- (Optional) RP2040 Zero with encoder for hardware jog control
