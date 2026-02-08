package com.cncpendant.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Available button functions for the encoder buttons
 */
enum class ButtonFunction(val displayName: String, val id: String) {
    NONE("None", "none"),
    
    // === Special modifier ===
    FN_MODIFIER("FN Modifier", "fn_modifier"),
    
    // Jog commands (typically idle only)
    JOG_X_PLUS("Jog X+", "jog_x_plus"),
    JOG_X_MINUS("Jog X-", "jog_x_minus"),
    JOG_Y_PLUS("Jog Y+", "jog_y_plus"),
    JOG_Y_MINUS("Jog Y-", "jog_y_minus"),
    JOG_Z_PLUS("Jog Z+", "jog_z_plus"),
    JOG_Z_MINUS("Jog Z-", "jog_z_minus"),
    
    // Home commands
    HOME_ALL("Home All", "home_all"),
    HOME_X("Home X", "home_x"),
    HOME_Y("Home Y", "home_y"),
    HOME_Z("Home Z", "home_z"),
    
    // Zero (WCS) commands
    ZERO_ALL("Set Zero All", "zero_all"),
    ZERO_X("Set Zero X", "zero_x"),
    ZERO_Y("Set Zero Y", "zero_y"),
    ZERO_Z("Set Zero Z", "zero_z"),
    
    // Machine control
    FEED_HOLD("Feed Hold / Pause", "feed_hold"),
    CYCLE_START("Cycle Start / Resume", "cycle_start"),
    STOP("Stop / Reset", "stop"),
    
    // Spindle/Coolant
    SPINDLE_TOGGLE("Spindle Toggle", "spindle_toggle"),
    COOLANT_TOGGLE("Coolant Toggle", "coolant_toggle"),
    
    // Probing
    PROBE_Z("Probe Z", "probe_z"),
    
    // Axis selection (for dial)
    SELECT_X("Select X Axis", "select_x"),
    SELECT_Y("Select Y Axis", "select_y"),
    SELECT_Z("Select Z Axis", "select_z"),
    
    // === Override functions (useful during job) ===
    
    // Feed rate override
    FEED_OVERRIDE_100("Feed Override 100%", "feed_ovr_100"),
    FEED_OVERRIDE_PLUS_10("Feed Override +10%", "feed_ovr_plus_10"),
    FEED_OVERRIDE_MINUS_10("Feed Override -10%", "feed_ovr_minus_10"),
    FEED_OVERRIDE_PLUS_1("Feed Override +1%", "feed_ovr_plus_1"),
    FEED_OVERRIDE_MINUS_1("Feed Override -1%", "feed_ovr_minus_1"),
    
    // Rapid override
    RAPID_OVERRIDE_100("Rapid Override 100%", "rapid_ovr_100"),
    RAPID_OVERRIDE_50("Rapid Override 50%", "rapid_ovr_50"),
    RAPID_OVERRIDE_25("Rapid Override 25%", "rapid_ovr_25"),
    
    // Spindle override
    SPINDLE_OVERRIDE_100("Spindle Override 100%", "spindle_ovr_100"),
    SPINDLE_OVERRIDE_PLUS_10("Spindle Override +10%", "spindle_ovr_plus_10"),
    SPINDLE_OVERRIDE_MINUS_10("Spindle Override -10%", "spindle_ovr_minus_10"),
    SPINDLE_OVERRIDE_PLUS_1("Spindle Override +1%", "spindle_ovr_plus_1"),
    SPINDLE_OVERRIDE_MINUS_1("Spindle Override -1%", "spindle_ovr_minus_1"),
    
    // === Jog step size control ===
    JOG_STEP_CYCLE("Cycle Jog Steps", "jog_step_cycle"),
    JOG_STEP_SMALL("Set Step Small (0.1)", "jog_step_small"),
    JOG_STEP_MEDIUM("Set Step Medium (1)", "jog_step_medium"),
    JOG_STEP_LARGE("Set Step Large (10)", "jog_step_large"),
    
