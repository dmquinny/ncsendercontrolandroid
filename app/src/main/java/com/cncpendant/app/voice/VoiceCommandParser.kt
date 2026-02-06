package com.cncpendant.app.voice

import android.util.Log

/**
 * Command types for voice control
 */
enum class CommandType {
    STOP, RESUME, RESET, HOME, UNLOCK, JOG, MOVE, PROBE, WORKSPACE, SETTING, ZERO, BLOCKED,
    QUERY, SPINDLE, COOLANT, TOOL_CHANGE, REPEAT, UNDO
}

/**
 * Represents a parsed voice command ready for execution
 */
data class VoiceCommand(
    val type: CommandType,
    val description: String,
    val gcode: String,
    val jogMoves: Map<String, Float>? = null,  // For undo functionality
    val confidence: Float = 1.0f  // NLU confidence score
)

/**
 * Configuration for the command parser
 */
data class ParserConfig(
    var currentFeed: Int = 1000,
    var currentStep: Float = 1f,
    var defaultProbeType: String = "3d-probe",
    var homeLocation: String = "back-left",
    var unitsPreference: String = "metric",
    var allowJobStartCommands: Boolean = false,
    var lastExecutedCommand: VoiceCommand? = null,
    var lastJogMoves: MutableMap<String, Float>? = null,
    var useNLU: Boolean = true,  // Enable NLU-based parsing
    var nluConfidenceThreshold: Float = 0.6f  // Minimum confidence for NLU results
)

/**
 * Parses voice input into executable commands.
 * 
 * Uses a hybrid approach:
 * 1. NLU (Natural Language Understanding) for intent classification and entity extraction
 * 2. Fuzzy matching for handling speech recognition variations
 * 3. Regex fallback for edge cases
 * 
 * This provides robust and flexible command parsing that handles:
 * - Natural speech variations ("move right" vs "jog right" vs "go right")
 * - Speech recognition errors with fuzzy matching
 * - Complex commands with multiple entities
 */
