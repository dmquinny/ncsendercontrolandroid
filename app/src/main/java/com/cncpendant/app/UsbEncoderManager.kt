package com.cncpendant.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Manages USB serial connection to an RP2040 encoder device.
 * Receives encoder rotation data and converts to jog commands.
 */
class UsbEncoderManager(private val context: Context) : SerialInputOutputManager.Listener {

    companion object {
        private const val TAG = "UsbEncoderManager"
        private const val ACTION_USB_PERMISSION = "com.cncpendant.app.USB_PERMISSION"
        
        // RP2040 USB VID (Raspberry Pi Foundation)
        private const val RP2040_VID = 0x2E8A
        
        // Various RP2040 PIDs used by different cores/boards
        private val RP2040_PIDS = setOf(
            0x0003,  // Pico default
            0x0005,  // Pico SDK
            0x000A,  // Arduino/earlephilhower CDC
            0x000B,  // Arduino CDC variant
            0x00C0,  // Arduino board CDC
            0x000C,  // Waveshare variant
            0x1024,  // Some custom firmware
        )
        
        // Serial settings
        private const val BAUD_RATE = 115200
        private const val DATA_BITS = 8
        private const val STOP_BITS = UsbSerialPort.STOPBITS_1
        private const val PARITY = UsbSerialPort.PARITY_NONE
    }

    interface EncoderListener {
        fun onEncoderConnected()
        fun onEncoderDisconnected()
        fun onEncoderRotation(delta: Int, position: Long)
        fun onEncoderError(error: String)
    }

    private var listener: EncoderListener? = null
    private var usbManager: UsbManager? = null
    private var usbSerialPort: UsbSerialPort? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var serialIoManager: SerialInputOutputManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Custom prober that includes RP2040 CDC devices
    private val customProber: UsbSerialProber by lazy {
        val probeTable = ProbeTable()
        // Add all known RP2040 PIDs as CDC ACM devices
        for (pid in RP2040_PIDS) {
            probeTable.addProduct(RP2040_VID, pid, CdcAcmSerialDriver::class.java)
        }
        UsbSerialProber(probeTable)
    }
    
    // Buffer for incoming serial data
    private val readBuffer = StringBuilder()
    
