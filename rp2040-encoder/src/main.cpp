/**
 * RP2040 Quadrature Encoder Reader with Button Support
 * PlatformIO / Arduino Framework
 * 
 * Supports: Raspberry Pi Pico, Waveshare RP2040-Zero, Pimoroni Tiny2040
 * Connect: Encoder A -> GP0, Encoder B -> GP1, GND -> GND
 * 
 * Sends JSON messages over USB serial when encoder rotates:
 * {"type":"encoder","delta":1,"position":123}
 * 
 * Button events:
 * {"type":"button","pin":2,"state":"pressed"}
 * {"type":"button","pin":2,"state":"released"}
 */

#include <Arduino.h>

// Board detection for LED type
#if defined(BOARD_RP2040_ZERO)
    // RP2040-Zero: WS2812 NeoPixel on GP16
    #include <Adafruit_NeoPixel.h>
    #define LED_TYPE_NEOPIXEL 1
    #define LED_TYPE_RGB 0
    #define LED_TYPE_SINGLE 0
    const uint8_t LED_PIN = 16;
    Adafruit_NeoPixel led(1, LED_PIN, NEO_GRB + NEO_KHZ800);
    const char* DEVICE_NAME = "RP2040-Zero";
#elif defined(BOARD_TINY2040)
    // Tiny2040: RGB LED on GP18(R), GP19(G), GP20(B) - active LOW
    #define LED_TYPE_NEOPIXEL 0
    #define LED_TYPE_RGB 1
    #define LED_TYPE_SINGLE 0
    const uint8_t LED_PIN_R = 18;
    const uint8_t LED_PIN_G = 19;
    const uint8_t LED_PIN_B = 20;
    const uint8_t LED_PIN = 18;  // Primary for blocking (all 3 are blocked)
    const char* DEVICE_NAME = "Tiny2040";
#else
    // Pico: Single green LED on GP25 (active HIGH)
    #define LED_TYPE_NEOPIXEL 0
    #define LED_TYPE_RGB 0
    #define LED_TYPE_SINGLE 1
    const uint8_t LED_PIN = 25;
    const char* DEVICE_NAME = "Pico";
#endif

// Encoder pins
const uint8_t PIN_A = 0;   // GP0
const uint8_t PIN_B = 1;   // GP1

// ==================== BUTTON CONFIGURATION ====================
const uint8_t MAX_BUTTONS = 12;
const unsigned long DEBOUNCE_MS = 50;  // Debounce time in milliseconds

struct ButtonState {
    uint8_t pin;           // GPIO pin number (0 = not configured)
    bool enabled;          // Is this button configured?
    bool lastState;        // Last stable state (true = pressed, active LOW)
    bool currentReading;   // Current raw reading
    unsigned long lastDebounceTime;  // Last time the reading changed
};

ButtonState buttons[MAX_BUTTONS];
uint8_t numConfiguredButtons = 0;

// ==============================================================

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
unsigned long lastCharTime = 0;
const unsigned long COMMAND_TIMEOUT_MS = 100;  // Process after 100ms of no input

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
    Serial.print("\",\"encoder\":\"100PPR\",\"maxButtons\":");
    Serial.print(MAX_BUTTONS);
    Serial.println(",\"pins\":{\"a\":0,\"b\":1}}");
}

// Send button state change
void sendButtonEvent(uint8_t pin, bool pressed) {
    Serial.print("{\"type\":\"button\",\"pin\":");
    Serial.print(pin);
    Serial.print(",\"state\":\"");
    Serial.print(pressed ? "pressed" : "released");
    Serial.println("\"}");
}

// Check if a pin is reserved (encoder or LED pins)
bool isPinReserved(uint8_t pin) {
    // Encoder pins always reserved
    if (pin == PIN_A || pin == PIN_B) return true;
    
#if LED_TYPE_RGB
    // Tiny2040 RGB LED uses 3 pins
    if (pin == LED_PIN_R || pin == LED_PIN_G || pin == LED_PIN_B) return true;
#else
    // Single LED pin
    if (pin == LED_PIN) return true;
#endif
    
    return false;
}

// Configure a button on a specific pin
void configureButton(uint8_t index, uint8_t pin) {
    if (index >= MAX_BUTTONS) return;
    
    // Don't allow reserved pins
    if (isPinReserved(pin)) return;
    
    buttons[index].pin = pin;
    buttons[index].enabled = true;
    buttons[index].lastState = false;
    buttons[index].currentReading = false;
    buttons[index].lastDebounceTime = 0;
    
    // Configure pin with internal pull-up (button connects to GND)
    pinMode(pin, INPUT_PULLUP);
}

// Clear all button configurations
void clearButtons() {
    for (uint8_t i = 0; i < MAX_BUTTONS; i++) {
        buttons[i].enabled = false;
        buttons[i].pin = 0;
    }
    numConfiguredButtons = 0;
}

// Initialize buttons array
void initButtons() {
    clearButtons();
}

