package com.cncpendant.app.voice

import android.util.Log

/**
 * Natural Language Understanding (NLU) system for voice command parsing.
 * Uses intent classification and entity extraction for robust command recognition.
 * 
 * This lightweight on-device NLU approach provides:
 * - Intent detection (what action the user wants)
 * - Entity extraction (parameters like axis, distance, direction)
 * - Fuzzy matching for speech variations
 * - Confidence scoring for ambiguous commands
 */
class VoiceNLU {
    
    companion object {
        private const val TAG = "VoiceNLU"
        
        // Confidence thresholds
        const val HIGH_CONFIDENCE = 0.85f
        const val MEDIUM_CONFIDENCE = 0.7f
        const val LOW_CONFIDENCE = 0.5f
    }
    
    /**
     * Recognized intents for CNC commands
     */
    enum class Intent {
        // Motion intents
        JOG,                // Relative movement
        MOVE_ABSOLUTE,      // Absolute positioning
        HOME,               // Homing operation
        
        // Control intents
        STOP,               // Emergency stop / feed hold
        RESUME,             // Continue operation
        RESET,              // Soft reset
        UNLOCK,             // Clear alarm
        
        // Probing intents
        PROBE,              // Touch probe operation
        
        // Setting intents
        SET_FEED,           // Change feed rate
        SET_STEP,           // Change step size
        SET_PROBE_TYPE,     // Change probe type
        SET_WORKSPACE,      // Change coordinate system
        
        // Zero/Origin intents
        ZERO_AXIS,          // Set work coordinate zero
        
        // Spindle intents
        SPINDLE_ON,
        SPINDLE_OFF,
        SET_SPINDLE_SPEED,
        
        // Coolant intents
        COOLANT_ON,
        COOLANT_OFF,
        MIST_ON,
        
        // Tool intents
        TOOL_CHANGE,
        
        // Query intents
        QUERY_POSITION,
        QUERY_STATUS,
        QUERY_FEED,
        QUERY_STEP,
        QUERY_DISTANCE,
        
        // Meta intents
        REPEAT,
        UNDO,
        CONFIRM,
        CANCEL,
        
        // Special
        START_JOB,          // Blocked by default
        UNKNOWN
    }
    
    /**
     * Entity types that can be extracted from commands
     */
    enum class EntityType {
        AXIS,           // X, Y, Z, XY, XYZ
        DIRECTION,      // left, right, forward, back, up, down, plus, minus
        DISTANCE,       // numeric distance value
        FEED_RATE,      // numeric feed rate
        STEP_SIZE,      // step/increment size
        SPEED_MODIFIER, // slow, fast, creep, etc.
        SPINDLE_RPM,    // spindle speed
        TOOL_NUMBER,    // tool number for changes
        WORKSPACE,      // G54-G59
        PROBE_TYPE,     // 3d-probe, standard-block, autozero-touch
        COORDINATE,     // absolute coordinate value
        BOOLEAN,        // on/off, yes/no
    }
    
    /**
     * Extracted entity with value and confidence
     */
    data class Entity(
        val type: EntityType,
        val value: Any,
        val rawText: String,
        val confidence: Float = 1.0f
    )
    
    /**
     * NLU parsing result
     */
    data class NLUResult(
        val intent: Intent,
        val confidence: Float,
        val entities: List<Entity>,
        val rawText: String,
        val alternativeIntents: List<Pair<Intent, Float>> = emptyList()
    ) {
        fun getEntity(type: EntityType): Entity? = entities.find { it.type == type }
        fun getEntities(type: EntityType): List<Entity> = entities.filter { it.type == type }
        fun hasEntity(type: EntityType): Boolean = entities.any { it.type == type }
        
        fun isHighConfidence(): Boolean = confidence >= HIGH_CONFIDENCE
        fun isMediumConfidence(): Boolean = confidence >= MEDIUM_CONFIDENCE
        fun isLowConfidence(): Boolean = confidence >= LOW_CONFIDENCE && confidence < MEDIUM_CONFIDENCE
    }
    