class VoiceCommandParser(
    private val config: ParserConfig = ParserConfig()
) {
    companion object {
        private const val TAG = "VoiceCommandParser"
        
        // Number word to digit conversion map
        private val numberWords = mapOf(
            "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
            "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
            "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
            "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
            "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30,
            "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
            "eighty" to 80, "ninety" to 90, "hundred" to 100, "thousand" to 1000,
            "point" to -1, "dot" to -1 // -1 = decimal marker
        )
    }
    
    // NLU processor for intent/entity extraction
    private val nlu = VoiceNLU()
    
    // Debug info from last parse
    private var lastParseDebugInfo: String = ""
    
    fun getLastParseDebugInfo(): String = lastParseDebugInfo
    
    /**
     * Listener for settings changes that occur during parsing
     */
    interface SettingsChangeListener {
        fun onFeedChanged(feed: Int)
        fun onStepChanged(step: Float)
        fun onProbeTypeChanged(probeType: String)
    }
    
    var settingsChangeListener: SettingsChangeListener? = null
    
    /**
     * Parse a voice command string into a VoiceCommand.
     * Uses NLU for primary parsing with regex fallback.
     * May return null if the command is not recognized.
     */
    fun parseCommand(text: String): VoiceCommand? {
        // Apply additional normalization for common misrecognitions
        val normalizedText = normalizeText(text)
        Log.d(TAG, "Normalized for parsing: $normalizedText")
        
        // Try NLU-based parsing first if enabled
        if (config.useNLU) {
            val nluResult = nlu.parse(normalizedText)
            
            // Store debug info
            val entityInfo = nluResult.entities.joinToString(", ") { "${it.type}=${it.value}" }
            lastParseDebugInfo = "Intent=${nluResult.intent}, Entities=[$entityInfo]"
            
            Log.d(TAG, "NLU result: intent=${nluResult.intent}, confidence=${nluResult.confidence}, entities=${nluResult.entities}")
            
            if (nluResult.confidence >= config.nluConfidenceThreshold && nluResult.intent != VoiceNLU.Intent.UNKNOWN) {
                val command = convertNLUToCommand(nluResult, normalizedText)
                if (command != null) {
                    Log.d(TAG, "NLU parsed command: ${command.description}")
                    return command
                }
            }
        } else {
            lastParseDebugInfo = "NLU disabled, using regex"
        }
        
        // Fallback to regex-based parsing
        Log.d(TAG, "Falling back to regex parsing")
        lastParseDebugInfo += " -> Regex fallback"
        return parseCommandRegex(normalizedText)
    }
    
    /**
     * Convert NLU result to VoiceCommand
     */
    private fun convertNLUToCommand(result: VoiceNLU.NLUResult, normalizedText: String): VoiceCommand? {
        return when (result.intent) {
            VoiceNLU.Intent.STOP -> VoiceCommand(
                CommandType.STOP, "Feed Hold (!)", "!",
                confidence = result.confidence
            )
            
            VoiceNLU.Intent.RESUME -> VoiceCommand(
                CommandType.RESUME, "Cycle Start (~)", "~",
                confidence = result.confidence
            )
            
            VoiceNLU.Intent.RESET -> VoiceCommand(
                CommandType.RESET, "Soft Reset (0x18)", "0x18",
                confidence = result.confidence
            )
            
            VoiceNLU.Intent.UNLOCK -> VoiceCommand(
                CommandType.UNLOCK, "Unlock (\$X)", "\$X",
                confidence = result.confidence
            )
            
            VoiceNLU.Intent.HOME -> {
                val axis = result.getEntity(VoiceNLU.EntityType.AXIS)?.value as? String ?: "XYZ"
                val (desc, gcode) = when (axis) {
                    "X" -> "Home X (\$HX)" to "\$HX"
                    "Y" -> "Home Y (\$HY)" to "\$HY"
                    "Z" -> "Home Z (\$HZ)" to "\$HZ"
                    else -> "Home All (\$H)" to "\$H"
                }
                VoiceCommand(CommandType.HOME, desc, gcode, confidence = result.confidence)
            }
            
            VoiceNLU.Intent.JOG -> parseJogFromNLU(result, normalizedText)
            
            VoiceNLU.Intent.MOVE_ABSOLUTE -> parseMoveAbsoluteFromNLU(result, normalizedText)
            
            VoiceNLU.Intent.PROBE -> {
                val axis = result.getEntity(VoiceNLU.EntityType.AXIS)?.value as? String ?: "Z"
                val probeType = result.getEntity(VoiceNLU.EntityType.PROBE_TYPE)?.value as? String 
                    ?: config.defaultProbeType
                VoiceCommand(
                    CommandType.PROBE, 
                    "Probe $axis ($probeType)", 
                    "PROBE:$axis:$probeType",
                    confidence = result.confidence
                )
            }
            
            VoiceNLU.Intent.SET_FEED -> {
                val feed = result.getEntity(VoiceNLU.EntityType.FEED_RATE)?.value as? Float
                    ?: result.getEntity(VoiceNLU.EntityType.DISTANCE)?.value as? Float
                if (feed != null && feed in 1f..50000f) {
                    val isMetric = config.unitsPreference == "metric"
                    val feedMm = if (isMetric) feed.toInt() else (feed * 25.4).toInt()
                    config.currentFeed = feedMm
                    settingsChangeListener?.onFeedChanged(feedMm)
                    val displayUnit = if (isMetric) "mm/min" else "in/min"
                    VoiceCommand(
                        CommandType.SETTING, 
                        "Set feed to ${feed.toInt()} $displayUnit", 
                        "FEED:$feedMm",
                        confidence = result.confidence
                    )
                } else null
            }
            
            VoiceNLU.Intent.SET_STEP -> {
                val step = result.getEntity(VoiceNLU.EntityType.DISTANCE)?.value as? Float
                if (step != null && step in 0.001f..100f) {
                    val isMetric = config.unitsPreference == "metric"
                    val stepMm = if (isMetric) step else step * 25.4f
                    config.currentStep = stepMm
                    settingsChangeListener?.onStepChanged(stepMm)
                    val displayUnit = if (isMetric) "mm" else "in"
                    VoiceCommand(
                        CommandType.SETTING, 
                        "Set step to $step $displayUnit", 
                        "STEP:$stepMm",
                        confidence = result.confidence
                    )
                } else null
            }
            
            VoiceNLU.Intent.SET_PROBE_TYPE -> {
                val probeType = result.getEntity(VoiceNLU.EntityType.PROBE_TYPE)?.value as? String
                if (probeType != null) {
                    config.defaultProbeType = probeType
                    settingsChangeListener?.onProbeTypeChanged(probeType)
                    VoiceCommand(
                        CommandType.SETTING, 
                        "Probe set to ${getProbeDisplayName(probeType)}", 
                        "PROBE_TYPE:$probeType",
                        confidence = result.confidence
                    )
                } else null
            }
            
            VoiceNLU.Intent.SET_WORKSPACE -> {
                val workspace = result.getEntity(VoiceNLU.EntityType.WORKSPACE)?.value as? String
                if (workspace != null) {
                    VoiceCommand(
                        CommandType.WORKSPACE, 
                        "Switch to $workspace", 
                        workspace,
                        confidence = result.confidence
                    )
                } else null
            }
            
            VoiceNLU.Intent.ZERO_AXIS -> {
                val axis = result.getEntity(VoiceNLU.EntityType.AXIS)?.value as? String ?: "XYZ"
                val gcode = when (axis) {
                    "X" -> "G10 L20 P1 X0"
                    "Y" -> "G10 L20 P1 Y0"
                    "Z" -> "G10 L20 P1 Z0"
                    "XY" -> "G10 L20 P1 X0 Y0"
                    else -> "G10 L20 P1 X0 Y0 Z0"
                }
                VoiceCommand(CommandType.ZERO, "Zero $axis", gcode, confidence = result.confidence)
            }
            
            VoiceNLU.Intent.SPINDLE_ON -> {
                val rpm = (result.getEntity(VoiceNLU.EntityType.SPINDLE_RPM)?.value as? Float)?.toInt()
                    ?: (result.getEntity(VoiceNLU.EntityType.DISTANCE)?.value as? Float)?.toInt()
                    ?: 10000
                val direction = result.getEntity(VoiceNLU.EntityType.DIRECTION)?.value as? VoiceNLU.Direction
                val mCode = if (direction == VoiceNLU.Direction.NEGATIVE) "M4" else "M3"
                val dirDesc = if (direction == VoiceNLU.Direction.NEGATIVE) "CCW" else "CW"
                VoiceCommand(
                    CommandType.SPINDLE, 
                    "Spindle $dirDesc at $rpm RPM ($mCode)", 
                    "$mCode S$rpm",
                    confidence = result.confidence
                )
            }
            
            VoiceNLU.Intent.SPINDLE_OFF -> VoiceCommand(
                CommandType.SPINDLE, "Spindle OFF (M5)", "M5",
                confidence = result.confidence
            )
            
            VoiceNLU.Intent.SET_SPINDLE_SPEED -> {
                val rpm = (result.getEntity(VoiceNLU.EntityType.SPINDLE_RPM)?.value as? Float)?.toInt()
                    ?: (result.getEntity(VoiceNLU.EntityType.DISTANCE)?.value as? Float)?.toInt()
                if (rpm != null) {
                    VoiceCommand(
                        CommandType.SPINDLE, 
                        "Set Spindle Speed to $rpm RPM", 
                        "S$rpm",
                        confidence = result.confidence
                    )
                } else null
            }
            
            VoiceNLU.Intent.COOLANT_ON -> VoiceCommand(
                CommandType.COOLANT, "Flood Coolant ON (M8)", "M8",
                confidence = result.confidence
            )
            
            VoiceNLU.Intent.COOLANT_OFF -> VoiceCommand(
                CommandType.COOLANT, "Coolant OFF (M9)", "M9",
                confidence = result.confidence
            )
            
            VoiceNLU.Intent.MIST_ON -> VoiceCommand(
                CommandType.COOLANT, "Mist Coolant ON (M7)", "M7",
                confidence = result.confidence
            )
            
            VoiceNLU.Intent.TOOL_CHANGE -> {
                val toolNum = (result.getEntity(VoiceNLU.EntityType.TOOL_NUMBER)?.value as? Int)
                    ?: (result.getEntity(VoiceNLU.EntityType.DISTANCE)?.value as? Float)?.toInt()
                    ?: 1
                VoiceCommand(
                    CommandType.TOOL_CHANGE, 
                    "Tool Change T$toolNum (M6 T$toolNum)", 
                    "M6 T$toolNum",
                    confidence = result.confidence
                )
            }
            
            VoiceNLU.Intent.QUERY_POSITION -> VoiceCommand(
                CommandType.QUERY, "Query Position", "QUERY:POSITION",
                confidence = result.confidence
            )
            
            VoiceNLU.Intent.QUERY_STATUS -> VoiceCommand(
                CommandType.QUERY, "Query Status", "QUERY:STATUS",
                confidence = result.confidence
            )
            
            VoiceNLU.Intent.QUERY_FEED -> VoiceCommand(
                CommandType.QUERY, "Query Feed Rate", "QUERY:FEED",
                confidence = result.confidence
            )
            
            VoiceNLU.Intent.QUERY_STEP -> VoiceCommand(
                CommandType.QUERY, "Query Step Size", "QUERY:STEP",
                confidence = result.confidence
            )
            
            VoiceNLU.Intent.QUERY_DISTANCE -> {
                val queryType = if (normalizedText.contains("machine") || normalizedText.contains("home")) {
                    "QUERY:DISTANCE_MACHINE"
                } else {
                    "QUERY:DISTANCE_WORK"
                }
                VoiceCommand(
                    CommandType.QUERY, 
                    "Distance query", 
                    queryType,
                    confidence = result.confidence
                )
            }
            
            VoiceNLU.Intent.REPEAT -> {
                if (config.lastExecutedCommand != null) {
                    VoiceCommand(
                        CommandType.REPEAT, 
                        "Repeat: ${config.lastExecutedCommand!!.description}", 
                        "REPEAT",
                        confidence = result.confidence
                    )
                } else {
                    VoiceCommand(CommandType.BLOCKED, "No command to repeat", "BLOCKED")
                }
            }
            
            VoiceNLU.Intent.UNDO -> {
                if (config.lastJogMoves != null && config.lastJogMoves!!.isNotEmpty()) {
                    val reverseMoves = config.lastJogMoves!!.mapValues { -it.value }
                    val jogParts = reverseMoves.entries.map { "${it.key}${it.value}" }.joinToString(" ")
                    val gcode = "\$J=G91 $jogParts F${config.currentFeed}"
                    val desc = reverseMoves.entries.joinToString(", ") { 
                        val sign = if (it.value >= 0) "+" else ""
                        "${it.key}$sign${it.value}"
                    }
                    VoiceCommand(CommandType.UNDO, "Undo jog: $desc", gcode, reverseMoves)
                } else {
                    VoiceCommand(CommandType.BLOCKED, "No jog to undo", "BLOCKED")
                }
            }
            
            VoiceNLU.Intent.START_JOB -> {
                if (!config.allowJobStartCommands) {
                    VoiceCommand(CommandType.BLOCKED, "Job start blocked (enable in settings)", "BLOCKED")
                } else {
                    VoiceCommand(CommandType.RESUME, "Start Job (~)", "~", confidence = result.confidence)
                }
            }
            
            VoiceNLU.Intent.CONFIRM -> VoiceCommand(
                CommandType.SETTING, "Confirmed", "CONFIRM",
                confidence = result.confidence
            )
            
            VoiceNLU.Intent.CANCEL -> VoiceCommand(
                CommandType.SETTING, "Cancelled", "CANCEL",
                confidence = result.confidence
            )
            
            VoiceNLU.Intent.UNKNOWN -> null
        }
    }
    
    /**
     * Parse jog command from NLU result
     */
    private fun parseJogFromNLU(result: VoiceNLU.NLUResult, normalizedText: String): VoiceCommand? {
        val moves = mutableMapOf<String, Float>()
        val isMetric = config.unitsPreference == "metric"
        
        // Get directions from entities
        val directions = result.getEntities(VoiceNLU.EntityType.DIRECTION)
        
        // Get distance (default to current step)
        val distanceEntity = result.getEntity(VoiceNLU.EntityType.DISTANCE)
        var distance = (distanceEntity?.value as? Float) ?: config.currentStep
        
        // Convert distance if imperial
        if (distanceEntity != null && !isMetric) {
            distance *= 25.4f
        }
        
        // Process each direction
        for (dirEntity in directions) {
            val dir = dirEntity.value as? VoiceNLU.Direction ?: continue
            when (dir) {
                VoiceNLU.Direction.LEFT -> moves["X"] = (moves["X"] ?: 0f) + (distance * getXDirectionLeft())
                VoiceNLU.Direction.RIGHT -> moves["X"] = (moves["X"] ?: 0f) + (distance * getXDirectionRight())
                VoiceNLU.Direction.FORWARD -> moves["Y"] = (moves["Y"] ?: 0f) + (distance * getYDirectionForward())
                VoiceNLU.Direction.BACK -> moves["Y"] = (moves["Y"] ?: 0f) + (distance * getYDirectionBack())
                VoiceNLU.Direction.UP -> moves["Z"] = (moves["Z"] ?: 0f) + distance
                VoiceNLU.Direction.DOWN -> moves["Z"] = (moves["Z"] ?: 0f) - distance
                VoiceNLU.Direction.POSITIVE -> {
                    // Check for axis context
                    val axis = result.getEntity(VoiceNLU.EntityType.AXIS)?.value as? String
                    when (axis) {
                        "X" -> moves["X"] = (moves["X"] ?: 0f) + distance
                        "Y" -> moves["Y"] = (moves["Y"] ?: 0f) + distance
                        "Z" -> moves["Z"] = (moves["Z"] ?: 0f) + distance
                    }
                }
                VoiceNLU.Direction.NEGATIVE -> {
                    val axis = result.getEntity(VoiceNLU.EntityType.AXIS)?.value as? String
                    when (axis) {
                        "X" -> moves["X"] = (moves["X"] ?: 0f) - distance
                        "Y" -> moves["Y"] = (moves["Y"] ?: 0f) - distance
                        "Z" -> moves["Z"] = (moves["Z"] ?: 0f) - distance
                    }
                }
            }
        }
        
        if (moves.isEmpty()) return null
        
        // Get speed modifier
        val speedModEntity = result.getEntity(VoiceNLU.EntityType.SPEED_MODIFIER)
        val speedModifier = (speedModEntity?.value as? VoiceNLU.SpeedModifier)?.multiplier ?: 1.0f
        
        // Check for feed rate from NLU entities first, then regex fallback
        val feedEntity = result.getEntity(VoiceNLU.EntityType.FEED_RATE)
        var jogFeed = if (feedEntity != null) {
            val feedInput = (feedEntity.value as? Float)?.toInt() ?: config.currentFeed
            if (isMetric) feedInput else (feedInput * 25.4).toInt()
        } else {
            // Fallback to regex: "at feed 6000", "feed rate 6000", etc.
            val feedOverrideMatch = Regex("(?:at )?feed\\s*(?:rate)?\\s*(\\d+)").find(normalizedText)
            if (feedOverrideMatch != null) {
                val feedInput = feedOverrideMatch.groupValues[1].toIntOrNull() ?: config.currentFeed
                if (isMetric) feedInput else (feedInput * 25.4).toInt()
            } else {
                config.currentFeed
            }
        }
        
        // Apply speed modifier
        jogFeed = (jogFeed * speedModifier).toInt().coerceIn(10, 50000)
        
        // Check for step override from NLU entities - this changes the move distance
        val stepEntity = result.getEntity(VoiceNLU.EntityType.STEP_SIZE)
        if (stepEntity != null) {
            val stepInput = (stepEntity.value as? Float) ?: config.currentStep
            val stepMm = if (isMetric) stepInput else stepInput * 25.4f
            // Re-calculate moves with the new step size, preserving direction
            moves.forEach { (axis, value) ->
                val direction = if (value >= 0) 1f else -1f
                moves[axis] = stepMm * direction
            }
        }
        
        val jogParts = moves.entries.map { "${it.key}${it.value}" }.joinToString(" ")
        val gcode = "\$J=G91 $jogParts F$jogFeed"
        val desc = moves.entries.joinToString(", ") { 
            val sign = if (it.value >= 0) "+" else ""
            "${it.key}$sign${it.value}"
        }
        val speedDesc = when {
            speedModifier < 0.1f -> " (creep)"
            speedModifier < 0.5f -> " (slow)"
            speedModifier > 1.5f -> " (fast)"
            else -> ""
        }
        
        return VoiceCommand(
            CommandType.JOG, 
            "Jog $desc at F$jogFeed$speedDesc", 
            gcode, 
            moves.toMap(),
            confidence = result.confidence
        )
    }
    
    /**
     * Parse absolute move from NLU result
     */
    private fun parseMoveAbsoluteFromNLU(result: VoiceNLU.NLUResult, normalizedText: String): VoiceCommand? {
        val isMachine = normalizedText.contains("machine")
        
        // Extract coordinates from the text
        val xMatch = Regex("x ?(-?\\d+\\.?\\d*)").find(normalizedText)
        val yMatch = Regex("y ?(-?\\d+\\.?\\d*)").find(normalizedText)
        val zMatch = Regex("z ?(-?\\d+\\.?\\d*)").find(normalizedText)
        
        val coordParts = mutableListOf<String>()
        xMatch?.let { coordParts.add("X${it.groupValues[1]}") }
        yMatch?.let { coordParts.add("Y${it.groupValues[1]}") }
        zMatch?.let { coordParts.add("Z${it.groupValues[1]}") }
        
        if (coordParts.isEmpty()) return null
        
        val coords = coordParts.joinToString(" ")
        val prefix = if (isMachine) "G53 " else ""
        val gcode = "${prefix}G0 $coords"
        val desc = "Move to $coords${if (isMachine) " (machine)" else ""}"
        
        return VoiceCommand(CommandType.MOVE, desc, gcode, confidence = result.confidence)
    }
    
    /**
     * Original regex-based parsing (fallback)
     */
    private fun parseCommandRegex(normalizedText: String): VoiceCommand? {
        // SAFETY: Block any job/run/execute file commands (unless explicitly enabled)
        val isJobStartCommand = normalizedText.contains("run job") || normalizedText.contains("start job") ||
            normalizedText.contains("run file") || normalizedText.contains("start file") ||
            normalizedText.contains("run program") || normalizedText.contains("start program") ||
            normalizedText.contains("execute") || normalizedText.contains("run gcode") ||
            normalizedText.contains("start gcode") || normalizedText.contains("load and run") ||
            normalizedText.contains("begin job") || normalizedText.contains("begin program")
        
        if (isJobStartCommand) {
            if (!config.allowJobStartCommands) {
                return VoiceCommand(CommandType.BLOCKED, "Job start blocked (enable in settings)", "BLOCKED")
            }
            // Job start is allowed - send cycle start command
            return VoiceCommand(CommandType.RESUME, "Start Job (~)", "~")
        }
        
        // Stop/hold commands - HIGHEST PRIORITY (safety critical)
        val stopPatterns = listOf(
            "stop", "halt", "hold", "pause", "cancel", "abort",
            "emergency", "e stop", "estop", "e-stop", "freeze", "wait"
        )
        if (stopPatterns.any { normalizedText.contains(it) }) {
            return VoiceCommand(CommandType.STOP, "Feed Hold (!)", "!")
        }

        // Resume commands - but NOT if it contains axis references (that would be jog)
        val resumePatterns = listOf("resume", "continue", "unpause", "un-pause")
        if (resumePatterns.any { normalizedText.contains(it) }) {
            return VoiceCommand(CommandType.RESUME, "Cycle Start (~)", "~")
        }

        // Reset command
        val resetPatterns = listOf("reset", "restart", "reboot", "soft reset")
        if (resetPatterns.any { normalizedText.contains(it) }) {
            return VoiceCommand(CommandType.RESET, "Soft Reset (0x18)", "0x18")
        }
        
        // Repeat last command
        val repeatPatterns = listOf("repeat", "again", "do that again", "same thing", "one more time", "do it again")
        if (repeatPatterns.any { normalizedText.contains(it) }) {
            if (config.lastExecutedCommand != null) {
                return VoiceCommand(CommandType.REPEAT, "Repeat: ${config.lastExecutedCommand!!.description}", "REPEAT")
            } else {
                return VoiceCommand(CommandType.BLOCKED, "No command to repeat", "BLOCKED")
            }
        }
        
        // Undo last jog movement
        val undoPatterns = listOf("undo", "go back", "reverse that", "undo that", "take it back")
        if (undoPatterns.any { normalizedText.contains(it) } && !normalizedText.contains("undo ")) {
            if (config.lastJogMoves != null && config.lastJogMoves!!.isNotEmpty()) {
                // Reverse the last jog
                val reverseMoves = config.lastJogMoves!!.mapValues { -it.value }
                val jogParts = reverseMoves.entries.map { "${it.key}${it.value}" }.joinToString(" ")
                val gcode = "\$J=G91 $jogParts F${config.currentFeed}"
                val desc = reverseMoves.entries.joinToString(", ") { 
                    val sign = if (it.value >= 0) "+" else ""
                    "${it.key}$sign${it.value}"
                }
                return VoiceCommand(CommandType.UNDO, "Undo jog: $desc", gcode, reverseMoves)
            } else {
                return VoiceCommand(CommandType.BLOCKED, "No jog to undo", "BLOCKED")
            }
        }
        
        // Tool change commands
        val toolChangePatterns = listOf("tool change", "change tool", "load tool", "switch tool", "m6", "m 6")
        if (toolChangePatterns.any { normalizedText.contains(it) }) {
            // Try to extract tool number
            val toolMatch = Regex("(?:tool|t)\\s*(\\d+)").find(normalizedText)
                ?: Regex("(\\d+)").find(normalizedText)
            val toolNum = toolMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
            return VoiceCommand(CommandType.TOOL_CHANGE, "Tool Change T$toolNum (M6 T$toolNum)", "M6 T$toolNum")
        }

        // Home commands - check for various phrasings
        val homePatterns = listOf("home", "homing", "go home", "return home", "find home")
        if (homePatterns.any { normalizedText.contains(it) }) {
            return when {
                normalizedText.contains("all") || 
                (!normalizedText.contains("x") && !normalizedText.contains("y") && !normalizedText.contains("z")) ->
                    VoiceCommand(CommandType.HOME, "Home All (\$H)", "\$H")
                normalizedText.contains("x") && !normalizedText.contains("y") && !normalizedText.contains("z") -> 
                    VoiceCommand(CommandType.HOME, "Home X (\$HX)", "\$HX")
                normalizedText.contains("y") && !normalizedText.contains("x") && !normalizedText.contains("z") -> 
                    VoiceCommand(CommandType.HOME, "Home Y (\$HY)", "\$HY")
                normalizedText.contains("z") && !normalizedText.contains("x") && !normalizedText.contains("y") -> 
                    VoiceCommand(CommandType.HOME, "Home Z (\$HZ)", "\$HZ")
                else -> VoiceCommand(CommandType.HOME, "Home All (\$H)", "\$H")
            }
        }

        // Unlock command
        val unlockPatterns = listOf("unlock", "clear alarm", "clear lock", "disable lock", "release")
        if (unlockPatterns.any { normalizedText.contains(it) }) {
            return VoiceCommand(CommandType.UNLOCK, "Unlock (\$X)", "\$X")
        }

        // Probe commands
        val probePatterns = listOf("probe", "touch off", "touch plate", "find surface", "measure")
        if (probePatterns.any { normalizedText.contains(it) }) {
            val axis = when {
                normalizedText.contains("xyz") || normalizedText.contains("all three") || 
                normalizedText.contains("all axis") || normalizedText.contains("all axes") -> "XYZ"
                normalizedText.contains("xy") || normalizedText.contains("x and y") || 
                normalizedText.contains("x y") -> "XY"
                (normalizedText.contains("x") || normalizedText.contains(" x ")) && 
                !normalizedText.contains("y") && !normalizedText.contains("z") -> "X"
                (normalizedText.contains("y") || normalizedText.contains(" y ")) && 
                !normalizedText.contains("x") && !normalizedText.contains("z") -> "Y"
                normalizedText.contains("z") || normalizedText.contains("height") || 
                normalizedText.contains("depth") -> "Z"
                normalizedText.contains("center") || normalizedText.contains("middle") -> "Center"
                else -> "Z" // Default to Z probe as it's most common
            }
            return VoiceCommand(CommandType.PROBE, "Probe $axis (${config.defaultProbeType})", "PROBE:$axis:${config.defaultProbeType}")
        }

        // Workspace/coordinate system commands
        val workspacePatterns = listOf("workspace", "work space", "coordinate", "g54", "g55", "g56", "g57", "g58", "g59")
        if (workspacePatterns.any { normalizedText.contains(it) }) {
            // Try to extract workspace number
            val wsMatch = Regex("g ?5([4-9])").find(normalizedText)
            if (wsMatch != null) {
                val ws = "G5${wsMatch.groupValues[1]}"
                return VoiceCommand(CommandType.WORKSPACE, "Switch to $ws", ws)
            }
            // Try word numbers
            val wordToWs = mapOf(
                "one" to "G54", "1" to "G54", "first" to "G54",
                "two" to "G55", "2" to "G55", "second" to "G55",
                "three" to "G56", "3" to "G56", "third" to "G56",
                "four" to "G57", "4" to "G57", "fourth" to "G57",
                "five" to "G58", "5" to "G58", "fifth" to "G58",
                "six" to "G59", "6" to "G59", "sixth" to "G59"
            )
            for ((word, ws) in wordToWs) {
                if (normalizedText.contains(word)) {
                    return VoiceCommand(CommandType.WORKSPACE, "Switch to $ws", ws)
                }
            }
        }

        // Feed rate commands - only match standalone "set feed to X" commands
        // NOT when feed is an inline parameter of a move command
        val feedSetPatterns = listOf("set feed", "set the feed", "set speed", "change feed", "feed to", "feed rate to")
        val movementWords = listOf("jog", "move", "go", "nudge", "shift", "travel", "left", "right", "forward", "back", "up", "down")
        val hasMovementWord = movementWords.any { normalizedText.contains(it) }
        
        if (feedSetPatterns.any { normalizedText.contains(it) } || 
            (!hasMovementWord && listOf("feed", "feed rate", "feedrate", "speed").any { normalizedText.contains(it) })) {
            // Extract number that follows feed/speed keyword, not just any number
            val feedMatch = Regex("(?:feed|feed rate|feedrate|speed|velocity)\\s*(?:to|rate)?\\s*(\\d+)").find(normalizedText)
            if (feedMatch != null) {
                val feedInput = feedMatch.groupValues[1].toIntOrNull()
                if (feedInput != null && feedInput in 1..50000) {
                    // If imperial, convert in/min to mm/min
                    val isMetric = config.unitsPreference == "metric"
                    val feedMm = if (isMetric) feedInput else (feedInput * 25.4).toInt()
                    config.currentFeed = feedMm
                    settingsChangeListener?.onFeedChanged(feedMm)
                    val displayUnit = if (isMetric) "mm/min" else "in/min"
                    return VoiceCommand(CommandType.SETTING, "Set feed to $feedInput $displayUnit", "FEED:$feedMm")
                }
            }
        }

        // Step size commands - only match standalone "set step to X" commands
        // NOT when step is an inline parameter of a move command
        val stepSetPatterns = listOf("set step", "set the step", "set increment", "change step", "step size to", "step to")
        
        if (stepSetPatterns.any { normalizedText.contains(it) } || 
            (!hasMovementWord && listOf("step size", "increment", "jog distance", "move distance").any { normalizedText.contains(it) })) {
            // Extract number that follows step keyword
            val stepMatch = Regex("(?:step|step size|increment)\\s*(?:to|size)?\\s*(\\d+\\.?\\d*)").find(normalizedText)
            if (stepMatch != null) {
                val stepInput = stepMatch.groupValues[1].toFloatOrNull()
                if (stepInput != null && stepInput in 0.001f..100f) {
                    // If imperial, convert inches to mm
                    val isMetric = config.unitsPreference == "metric"
                    val stepMm = if (isMetric) stepInput else stepInput * 25.4f
                    config.currentStep = stepMm
                    settingsChangeListener?.onStepChanged(stepMm)
                    val displayUnit = if (isMetric) "mm" else "in"
                    return VoiceCommand(CommandType.SETTING, "Set step to $stepInput $displayUnit", "STEP:$stepMm")
                }
            }
        }

        // Probe type commands - "set probe to 3d" or "use standard block" or "what probe"
        val probeTypePatterns = listOf("set probe", "use probe", "probe type", "switch probe", "change probe")
        if (probeTypePatterns.any { normalizedText.contains(it) }) {
            val newProbeType = when {
                normalizedText.contains("3d") || normalizedText.contains("three d") || normalizedText.contains("3 d") -> "3d-probe"
                normalizedText.contains("standard") || normalizedText.contains("block") -> "standard-block"
                normalizedText.contains("auto") || normalizedText.contains("touch") || normalizedText.contains("zero") -> "autozero-touch"
                else -> null
            }
            if (newProbeType != null) {
                config.defaultProbeType = newProbeType
                settingsChangeListener?.onProbeTypeChanged(newProbeType)
                return VoiceCommand(CommandType.SETTING, "Probe set to ${getProbeDisplayName(newProbeType)}", "PROBE_TYPE:$newProbeType")
            }
        }

        // Check/query probe type - "what probe" or "check probe" or "current probe"
        val checkProbePatterns = listOf("what probe", "which probe", "check probe", "current probe", "show probe", "probe status")
        if (checkProbePatterns.any { normalizedText.contains(it) }) {
            val probeName = getProbeDisplayName(config.defaultProbeType)
            return VoiceCommand(CommandType.SETTING, "Current probe: $probeName", "PROBE_CHECK")
        }

        // Jog/Move commands - supports compound moves like "move left 50 and forward 50"
        val jogPatterns = listOf("jog", "move", "go", "nudge", "shift", "travel")
        if (jogPatterns.any { normalizedText.contains(it) }) {
            return parseJogCommand(normalizedText)
        }

        // Zero/origin commands
        // Note: "zero" may have been converted to "0" by convertWordsToNumbers, so check for both
        val zeroPatterns = listOf("zero", "0 x", "0 y", "0 z", "0 all", "0 xy", "set zero", "set origin", "origin", "set work", "work zero", "wcs zero")
        if (zeroPatterns.any { normalizedText.contains(it) }) {
            val axis = when {
                normalizedText.contains("all") || normalizedText.contains("xyz") ||
                normalizedText == "zero" || normalizedText == "0" ||
                (!normalizedText.contains("x") && !normalizedText.contains("y") && !normalizedText.contains("z")) -> "XYZ"
                normalizedText.contains("xy") || normalizedText.contains("x and y") -> "XY"
                normalizedText.contains("x") && !normalizedText.contains("y") && !normalizedText.contains("z") -> "X"
                normalizedText.contains("y") && !normalizedText.contains("x") && !normalizedText.contains("z") -> "Y"
                normalizedText.contains("z") -> "Z"
                else -> "XYZ"
            }

            val gcodes = when (axis) {
                "X" -> "G10 L20 P1 X0"
                "Y" -> "G10 L20 P1 Y0"
                "Z" -> "G10 L20 P1 Z0"
                "XY" -> "G10 L20 P1 X0 Y0"
                else -> "G10 L20 P1 X0 Y0 Z0"
            }
            return VoiceCommand(CommandType.ZERO, "Zero $axis", gcodes)
        }
        
        // Query commands - ask about machine state
        val distanceQueryPatterns = listOf(
            "how far", "distance to", "distance from", "far from", "away from"
        )
        if (distanceQueryPatterns.any { normalizedText.contains(it) }) {
            return when {
                normalizedText.contains("zero") || normalizedText.contains("origin") || normalizedText.contains("work") ->
                    VoiceCommand(CommandType.QUERY, "Distance from work zero", "QUERY:DISTANCE_WORK")
                normalizedText.contains("home") || normalizedText.contains("machine") ->
                    VoiceCommand(CommandType.QUERY, "Distance from machine home", "QUERY:DISTANCE_MACHINE")
                else -> VoiceCommand(CommandType.QUERY, "Distance from work zero", "QUERY:DISTANCE_WORK")
            }
        }
        
        // Travel remaining queries - "how much travel left on Z"
        if (normalizedText.contains("travel") && (normalizedText.contains("left") || normalizedText.contains("remaining") || normalizedText.contains("available"))) {
            val axis = when {
                normalizedText.contains(" x") || normalizedText.contains("x ") -> "X"
                normalizedText.contains(" y") || normalizedText.contains("y ") -> "Y"
                normalizedText.contains(" z") || normalizedText.contains("z ") -> "Z"
                else -> "ALL"
            }
            return VoiceCommand(CommandType.QUERY, "Query travel remaining $axis", "QUERY:TRAVEL:$axis")
        }
        
        // Position query
        if ((normalizedText.contains("what") || normalizedText.contains("where") || normalizedText.contains("tell") || normalizedText.contains("read")) &&
            (normalizedText.contains("position") || normalizedText.contains("location") || normalizedText.contains("coordinates"))) {
            return VoiceCommand(CommandType.QUERY, "Query Position", "QUERY:POSITION")
        }
        
        // Status query  
        if ((normalizedText.contains("what") || normalizedText.contains("machine")) &&
            (normalizedText.contains("status") || normalizedText.contains("state"))) {
            return VoiceCommand(CommandType.QUERY, "Query Status", "QUERY:STATUS")
        }
        
        // Feed/step query
        if (normalizedText.contains("what") && normalizedText.contains("feed") && !normalizedText.contains("set")) {
            return VoiceCommand(CommandType.QUERY, "Query Feed Rate", "QUERY:FEED")
        }
        if (normalizedText.contains("what") && normalizedText.contains("step") && !normalizedText.contains("set")) {
            return VoiceCommand(CommandType.QUERY, "Query Step Size", "QUERY:STEP")
        }
        
        // Spindle commands
        val spindleOnPatterns = listOf("spindle on", "start spindle", "turn on spindle", "spindle start", "run spindle")
        val spindleOffPatterns = listOf("spindle off", "stop spindle", "turn off spindle", "spindle stop", "kill spindle")
        val spindleCWPatterns = listOf("spindle clockwise", "spindle cw", "spindle forward")
        val spindleCCWPatterns = listOf("spindle counterclockwise", "spindle ccw", "spindle reverse", "spindle counter")
        
        if (spindleOffPatterns.any { normalizedText.contains(it) }) {
            return VoiceCommand(CommandType.SPINDLE, "Spindle OFF (M5)", "M5")
        }
        if (spindleCCWPatterns.any { normalizedText.contains(it) }) {
            // Try to extract RPM
            val rpmMatch = Regex("(\\d+)\\s*(?:rpm)?").find(normalizedText)
            val rpm = rpmMatch?.groupValues?.get(1)?.toIntOrNull() ?: 10000
            return VoiceCommand(CommandType.SPINDLE, "Spindle CCW at $rpm RPM (M4)", "M4 S$rpm")
        }
        if (spindleCWPatterns.any { normalizedText.contains(it) } || spindleOnPatterns.any { normalizedText.contains(it) }) {
            // Try to extract RPM
            val rpmMatch = Regex("(\\d+)\\s*(?:rpm)?").find(normalizedText)
            val rpm = rpmMatch?.groupValues?.get(1)?.toIntOrNull() ?: 10000
            return VoiceCommand(CommandType.SPINDLE, "Spindle CW at $rpm RPM (M3)", "M3 S$rpm")
        }
        
        // Set spindle speed without turning on
        if ((normalizedText.contains("spindle") || normalizedText.contains("rpm")) && 
            normalizedText.contains("speed") || normalizedText.contains("set spindle")) {
            val rpmMatch = Regex("(\\d+)").find(normalizedText)
            val rpm = rpmMatch?.groupValues?.get(1)?.toIntOrNull()
            if (rpm != null) {
                return VoiceCommand(CommandType.SPINDLE, "Set Spindle Speed to $rpm RPM", "S$rpm")
            }
        }
        
        // Coolant commands
        val coolantOnPatterns = listOf("coolant on", "start coolant", "turn on coolant", "flood on", "flood coolant")
        val coolantOffPatterns = listOf("coolant off", "stop coolant", "turn off coolant", "flood off")
        val mistOnPatterns = listOf("mist on", "start mist", "turn on mist", "mist coolant")
        val mistOffPatterns = listOf("mist off", "stop mist", "turn off mist")
        
        if (coolantOffPatterns.any { normalizedText.contains(it) } || mistOffPatterns.any { normalizedText.contains(it) }) {
            return VoiceCommand(CommandType.COOLANT, "Coolant OFF (M9)", "M9")
        }
        if (mistOnPatterns.any { normalizedText.contains(it) }) {
            return VoiceCommand(CommandType.COOLANT, "Mist Coolant ON (M7)", "M7")
        }
        if (coolantOnPatterns.any { normalizedText.contains(it) }) {
            return VoiceCommand(CommandType.COOLANT, "Flood Coolant ON (M8)", "M8")
        }

        return null
    }
    
    /**
     * Parse compound jog commands like "move left 50 and forward 50"
     */
    private fun parseJogCommand(normalizedText: String): VoiceCommand? {
        val isMachine = normalizedText.contains("machine")

        // Parse axis and value for absolute moves
        val xMatch = Regex("x ?(-?\\d+\\.?\\d*)").find(normalizedText)
        val yMatch = Regex("y ?(-?\\d+\\.?\\d*)").find(normalizedText)
        val zMatch = Regex("z ?(-?\\d+\\.?\\d*)").find(normalizedText)

        // Parse compound directional moves (e.g., "move left 50 and forward 50")
        val moves = mutableMapOf<String, Float>()
        val isMetric = config.unitsPreference == "metric"
        
        // Split on "and" to handle compound commands
        val parts = normalizedText.split(" and ", ",")
        
        // Extract feed rate and step values FIRST so we can exclude them from distance parsing
        val feedOverrideForExclusion = Regex("(?:at )?feed\\s*(?:rate)?\\s*(\\d+)").find(normalizedText)
        val stepOverrideForExclusion = Regex("step\\s*(\\d+\\.?\\d*)").find(normalizedText)
        val feedValue = feedOverrideForExclusion?.groupValues?.get(1)
        val stepValue = stepOverrideForExclusion?.groupValues?.get(1)
        
        for (part in parts) {
            // Find the distance in this part (or use current step)
            // Exclude numbers that belong to feed rate or step parameters
            val distMatch = Regex("(\\d+\\.?\\d*)\\s*(?:mm|millimeter|in|inch)?").findAll(part)
                .filter { match ->
                    val value = match.groupValues[1]
                    // Exclude if this number is part of "feed [rate] X" or "step X"
                    val matchStart = match.range.first
                    val textBefore = part.substring(0, matchStart).lowercase()
                    val isFeedNumber = textBefore.endsWith("feed ") || textBefore.endsWith("feed rate ") || textBefore.endsWith("rate ")
                    val isStepNumber = textBefore.endsWith("step ")
                    !isFeedNumber && !isStepNumber && value != feedValue && value != stepValue
                }
                .firstOrNull()
            var distance = distMatch?.groupValues?.get(1)?.toFloatOrNull() ?: config.currentStep
            
            // If user specified a number without explicit unit, convert based on preference
            if (distMatch != null && distance != config.currentStep) {
                // User said a specific distance - convert if imperial
                if (!isMetric) {
                    distance *= 25.4f  // Convert inches to mm
                }
            }
            
            // Check for each direction and accumulate moves
            when {
                part.contains("left") -> moves["X"] = (moves["X"] ?: 0f) + (distance * getXDirectionLeft())
                part.contains("right") -> moves["X"] = (moves["X"] ?: 0f) + (distance * getXDirectionRight())
            }
            when {
                part.contains("forward") || part.contains("front") -> moves["Y"] = (moves["Y"] ?: 0f) + (distance * getYDirectionForward())
                part.contains("back") || part.contains("rear") -> moves["Y"] = (moves["Y"] ?: 0f) + (distance * getYDirectionBack())
            }
            when {
                part.contains("up") -> moves["Z"] = (moves["Z"] ?: 0f) + distance
                part.contains("down") -> moves["Z"] = (moves["Z"] ?: 0f) - distance
            }
            
            // Also check for explicit axis + direction (e.g., "x plus", "y minus")
            if (part.contains("x") && !part.contains("xy")) {
                val dir = when {
                    part.contains("plus") || part.contains("positive") -> 1f
                    part.contains("minus") || part.contains("negative") -> -1f
                    else -> null
                }
                if (dir != null) moves["X"] = (moves["X"] ?: 0f) + (distance * dir)
            }
            if (part.contains("y") && !part.contains("xy")) {
                val dir = when {
                    part.contains("plus") || part.contains("positive") -> 1f
                    part.contains("minus") || part.contains("negative") -> -1f
                    else -> null
                }
                if (dir != null) moves["Y"] = (moves["Y"] ?: 0f) + (distance * dir)
            }
            if (part.contains("z")) {
                val dir = when {
                    part.contains("plus") || part.contains("positive") -> 1f
                    part.contains("minus") || part.contains("negative") -> -1f
                    else -> null
                }
                if (dir != null) moves["Z"] = (moves["Z"] ?: 0f) + (distance * dir)
            }
        }
        
        // If we found directional moves, generate jog command
        if (moves.isNotEmpty()) {
            // Check for speed modifiers
            val speedModifier = when {
                normalizedText.contains("slowly") || normalizedText.contains("slow") || 
                normalizedText.contains("careful") || normalizedText.contains("gently") -> 0.1f  // 10% feed
                normalizedText.contains("creep") || normalizedText.contains("inch") || 
                normalizedText.contains("crawl") || normalizedText.contains("very slow") -> 0.05f  // 5% feed
                normalizedText.contains("fast") || normalizedText.contains("quick") || 
                normalizedText.contains("rapid") -> 3.0f  // 300% feed (up to max)
                normalizedText.contains("half") || normalizedText.contains("medium") -> 0.5f  // 50% feed
                else -> 1.0f
            }
            
            // Check for inline feed override: "at feed 6000", "feed 6000", "at feed rate 6000", "feed rate 6000"
            val feedOverrideMatch = Regex("(?:at )?feed\\s*(?:rate)?\\s*(\\d+)").find(normalizedText)
            var jogFeed = if (feedOverrideMatch != null) {
                val feedInput = feedOverrideMatch.groupValues[1].toIntOrNull() ?: config.currentFeed
                // Convert if imperial
                if (isMetric) feedInput else (feedInput * 25.4).toInt()
            } else {
                config.currentFeed
            }
            
            // Apply speed modifier
            jogFeed = (jogFeed * speedModifier).toInt().coerceIn(10, 50000)
            
            // Check for inline step override: "step 10" - this overrides the distance for all moves
            val stepOverrideMatch = Regex("step\\s*(\\d+\\.?\\d*)").find(normalizedText)
            if (stepOverrideMatch != null) {
                val stepInput = stepOverrideMatch.groupValues[1].toFloatOrNull()
                if (stepInput != null) {
                    // Convert if imperial
                    val stepMm = if (isMetric) stepInput else stepInput * 25.4f
                    // Re-calculate moves with the new step size, preserving direction
                    moves.forEach { (axis, value) ->
                        val direction = if (value >= 0) 1f else -1f
                        moves[axis] = stepMm * direction
                    }
                }
            }
            
            val jogParts = moves.entries.map { "${it.key}${it.value}" }.joinToString(" ")
            val gcode = "\$J=G91 $jogParts F$jogFeed"
            val desc = moves.entries.joinToString(", ") { 
                val sign = if (it.value >= 0) "+" else ""
                "${it.key}$sign${it.value}"
            }
            val speedDesc = when {
                speedModifier < 0.1f -> " (creep)"
                speedModifier < 0.5f -> " (slow)"
                speedModifier > 1.5f -> " (fast)"
                else -> ""
            }
            return VoiceCommand(CommandType.JOG, "Jog $desc at F$jogFeed$speedDesc", gcode, moves.toMap())
        }

        // Absolute move with coordinates (e.g., "move to x 100 y 200")
        if (xMatch != null || yMatch != null || zMatch != null) {
            val coordParts = mutableListOf<String>()
            xMatch?.let { coordParts.add("X${it.groupValues[1]}") }
            yMatch?.let { coordParts.add("Y${it.groupValues[1]}") }
            zMatch?.let { coordParts.add("Z${it.groupValues[1]}") }

            if (coordParts.isNotEmpty()) {
                val coords = coordParts.joinToString(" ")
                val prefix = if (isMachine) "G53 " else ""
                val gcode = "${prefix}G0 $coords"
                val desc = "Move to $coords${if (isMachine) " (machine)" else ""}"
                return VoiceCommand(CommandType.MOVE, desc, gcode)
            }
        }
        
        return null
    }
    
    /**
     * Parse chained commands separated by "then" keyword
     */
    fun parseChainedCommands(text: String): List<VoiceCommand> {
        val commands = mutableListOf<VoiceCommand>()
        val parts = text.split(" then ", ", then ", " and then ")
        
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isNotEmpty()) {
                val command = parseCommand(trimmed)
                if (command != null && command.type != CommandType.BLOCKED) {
                    commands.add(command)
                }
            }
        }
        
        return commands
    }
    
    /**
     * Check if text contains chained commands
     */
    fun hasChainedCommands(text: String): Boolean {
        return text.contains(" then ") || text.contains(", then ") || text.contains(" and then ")
    }
    
    /**
     * Preprocess voice input: convert number words to digits
     */
    fun preprocessInput(text: String): String {
        return convertWordsToNumbers(text.lowercase())
    }
    
    /**
     * Convert spoken number words to digits
     */
    private fun convertWordsToNumbers(text: String): String {
        var result = text
        
        // Handle "point five" -> ".5" patterns for decimals
        result = result.replace(Regex("point (\\w+)")) { match ->
            val word = match.groupValues[1]
            val num = numberWords[word]
            if (num != null && num >= 0) ".${num}" else match.value
        }
        
        // Handle compound numbers like "twenty five" -> "25"
        val tensWords = listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
        for (tens in tensWords) {
            val tensValue = numberWords[tens] ?: continue
            for ((ones, onesValue) in numberWords) {
                if (onesValue in 1..9) {
                    result = result.replace("$tens $ones", (tensValue + onesValue).toString())
                    result = result.replace("$tens-$ones", (tensValue + onesValue).toString())
                }
            }
        }
        
        // Handle "X hundred" patterns
        result = result.replace(Regex("(\\w+) hundred")) { match ->
            val word = match.groupValues[1]
            val num = numberWords[word]
            if (num != null && num in 1..9) "${num * 100}" else match.value
        }
        
        // Handle "X thousand" patterns  
        result = result.replace(Regex("(\\w+) thousand")) { match ->
            val word = match.groupValues[1]
            val num = numberWords[word]
            if (num != null && num in 1..99) "${num * 1000}" else match.value
        }
        
        // Simple word replacements
        for ((word, num) in numberWords) {
            if (num >= 0) {
                result = result.replace(Regex("\\b$word\\b"), num.toString())
            }
        }
        
        // Clean up multiple spaces
        result = result.replace(Regex("\\s+"), " ")
        
        return result
    }
    
    /**
     * Normalize text to handle common speech recognition misinterpretations.
     * Uses both direct substitution and fuzzy matching for robustness.
     */
    private fun normalizeText(text: String): String {
        var result = text
        
        // Remove commas from numbers (e.g., "6,000" -> "6000")
        // This handles speech recognition that formats numbers with commas
        result = result.replace(Regex("(\\d),(?=\\d{3})"), "$1")
        
        // First pass: Direct misrecognition replacements (fastest)
        
        // Common misrecognitions for axes
        result = result.replace("ex ", "x ")
        result = result.replace(" ex", " x")
        result = result.replace("eggs", "x")
        result = result.replace("axe ", "x ")
        result = result.replace("access", "axis")
        result = result.replace(Regex("\\bwhy\\b"), "y")
        result = result.replace("zee", "z")
        result = result.replace("zed", "z")
        result = result.replace(Regex("\\bsaid\\b"), "z")
        
        // Common command misrecognitions - use word boundaries to avoid false matches
        result = result.replace(Regex("\\bwhole\\b"), "hold")
        result = result.replace(Regex("\\bhope\\b"), "home")
        result = result.replace(Regex("\\bhone\\b"), "home")
        result = result.replace(Regex("\\bown\\b"), "home") // Only standalone "own", not "down"
        result = result.replace("probably", "probe")
        result = result.replace("jogging", "jog")
        result = result.replace(Regex("\\bjack\\b"), "jog")
        result = result.replace(Regex("\\bjoke\\b"), "jog")
        result = result.replace("unlock it", "unlock")
        result = result.replace("on lock", "unlock")
        
        // Movement directions - use word boundaries
        result = result.replace(Regex("\\bwrite\\b"), "right")
        result = result.replace(Regex("\\brite\\b"), "right")
        result = result.replace(Regex("\\bwright\\b"), "right")
        result = result.replace(Regex("\\blaughed\\b"), "left")
        result = result.replace(Regex("\\blaugh\\b"), "left")
        result = result.replace(Regex("\\blift\\b"), "left")
        result = result.replace(Regex("\\bdone\\b"), "down")
        result = result.replace(Regex("\\bdawn\\b"), "down")
        result = result.replace(Regex("\\btown\\b"), "down")
        result = result.replace("four word", "forward")
        result = result.replace("foreword", "forward")
        result = result.replace(Regex("\\bbeck\\b"), "back")
        result = result.replace(Regex("\\bbake\\b"), "back")
        
        // Numbers that might be misheard - be more careful with word boundaries
        result = result.replace(Regex("\\bwon\\b"), "1")
        result = result.replace(Regex("\\bate\\b"), "8")
        result = result.replace(Regex("\\bnein\\b"), "9")
        
        // Feed/speed
        result = result.replace(Regex("\\bfeet\\b"), "feed")
        result = result.replace(Regex("\\bfeat\\b"), "feed")
        result = result.replace(Regex("\\bfreed\\b"), "feed")
        
        // Step
        result = result.replace(Regex("\\bstab\\b"), "step")
        result = result.replace(Regex("\\bstuff\\b"), "step")
        result = result.replace("stop size", "step size")
        
        // Second pass: Fuzzy matching for commands that weren't caught by direct replacement
        result = applyFuzzyCorrections(result)
        
        return result
    }
    
    /**
     * Apply fuzzy matching corrections for words that might have been misrecognized
     * but weren't caught by direct substitutions
     */
    private fun applyFuzzyCorrections(text: String): String {
        val words = text.split(" ").toMutableList()
        var changed = false
        
        // Command keywords to check with fuzzy matching
        val commandKeywords = mapOf(
            "jog" to listOf("job", "dog", "hog", "log", "fog", "jag"),
            "move" to listOf("moove", "muve", "mov"),
            "home" to listOf("ohm", "hm", "hum", "foam"),
            "probe" to listOf("prob", "proba", "prode"),
            "stop" to listOf("top", "stoop", "stopp"),
            "reset" to listOf("resit", "recet", "reet"),
            "zero" to listOf("xero", "sero", "zeero"),
            "unlock" to listOf("unlog", "anlock"),
            "feed" to listOf("fead", "fid", "fede"),
            "step" to listOf("stap", "stepp", "stp"),
            "spindle" to listOf("spindel", "spindal", "spinle"),
            "coolant" to listOf("coolent", "coolat", "cooland"),
            "left" to listOf("lef", "lefft", "leaft"),
            "right" to listOf("rite", "rihgt", "rightt"),
            "forward" to listOf("foward", "forwad", "forword"),
            "back" to listOf("bak", "bakc", "bacck"),
            "up" to listOf("upp", "uhp"),
            "down" to listOf("dwon", "don", "donw")
        )
        
        for (i in words.indices) {
            val word = words[i].lowercase()
            
            // Check each command keyword
            for ((correct, variants) in commandKeywords) {
                // Check if word matches any known variant
                if (word in variants) {
                    words[i] = correct
                    changed = true
                    break
                }
                
                // Use fuzzy matching for very close matches
                if (FuzzyMatcher.isSimilar(word, correct, 0.85f) && word != correct) {
                    // Only replace if it's very similar but not already the correct word
                    if (word.length >= 3) {  // Avoid matching very short words
                        words[i] = correct
                        changed = true
                        break
                    }
                }
            }
        }
        
        return if (changed) words.joinToString(" ") else text
    }
    
    // Direction helpers based on home location
    
    private fun getYDirectionForward(): Float {
        // If home is at back (Y+), forward is Y-
        // If home is at front (Y-), forward is Y+
        return if (config.homeLocation.startsWith("back")) -1f else 1f
    }
    
    private fun getYDirectionBack(): Float {
        return -getYDirectionForward()
    }
    
    private fun getXDirectionLeft(): Float {
        // Left is always X- regardless of home position
        return -1f
    }
    
    private fun getXDirectionRight(): Float {
        return 1f
    }
    
    /**
     * Get human-readable probe type name
     */
    private fun getProbeDisplayName(probeType: String): String {
        return when (probeType) {
            "3d-probe" -> "3D Probe"
            "standard-block" -> "Standard Block"
            "autozero-touch" -> "AutoZero Touch"
            else -> probeType
        }
    }
}
