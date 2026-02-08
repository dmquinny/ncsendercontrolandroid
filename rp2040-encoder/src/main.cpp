/**
 * RP2040 Quadrature Encoder Reader
 * PlatformIO / Arduino Framework
 * 
 * Supports: Raspberry Pi Pico, Waveshare RP2040-Zero
 * Connect: Encoder A -> GP0, Encoder B -> GP1, GND -> GND
 * 
 * Sends JSON messages over USB serial when encoder rotates:
 * {"type":"encoder","delta":1,"position":123}
 */

#include <Arduino.h>

// Board detection - RP2040-Zero has NeoPixel, Pico has regular LED
#ifdef BOARD_RP2040_ZERO
    #include <Adafruit_NeoPixel.h>
    #define HAS_NEOPIXEL 1
    const uint8_t LED_PIN = 16;  // WS2812 on GP16
    Adafruit_NeoPixel led(1, LED_PIN, NEO_GRB + NEO_KHZ800);
    const char* DEVICE_NAME = "RP2040-Zero";
#else
    #define HAS_NEOPIXEL 0
    const uint8_t LED_PIN = 25;  // Built-in LED on standard Pico (active high)
    const char* DEVICE_NAME = "Pico";
#endif

// Encoder pins
const uint8_t PIN_A = 0;   // GP0
const uint8_t PIN_B = 1;   // GP1

// LED colors
const uint32_t COLOR_OFF = 0x000000;
const uint32_t COLOR_GREEN = 0x00FF00;   // Encoder movement
const uint32_t COLOR_BLUE = 0x0000FF;    // Heartbeat
const uint32_t COLOR_RED = 0xFF0000;     // Startup

// LED state
unsigned long ledOffTime = 0;

// Encoder state (volatile for ISR access)
volatile long encoderPosition = 0;      // Position in physical clicks
volatile int8_t lastEncoded = 0;
volatile int accumulatedPulses = 0;     // Raw pulses (4 per click)
volatile int accumulatedClicks = 0;     // Clicks to send (after /4)

// Timing
unsigned long lastSendTime = 0;
unsigned long lastHeartbeatTime = 0;
const unsigned long SEND_INTERVAL_MS = 50;      // 20Hz update rate for encoder data
const unsigned long HEARTBEAT_INTERVAL_MS = 2000; // Heartbeat every 2 seconds

// Command buffer
String inputBuffer = "";

// State table for quadrature decoding
// Index = (lastState << 2) | currentState
// Values: 0 = no change, 1 = CW, -1 = CCW
const int8_t ENCODER_TABLE[16] = {
     0,  // 00 -> 00: no change
     1,  // 00 -> 01: CW
    -1,  // 00 -> 10: CCW
     0,  // 00 -> 11: invalid (skip)
    -1,  // 01 -> 00: CCW
     0,  // 01 -> 01: no change
     0,  // 01 -> 10: invalid (skip)
     1,  // 01 -> 11: CW
     1,  // 10 -> 00: CW
     0,  // 10 -> 01: invalid (skip)
     0,  // 10 -> 10: no change
    -1,  // 10 -> 11: CCW
     0,  // 11 -> 00: invalid (skip)
    -1,  // 11 -> 01: CCW
     1,  // 11 -> 10: CW
     0   // 11 -> 11: no change
};

// Interrupt handler for encoder
void encoderISR() {
    uint8_t a = digitalRead(PIN_A);
    uint8_t b = digitalRead(PIN_B);
    int8_t encoded = (a << 1) | b;
    
    int8_t index = (lastEncoded << 2) | encoded;
    int8_t delta = ENCODER_TABLE[index];
    
    if (delta != 0) {
        // Invert direction and accumulate raw pulses
        accumulatedPulses -= delta;
        
        // Convert to clicks (4 pulses = 1 physical click)
        while (accumulatedPulses >= 4) {
            accumulatedPulses -= 4;
            encoderPosition = (encoderPosition + 1) % 100;
            accumulatedClicks++;
        }
        while (accumulatedPulses <= -4) {
            accumulatedPulses += 4;
            encoderPosition = (encoderPosition + 99) % 100;  // +99 mod 100 = -1
            accumulatedClicks--;
        }
    }
    
    lastEncoded = encoded;
}

void sendEncoderData(int delta, long position) {
    Serial.print("{\"type\":\"encoder\",\"delta\":");
    Serial.print(delta);
    Serial.print(",\"position\":");
    Serial.print(position);
    Serial.println("}");
}

void sendPong(long position) {
    Serial.print("{\"type\":\"pong\",\"position\":");
    Serial.print(position);
    Serial.println("}");
}

void sendReady() {
    Serial.print("{\"type\":\"ready\",\"device\":\"");
    Serial.print(DEVICE_NAME);
    Serial.println("\",\"encoder\":\"100PPR\",\"pins\":{\"a\":0,\"b\":1}}");
}

