# RP2040 Quadrature Encoder for CNC Pendant

Firmware for RP2040-based microcontrollers to read a 100 PPR (pulses per revolution) quadrature encoder and communicate with the Android CNC Pendant app.

## Supported Boards

| Board | LED Type | Firmware File | Button Pins |
|-------|----------|---------------|-------------|
| **Waveshare RP2040-Zero** | RGB NeoPixel (GP16) | `encoder-rp2040zero.uf2` | GP2-15, GP17-28 |
| **Raspberry Pi Pico** | Green LED (GP25) | `encoder-pico.uf2` | GP2-24, GP26-28 |
| **Pimoroni Tiny2040** | RGB LED (GP18-20) | `encoder-tiny2040.uf2` | GP2-7, GP26-28 |

## Quick Start

1. Download the appropriate `.uf2` file from the [Releases](../../releases) page
2. Hold BOOT button while plugging in USB (enters bootloader mode)
3. Drag the `.uf2` file to the `RPI-RP2` drive that appears
4. The board will reboot and start running

## Hardware Connections

| Encoder Wire | RP2040 Pin | Description |
|--------------|------------|-------------|
| A (Channel A) | GP0 | Quadrature signal A |
| B (Channel B) | GP1 | Quadrature signal B |
| VCC | 3.3V | Power (if 3.3V encoder) |
| GND | GND | Ground |

**Note:** The RP2040 has internal pull-up resistors enabled on GP0/GP1. If your encoder has open-collector outputs, no external pull-ups are needed. For push-pull encoders, this still works fine.

## Button Support

The firmware supports up to 12 physical buttons connected to GPIO pins. Buttons should be wired between the GPIO pin and GND (active LOW with internal pull-ups).

### Available GPIO Pins for Buttons
GP2-GP15, GP17-GP22, GP26-GP28 (Pins GP0, GP1 are used by encoder; GP16/GP25 for LED)

### Button Wiring
```
GPIO Pin ---- Button ---- GND
         (normally open)
```

### Configuration
Buttons are configured in the Android app under Settings → Configure Button Functions. For each button you can assign:
- **GPIO Pin**: Which pin the button is connected to
- **Function**: What action to perform (jog, home, zero, etc.)

### Available Functions
- Jog X+/X-/Y+/Y-/Z+/Z-
- Home All/X/Y/Z
- Set Zero All/X/Y/Z
- Feed Hold / Cycle Start / Stop
- Spindle Toggle / Coolant Toggle
- Probe Z
- Select X/Y/Z Axis (for encoder jog)

## Building from Source (PlatformIO)

### Setup in VS Code
1. Install the **PlatformIO IDE** extension in VS Code
2. Open this folder (`rp2040-encoder`) as a PlatformIO project
3. PlatformIO will auto-detect `platformio.ini` and set up the environment

### Build & Upload

**For RP2040-Zero:**
```bash
pio run -e rp2040zero           # Build
pio run -e rp2040zero -t upload # Upload
```

**For Raspberry Pi Pico:**
```bash
pio run -e pico           # Build
pio run -e pico -t upload # Upload
```

**For Pimoroni Tiny2040:**
```bash
pio run -e tiny2040           # Build
pio run -e tiny2040 -t upload # Upload
```

Or use VS Code:
1. Connect board while holding BOOT button (enters bootloader mode)
2. Select the environment (rp2040zero, pico, or tiny2040) in the status bar
3. Click **PlatformIO: Build** (checkmark icon) to compile
4. Click **PlatformIO: Upload** (arrow icon) to flash
5. Open **PlatformIO: Serial Monitor** to see output

### Manual Upload
If automatic upload fails:
1. Hold BOOT, plug in USB, release BOOT
2. The board appears as `RPI-RP2` drive
3. Copy the firmware file:
   - RP2040-Zero: `.pio/build/rp2040zero/firmware.uf2`
   - Pico: `.pio/build/pico/firmware.uf2`
   - Tiny2040: `.pio/build/tiny2040/firmware.uf2`

## CircuitPython Alternative (RP2040-Zero only)

1. **Install CircuitPython on RP2040-Zero:**
   - Download CircuitPython 9.x from: https://circuitpython.org/board/waveshare_rp2040_zero/
   - Hold BOOT button while connecting USB
   - Copy the `.uf2` file to the RPI-RP2 drive that appears
   - The board will reboot as CIRCUITPY drive

2. **Copy the firmware:**
   - Copy `code.py` to the CIRCUITPY drive
   - The board will auto-run the code

3. **Optional - Enable USB Data Serial:**
   - Create `boot.py` with:
     ```python
     import usb_cdc
     usb_cdc.enable(console=True, data=True)
     ```
   - This enables a second USB serial port for data

## Protocol

The encoder sends JSON messages over USB serial at 115200 baud:

### Encoder Movement (RP2040 → Android)
```json
{"type": "encoder", "delta": 1, "position": 42}
```
- `delta`: Number of clicks since last message (+/- indicates direction)
- `position`: Position counter (0-99, wraps at 100)

### Button Events (RP2040 → Android)
```json
{"type": "button", "pin": 2, "state": "pressed"}
{"type": "button", "pin": 2, "state": "released"}
```

### Commands (Android → RP2040)
```json
{"type": "reset", "position": 0}     // Reset position counter
{"type": "ping"}                      // Request status
{"type": "buttons", "pins": [2,3,4]} // Configure button pins
{"type": "clear_buttons"}             // Clear button config
```

### Responses
```json
{"type": "pong", "position": 42}
{"type": "buttons_configured", "count": 3}
{"type": "buttons_cleared"}
```

## Resolution

With a 100 PPR encoder:
- 100 detents (clicks) per full rotation
- 4 quadrature pulses per detent (400 raw pulses/revolution)
- Firmware converts 4 pulses → 1 click
- Position wraps 0-99, matching one full rotation

The Android app maps encoder deltas to jog commands based on your step size setting.

## LED Indicators

**RP2040-Zero (RGB NeoPixel) & Tiny2040 (RGB LED):**
- **Red → Green → Blue**: Startup sequence
- **Green flash**: Encoder movement or button press
- **Blue flash**: Heartbeat (every 2 seconds)

**Raspberry Pi Pico (Green LED):**
- **Blinks on startup**: Firmware initialized
- **Flash on activity**: Encoder movement or button press
- **Periodic flash**: Heartbeat

## Troubleshooting

1. **Upload fails:**
   - Hold BOOT button while plugging in USB
   - Copy firmware `.uf2` to the RPI-RP2 drive manually

2. **Encoder not detected:**
   - Check wiring (A→GP0, B→GP1, GND→GND)
   - Verify encoder VCC is 3.3V (or use level shifter for 5V encoders)

3. **Position jumps/skips:**
   - Reduce encoder rotation speed
   - Check for loose connections

4. **Buttons not working:**
   - Check button is wired between GPIO pin and GND
   - Ensure pin is configured in the Android app settings
   - Verify button function is assigned (not "None")

5. **Android app doesn't see encoder:**
   - Make sure USB OTG is supported on your device
   - Grant USB permission when prompted
   - Check the encoder status in the app UI

## Project Structure

```
rp2040-encoder/
├── platformio.ini      # PlatformIO config (supports both boards)
├── src/
│   └── main.cpp        # Main firmware code
├── code.py             # CircuitPython alternative (RP2040-Zero only)
├── boot.py             # CircuitPython USB config
└── README.md           # This file
```