    // Intent patterns with their trigger words/phrases
    private val intentPatterns = mapOf(
        Intent.STOP to listOf(
            "stop", "halt", "hold", "pause", "cancel", "abort",
            "emergency", "e stop", "estop", "freeze", "wait"
        ),
        Intent.RESUME to listOf(
            "resume", "continue", "unpause", "go ahead", "proceed", "start"
        ),
        Intent.RESET to listOf(
            "reset", "restart", "reboot", "soft reset", "clear"
        ),
        Intent.HOME to listOf(
            "home", "homing", "go home", "return home", "find home"
        ),
        Intent.UNLOCK to listOf(
            "unlock", "clear alarm", "clear lock", "release", "disable lock"
        ),
        Intent.JOG to listOf(
            "jog", "move", "go", "nudge", "shift", "travel",
            "left", "right", "forward", "back", "up", "down"
        ),
        Intent.MOVE_ABSOLUTE to listOf(
            "move to", "go to", "position to", "rapid to"
        ),
        Intent.PROBE to listOf(
            "probe", "touch off", "touch plate", "find surface", "measure", "probing"
        ),
        Intent.SET_FEED to listOf(
            "set feed", "set the feed", "change feed", "feed to", "set speed", "change speed"
        ),
        Intent.SET_STEP to listOf(
            "set step", "set the step", "step size to", "set increment", "change step"
        ),
        Intent.SET_PROBE_TYPE to listOf(
            "set probe", "use probe", "probe type", "switch probe", "change probe"
        ),
        Intent.SET_WORKSPACE to listOf(
            "workspace", "work space", "coordinate system", "g54", "g55", "g56", "g57", "g58", "g59"
        ),
        Intent.ZERO_AXIS to listOf(
            "zero", "set zero", "set origin", "origin", "work zero", "wcs zero"
        ),
        Intent.SPINDLE_ON to listOf(
            "spindle on", "start spindle", "turn on spindle", "run spindle",
            "spindle clockwise", "spindle cw", "spindle counterclockwise", "spindle ccw"
        ),
        Intent.SPINDLE_OFF to listOf(
            "spindle off", "stop spindle", "turn off spindle", "kill spindle"
        ),
        Intent.SET_SPINDLE_SPEED to listOf(
            "spindle speed", "set rpm", "rpm to"
        ),
        Intent.COOLANT_ON to listOf(
            "coolant on", "start coolant", "turn on coolant", "flood on", "flood coolant"
        ),
        Intent.COOLANT_OFF to listOf(
            "coolant off", "stop coolant", "turn off coolant", "flood off"
        ),
        Intent.MIST_ON to listOf(
            "mist on", "start mist", "turn on mist", "mist coolant"
        ),
        Intent.TOOL_CHANGE to listOf(
            "tool change", "change tool", "load tool", "switch tool", "m6"
        ),
        Intent.QUERY_POSITION to listOf(
            "what position", "where am i", "current position", "read position",
            "tell me position", "show position", "what's my position"
        ),
        Intent.QUERY_STATUS to listOf(
            "what status", "machine status", "current status", "what state"
        ),
        Intent.QUERY_FEED to listOf(
            "what feed", "current feed", "what's the feed", "show feed"
        ),
        Intent.QUERY_STEP to listOf(
            "what step", "current step", "what's the step", "show step"
        ),
        Intent.QUERY_DISTANCE to listOf(
            "how far", "distance to", "distance from", "far from", "away from"
        ),
        Intent.REPEAT to listOf(
            "repeat", "again", "do that again", "same thing", "one more time", "do it again"
        ),
        Intent.UNDO to listOf(
            "undo", "go back", "reverse that", "undo that", "take it back"
        ),
        Intent.CONFIRM to listOf(
            "yes", "yeah", "yep", "confirm", "affirmative", "do it", "execute", "ok", "okay", "proceed"
        ),
        Intent.CANCEL to listOf(
            "no", "nope", "cancel", "abort", "stop", "never mind", "nevermind", "don't", "negative"
        ),
        Intent.START_JOB to listOf(
            "run job", "start job", "run file", "start file", "run program",
            "start program", "execute", "run gcode", "begin job"
        )
    )
    
    // Entity extraction patterns
    private val axisPatterns = mapOf(
        "x" to "X", "ex" to "X", "eggs" to "X",
        "y" to "Y", "why" to "Y",
        "z" to "Z", "zee" to "Z", "zed" to "Z",
        "xy" to "XY", "x and y" to "XY", "x y" to "XY",
        "xyz" to "XYZ", "all" to "XYZ", "all axes" to "XYZ", "all axis" to "XYZ"
    )
    
