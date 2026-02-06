package com.cncpendant.app.voice

import android.util.Log
import com.cncpendant.app.ConnectionManager
import com.google.gson.JsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Handles execution of parsed voice commands.
 * Separates command execution logic from parsing and UI concerns.
 */
class VoiceCommandExecutor(
    private val serverAddress: String
) {
    companion object {
        private const val TAG = "VoiceCommandExecutor"
    }
    
    /**
     * Configuration for the executor - holds current machine state for queries
     */
    data class MachineState(
        var machineX: Float = 0f,
        var machineY: Float = 0f,
        var machineZ: Float = 0f,
        var workX: Float = 0f,
        var workY: Float = 0f,
        var workZ: Float = 0f,
        var machineStatus: String = "Unknown",
        var currentFeed: Int = 1000,
        var currentStep: Float = 1f,
        var unitsPreference: String = "metric"
    )
    
    /**
     * Listener for execution results and queries
     */
    interface ExecutionListener {
        fun onCommandExecuted(command: VoiceCommand)
        fun onQueryResult(query: String, response: String)
        fun onProbeStarted(axis: String)
        fun onProbeCompleted(axis: String, success: Boolean)
        fun onProbeError(axis: String, error: String)
        fun onExecutionError(command: VoiceCommand, error: String)
    }
    
    var executionListener: ExecutionListener? = null
    var machineState: MachineState = MachineState()
    
    // HTTP client for probe API
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    // Track last executed command for repeat functionality
    var lastExecutedCommand: VoiceCommand? = null
        private set
    
    // Track last jog moves for undo functionality
    var lastJogMoves: MutableMap<String, Float>? = null
        private set
    
    /**
     * Execute a voice command.
     * Returns true if the command was handled, false otherwise.
     */
    fun executeCommand(command: VoiceCommand): Boolean {
        // Store command for repeat (except repeat/undo themselves)
        if (command.type != CommandType.REPEAT && command.type != CommandType.UNDO && 
            command.type != CommandType.BLOCKED && command.type != CommandType.QUERY) {
            lastExecutedCommand = command
        }
        
        // Store jog moves for undo
        if (command.type == CommandType.JOG && command.jogMoves != null) {
            lastJogMoves = command.jogMoves.toMutableMap()
        }
        
        return when (command.type) {
            CommandType.STOP -> {
                ConnectionManager.sendCommand(command.gcode)
                executionListener?.onCommandExecuted(command)
                true
            }
            CommandType.RESUME -> {
                ConnectionManager.sendCycleStart()
                executionListener?.onCommandExecuted(command)
                true
            }
            CommandType.RESET -> {
                ConnectionManager.sendSoftReset()
                executionListener?.onCommandExecuted(command)
                true
            }
            CommandType.HOME, CommandType.UNLOCK, CommandType.WORKSPACE, CommandType.ZERO -> {
                ConnectionManager.sendCommand(command.gcode)
                executionListener?.onCommandExecuted(command)
                true
            }
            CommandType.JOG, CommandType.MOVE -> {
                ConnectionManager.sendCommand(command.gcode)
                executionListener?.onCommandExecuted(command)
                true
            }
            CommandType.UNDO -> {
                // Undo is a reverse jog - send the command and clear undo history
                ConnectionManager.sendCommand(command.gcode)
                lastJogMoves = null  // Can't undo an undo
                executionListener?.onCommandExecuted(command)
                true
            }
            CommandType.REPEAT -> {
                // Re-execute the last command
                lastExecutedCommand?.let { 
                    executeCommand(it)
                }
                true
            }
            CommandType.TOOL_CHANGE -> {
                // Send tool change command
                ConnectionManager.sendCommand(command.gcode)
                executionListener?.onCommandExecuted(command)
                true
            }
            CommandType.PROBE -> {
                // Parse probe command and execute via HTTP API
                val parts = command.gcode.split(":")
                if (parts.size >= 3) {
                    val axis = parts[1]
                    val probeType = parts[2]
                    executeProbe(axis, probeType)
                }
                true
            }
            CommandType.SETTING -> {
                // Settings are already applied in parseCommand
                executionListener?.onCommandExecuted(command)
                true
            }
            CommandType.BLOCKED -> {
                // Safety block - do nothing
                false
            }
            CommandType.QUERY -> {
                // Handle query commands - speak the result
                executeQuery(command.gcode)
                true
            }
            CommandType.SPINDLE, CommandType.COOLANT -> {
                // Send spindle/coolant G-code
                ConnectionManager.sendCommand(command.gcode)
                executionListener?.onCommandExecuted(command)
                true
            }
        }
    }
    
    /**
     * Execute a query command and return the result via listener
     */
    fun executeQuery(queryType: String): String {
        val isMetric = machineState.unitsPreference == "metric"
        val unitLabel = if (isMetric) "millimeters" else "inches"
        val conversionFactor = if (isMetric) 1f else 1f / 25.4f
        
        val response = when {
            queryType == "QUERY:POSITION" -> {
                val wx = machineState.workX * conversionFactor
                val wy = machineState.workY * conversionFactor
                val wz = machineState.workZ * conversionFactor
                "Work position is X %.2f, Y %.2f, Z %.2f %s".format(wx, wy, wz, unitLabel)
            }
            queryType == "QUERY:STATUS" -> {
                "Machine status is ${machineState.machineStatus}"
            }
            queryType == "QUERY:FEED" -> {
                if (isMetric) {
                    "${machineState.currentFeed} millimeters per minute"
                } else {
                    "%.1f inches per minute".format(machineState.currentFeed / 25.4f)
                }.let { "Feed rate is $it" }
            }
            queryType == "QUERY:STEP" -> {
                if (isMetric) {
                    "%.2f millimeters".format(machineState.currentStep)
                } else {
                    "%.4f inches".format(machineState.currentStep / 25.4f)
                }.let { "Step size is $it" }
            }
            queryType == "QUERY:DISTANCE_WORK" -> {
                // Distance from work zero (current work position)
                val wx = kotlin.math.abs(machineState.workX) * conversionFactor
                val wy = kotlin.math.abs(machineState.workY) * conversionFactor
                val wz = kotlin.math.abs(machineState.workZ) * conversionFactor
                val totalDist = kotlin.math.sqrt(
                    machineState.workX*machineState.workX + 
                    machineState.workY*machineState.workY + 
                    machineState.workZ*machineState.workZ
                ) * conversionFactor
                "Distance from work zero: X %.2f, Y %.2f, Z %.2f. Total %.2f %s".format(wx, wy, wz, totalDist, unitLabel)
            }
            queryType == "QUERY:DISTANCE_MACHINE" -> {
                // Distance from machine home
                val mx = kotlin.math.abs(machineState.machineX) * conversionFactor
                val my = kotlin.math.abs(machineState.machineY) * conversionFactor
                val mz = kotlin.math.abs(machineState.machineZ) * conversionFactor
                val totalDist = kotlin.math.sqrt(
                    machineState.machineX*machineState.machineX + 
                    machineState.machineY*machineState.machineY + 
                    machineState.machineZ*machineState.machineZ
                ) * conversionFactor
                "Distance from machine home: X %.2f, Y %.2f, Z %.2f. Total %.2f %s".format(mx, my, mz, totalDist, unitLabel)
            }
            queryType.startsWith("QUERY:TRAVEL:") -> {
                val axis = queryType.substringAfter("QUERY:TRAVEL:")
                when (axis) {
                    "X" -> "X axis at %.2f %s from home".format(kotlin.math.abs(machineState.machineX) * conversionFactor, unitLabel)
                    "Y" -> "Y axis at %.2f %s from home".format(kotlin.math.abs(machineState.machineY) * conversionFactor, unitLabel)
                    "Z" -> "Z axis at %.2f %s from home".format(kotlin.math.abs(machineState.machineZ) * conversionFactor, unitLabel)
                    else -> "Machine at X %.2f, Y %.2f, Z %.2f %s from home".format(
                        kotlin.math.abs(machineState.machineX) * conversionFactor,
                        kotlin.math.abs(machineState.machineY) * conversionFactor,
                        kotlin.math.abs(machineState.machineZ) * conversionFactor,
                        unitLabel
                    )
                }
            }
            else -> "Unknown query"
        }
        
        executionListener?.onQueryResult(queryType, response)
        return response
    }
    
    /**
     * Execute a probe operation via HTTP API
     */
    fun executeProbe(axis: String, probeType: String) {
        // Build probe request JSON
        val json = JsonObject()
        json.addProperty("probeType", probeType)
        json.addProperty("probingAxis", axis)

        // For voice commands, use sensible defaults based on axis type
        // Note: selectedCorner values must be PascalCase: TopLeft, TopRight, BottomLeft, BottomRight
        // Note: selectedSide values must be Capitalized: Left, Right, Front, Back
        when (probeType) {
            "3d-probe" -> {
                json.addProperty("toolDiameter", 6f)
                json.addProperty("rapidMovement", 2000f)
                when (axis) {
                    "Z" -> json.addProperty("zOffset", 0f)
                    "XYZ" -> {
                        json.addProperty("zPlunge", 3f)
                        json.addProperty("zOffset", 0f)
                        json.addProperty("selectedCorner", "BottomLeft")
                    }
                    "XY" -> json.addProperty("selectedCorner", "BottomLeft")
                    "X" -> json.addProperty("selectedSide", "Left")
                    "Y" -> json.addProperty("selectedSide", "Front")
                }
            }
            "standard-block" -> {
                json.addProperty("bitDiameter", 6.35f)
                json.addProperty("rapidMovement", 2000f)
                when (axis) {
                    "Z" -> json.addProperty("zThickness", 15f)
                    "XYZ" -> {
                        json.addProperty("xyThickness", 10f)
                        json.addProperty("zThickness", 15f)
                        json.addProperty("zProbeDistance", 3f)
                        json.addProperty("selectedCorner", "BottomLeft")
                    }
                    "XY" -> {
                        json.addProperty("xyThickness", 10f)
                        json.addProperty("selectedCorner", "BottomLeft")
                    }
                    "X" -> {
                        json.addProperty("xyThickness", 10f)
                        json.addProperty("selectedSide", "Left")
                    }
                    "Y" -> {
                        json.addProperty("xyThickness", 10f)
                        json.addProperty("selectedSide", "Front")
                    }
                }
            }
            "autozero-touch" -> {
                json.addProperty("selectedBitDiameter", "Auto")
                json.addProperty("rapidMovement", 2000f)
            }
        }

        Log.d(TAG, "Probe request: $json")

        // Extract host:port from server address (remove ws:// prefix if present)
        val httpAddress = serverAddress
            .removePrefix("ws://")
            .removePrefix("wss://")

        val url = "http://$httpAddress/api/probe/start"
        Log.d(TAG, "Probe URL: $url")
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = json.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        executionListener?.onProbeStarted(axis)

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Probe start failed", e)
                executionListener?.onProbeError(axis, e.message ?: "Unknown error")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                Log.d(TAG, "Probe response: $responseBody")
                
                if (response.isSuccessful) {
                    executionListener?.onProbeCompleted(axis, true)
                } else {
                    executionListener?.onProbeError(axis, "HTTP ${response.code}")
                }
            }
        })
    }
    
    /**
     * Get delay for chained command execution based on command type
     */
    fun getCommandDelay(command: VoiceCommand): Long {
        return when (command.type) {
            CommandType.JOG, CommandType.MOVE -> 1500L  // Wait for jog to complete
            CommandType.PROBE -> 30000L  // Probe takes longer
            else -> 500L
        }
    }
    
    /**
     * Update the machine state from external source
     */
    fun updateMachineState(
        machineX: Float? = null,
        machineY: Float? = null,
        machineZ: Float? = null,
        workX: Float? = null,
        workY: Float? = null,
        workZ: Float? = null,
        status: String? = null,
        feed: Int? = null,
        step: Float? = null,
        units: String? = null
    ) {
        machineX?.let { machineState.machineX = it }
        machineY?.let { machineState.machineY = it }
        machineZ?.let { machineState.machineZ = it }
        workX?.let { machineState.workX = it }
        workY?.let { machineState.workY = it }
        workZ?.let { machineState.workZ = it }
        status?.let { machineState.machineStatus = it }
        feed?.let { machineState.currentFeed = it }
        step?.let { machineState.currentStep = it }
        units?.let { machineState.unitsPreference = it }
    }
}
