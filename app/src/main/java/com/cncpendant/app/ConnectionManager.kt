package com.cncpendant.app

import android.content.Context
import android.util.Log

/**
 * Singleton connection manager that maintains a shared WebSocket connection
 * across all activities. This ensures connection state is consistent app-wide.
 */
object ConnectionManager {
    private const val TAG = "ConnectionManager"
    
    private var wsManager: WebSocketManager? = null
    private var currentServerAddress: String = ""
    private var isConnected: Boolean = false
    private var currentActiveState: String = ""
    
    // Listeners for connection state changes (activities register/unregister)
    private val listeners = mutableSetOf<ConnectionStateListener>()
    
    interface ConnectionStateListener {
        fun onConnectionStateChanged(connected: Boolean)
        fun onMachineStateUpdate(mPos: WebSocketManager.Position?, wco: WebSocketManager.Position?, wcs: String?)
        fun onActiveStateChanged(state: String)
        fun onError(error: String)
        // Optional callbacks - provide default empty implementations
        fun onSenderStatusChanged(status: String) {}
        fun onOverridesChanged(feedOverride: Int, spindleOverride: Int, feedRate: Float, spindleSpeed: Float, requestedSpindleSpeed: Float) {}
        fun onPinStateChanged(pn: String) {}
        fun onSenderConnectedChanged(connected: Boolean) {}
        fun onHomingStateChanged(homed: Boolean, homingCycle: Int) {}
        fun onJobLoadedChanged(filename: String?) {}
        fun onGrblMessage(message: String) {}
        fun onAlarmCodeChanged(alarmCode: Int?, alarmDescription: String?) {}
    }
    
    fun addListener(listener: ConnectionStateListener) {
        listeners.add(listener)
        // Immediately notify of current state
        listener.onConnectionStateChanged(isConnected)
        // Also notify of current alarm state if connected
        if (isConnected && currentActiveState.isNotEmpty()) {
            listener.onActiveStateChanged(currentActiveState)
        }
    }
    
    fun removeListener(listener: ConnectionStateListener) {
        listeners.remove(listener)
    }
    
    fun isConnected(): Boolean = isConnected
    
    fun getServerAddress(): String = currentServerAddress
    
    fun isInAlarmState(): Boolean = currentActiveState.startsWith("Alarm")
    
    fun getCurrentActiveState(): String = currentActiveState
    
    fun connect(context: Context, serverAddress: String) {
        // If already connected to this address, don't reconnect
        if (isConnected && serverAddress == currentServerAddress && wsManager != null) {
            Log.d(TAG, "Already connected to $serverAddress")
            return
        }
        
        // Disconnect existing connection
        disconnect()
        
        currentServerAddress = serverAddress
        
        // Ensure serverAddress has proper WebSocket scheme
        val wsUrl = if (serverAddress.startsWith("ws://") || serverAddress.startsWith("wss://")) {
            serverAddress
        } else {
            "ws://$serverAddress"
        }
        
        Log.d(TAG, "Connecting to: $wsUrl")
        
        wsManager = WebSocketManager(context)
        wsManager?.connect(wsUrl, object : WebSocketManager.ConnectionListener {
            override fun onConnected() {
                Log.d(TAG, "Connected")
                isConnected = true
                listeners.forEach { it.onConnectionStateChanged(true) }
            }
            
            override fun onDisconnected() {
                Log.d(TAG, "Disconnected")
                isConnected = false
                listeners.forEach { it.onConnectionStateChanged(false) }
            }
            
            override fun onError(error: String) {
                Log.e(TAG, "Error: $error")
                isConnected = false
                listeners.forEach { 
                    it.onError(error)
                    it.onConnectionStateChanged(false)
                }
            }
            
            override fun onMachineStateUpdate(mPos: WebSocketManager.Position?, wco: WebSocketManager.Position?, wcs: String?) {
                listeners.forEach { it.onMachineStateUpdate(mPos, wco, wcs) }
            }
            
            override fun onActiveStateChanged(state: String) {
                currentActiveState = state
                listeners.forEach { it.onActiveStateChanged(state) }
            }
            
            override fun onSenderStatusChanged(status: String) {
                listeners.forEach { it.onSenderStatusChanged(status) }
            }
            
            override fun onOverridesChanged(feedOverride: Int, spindleOverride: Int, feedRate: Float, spindleSpeed: Float, requestedSpindleSpeed: Float) {
                listeners.forEach { it.onOverridesChanged(feedOverride, spindleOverride, feedRate, spindleSpeed, requestedSpindleSpeed) }
            }
            
            override fun onPinStateChanged(pn: String) {
                listeners.forEach { it.onPinStateChanged(pn) }
            }
            
            override fun onSenderConnectedChanged(connected: Boolean) {
                listeners.forEach { it.onSenderConnectedChanged(connected) }
            }
            
            override fun onHomingStateChanged(homed: Boolean, homingCycle: Int) {
                listeners.forEach { it.onHomingStateChanged(homed, homingCycle) }
            }
            
            override fun onJobLoadedChanged(filename: String?) {
                listeners.forEach { it.onJobLoadedChanged(filename) }
            }
            
            override fun onGrblMessage(message: String) {
                listeners.forEach { it.onGrblMessage(message) }
            }
            
            override fun onAlarmCodeChanged(alarmCode: Int?, alarmDescription: String?) {
                listeners.forEach { it.onAlarmCodeChanged(alarmCode, alarmDescription) }
            }
        })
    }
    
    fun disconnect() {
        Log.d(TAG, "Disconnecting")
        wsManager?.disconnect()
        wsManager = null
        isConnected = false
        currentActiveState = ""
    }
    
    fun reconnect(context: Context) {
        if (currentServerAddress.isNotEmpty()) {
            Log.d(TAG, "Reconnecting to: $currentServerAddress")
            disconnect()
            // Small delay then reconnect
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                connect(context, currentServerAddress)
            }, 500)
        }
    }
    
    fun sendCommand(command: String) {
        wsManager?.sendCommand(command)
    }
    
    fun sendCycleStart() {
        wsManager?.sendCycleStart()
    }
    
    fun sendSoftReset() {
        wsManager?.sendSoftReset()
    }
}