void handleCommand(const String& line) {
    // Simple text commands (for easy serial monitor testing)
    String trimmed = line;
    trimmed.trim();
    
    if (trimmed.equalsIgnoreCase("test")) {
        // Quick test mode - configure GP2-GP7 as buttons
        clearButtons();
        uint8_t testPins[] = {2, 3, 4, 5, 6, 7};
        for (uint8_t i = 0; i < 6; i++) {
            configureButton(i, testPins[i]);
        }
        numConfiguredButtons = 6;
        Serial.println("{\"type\":\"test_mode\",\"pins\":[2,3,4,5,6,7],\"msg\":\"Ground GP2-GP7 to test buttons\"}");
        return;
    }
    if (trimmed.equalsIgnoreCase("status")) {
        Serial.print("{\"type\":\"status\",\"buttons\":");
        Serial.print(numConfiguredButtons);
        Serial.print(",\"position\":");
        Serial.print(encoderPosition);
        Serial.println("}");
        return;
    }
    if (trimmed.equalsIgnoreCase("help")) {
        Serial.println("{\"type\":\"help\",\"commands\":[\"test\",\"status\",\"help\"]}");
        return;
    }
    
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
    // Button configuration: {"type":"buttons","pins":[2,3,4,5]}
    else if (line.indexOf("\"type\":\"buttons\"") >= 0) {
        clearButtons();
        
        int pinsIdx = line.indexOf("\"pins\":[");
        if (pinsIdx >= 0) {
            int startIdx = pinsIdx + 8;
            int endIdx = line.indexOf(']', startIdx);
            if (endIdx > startIdx) {
                String pinsStr = line.substring(startIdx, endIdx);
                uint8_t buttonIndex = 0;
                
                // Parse comma-separated pin numbers
                int pos = 0;
                while (pos < pinsStr.length() && buttonIndex < MAX_BUTTONS) {
                    int commaIdx = pinsStr.indexOf(',', pos);
                    if (commaIdx < 0) commaIdx = pinsStr.length();
                    
                    String pinStr = pinsStr.substring(pos, commaIdx);
                    pinStr.trim();
                    int pin = pinStr.toInt();
                    
                    if (pin >= 2 && pin <= 29) {  // Valid GPIO range
                        configureButton(buttonIndex, pin);
                        buttonIndex++;
                    }
                    pos = commaIdx + 1;
                }
                numConfiguredButtons = buttonIndex;
            }
        }
        
        // Confirm configuration
        Serial.print("{\"type\":\"buttons_configured\",\"count\":");
        Serial.print(numConfiguredButtons);
        Serial.println("}");
    }
    // Clear buttons: {"type":"clear_buttons"}
    else if (line.indexOf("\"type\":\"clear_buttons\"") >= 0) {
        clearButtons();
        Serial.println("{\"type\":\"buttons_cleared\"}");
    }
    // Test mode: {"type":"test"} - configures GP2-GP7 as buttons for testing
    else if (line.indexOf("\"type\":\"test\"") >= 0) {
        clearButtons();
        uint8_t testPins[] = {2, 3, 4, 5, 6, 7};
        for (uint8_t i = 0; i < 6; i++) {
            configureButton(i, testPins[i]);
        }
        numConfiguredButtons = 6;
        Serial.println("{\"type\":\"test_mode\",\"pins\":[2,3,4,5,6,7]}");
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
#if LED_TYPE_NEOPIXEL
    led.setPixelColor(0, color);
    led.show();
#elif LED_TYPE_RGB
    // Tiny2040 RGB LED - active LOW (0 = on, 1 = off)
    uint8_t r = (color >> 16) & 0xFF;
    uint8_t g = (color >> 8) & 0xFF;
    uint8_t b = color & 0xFF;
    // Use PWM for brightness, inverted for active LOW
    analogWrite(LED_PIN_R, 255 - r);
    analogWrite(LED_PIN_G, 255 - g);
    analogWrite(LED_PIN_B, 255 - b);
#else
    // Single LED: on if any color component is set
    digitalWrite(LED_PIN, color != 0 ? HIGH : LOW);
#endif
}

void flashLed(uint32_t color, unsigned long durationMs) {
    setLed(color);
    ledOffTime = millis() + durationMs;
}

void setup() {
#if LED_TYPE_NEOPIXEL
    // Initialize NeoPixel RGB LED
    led.begin();
    led.setBrightness(30);  // Dim (0-255) - these LEDs are bright!
#elif LED_TYPE_RGB
    // Initialize Tiny2040 RGB LED (3 separate pins, active LOW)
    pinMode(LED_PIN_R, OUTPUT);
    pinMode(LED_PIN_G, OUTPUT);
    pinMode(LED_PIN_B, OUTPUT);
#else
    // Initialize single LED
    pinMode(LED_PIN, OUTPUT);
#endif
    setLed(COLOR_RED);
    
    // Initialize buttons
    initButtons();
    
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
    
    // Scan configured buttons with debouncing
    for (uint8_t i = 0; i < MAX_BUTTONS; i++) {
        if (!buttons[i].enabled) continue;
        
        // Read button (active LOW - pressed when connected to GND)
        bool reading = !digitalRead(buttons[i].pin);
        
        // If reading changed, reset debounce timer
        if (reading != buttons[i].currentReading) {
            buttons[i].currentReading = reading;
            buttons[i].lastDebounceTime = now;
        }
        
        // If reading has been stable for debounce period
        if ((now - buttons[i].lastDebounceTime) >= DEBOUNCE_MS) {
            // If state has changed
            if (reading != buttons[i].lastState) {
                buttons[i].lastState = reading;
                sendButtonEvent(buttons[i].pin, reading);
                
                // Flash LED on button press
                if (reading) {
                    flashLed(COLOR_GREEN, 50);
                }
            }
        }
    }
    
    // Process incoming serial commands
    while (Serial.available() > 0) {
        char c = Serial.read();
        lastCharTime = now;
        
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
    
    // Timeout-based command processing (for serial monitors that don't send newline)
    if (inputBuffer.length() > 0 && (now - lastCharTime) >= COMMAND_TIMEOUT_MS) {
        handleCommand(inputBuffer);
        inputBuffer = "";
    }
}