    // === Diagonal jog (XY) ===
    JOG_XY_PLUS_PLUS("Jog X+ Y+", "jog_xy_pp"),
    JOG_XY_PLUS_MINUS("Jog X+ Y-", "jog_xy_pm"),
    JOG_XY_MINUS_PLUS("Jog X- Y+", "jog_xy_mp"),
    JOG_XY_MINUS_MINUS("Jog X- Y-", "jog_xy_mm"),
    
    // === Machine control ===
    SOFT_RESET("Soft Reset", "soft_reset"),
    UNLOCK("Unlock (\$X)", "unlock"),
    
    // === Tool functions ===
    TOOL_LENGTH_SENSOR("Tool Length Sensor", "tls"),
    
    // === Job control ===
    JOB_START("Start Job", "job_start"),
    
    // === Macros (1-9) ===
    RUN_MACRO_1("Run Macro 1", "macro_1"),
    RUN_MACRO_2("Run Macro 2", "macro_2"),
    RUN_MACRO_3("Run Macro 3", "macro_3"),
    RUN_MACRO_4("Run Macro 4", "macro_4"),
    RUN_MACRO_5("Run Macro 5", "macro_5"),
    RUN_MACRO_6("Run Macro 6", "macro_6"),
    RUN_MACRO_7("Run Macro 7", "macro_7"),
    RUN_MACRO_8("Run Macro 8", "macro_8"),
    RUN_MACRO_9("Run Macro 9", "macro_9");
    
    companion object {
        fun fromId(id: String): ButtonFunction {
            return entries.find { it.id == id } ?: NONE
        }
        
        fun getDisplayNames(): Array<String> {
            return entries.map { it.displayName }.toTypedArray()
        }
    }
}

/**
 * Configuration for a single button with triple-mode functions:
 * - Idle: When no job is running
 * - Running: When job is running  
 * - Secondary (Fn+): When FN modifier button is held
 */
data class ButtonConfig(
    val index: Int,                    // Button index (0-11)
    val gpioPin: Int,                  // GPIO pin number on RP2040
    val idleFunction: ButtonFunction,  // Function when no job is running
    val runningFunction: ButtonFunction = ButtonFunction.NONE,  // Function when job is running
    val secondaryFunction: ButtonFunction = ButtonFunction.NONE  // Function when FN is held
) {
    // Legacy constructor for backwards compatibility
    constructor(index: Int, gpioPin: Int, function: ButtonFunction) : this(index, gpioPin, function, ButtonFunction.NONE, ButtonFunction.NONE)
    
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("index", index)
            put("pin", gpioPin)
            put("idleFunction", idleFunction.id)
            put("runningFunction", runningFunction.id)
            put("secondaryFunction", secondaryFunction.id)
        }
    }
    
    companion object {
        fun fromJson(json: JSONObject): ButtonConfig {
            // Support both old format (function) and new format (idleFunction/runningFunction/secondaryFunction)
            val idleFunc = if (json.has("idleFunction")) {
                ButtonFunction.fromId(json.getString("idleFunction"))
            } else if (json.has("function")) {
                ButtonFunction.fromId(json.getString("function"))
            } else {
                ButtonFunction.NONE
            }
            
            val runningFunc = if (json.has("runningFunction")) {
                ButtonFunction.fromId(json.getString("runningFunction"))
            } else {
                ButtonFunction.NONE
            }
            
            val secondaryFunc = if (json.has("secondaryFunction")) {
                ButtonFunction.fromId(json.getString("secondaryFunction"))
            } else {
                ButtonFunction.NONE
            }
            
            return ButtonConfig(
                index = json.getInt("index"),
                gpioPin = json.getInt("pin"),
                idleFunction = idleFunc,
                runningFunction = runningFunc,
                secondaryFunction = secondaryFunc
            )
        }
    }
}

/**
 * Supported RP2040 board types with their available GPIO pins
 */