    private val directionPatterns = mapOf(
        "left" to Direction.LEFT,
        "right" to Direction.RIGHT,
        "forward" to Direction.FORWARD,
        "front" to Direction.FORWARD,
        "ahead" to Direction.FORWARD,
        "back" to Direction.BACK,
        "backward" to Direction.BACK,
        "rear" to Direction.BACK,
        "up" to Direction.UP,
        "down" to Direction.DOWN,
        "plus" to Direction.POSITIVE,
        "positive" to Direction.POSITIVE,
        "minus" to Direction.NEGATIVE,
        "negative" to Direction.NEGATIVE
    )
    
    private val speedModifiers = mapOf(
        "slowly" to SpeedModifier.SLOW,
        "slow" to SpeedModifier.SLOW,
        "careful" to SpeedModifier.SLOW,
        "gently" to SpeedModifier.SLOW,
        "creep" to SpeedModifier.CREEP,
        "crawl" to SpeedModifier.CREEP,
        "inch" to SpeedModifier.CREEP,
        "very slow" to SpeedModifier.CREEP,
        "fast" to SpeedModifier.FAST,
        "quick" to SpeedModifier.FAST,
        "rapid" to SpeedModifier.FAST,
        "half" to SpeedModifier.MEDIUM,
        "medium" to SpeedModifier.MEDIUM
    )
    
    private val probeTypes = mapOf(
        "3d" to "3d-probe",
        "three d" to "3d-probe",
        "3 d" to "3d-probe",
        "standard" to "standard-block",
        "block" to "standard-block",
        "auto" to "autozero-touch",
        "touch" to "autozero-touch",
        "autozero" to "autozero-touch"
    )
    
    enum class Direction {
        LEFT, RIGHT, FORWARD, BACK, UP, DOWN, POSITIVE, NEGATIVE
    }
    
    enum class SpeedModifier(val multiplier: Float) {
        CREEP(0.05f),   // 5% feed
        SLOW(0.1f),     // 10% feed
        MEDIUM(0.5f),   // 50% feed
        NORMAL(1.0f),   // 100% feed
        FAST(3.0f)      // 300% feed
    }
    
    /**
     * Parse natural language input into structured NLU result
     */
    fun parse(input: String): NLUResult {
        val normalizedInput = input.lowercase().trim()
        Log.d(TAG, "Parsing: $normalizedInput")
        
        // Classify intent with fuzzy matching
        val (intent, confidence, alternatives) = classifyIntent(normalizedInput)
        
        // Extract entities based on detected intent
        val entities = extractEntities(normalizedInput, intent)
        
        return NLUResult(
            intent = intent,
            confidence = confidence,
            entities = entities,
            rawText = input,
            alternativeIntents = alternatives
        )
    }
    
    /**
     * Classify the intent of the input with confidence scoring
     */
    private fun classifyIntent(input: String): Triple<Intent, Float, List<Pair<Intent, Float>>> {
        val scores = mutableMapOf<Intent, Float>()
        
        for ((intent, patterns) in intentPatterns) {
            var maxScore = 0f
            
            for (pattern in patterns) {
                // Exact match
                if (input.contains(pattern)) {
                    maxScore = maxOf(maxScore, 1.0f)
                    continue
                }
                
                // Fuzzy match
                val fuzzyMatch = FuzzyMatcher.fuzzyContains(input, listOf(pattern), 0.75f)
                if (fuzzyMatch != null) {
                    val similarity = FuzzyMatcher.similarity(fuzzyMatch, pattern)
                    maxScore = maxOf(maxScore, similarity * 0.95f) // Slight penalty for fuzzy
                }
            }
            
            if (maxScore > 0) {
                scores[intent] = maxScore
            }
        }
        
        // Handle special cases and intent disambiguation
        scores.entries.toList().let { entries ->
            // If STOP and CANCEL both match, prefer STOP (safety)
            if (scores.containsKey(Intent.STOP) && scores.containsKey(Intent.CANCEL)) {
                scores[Intent.STOP] = scores[Intent.STOP]!! + 0.1f
            }
            
            // If we have direction words without explicit jog/move, still classify as JOG
            if (!scores.containsKey(Intent.JOG) && !scores.containsKey(Intent.MOVE_ABSOLUTE)) {
                val hasDirection = directionPatterns.keys.any { input.contains(it) }
                if (hasDirection) {
                    scores[Intent.JOG] = 0.7f
                }
            }
            
            // IMPORTANT: If JOG matches along with SET_FEED or SET_STEP, prefer JOG
            // This handles commands like "move right 500 at feed rate 6000 step 10"
            // where feed/step are inline parameters, not standalone commands
            if (scores.containsKey(Intent.JOG)) {
                val jogScore = scores[Intent.JOG]!!
                if (scores.containsKey(Intent.SET_FEED) && jogScore >= scores[Intent.SET_FEED]!!) {
                    scores[Intent.JOG] = jogScore + 0.15f  // Boost JOG over SET_FEED
                }
                if (scores.containsKey(Intent.SET_STEP) && jogScore >= scores[Intent.SET_STEP]!!) {
                    scores[Intent.JOG] = scores[Intent.JOG]!! + 0.15f  // Boost JOG over SET_STEP
                }
            }
        }
        
        // Sort by score and get top results
        val sorted = scores.entries.sortedByDescending { it.value }
        
        return if (sorted.isNotEmpty()) {
            val best = sorted.first()
            val alternatives = sorted.drop(1).take(3).map { it.key to it.value }
            Triple(best.key, best.value, alternatives)
        } else {
            Triple(Intent.UNKNOWN, 0f, emptyList())
        }
    }
    