    // Track connection state
    private var isConnected = false
    private var pendingDevice: UsbDevice? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let { connectToDevice(it) }
                        } else {
                            Log.w(TAG, "USB permission denied")
                            mainHandler.post {
                                listener?.onEncoderError("USB permission denied")
                            }
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.d(TAG, "USB device attached")
                    scanForEncoder()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    
                    if (device != null && isOurDevice(device)) {
                        Log.d(TAG, "Encoder device detached")
                        disconnect()
                    }
                }
            }
        }
    }

    fun setEncoderListener(listener: EncoderListener?) {
        this.listener = listener
    }

    fun initialize() {
        usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        
        // Register for USB events
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        
        // Scan for already-connected encoder
        scanForEncoder()
    }

    fun release() {
        disconnect()
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }

    fun scanForEncoder() {
        val manager = usbManager ?: return
        
        // Log all connected USB devices for debugging
        val deviceList = manager.deviceList
        Log.d(TAG, "=== USB Device Scan ===")
        Log.d(TAG, "Total USB devices: ${deviceList.size}")
        for ((name, device) in deviceList) {
            Log.d(TAG, "Device: $name, VID=0x${device.vendorId.toString(16).uppercase()}, PID=0x${device.productId.toString(16).uppercase()}, Class=${device.deviceClass}")
        }
        
        // Try custom prober first (for RP2040 devices)
        var availableDrivers = customProber.findAllDrivers(manager)
        Log.d(TAG, "Custom prober found ${availableDrivers.size} drivers")
        
        // Fall back to default prober if custom didn't find anything
        if (availableDrivers.isEmpty()) {
            availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
            Log.d(TAG, "Default prober found ${availableDrivers.size} drivers")
        }
        
        if (availableDrivers.isEmpty()) {
            Log.d(TAG, "No USB serial devices found by any prober")
            // Try to manually create a driver for any RP2040 device
            for ((_, device) in deviceList) {
                if (device.vendorId == RP2040_VID) {
                    Log.d(TAG, "Found RP2040 device not recognized by prober, attempting manual CDC driver")
                    requestPermissionAndConnect(device, null)
                    return
                }
            }
            return
        }
        
        // Look for RP2040 device first
        for (driver in availableDrivers) {
            val device = driver.device
            Log.d(TAG, "Prober found: VID=0x${device.vendorId.toString(16).uppercase()}, PID=0x${device.productId.toString(16).uppercase()}")
            
            if (isOurDevice(device)) {
                Log.d(TAG, "Found RP2040 encoder device!")
                requestPermissionAndConnect(device, driver)
                return
            }
        }
        
        // If no RP2040 found, try connecting to first available serial device
        Log.d(TAG, "No RP2040 found by VID, trying first available serial device")
        val driver = availableDrivers[0]
        requestPermissionAndConnect(driver.device, driver)
    }

    private fun isOurDevice(device: UsbDevice): Boolean {
        return device.vendorId == RP2040_VID && device.productId in RP2040_PIDS
    }

    private fun requestPermissionAndConnect(device: UsbDevice, driver: UsbSerialDriver?) {
        val manager = usbManager ?: return
        
        if (manager.hasPermission(device)) {
            connectToDevice(device)
        } else {
            pendingDevice = device
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            manager.requestPermission(device, permissionIntent)
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        val manager = usbManager ?: return
        
        Log.d(TAG, "Attempting to connect to device: VID=0x${device.vendorId.toString(16).uppercase()}, PID=0x${device.productId.toString(16).uppercase()}")
        
        // Try custom prober first, then default
        var drivers = customProber.findAllDrivers(manager)
        if (drivers.isEmpty()) {
            drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        }
        
        var driver = drivers.find { it.device.deviceId == device.deviceId }
        
        // If still no driver, create a CDC ACM driver manually
        if (driver == null && device.vendorId == RP2040_VID) {
            Log.d(TAG, "Creating manual CDC ACM driver for RP2040")
            driver = CdcAcmSerialDriver(device)
        }
        
        if (driver == null) {
            Log.e(TAG, "No driver found for device")
            mainHandler.post { listener?.onEncoderError("No driver found for device") }
            return
        }
        
        try {
            // Open connection
            usbConnection = manager.openDevice(device)
            if (usbConnection == null) {
                Log.e(TAG, "Failed to open USB connection")
                mainHandler.post { listener?.onEncoderError("Failed to open USB connection") }
                return
            }
            
            Log.d(TAG, "USB connection opened, driver has ${driver.ports.size} port(s)")
            
            // Open serial port (use first port, index 0)
            usbSerialPort = driver.ports[0]
            usbSerialPort?.open(usbConnection)
            usbSerialPort?.setParameters(BAUD_RATE, DATA_BITS, STOP_BITS, PARITY)
            
            // Enable DTR/RTS for proper CDC communication
            usbSerialPort?.dtr = true
            usbSerialPort?.rts = true
            
            Log.d(TAG, "Serial port opened and configured")
            
            // Start I/O manager for async reads
            serialIoManager = SerialInputOutputManager(usbSerialPort, this)
            Executors.newSingleThreadExecutor().submit(serialIoManager)
            
            isConnected = true
            Log.d(TAG, "Connected to encoder device successfully!")
            
            mainHandler.post {
                listener?.onEncoderConnected()
            }
            
            // Send a ping to verify communication
            sendCommand(JSONObject().put("type", "ping"))
            
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to device", e)
            mainHandler.post { listener?.onEncoderError("Connection error: ${e.message}") }
            disconnect()
        }
    }

    fun disconnect() {
        isConnected = false
        
        serialIoManager?.listener = null
        serialIoManager?.stop()
        serialIoManager = null
        
        try {
            usbSerialPort?.close()
        } catch (e: IOException) {
            // Ignore
        }
        usbSerialPort = null
        
        usbConnection?.close()
        usbConnection = null
        
        mainHandler.post {
            listener?.onEncoderDisconnected()
        }
    }

    fun isConnected(): Boolean = isConnected

    fun sendCommand(json: JSONObject) {
        if (!isConnected) return
        
        try {
            val data = (json.toString() + "\n").toByteArray()
            usbSerialPort?.write(data, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending command", e)
        }
    }

    fun resetPosition(position: Long = 0) {
        sendCommand(JSONObject().apply {
            put("type", "reset")
            put("position", position)
        })
    }

    fun setLed(on: Boolean) {
        sendCommand(JSONObject().apply {
            put("type", "led")
            put("on", on)
        })
    }

    // SerialInputOutputManager.Listener implementation
    override fun onNewData(data: ByteArray) {
        // Append to buffer and process complete lines
        val text = String(data)
        readBuffer.append(text)
        
        // Process complete JSON lines
        var newlineIndex: Int
        while (readBuffer.indexOf("\n").also { newlineIndex = it } >= 0) {
            val line = readBuffer.substring(0, newlineIndex).trim()
            readBuffer.delete(0, newlineIndex + 1)
            
            if (line.isNotEmpty()) {
                processMessage(line)
            }
        }
    }

    override fun onRunError(e: Exception) {
        Log.e(TAG, "Serial I/O error", e)
        mainHandler.post {
            listener?.onEncoderError("Serial error: ${e.message}")
            disconnect()
        }
    }

    private fun processMessage(line: String) {
        try {
            val json = JSONObject(line)
            val type = json.optString("type", "")
            
            when (type) {
                "encoder" -> {
                    val delta = json.optInt("delta", 0)
                    val position = json.optLong("position", 0)
                    
                    if (delta != 0) {
                        mainHandler.post {
                            listener?.onEncoderRotation(delta, position)
                        }
                    }
                }
                "pong" -> {
                    Log.d(TAG, "Received pong, position: ${json.optLong("position")}")
                }
                "ready" -> {
                    Log.d(TAG, "Encoder device ready: ${json.optString("message")}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse message: $line", e)
        }
    }
}
