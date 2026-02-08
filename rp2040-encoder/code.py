# RP2040 Zero Quadrature Encoder Reader
# CircuitPython firmware for 100 PPR rotary encoder
# Connect: Encoder A -> GP0, Encoder B -> GP1, GND -> GND
#
# Sends JSON messages over USB serial when encoder rotates:
# {"type":"encoder","delta":1,"position":123}
#
# Install: Copy this file to the CIRCUITPY drive as code.py

import board
import digitalio
import usb_cdc
import json
import time

# Encoder pins - using GP0 (A) and GP1 (B)
pin_a = digitalio.DigitalInOut(board.GP0)
pin_a.direction = digitalio.Direction.INPUT
pin_a.pull = digitalio.Pull.UP

pin_b = digitalio.DigitalInOut(board.GP1)
pin_b.direction = digitalio.Direction.INPUT
pin_b.pull = digitalio.Pull.UP

# Optional: LED on GP25 for status indication
led = digitalio.DigitalInOut(board.GP25)
led.direction = digitalio.Direction.OUTPUT
led.value = False

# Encoder state
position = 0
last_a = pin_a.value
last_b = pin_b.value

# For accumulating deltas before sending (reduces USB traffic)
accumulated_delta = 0
last_send_time = time.monotonic()
SEND_INTERVAL = 0.05  # Send updates every 50ms max (20Hz)

# State table for quadrature decoding
# Current state = (A << 1) | B
# Transitions: 00->01->11->10->00 = clockwise (+1)
# Transitions: 00->10->11->01->00 = counter-clockwise (-1)
ENCODER_TABLE = [
    0,   # 00 -> 00: no change
    1,   # 00 -> 01: CW
    -1,  # 00 -> 10: CCW
    0,   # 00 -> 11: invalid (skip)
    -1,  # 01 -> 00: CCW
    0,   # 01 -> 01: no change
    0,   # 01 -> 10: invalid (skip)
    1,   # 01 -> 11: CW
    1,   # 10 -> 00: CW
    0,   # 10 -> 01: invalid (skip)
    0,   # 10 -> 10: no change
    -1,  # 10 -> 11: CCW
    0,   # 11 -> 00: invalid (skip)
    -1,  # 11 -> 01: CCW
    1,   # 11 -> 10: CW
    0,   # 11 -> 11: no change
]

last_state = (last_a << 1) | last_b

def send_encoder_data(delta, pos):
    """Send encoder data as JSON over USB serial"""
    if usb_cdc.data:
        try:
            msg = {"type": "encoder", "delta": delta, "position": pos}
            usb_cdc.data.write((json.dumps(msg) + "\n").encode())
        except Exception:
            pass  # Ignore write errors

def check_for_commands():
    """Check for incoming commands from Android"""
    if usb_cdc.data and usb_cdc.data.in_waiting > 0:
        try:
            line = usb_cdc.data.readline().decode().strip()
            if line:
                cmd = json.loads(line)
                handle_command(cmd)
        except Exception:
            pass

def handle_command(cmd):
    """Handle commands from Android"""
    global position
    
    cmd_type = cmd.get("type", "")
    
    if cmd_type == "reset":
        # Reset encoder position to 0 or specified value
        position = cmd.get("position", 0)
        send_encoder_data(0, position)
        # Flash LED to confirm
        led.value = True
        time.sleep(0.1)
        led.value = False
        
    elif cmd_type == "ping":
        # Respond to ping with current state
        msg = {"type": "pong", "position": position}
        if usb_cdc.data:
            usb_cdc.data.write((json.dumps(msg) + "\n").encode())
            
    elif cmd_type == "led":
        # Control LED
        led.value = cmd.get("on", False)

# Startup flash
for _ in range(3):
    led.value = True
    time.sleep(0.1)
    led.value = False
    time.sleep(0.1)

print("RP2040 Encoder Ready - 100 PPR on GP0/GP1")

# Main loop - poll encoder as fast as possible
while True:
    # Read current encoder state
    a = pin_a.value
    b = pin_b.value
    current_state = (a << 1) | b
    
    # Look up direction from state transition
    if current_state != last_state:
        index = (last_state << 2) | current_state
        delta = ENCODER_TABLE[index]
        
        if delta != 0:
            position += delta
            accumulated_delta += delta
            # Brief LED flash on movement
            led.value = True
        
        last_state = current_state
    else:
        led.value = False
    
    # Send accumulated deltas periodically (reduces USB traffic)
    now = time.monotonic()
    if accumulated_delta != 0 and (now - last_send_time) >= SEND_INTERVAL:
        send_encoder_data(accumulated_delta, position)
        accumulated_delta = 0
        last_send_time = now
    
    # Check for incoming commands (non-blocking)
    check_for_commands()