    /**
     * Extract entities from input based on the detected intent
     */
    private fun extractEntities(input: String, intent: Intent): List<Entity> {
        val entities = mutableListOf<Entity>()
        
        // Extract axis entities
        extractAxisEntities(input, entities)
        
        // Extract direction entities
        extractDirectionEntities(input, entities)
        
        // Extract numeric entities (distance, feed, rpm, etc.)
        extractNumericEntities(input, intent, entities)
        
        // Extract speed modifiers
        extractSpeedModifiers(input, entities)
        
        // Intent-specific entity extraction
        when (intent) {
            Intent.SET_PROBE_TYPE, Intent.PROBE -> extractProbeType(input, entities)
            Intent.SET_WORKSPACE -> extractWorkspace(input, entities)
            Intent.TOOL_CHANGE -> extractToolNumber(input, entities)
            Intent.SPINDLE_ON -> extractSpindleDirection(input, entities)
            else -> {}
        }
        
        return entities
    }
    
    private fun extractAxisEntities(input: String, entities: MutableList<Entity>) {
        // Check for compound axis specifications first
        for ((pattern, axis) in axisPatterns.entries.sortedByDescending { it.key.length }) {
            if (input.contains(pattern)) {
                entities.add(Entity(EntityType.AXIS, axis, pattern))
                return // Only one axis entity per command
            }
            
            // Fuzzy match for axis
            val fuzzyMatch = FuzzyMatcher.fuzzyContains(input, listOf(pattern), 0.8f)
            if (fuzzyMatch != null) {
                entities.add(Entity(
                    EntityType.AXIS, axis, fuzzyMatch,
                    confidence = FuzzyMatcher.similarity(fuzzyMatch, pattern)
                ))
                return
            }
        }
    }
    
    private fun extractDirectionEntities(input: String, entities: MutableList<Entity>) {
        for ((pattern, direction) in directionPatterns) {
            if (input.contains(pattern)) {
                entities.add(Entity(EntityType.DIRECTION, direction, pattern))
            } else {
                // Fuzzy match for directions
                val fuzzyMatch = FuzzyMatcher.fuzzyContains(input, listOf(pattern), 0.8f)
                if (fuzzyMatch != null) {
                    entities.add(Entity(
                        EntityType.DIRECTION, direction, fuzzyMatch,
                        confidence = FuzzyMatcher.similarity(fuzzyMatch, pattern)
                    ))
                }
            }
        }
    }
    
