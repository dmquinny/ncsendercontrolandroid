package com.cncpendant.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.driver.BlockDeviceDriverFactory
import me.jahnen.libaums.core.usb.UsbCommunication
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume

/**
 * Manages USB Mass Storage operations for flashing RP2040 firmware.
 * Uses raw block writes to bypass Android's limited FAT12 support.
 * 
 * The RP2040 BOOTSEL mode presents a virtual FAT12 filesystem, but we can
 * write UF2 blocks directly to the block device - the bootloader will
 * parse them and flash the firmware.
 */
class UsbMassStorageManager(private val context: Context) {
    
    companion object {
        private const val TAG = "UsbMassStorage"
        private const val ACTION_USB_PERMISSION = "com.cncpendant.app.USB_PERMISSION"
        
        // Raspberry Pi Foundation USB Vendor ID
        private const val RP2040_VENDOR_ID = 0x2E8A  // 11914 decimal
        
        // RP2040 BOOTSEL mode product IDs
        private const val RP2040_BOOTSEL_PID = 0x0003  // Standard BOOTSEL
        private const val RP2040_BOOTSEL_PID_ALT = 0x0005  // Alternative BOOTSEL
        
        // UF2 block size is always 512 bytes
        private const val UF2_BLOCK_SIZE = 512
        
        // UF2 magic numbers (using signed Int representation for values > Int.MAX_VALUE)
        private const val UF2_MAGIC_START0: Int = 0x0A324655  // "UF2\n"
        private const val UF2_MAGIC_START1: Int = -1638051497  // 0x9E5D5157 as signed Int (2656915799 - 2^32)
        private const val UF2_MAGIC_END: Int = 0x0AB16F30
    }
    
    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    
    /**
     * Result of a firmware flash operation
     */
    sealed class FlashResult {
        data object Success : FlashResult()
        data class Error(val message: String) : FlashResult()
        data object NoDeviceFound : FlashResult()
        data object PermissionDenied : FlashResult()
    }
    
    /**
     * Check if an RP2040 device is connected in BOOTSEL mode
     */
    fun findRp2040BootselDevice(): UsbDevice? {
        val devices = UsbMassStorageDevice.getMassStorageDevices(context)
        Log.d(TAG, "Found ${devices.size} mass storage devices")
        
        for (device in devices) {
            val usbDevice = device.usbDevice
            Log.d(TAG, "Device: VID=${usbDevice.vendorId}, PID=${usbDevice.productId}, Name=${usbDevice.deviceName}")
            
            if (usbDevice.vendorId == RP2040_VENDOR_ID &&
                (usbDevice.productId == RP2040_BOOTSEL_PID || usbDevice.productId == RP2040_BOOTSEL_PID_ALT)) {
                Log.d(TAG, "Found RP2040 in BOOTSEL mode!")
                return usbDevice
            }
        }
        
        // Also check raw USB devices in case libaums doesn't detect it
        usbManager.deviceList.values.forEach { device ->
            Log.d(TAG, "Raw USB device: VID=${device.vendorId}, PID=${device.productId}")
            if (device.vendorId == RP2040_VENDOR_ID &&
                (device.productId == RP2040_BOOTSEL_PID || device.productId == RP2040_BOOTSEL_PID_ALT)) {
                Log.d(TAG, "Found RP2040 in BOOTSEL mode (raw USB)")
                return device
            }
        }
        
        return null
    }
    