void handleCommand(const String& line) {
    // Simple JSON command parsing
    if (line.indexOf("\"type\":\"reset\"") >= 0) {
        // Reset position counter
        noInterrupts();
        int posIdx = line.indexOf("\"position\":");
        if (posIdx >= 0) {
            int startIdx = posIdx + 11;
            int endIdx = line.indexOf('}', startIdx);
            if (endIdx < 0) endIdx = line.length();
            String posStr = line.substring(startIdx, endIdx);
            int commaIdx = posStr.indexOf(',');
            if (commaIdx >= 0) posStr = posStr.substring(0, commaIdx);
            posStr.trim();
            encoderPosition = posStr.toInt();
        } else {
            encoderPosition = 0;
        }
        accumulatedPulses = 0;
        accumulatedClicks = 0;
        interrupts();
        
        sendEncoderData(0, encoderPosition);
    }
    else if (line.indexOf("\"type\":\"ping\"") >= 0) {
        sendPong(encoderPosition);
    }
}

void sendHeartbeat() {
    Serial.print("{\"type\":\"heartbeat\",\"position\":");
    Serial.print(encoderPosition);
    Serial.print(",\"pinA\":");
    Serial.print(digitalRead(PIN_A));
    Serial.print(",\"pinB\":");
    Serial.print(digitalRead(PIN_B));
    Serial.println("}");
}

void setLed(uint32_t color) {
#if HAS_NEOPIXEL
    led.setPixelColor(0, color);
    led.show();
#else
    // For regular LED: on if any color component is set
    digitalWrite(LED_PIN, color != 0 ? HIGH : LOW);
#endif
}

void flashLed(uint32_t color, unsigned long durationMs) {
    setLed(color);
    ledOffTime = millis() + durationMs;
}

void setup() {
#if HAS_NEOPIXEL
    // Initialize RGB LED
    led.begin();
    led.setBrightness(30);  // Dim (0-255) - these LEDs are bright!
#else
    // Initialize regular LED
    pinMode(LED_PIN, OUTPUT);
#endif
    setLed(COLOR_RED);
    
    // Initialize encoder pins with pull-ups
    pinMode(PIN_A, INPUT_PULLUP);
    pinMode(PIN_B, INPUT_PULLUP);
    
    // Read initial encoder state
    lastEncoded = (digitalRead(PIN_A) << 1) | digitalRead(PIN_B);
    
    // Attach interrupts to both encoder pins
    attachInterrupt(digitalPinToInterrupt(PIN_A), encoderISR, CHANGE);
    attachInterrupt(digitalPinToInterrupt(PIN_B), encoderISR, CHANGE);
    
    // Initialize USB Serial
    Serial.begin(115200);
    
    // Startup blink: red -> green -> blue
    delay(200);
    setLed(COLOR_GREEN);
    delay(200);
    setLed(COLOR_BLUE);
    delay(200);
    setLed(COLOR_OFF);
    
    // Wait for serial connection (with timeout)
    unsigned long startWait = millis();
    while (!Serial && (millis() - startWait < 5000)) {
        delay(10);
    }
    
    // Send ready message
    delay(500); // Give serial time to stabilize
    sendReady();
}

void loop() {
    unsigned long now = millis();
    
    // Turn off LED after flash duration
    if (ledOffTime > 0 && now >= ledOffTime) {
        setLed(COLOR_OFF);
        ledOffTime = 0;
    }
    
    // Send accumulated encoder data at regular intervals
    if (accumulatedClicks != 0 && (now - lastSendTime) >= SEND_INTERVAL_MS) {
        noInterrupts();
        int clicks = accumulatedClicks;
        long pos = encoderPosition;
        accumulatedClicks = 0;
        interrupts();
        
        sendEncoderData(clicks, pos);
        lastSendTime = now;
        
        // Flash green on encoder movement
        flashLed(COLOR_GREEN, 50);
    }
    
    // Send heartbeat periodically so we know the device is alive
    if ((now - lastHeartbeatTime) >= HEARTBEAT_INTERVAL_MS) {
        sendHeartbeat();
        lastHeartbeatTime = now;
        
        // Brief blue flash on heartbeat (only if not already flashing)
        if (ledOffTime == 0) {
            flashLed(COLOR_BLUE, 100);
        }
    }
    
    // Process incoming serial commands
    while (Serial.available() > 0) {
        char c = Serial.read();
        if (c == '\n' || c == '\r') {
            if (inputBuffer.length() > 0) {
                handleCommand(inputBuffer);
                inputBuffer = "";
            }
        } else {
            inputBuffer += c;
            // Prevent buffer overflow
            if (inputBuffer.length() > 256) {
                inputBuffer = "";
            }
        }
    }
}