    private fun extractNumericEntities(input: String, intent: Intent, entities: MutableList<Entity>) {
        // Extract all numbers from the input with their positions
        val numberPattern = Regex("(-?\\d+\\.?\\d*)")
        val matches = numberPattern.findAll(input).toList()
        
        for (match in matches) {
            val value = match.groupValues[1].toFloatOrNull() ?: continue
            val rawText = match.value
            val position = match.range.first
            
            // Look at the text BEFORE this number to determine its context
            val textBefore = if (position > 0) input.substring(0, position).lowercase() else ""
            
            // Determine entity type based on what immediately precedes the number
            val entityType = when {
                // Check for feed context: "feed 6000", "feed rate 6000", "at feed 6000"
                textBefore.endsWith("feed ") || 
                textBefore.endsWith("feed rate ") || 
                textBefore.endsWith("at feed ") ||
                textBefore.endsWith("feedrate ") ||
                textBefore.endsWith("speed ") -> EntityType.FEED_RATE
                
                // Check for step context: "step 10"
                textBefore.endsWith("step ") ||
                textBefore.endsWith("step size ") -> EntityType.STEP_SIZE
                
                // Check for RPM context
                textBefore.endsWith("rpm ") ||
                textBefore.endsWith("spindle ") ||
                (intent == Intent.SET_SPINDLE_SPEED) -> EntityType.SPINDLE_RPM
                
                // Check for tool context
                textBefore.endsWith("tool ") ||
                textBefore.endsWith("t") ||
                (intent == Intent.TOOL_CHANGE) -> EntityType.TOOL_NUMBER
                
                // For absolute moves with coordinate context
                textBefore.endsWith("x ") || textBefore.endsWith("x") ||
                textBefore.endsWith("y ") || textBefore.endsWith("y") ||
                textBefore.endsWith("z ") || textBefore.endsWith("z") -> EntityType.COORDINATE
                
                // For explicit setting intents, the first number is the value being set
                (intent == Intent.SET_FEED && entities.none { it.type == EntityType.FEED_RATE }) -> EntityType.FEED_RATE
                (intent == Intent.SET_STEP && entities.none { it.type == EntityType.STEP_SIZE }) -> EntityType.STEP_SIZE
                
                // Default: treat as distance for jog commands
                else -> EntityType.DISTANCE
            }
            
            entities.add(Entity(entityType, value, rawText))
        }
    }
    
    private fun extractSpeedModifiers(input: String, entities: MutableList<Entity>) {
        for ((pattern, modifier) in speedModifiers) {
            if (input.contains(pattern)) {
                entities.add(Entity(EntityType.SPEED_MODIFIER, modifier, pattern))
                return // Only one speed modifier per command
            }
        }
    }
    
    private fun extractProbeType(input: String, entities: MutableList<Entity>) {
        for ((pattern, probeType) in probeTypes) {
            if (input.contains(pattern)) {
                entities.add(Entity(EntityType.PROBE_TYPE, probeType, pattern))
                return
            }
        }
    }
    
    private fun extractWorkspace(input: String, entities: MutableList<Entity>) {
        val wsPattern = Regex("g ?5([4-9])")
        wsPattern.find(input)?.let {
            val workspace = "G5${it.groupValues[1]}"
            entities.add(Entity(EntityType.WORKSPACE, workspace, it.value))
            return
        }
        
        // Word-based workspace
        val wordToWs = mapOf(
            "one" to "G54", "1" to "G54", "first" to "G54",
            "two" to "G55", "2" to "G55", "second" to "G55",
            "three" to "G56", "3" to "G56", "third" to "G56",
            "four" to "G57", "4" to "G57", "fourth" to "G57",
            "five" to "G58", "5" to "G58", "fifth" to "G58",
            "six" to "G59", "6" to "G59", "sixth" to "G59"
        )
        
        for ((word, ws) in wordToWs) {
            if (input.contains(word)) {
                entities.add(Entity(EntityType.WORKSPACE, ws, word))
                return
            }
        }
    }
    
    private fun extractToolNumber(input: String, entities: MutableList<Entity>) {
        val toolPattern = Regex("(?:tool|t)\\s*(\\d+)")
        toolPattern.find(input)?.let {
            val toolNum = it.groupValues[1].toIntOrNull() ?: return
            entities.add(Entity(EntityType.TOOL_NUMBER, toolNum, it.value))
        }
    }
    
    private fun extractSpindleDirection(input: String, entities: MutableList<Entity>) {
        when {
            input.contains("counterclockwise") || input.contains("ccw") || input.contains("reverse") -> {
                entities.add(Entity(EntityType.DIRECTION, Direction.NEGATIVE, "ccw"))
            }
            input.contains("clockwise") || input.contains("cw") || input.contains("forward") -> {
                entities.add(Entity(EntityType.DIRECTION, Direction.POSITIVE, "cw"))
            }
        }
    }
    
    /**
     * Check if the input likely contains a chained command
     */
    fun hasChainedCommands(input: String): Boolean {
        val chainKeywords = listOf(" then ", ", then ", " and then ", " after that ")
        return chainKeywords.any { input.contains(it) }
    }
    
    /**
     * Split chained commands for individual parsing
     */
    fun splitChainedCommands(input: String): List<String> {
        return input.split(Regex(" then |, then | and then | after that "))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