    /**
     * Request USB permission for the device
     */
    suspend fun requestPermission(device: UsbDevice): Boolean = suspendCancellableCoroutine { continuation ->
        if (usbManager.hasPermission(device)) {
            continuation.resume(true)
            return@suspendCancellableCoroutine
        }
        
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (ACTION_USB_PERMISSION == intent.action) {
                    context.unregisterReceiver(this)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (continuation.isActive) {
                        continuation.resume(granted)
                    }
                }
            }
        }
        
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        // Make the intent explicit by setting the package (required for Android 14+)
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, permissionIntent)
        
        continuation.invokeOnCancellation {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                // Already unregistered
            }
        }
    }
    
    /**
     * Flash firmware to an RP2040 device in BOOTSEL mode using raw block writes.
     * 
     * UF2 files are designed to be written as raw 512-byte blocks. The RP2040 
     * bootloader intercepts these writes and flashes the firmware directly.
     * 
     * @param firmwareStream InputStream containing the .uf2 firmware data
     * @param filename The filename (for logging only)
     * @return FlashResult indicating success or failure
     */
    suspend fun flashFirmware(firmwareStream: InputStream, filename: String): FlashResult = withContext(Dispatchers.IO) {
        try {
            // Find RP2040 in BOOTSEL mode
            val usbDevice = findRp2040BootselDevice()
            if (usbDevice == null) {
                Log.w(TAG, "No RP2040 device found in BOOTSEL mode")
                return@withContext FlashResult.NoDeviceFound
            }
            
            // Request permission if needed
            if (!usbManager.hasPermission(usbDevice)) {
                val granted = requestPermission(usbDevice)
                if (!granted) {
                    Log.w(TAG, "USB permission denied")
                    return@withContext FlashResult.PermissionDenied
                }
            }
            
            // Get the mass storage devices again after permission is granted
            val massStorageDevices = UsbMassStorageDevice.getMassStorageDevices(context)
            val targetDevice = massStorageDevices.find { 
                it.usbDevice.vendorId == usbDevice.vendorId && 
                it.usbDevice.productId == usbDevice.productId 
            }
            
            if (targetDevice == null) {
                Log.e(TAG, "Could not find mass storage device after permission granted")
                return@withContext FlashResult.Error("Device not recognized as mass storage")
            }
            
            try {
                // Initialize the device
                targetDevice.init()
                
                // Read firmware bytes
                val firmwareBytes = firmwareStream.readBytes()
                Log.d(TAG, "Firmware size: ${firmwareBytes.size} bytes")
                
                // Validate UF2 format
                if (firmwareBytes.size % UF2_BLOCK_SIZE != 0) {
                    return@withContext FlashResult.Error("Invalid UF2 file: size not multiple of 512")
                }
                
                val numBlocks = firmwareBytes.size / UF2_BLOCK_SIZE
                Log.d(TAG, "UF2 file contains $numBlocks blocks")
                
                // Validate first block is UF2
                if (!isValidUf2Block(firmwareBytes, 0)) {
                    return@withContext FlashResult.Error("Invalid UF2 file: bad magic numbers")
                }
                
                // Get the usbCommunication using reflection (libaums 0.10.0 doesn't expose it publicly)
                // blockDevice is NOT a field - it's only created locally in setupDevice()
                // We need to create our own block device using the communication layer
                val usbCommField = targetDevice.javaClass.getDeclaredField("usbCommunication").apply {
                    isAccessible = true
                }
                val usbComm = usbCommField.get(targetDevice) as UsbCommunication
                
                // Create our own block device using the communication layer
                val blockDevice = BlockDeviceDriverFactory.createBlockDevice(usbComm, lun = 0)
                blockDevice.init()
                
                val blockSize = blockDevice.blockSize
                Log.d(TAG, "Block device block size: $blockSize")
                
                // The RP2040 BOOTSEL expects UF2 blocks written starting at a specific location
                // We write to the "data area" of the virtual FAT filesystem
                // Starting block for data area is typically around block 34-64 for FAT12
                // But actually, we can write UF2 blocks anywhere - the bootloader scans for UF2 magic
                
                // Write each UF2 block to sequential locations
                // Start after the reserved sectors (boot sector + FAT tables + root directory)
                // For RP2040's tiny FAT12, data starts around sector 34
                val startBlock: Long = 64  // Safe starting point past FAT structures
                
                val buffer = ByteBuffer.allocate(UF2_BLOCK_SIZE)
                
                for (i in 0 until numBlocks) {
                    val offset = i * UF2_BLOCK_SIZE
                    buffer.clear()
                    buffer.put(firmwareBytes, offset, UF2_BLOCK_SIZE)
                    buffer.flip()
                    
                    // Write block to device
                    blockDevice.write(startBlock + i, buffer)
                    
                    if (i % 50 == 0) {
                        Log.d(TAG, "Written ${i + 1}/$numBlocks blocks")
                    }
                }
                
                Log.d(TAG, "All $numBlocks blocks written successfully!")
                
                // Close the device - this should trigger the RP2040 to process the UF2 and reboot
                targetDevice.close()
                
                return@withContext FlashResult.Success
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during firmware write", e)
                try {
                    targetDevice.close()
                } catch (e2: Exception) {
                    // Ignore close errors
                }
                return@withContext FlashResult.Error("Write failed: ${e.message}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during firmware flash", e)
            return@withContext FlashResult.Error("Unexpected error: ${e.message}")
        }
    }
    
    /**
     * Check if a block in the firmware data is a valid UF2 block
     */
    private fun isValidUf2Block(data: ByteArray, blockIndex: Int): Boolean {
        val offset = blockIndex * UF2_BLOCK_SIZE
        if (offset + UF2_BLOCK_SIZE > data.size) return false
        
        // Read magic numbers directly from byte array (little endian)
        fun readInt32LE(pos: Int): Int {
            return (data[offset + pos].toInt() and 0xFF) or
                   ((data[offset + pos + 1].toInt() and 0xFF) shl 8) or
                   ((data[offset + pos + 2].toInt() and 0xFF) shl 16) or
                   ((data[offset + pos + 3].toInt() and 0xFF) shl 24)
        }
        
        val magic0 = readInt32LE(0)
        val magic1 = readInt32LE(4)
        
        // Check start magic
        if (magic0 != UF2_MAGIC_START0 || magic1 != UF2_MAGIC_START1) {
            Log.w(TAG, "Invalid UF2 start magic: ${String.format("0x%08X 0x%08X", magic0, magic1)}")
            return false
        }
        
        // Check end magic (at offset 508 within the block)
        val magicEnd = readInt32LE(508)
        if (magicEnd != UF2_MAGIC_END) {
            Log.w(TAG, "Invalid UF2 end magic: ${String.format("0x%08X", magicEnd)}")
            return false
        }
        
        return true
    }
    
    /**
     * Get a list of all connected mass storage devices (for debugging)
     */
    fun listMassStorageDevices(): List<String> {
        val devices = UsbMassStorageDevice.getMassStorageDevices(context)
        return devices.map { device ->
            val usb = device.usbDevice
            "VID:${String.format("0x%04X", usb.vendorId)} PID:${String.format("0x%04X", usb.productId)} ${usb.deviceName}"
        }
    }
}
