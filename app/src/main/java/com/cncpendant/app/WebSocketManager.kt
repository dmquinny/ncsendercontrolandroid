package com.cncpendant.app

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class WebSocketManager(private val context: Context) {

    // Data class for 3D position coordinates
    data class Position(val x: Float, val y: Float, val z: Float)

    private var webSocket: WebSocket? = null
    private var listener: ConnectionListener? = null
    private var currentUrl: String? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)  // Keep connection alive with periodic pings
        .build()
    private val gson = Gson()
    
    // Preserve last known override values (like ncSender's lastStatus)
    private var lastFeedOverride = 100
    private var lastSpindleOverride = 100
    private var lastFeedRate = 0f
    private var lastSpindleRpmActual = 0f
    private var lastSpindleRpmTarget = 0f
    
    // Preserve last known homing state (don't reset to false if field missing in update)
    private var lastHomed = false
    private var lastHomingCycle = 0

    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected()
        fun onError(error: String)
        fun onMachineStateUpdate(mPos: Position?, wco: Position?, wcs: String?)
        fun onActiveStateChanged(state: String)
        fun onPinStateChanged(pn: String) {}
        fun onHomingStateChanged(homed: Boolean, homingCycle: Int) {}
        fun onSenderStatusChanged(status: String) {}
        fun onSenderConnectedChanged(connected: Boolean) {}
        fun onOverridesChanged(feedOverride: Int, spindleOverride: Int, feedRate: Float, spindleSpeed: Float, requestedSpindleSpeed: Float) {}
        fun onJobLoadedChanged(filename: String?) {}
        fun onGrblMessage(message: String) {}
        fun onAlarmCodeChanged(alarmCode: Int?, alarmDescription: String?) {}
    }

    fun connect(url: String, listener: ConnectionListener) {
        this.listener = listener
        this.currentUrl = url
        
        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                listener.onDisconnected()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                listener.onDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error", t)
                listener.onError(t.message ?: "Connection failed")
                listener.onDisconnected()
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        // Reset homing state on disconnect (machine state unknown)
        lastHomed = false
        lastHomingCycle = 0
    }

    fun sendCommand(command: String) {
        val message = JsonObject().apply {
            addProperty("type", "cnc:command")
            add("data", JsonObject().apply {
                addProperty("command", command)
                addProperty("commandId", "pendant-${System.currentTimeMillis()}-${(Math.random() * 65536).toInt().toString(16)}")
                addProperty("displayCommand", command)
            })
        }
        send(message.toString())
    }

    fun sendJogCommand(axis: String, distance: Float, feedRate: Int) {
        val command = "\$J=G91 $axis${String.format("%.3f", distance)} F$feedRate"
        val direction = if (distance > 0) "+" else "-"
        
        val message = JsonObject().apply {
            addProperty("type", "jog:step")
            add("data", JsonObject().apply {
                addProperty("command", command)
                addProperty("displayCommand", "Jog $axis${if (distance > 0) "+" else ""}${String.format("%.3f", distance)}")
                addProperty("axis", axis)
                addProperty("direction", direction)
                addProperty("feedRate", feedRate)
                addProperty("distance", kotlin.math.abs(distance))
                addProperty("commandId", "pendant-${System.currentTimeMillis()}-${(Math.random() * 65536).toInt().toString(16)}")
            })
        }
        send(message.toString())
    }
    
    fun sendAbsoluteJogCommand(axis: String, position: Double, feedRate: Int) {
        // Use G53 (machine coordinates) with G90 (absolute) for precise positioning
        val command = "\$J=G53 G90 $axis${String.format("%.0f", position)} F$feedRate"
        
        val message = JsonObject().apply {
            addProperty("type", "jog:step")
            add("data", JsonObject().apply {
                addProperty("command", command)
                addProperty("displayCommand", "Jog to $axis${String.format("%.0f", position)}")
                addProperty("axis", axis)
                addProperty("direction", "")
                addProperty("feedRate", feedRate)
                addProperty("distance", 0)
                addProperty("commandId", "pendant-${System.currentTimeMillis()}-${(Math.random() * 65536).toInt().toString(16)}")
            })
        }
        send(message.toString())
    }

    fun sendJogCancel() {
        // Send the jog cancel realtime command (0x85) - cleanly stops only the active jog
        val message = JsonObject().apply {
            addProperty("type", "cnc:command")
            add("data", JsonObject().apply {
                addProperty("command", "0x85")
                addProperty("commandId", "pendant-jogcancel-${System.currentTimeMillis()}-${(Math.random() * 65536).toInt().toString(16)}")
                addProperty("displayCommand", "0x85 (Jog Cancel)")
            })
        }
        send(message.toString())
    }

    fun sendSoftReset() {
        // Send the soft reset command (0x18) - full GRBL reset
        val message = JsonObject().apply {
            addProperty("type", "cnc:command")
            add("data", JsonObject().apply {
                addProperty("command", "0x18")
                addProperty("commandId", "pendant-reset-${System.currentTimeMillis()}-${(Math.random() * 65536).toInt().toString(16)}")
                addProperty("displayCommand", "0x18 (Soft Reset)")
            })
        }
        send(message.toString())
    }

    fun sendCycleStart() {
        // Send the cycle start/resume command (~) - this resumes from feed hold
        val message = JsonObject().apply {
            addProperty("type", "cnc:command")
            add("data", JsonObject().apply {
                addProperty("command", "~")
                addProperty("commandId", "pendant-start-${System.currentTimeMillis()}-${(Math.random() * 65536).toInt().toString(16)}")
                addProperty("displayCommand", "~ (Cycle Start/Resume)")
            })
        }
        send(message.toString())
    }

    fun getHttpBaseUrl(): String? {
        // Convert ws://host:port/ws to http://host:port
        val wsUrl = currentUrl ?: return null
        return wsUrl
            .replace("ws://", "http://")
            .replace("wss://", "https://")
            .replace("/ws", "")
    }

    fun sendSenderStart(filename: String) {
        // Start the loaded job via HTTP API - POST /api/gcode-job
        Log.d(TAG, "sendSenderStart() called with filename: $filename")
        val baseUrl = getHttpBaseUrl()
        if (baseUrl == null) {
            Log.e(TAG, "Cannot start job - no connection URL")
            return
        }
        
        val url = "$baseUrl/api/gcode-job"
        val json = JsonObject().apply {
            addProperty("filename", filename)
        }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()
        
        Log.d(TAG, "POST $url with body: $json")
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to start job", e)
            }
            override fun onResponse(call: Call, response: Response) {
                Log.d(TAG, "Start job response: ${response.code} - ${response.body?.string()}")
                response.close()
            }
        })
    }

    fun sendSenderPause() {
        // Pause the running job - send feed hold command via WebSocket
        Log.d(TAG, "sendSenderPause() called")
        sendCommand("!")
    }

    fun sendSenderResume() {
        // Resume a paused job - send cycle start command via WebSocket
        Log.d(TAG, "sendSenderResume() called")
        sendCommand("~")
    }

    fun sendSenderStop() {
        // Stop the running job via HTTP API - POST /api/gcode-job/stop
        Log.d(TAG, "sendSenderStop() called")
        val baseUrl = getHttpBaseUrl()
        if (baseUrl == null) {
            Log.e(TAG, "Cannot stop job - no connection URL")
            return
        }
        
        val url = "$baseUrl/api/gcode-job/stop"
        val body = "{}".toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()
        
        Log.d(TAG, "POST $url")
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to stop job", e)
            }
            override fun onResponse(call: Call, response: Response) {
                Log.d(TAG, "Stop job response: ${response.code} - ${response.body?.string()}")
                response.close()
            }
        })
    }
    fun sendJogStart(jogId: String, command: String, axis: String, direction: String, feedRate: Int) {
        val message = JsonObject().apply {
            addProperty("type", "jog:start")
            add("data", JsonObject().apply {
                addProperty("jogId", jogId)
                addProperty("command", command)
                addProperty("displayCommand", command)
                addProperty("axis", axis)
                addProperty("direction", direction)
                addProperty("feedRate", feedRate)
            })
        }
        send(message.toString())
    }

    fun sendJogHeartbeat(jogId: String) {
        val message = JsonObject().apply {
            addProperty("type", "jog:heartbeat")
            add("data", JsonObject().apply {
                addProperty("jogId", jogId)
            })
        }
        send(message.toString())
    }

    fun sendJogStop(jogId: String) {
        val message = JsonObject().apply {
            addProperty("type", "jog:stop")
            add("data", JsonObject().apply {
                addProperty("jogId", jogId)
            })
        }
        send(message.toString())
    }

    fun sendFeedHold() {
        // Send the feed hold command (!)
        val message = JsonObject().apply {
            addProperty("type", "cnc:command")
            add("data", JsonObject().apply {
                addProperty("command", "!")
                addProperty("commandId", "pendant-hold-${System.currentTimeMillis()}-${(Math.random() * 65536).toInt().toString(16)}")
                addProperty("displayCommand", "! (Feed Hold)")
            })
        }
        send(message.toString())
    }

    fun sendFeedOverride(percent: Int) {
        // GRBL feed override realtime commands:
        // 0x90 = Set 100%
        // 0x91 = +10%, 0x92 = -10%
        // 0x93 = +1%, 0x94 = -1%
        val command = when {
            percent == 100 -> "0x90"
            percent > 100 -> if ((percent - 100) % 10 == 0) "0x91" else "0x93"
            else -> if ((100 - percent) % 10 == 0) "0x92" else "0x94"
        }
        val message = JsonObject().apply {
            addProperty("type", "cnc:command")
            add("data", JsonObject().apply {
                addProperty("command", command)
                addProperty("commandId", "pendant-feedoverride-${System.currentTimeMillis()}-${(Math.random() * 65536).toInt().toString(16)}")
                addProperty("displayCommand", "Feed Override $percent%")
            })
        }
        send(message.toString())
    }

    fun sendSpindleOverride(percent: Int) {
        // GRBL spindle override realtime commands:
        // 0x99 = Set 100%
        // 0x9A = +10%, 0x9B = -10%
        // 0x9C = +1%, 0x9D = -1%
        val command = when {
            percent == 100 -> "0x99"
            percent > 100 -> if ((percent - 100) % 10 == 0) "0x9A" else "0x9C"
            else -> if ((100 - percent) % 10 == 0) "0x9B" else "0x9D"
        }
        val message = JsonObject().apply {
            addProperty("type", "cnc:command")
            add("data", JsonObject().apply {
                addProperty("command", command)
                addProperty("commandId", "pendant-spindleoverride-${System.currentTimeMillis()}-${(Math.random() * 65536).toInt().toString(16)}")
                addProperty("displayCommand", "Spindle Override $percent%")
            })
        }
        send(message.toString())
    }

    fun resetFeedOverride() {
        sendFeedOverride(100)
    }

    fun resetSpindleOverride() {
        sendSpindleOverride(100)
    }

    private fun send(json: String) {
        webSocket?.send(json)
        Log.d(TAG, "Sent: $json")
    }

    private fun handleMessage(text: String) {
        try {
            val message = gson.fromJson(text, JsonObject::class.java)
            val type = message.get("type")?.asString ?: return
            val dataElement = message.get("data")
            val data = if (dataElement is JsonObject) dataElement else null

            Log.d(TAG, "Received: $type")

            when (type) {
                "server-state-updated" -> {
                    Log.d(TAG, "server-state-updated data: $dataElement")
                    // Parse senderStatus (top-level in data, not in machineState)
                    data?.get("senderStatus")?.asString?.let { senderStatus ->
                        listener?.onSenderStatusChanged(senderStatus)
                    }
                    // Parse jobLoaded (top-level in data)
                    val jobLoaded = data?.getAsJsonObject("jobLoaded")
                    val jobFilename = jobLoaded?.get("filename")?.asString
                    listener?.onJobLoadedChanged(jobFilename)
                    
                    data?.getAsJsonObject("machineState")?.let { machineState ->
                        var mPos: Position? = null
                        var wco: Position? = null
                        var wcs: String? = null

                        // Parse active state (e.g. "Idle", "Home", "Run", "Alarm")
                        (machineState.get("status") ?: machineState.get("activeState"))?.asString?.let { state ->
                            listener?.onActiveStateChanged(state)
                        }

                        // Parse MPos
                        machineState.get("MPos")?.asString?.let { mposStr ->
                            val parts = mposStr.split(",")
                            if (parts.size >= 3) {
                                mPos = Position(
                                    parts[0].toFloatOrNull() ?: 0f,
                                    parts[1].toFloatOrNull() ?: 0f,
                                    parts[2].toFloatOrNull() ?: 0f
                                )
                            }
                        }

                        // Parse WCO
                        machineState.get("WCO")?.asString?.let { wcoStr ->
                            val parts = wcoStr.split(",")
                            if (parts.size >= 3) {
                                wco = Position(
                                    parts[0].toFloatOrNull() ?: 0f,
                                    parts[1].toFloatOrNull() ?: 0f,
                                    parts[2].toFloatOrNull() ?: 0f
                                )
                            }
                        }

                        // Parse WCS
                        wcs = machineState.get("WCS")?.asString

                        // Parse Pn (pin state - contains 'P' when probe is triggered)
                        if (machineState.has("Pn")) {
                            val pn = machineState.get("Pn")?.asString ?: ""
                            listener?.onPinStateChanged(pn)
                        }

                        // Parse connected (whether serial port is connected to GRBL)
                        if (machineState.has("connected")) {
                            val connected = machineState.get("connected")?.asBoolean ?: false
                            listener?.onSenderConnectedChanged(connected)
                        }

                        // Parse homing state - only update each field when explicitly present
                        // (preserve last known values to avoid false resets)
                        var homingStateChanged = false
                        if (machineState.has("homed") && !machineState.get("homed").isJsonNull) {
                            lastHomed = machineState.get("homed").asBoolean
                            homingStateChanged = true
                        }
                        if (machineState.has("homingCycle") && !machineState.get("homingCycle").isJsonNull) {
                            lastHomingCycle = machineState.get("homingCycle").asInt
                            homingStateChanged = true
                        }
                        if (homingStateChanged) {
                            listener?.onHomingStateChanged(lastHomed, lastHomingCycle)
                        }

                        // Parse alarm code and description (sent by ncSender when in alarm state)
                        if (machineState.has("alarmCode") || machineState.has("alarmDescription")) {
                            val alarmCode = if (machineState.has("alarmCode") && !machineState.get("alarmCode").isJsonNull) {
                                machineState.get("alarmCode").asInt
                            } else null
                            val alarmDescription = machineState.get("alarmDescription")?.asString
                            listener?.onAlarmCodeChanged(alarmCode, alarmDescription)
                        }

                        // Parse overrides - ncSender uses these field names:
                        // feedrateOverride, spindleOverride, feedRate, spindleRpmTarget, spindleRpmActual
                        // Only update values if fields are present (preserve last known values)
                        var hasOverrideData = false
                        
                        if (machineState.has("feedrateOverride")) {
                            lastFeedOverride = machineState.get("feedrateOverride").asInt
                            hasOverrideData = true
                        } else if (machineState.has("Ov")) {
                            machineState.get("Ov")?.asString?.split(",")?.getOrNull(0)?.toIntOrNull()?.let {
                                lastFeedOverride = it
                                hasOverrideData = true
                            }
                        }
                        
                        if (machineState.has("spindleOverride")) {
                            lastSpindleOverride = machineState.get("spindleOverride").asInt
                            hasOverrideData = true
                        } else if (machineState.has("Ov")) {
                            machineState.get("Ov")?.asString?.split(",")?.getOrNull(2)?.toIntOrNull()?.let {
                                lastSpindleOverride = it
                                hasOverrideData = true
                            }
                        }
                        
                        if (machineState.has("feedRate")) {
                            lastFeedRate = machineState.get("feedRate").asFloat
                            hasOverrideData = true
                        } else if (machineState.has("F")) {
                            lastFeedRate = machineState.get("F").asFloat
                            hasOverrideData = true
                        }
                        
                        if (machineState.has("spindleRpmActual")) {
                            lastSpindleRpmActual = machineState.get("spindleRpmActual").asFloat
                            hasOverrideData = true
                        }
                        if (machineState.has("spindleRpmTarget")) {
                            lastSpindleRpmTarget = machineState.get("spindleRpmTarget").asFloat
                            hasOverrideData = true
                        }
                        
                        if (hasOverrideData) {
                            listener?.onOverridesChanged(lastFeedOverride, lastSpindleOverride, lastFeedRate, lastSpindleRpmActual, lastSpindleRpmTarget)
                        }

                        if (mPos != null || wco != null || wcs != null) {
                            listener?.onMachineStateUpdate(mPos, wco, wcs)
                        }
                    }
                }

                "cnc-data" -> {
                    // Parse raw GRBL status: <Idle|MPos:0.000,0.000,0.000|...>
                    val statusStr = data?.asString ?: message.get("data")?.asString ?: return
                    
                    // Forward raw GRBL messages (for unlock detection etc)
                    listener?.onGrblMessage(statusStr)

                    // Parse active state from <State|...>
                    val stateRegex = Regex("<([A-Za-z]+)[|:]")
                    stateRegex.find(statusStr)?.let { match ->
                        listener?.onActiveStateChanged(match.groupValues[1])
                    }

                    val mposRegex = Regex("MPos:([\\d.-]+),([\\d.-]+),([\\d.-]+)")
                    mposRegex.find(statusStr)?.let { match ->
                        val mPos = Position(
                            match.groupValues[1].toFloatOrNull() ?: 0f,
                            match.groupValues[2].toFloatOrNull() ?: 0f,
                            match.groupValues[3].toFloatOrNull() ?: 0f
                        )
                        listener?.onMachineStateUpdate(mPos, null, null)
                    }

                    // Parse Pn field from raw status: |Pn:XZP|
                    val pnRegex = Regex("Pn:([A-Za-z]*)")
                    val pnMatch = pnRegex.find(statusStr)
                    listener?.onPinStateChanged(pnMatch?.groupValues?.get(1) ?: "")
                    
                    // Parse overrides from raw status: |Ov:100,100,100| (feed,rapid,spindle)
                    // Only update values that are present (preserve last known values)
                    val ovRegex = Regex("Ov:(\\d+),(\\d+),(\\d+)")
                    val ovMatch = ovRegex.find(statusStr)
                    
                    // Parse feed rate from raw status: |F:1000| or |FS:feed,targetRpm| or |FS:feed,targetRpm,actualRpm|
                    val fsRegex = Regex("FS:([\\d.]+),([\\d.]+)(?:,([\\d.]+))?")
                    val fRegex = Regex("\\|F:([\\d.]+)")
                    val fsMatch = fsRegex.find(statusStr)
                    val fMatch = fRegex.find(statusStr)
                    
                    var hasOverrideData = false
                    
                    ovMatch?.let { match ->
                        match.groupValues[1].toIntOrNull()?.let { lastFeedOverride = it }
                        match.groupValues[3].toIntOrNull()?.let { lastSpindleOverride = it }
                        hasOverrideData = true
                    }
                    
                    fsMatch?.let { match ->
                        match.groupValues[1].toFloatOrNull()?.let { lastFeedRate = it }
                        match.groupValues[2].toFloatOrNull()?.let { lastSpindleRpmTarget = it }
                        match.groupValues[3].toFloatOrNull()?.let { lastSpindleRpmActual = it } ?: run { lastSpindleRpmActual = lastSpindleRpmTarget }
                        hasOverrideData = true
                    } ?: fMatch?.let { match ->
                        match.groupValues[1].toFloatOrNull()?.let { lastFeedRate = it }
                        hasOverrideData = true
                    }
                    
                    if (hasOverrideData) {
                        listener?.onOverridesChanged(lastFeedOverride, lastSpindleOverride, lastFeedRate, lastSpindleRpmActual, lastSpindleRpmTarget)
                    }
                }

                "client-id" -> {
                    Log.d(TAG, "Client ID assigned: ${data?.get("clientId")?.asString}")
                }

                "cnc-command-result" -> {
                    Log.d(TAG, "Command result: ${data?.get("status")?.asString}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message", e)
        }
    }

    companion object {
        private const val TAG = "WebSocketManager"
    }
}
