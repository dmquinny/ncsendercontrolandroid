/**
 * RP2040 Zero Quadrature Encoder Reader - Arduino/C++ Version
 * 
 * For Arduino IDE with Raspberry Pi Pico/RP2040 board support
 * 
 * Board: "Raspberry Pi Pico" or "Waveshare RP2040-Zero"
 * USB Stack: "Adafruit TinyUSB" or "Pico SDK"
 * 
 * Connect: Encoder A -> GP0, Encoder B -> GP1, GND -> GND
 * 
 * Sends JSON messages over USB serial when encoder rotates:
 * {"type":"encoder","delta":1,"position":123}
 */

#include <Arduino.h>

// Encoder pins
const int PIN_A = 0;  // GP0
const int PIN_B = 1;  // GP1
const int PIN_LED = 25; // Onboard LED (GP25 on most RP2040 boards)

// Encoder state
volatile long encoderPosition = 0;
volatile int lastEncoded = 0;
volatile int accumulatedDelta = 0;

// Timing
unsigned long lastSendTime = 0;
const unsigned long SEND_INTERVAL_MS = 50; // Send updates every 50ms

// State table for quadrature decoding (same as Python version)
const int8_t ENCODER_TABLE[16] = {
    0,   // 00 -> 00
    1,   // 00 -> 01: CW
    -1,  // 00 -> 10: CCW
    0,   // 00 -> 11: invalid
    -1,  // 01 -> 00: CCW
    0,   // 01 -> 01
    0,   // 01 -> 10: invalid
    1,   // 01 -> 11: CW
    1,   // 10 -> 00: CW
    0,   // 10 -> 01: invalid
    0,   // 10 -> 10
    -1,  // 10 -> 11: CCW
    0,   // 11 -> 00: invalid
    -1,  // 11 -> 01: CCW
    1,   // 11 -> 10: CW
    0    // 11 -> 11
};

void encoderISR() {
    int a = digitalRead(PIN_A);
    int b = digitalRead(PIN_B);
    int encoded = (a << 1) | b;
    
    int index = (lastEncoded << 2) | encoded;
    int delta = ENCODER_TABLE[index];
    
    if (delta != 0) {
        encoderPosition += delta;
        accumulatedDelta += delta;
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

void handleCommand(String& line) {
    // Simple JSON parsing for commands
    if (line.indexOf("\"type\":\"reset\"") >= 0) {
        // Reset position
        int posIdx = line.indexOf("\"position\":");
        if (posIdx >= 0) {
            int startIdx = posIdx + 11;
            int endIdx = line.indexOf('}', startIdx);
            if (endIdx < 0) endIdx = line.length();
            String posStr = line.substring(startIdx, endIdx);
            posStr.trim();
            // Remove trailing comma if present
            int commaIdx = posStr.indexOf(',');
            if (commaIdx >= 0) posStr = posStr.substring(0, commaIdx);
            encoderPosition = posStr.toInt();
        } else {
            encoderPosition = 0;
        }
        accumulatedDelta = 0;
        sendEncoderData(0, encoderPosition);
        // Flash LED
        digitalWrite(PIN_LED, HIGH);
        delay(100);
        digitalWrite(PIN_LED, LOW);
    }
    else if (line.indexOf("\"type\":\"ping\"") >= 0) {
        Serial.print("{\"type\":\"pong\",\"position\":");
        Serial.print(encoderPosition);
        Serial.println("}");
    }
    else if (line.indexOf("\"type\":\"led\"") >= 0) {
        bool ledOn = line.indexOf("\"on\":true") >= 0;
        digitalWrite(PIN_LED, ledOn ? HIGH : LOW);
    }
}

void setup() {
    // Initialize pins
    pinMode(PIN_A, INPUT_PULLUP);
    pinMode(PIN_B, INPUT_PULLUP);
    pinMode(PIN_LED, OUTPUT);
    
    // Initialize encoder state
    lastEncoded = (digitalRead(PIN_A) << 1) | digitalRead(PIN_B);
    
    // Attach interrupts for both pins (CHANGE = rising and falling edges)
    attachInterrupt(digitalPinToInterrupt(PIN_A), encoderISR, CHANGE);
    attachInterrupt(digitalPinToInterrupt(PIN_B), encoderISR, CHANGE);
    
    // Initialize USB serial
    Serial.begin(115200);
    while (!Serial) {
        delay(10); // Wait for serial connection
    }
    
    // Startup blink
    for (int i = 0; i < 3; i++) {
        digitalWrite(PIN_LED, HIGH);
        delay(100);
        digitalWrite(PIN_LED, LOW);
        delay(100);
    }
    
    Serial.println("{\"type\":\"ready\",\"message\":\"RP2040 Encoder Ready - 100 PPR on GP0/GP1\"}");
}

void loop() {
    // Send accumulated encoder data periodically
    unsigned long now = millis();
    if (accumulatedDelta != 0 && (now - lastSendTime) >= SEND_INTERVAL_MS) {
        noInterrupts();
        int delta = accumulatedDelta;
        long pos = encoderPosition;
        accumulatedDelta = 0;
        interrupts();
        
        sendEncoderData(delta, pos);
        lastSendTime = now;
        
        // Brief LED flash
        digitalWrite(PIN_LED, HIGH);
        delayMicroseconds(1000);
        digitalWrite(PIN_LED, LOW);
    }
    
    // Check for incoming commands
    if (Serial.available() > 0) {
        String line = Serial.readStringUntil('\n');
        line.trim();
        if (line.length() > 0) {
            handleCommand(line);
        }
    }
}