enum class BoardType(val displayName: String, val availablePins: List<Int>) {
    RP2040_ZERO(
        "RP2040-Zero",
        // GP0-1 encoder, GP16 NeoPixel LED
        listOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28)
    ),
    PICO(
        "Raspberry Pi Pico",
        // GP0-1 encoder, GP25 LED
        listOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 26, 27, 28)
    ),
    TINY2040(
        "Pimoroni Tiny2040",
        // GP0-1 encoder, GP18-20 RGB LED, only GP2-7 and GP26-29 broken out
        listOf(2, 3, 4, 5, 6, 7, 26, 27, 28, 29)
    );
    
    companion object {
        fun fromId(id: String): BoardType {
            return entries.find { it.name == id } ?: RP2040_ZERO
        }
        
        fun getDisplayNames(): List<String> = entries.map { it.displayName }
    }
}

/**
 * Manager for button configuration storage
 */
object ButtonConfigManager {
    private const val PREFS_NAME = "encoder_buttons"
    private const val KEY_NUM_BUTTONS = "num_buttons"
    private const val KEY_BUTTON_CONFIG = "button_config"
    private const val KEY_BOARD_TYPE = "board_type"
    
    fun saveBoardType(context: Context, boardType: BoardType) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BOARD_TYPE, boardType.name)
            .apply()
    }
    
    fun loadBoardType(context: Context): BoardType {
        val id = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BOARD_TYPE, BoardType.RP2040_ZERO.name) ?: BoardType.RP2040_ZERO.name
        return BoardType.fromId(id)
    }
    
    /**
     * Get available pins for the currently selected board
     */
    fun getAvailablePins(context: Context): List<Int> {
        return loadBoardType(context).availablePins
    }
    
    /**
     * Get display names for pins based on board type
     * For Tiny2040, GP26-29 are also labeled A0-A3
     */
    fun getPinDisplayName(pin: Int, boardType: BoardType): String {
        return when {
            boardType == BoardType.TINY2040 && pin in 26..29 -> {
                val analogNum = pin - 26  // GP26=A0, GP27=A1, GP28=A2, GP29=A3
                "A$analogNum (GP$pin)"
            }
            else -> "GP$pin"
        }
    }
    
    /**
     * Get list of pin display names for the currently selected board
     */
    fun getPinDisplayNames(context: Context): List<String> {
        val boardType = loadBoardType(context)
        return boardType.availablePins.map { getPinDisplayName(it, boardType) }
    }
    
    fun saveNumButtons(context: Context, numButtons: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_NUM_BUTTONS, numButtons)
            .apply()
    }
    
    fun loadNumButtons(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_NUM_BUTTONS, 0)
    }
    
    fun saveButtonConfigs(context: Context, configs: List<ButtonConfig>) {
        val jsonArray = JSONArray()
        configs.forEach { jsonArray.put(it.toJson()) }
        
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BUTTON_CONFIG, jsonArray.toString())
            .apply()
    }
    
    fun loadButtonConfigs(context: Context): List<ButtonConfig> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BUTTON_CONFIG, null) ?: return emptyList()
        
        return try {
            val jsonArray = JSONArray(json)
            (0 until jsonArray.length()).map { 
                ButtonConfig.fromJson(jsonArray.getJSONObject(it))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Get the list of GPIO pins to configure on the device
     */
    fun getConfiguredPins(context: Context): List<Int> {
        val numButtons = loadNumButtons(context)
        if (numButtons == 0) return emptyList()
        
        val configs = loadButtonConfigs(context)
        return configs.take(numButtons).map { it.gpioPin }
    }
    
    /**
     * Get button config for a specific GPIO pin
     */
    fun getConfigForPin(context: Context, pin: Int): ButtonConfig? {
        val configs = loadButtonConfigs(context)
        return configs.find { it.gpioPin == pin }
    }
    
    /**
     * Get button function for a specific GPIO pin based on job state
     * @param isJobRunning true if a job is currently running
     */
    fun getFunctionForPin(context: Context, pin: Int, isJobRunning: Boolean = false): ButtonFunction? {
        val config = getConfigForPin(context, pin) ?: return null
        val function = if (isJobRunning && config.runningFunction != ButtonFunction.NONE) {
            config.runningFunction
        } else {
            config.idleFunction
        }
        return function
    }
}
