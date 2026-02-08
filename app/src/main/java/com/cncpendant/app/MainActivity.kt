package com.cncpendant.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import com.cncpendant.app.databinding.ActivityMainBinding
import android.graphics.Color
import android.util.Log
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout as WidgetLinearLayout
import android.widget.TextView
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import com.google.android.material.tabs.TabLayout
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.TimeUnit
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.DocumentsContract

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private lateinit var webSocketManager: WebSocketManager
    private var vibrator: Vibrator? = null
    private var soundPool: SoundPool? = null
    private var clickSoundId: Int = 0
    
    // USB Encoder support
    private var usbEncoderManager: UsbEncoderManager? = null
    private var encoderConnected = false
    
    // USB Mass Storage for firmware flashing
    private val usbMassStorageManager by lazy { UsbMassStorageManager(this) }
    private var lastEncoderJogSentAt = 0L
    private val ENCODER_MIN_SEND_INTERVAL_MS = 100L
    private var lastEncoderPositionActedOn: Long? = null  // Track position to catch up on dropped messages
    
    // FN modifier button state
    private var isFnHeld = false
    
    // Firmware flashing - track selected board for SAF callback
    private var pendingFirmwareBoardType: BoardType? = null
    
    // SAF launcher for creating firmware file on RPI-RP2
    private val firmwareFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { writeFirmwareToUri(it) }
    }

    private var selectedAxis: String = ""
    private var currentStep: Float = 0.05f
    private var currentFeedRate: Int = 500
    private var machinePosition = Position()
    private var workspacePosition = Position()
    private var workCoordinateOffset = Position()
    private var currentWorkspace = "G54"
    private var isConnected = false

    // Continuous jog (hold-to-move)
    private val jogHandler = Handler(Looper.getMainLooper())
    private var longPressTimer: Runnable? = null
    private var heartbeatTimer: Runnable? = null
    private var activeJogId: String? = null
    private var isLongPress = false
    private val LONG_PRESS_DELAY_MS = 300L
    private val HEARTBEAT_INTERVAL_MS = 250L
    private val CONTINUOUS_TRAVEL_DISTANCE = 400f
    private val JOG_COOLDOWN_MS = 200L
    private var jogCooldownUntil = 0L

    // Dial step jog rate limiting - drop commands if sent too fast
    private val DIAL_MIN_SEND_INTERVAL_MS = 200L
    private var lastDialJogSentAt = 0L

    private val STORAGE_KEY = "cnc_pendant_last_url"
    private val SAVED_URLS_KEY = "cnc_pendant_saved_urls"
    private val PREF_STEP_SIZE = "cnc_pendant_step_size"
    private val PREF_FEED_RATE = "cnc_pendant_feed_rate"
    private val PREF_WORKSPACE = "cnc_pendant_workspace"
    private val PREF_DIAL_MODE = "cnc_pendant_dial_mode"
    private val PREF_DIAL_POINTS = "cnc_pendant_dial_points"
    private val gson = Gson()
    private var savedUrls: MutableList<String> = mutableListOf()
    private var scanJob: Job? = null
    private var isHoming = false
    private var isHomed = false
    
    // Background timeout - disconnect after 30 minutes in background
    private val BACKGROUND_TIMEOUT_MS = 30L * 60L * 1000L  // 30 minutes
    private var backgroundDisconnectRunnable: Runnable? = null
    private var homingCycle = 0
    private var currentActiveState = ""
    private var senderStatus = ""
    private var senderConnected = false
    private var lockUiUpdatePending = false
    
    // Unlock sequence state
    private var awaitingUnlockMessage = false
    
    // Units preference from ncSender (metric or imperial)
    private var unitsPreference = "metric"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    
    // Override controls - track both current (from status) and previous (for delta calculation)
    private var currentFeedOverride = 100
    private var currentSpindleOverride = 100
    private var previousFeedOverride = 100  // Track what we last sent
    private var previousSpindleOverride = 100  // Track what we last sent
    private var currentMachineFeedRate = 0f
    private var currentSpindleSpeed = 0f
    private var requestedSpindleSpeed = 0f
    private var isUpdatingSlider = false
    private var userInteractingWithSlider = false  // Don't update slider while user is dragging
    private var overrideUpdateCooldownUntil = 0L  // Don't sync slider until cooldown expires (like ncSender's timeout)
    private var loadedJobFilename: String? = null
    
    // Update checker - only check once per hour
    private var lastUpdateCheckTime = 0L
    private val UPDATE_CHECK_INTERVAL_MS = 60 * 60 * 1000L  // 1 hour

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vibrator = getSystemService()
        webSocketManager = WebSocketManager(this)
        
        // Set volume controls to adjust media volume
        volumeControlStream = AudioManager.STREAM_MUSIC
        
        // Initialize SoundPool for click sounds
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            soundPool = SoundPool.Builder()
                .setMaxStreams(3)
                .setAudioAttributes(audioAttributes)
                .build()
            clickSoundId = soundPool?.load(this, R.raw.click, 1) ?: 0
            android.util.Log.d("MainActivity", "SoundPool initialized")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to init SoundPool", e)
        }

        setupUI()
        setupTabs()
        setupConnectionDot()
        setupProbeButton()
        updateConnectionUI(false)
        loadSavedUrls()
        loadUserSettings()
        
        // Initialize USB encoder manager
        setupUsbEncoder()
    }
    
    private fun checkForUpdates() {
        CoroutineScope(Dispatchers.Main).launch {
            val release = UpdateChecker.checkForUpdate(this@MainActivity)
            if (release != null) {
                UpdateChecker.showUpdateDialog(this@MainActivity, release)
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        
        // Cancel any pending background disconnect
        backgroundDisconnectRunnable?.let {
            jogHandler.removeCallbacks(it)
            backgroundDisconnectRunnable = null
            Log.d(TAG, "Cancelled background disconnect timer")
        }
        
        // Check for updates (with cooldown to avoid checking too often)
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheckTime > UPDATE_CHECK_INTERVAL_MS) {
            lastUpdateCheckTime = now
            checkForUpdates()
        }
        
        // Check if ConnectionManager has a different connection state than we think
        // This handles the case where VoiceControlActivity reconnected
        if (ConnectionManager.isConnected() && !isConnected) {
            // ConnectionManager is connected but we think we're disconnected
            // This means another activity reconnected - sync our state
            val serverAddress = ConnectionManager.getServerAddress()
            if (serverAddress.isNotEmpty()) {
                // Reconnect our local webSocketManager to match
                Log.d("MainActivity", "Syncing connection state from ConnectionManager")
                binding.wsUrlInput.setText(serverAddress.removePrefix("ws://").removePrefix("wss://"))
                connect()
            }
        } else if (!ConnectionManager.isConnected() && isConnected) {
            // We think we're connected but ConnectionManager says disconnected
            isConnected = false
            updateConnectionUI(false)
            binding.connectBtn.text = "Connect"
        }
    }
    
    override fun onStop() {
        super.onStop()
        
        // Schedule disconnect after 30 minutes in background
        // This keeps the connection alive for quick app switches but saves resources for long backgrounds
        if (isConnected) {
            backgroundDisconnectRunnable = Runnable {
                Log.d(TAG, "Background timeout reached, disconnecting WebSocket")
                disconnect()
                backgroundDisconnectRunnable = null
            }
            jogHandler.postDelayed(backgroundDisconnectRunnable!!, BACKGROUND_TIMEOUT_MS)
            Log.d(TAG, "Scheduled background disconnect in ${BACKGROUND_TIMEOUT_MS / 1000 / 60} minutes")
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle USB device attached intent when activity is already running
        // This prevents the activity from being recreated
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            Log.d(TAG, "USB device attached via onNewIntent")
            // The UsbEncoderManager will handle the device detection via its broadcast receiver
        }
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        binding.controlsTab.visibility = View.VISIBLE
                        binding.connectionTab.visibility = View.GONE
                    }
                    1 -> {
                        binding.controlsTab.visibility = View.GONE
                        binding.connectionTab.visibility = View.VISIBLE
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private var connectionDot: ImageView? = null

    private fun setupConnectionDot() {
        val tab = binding.tabLayout.getTabAt(1) ?: return
        val dot = ImageView(this).apply {
            val size = (12 * resources.displayMetrics.density).toInt()
            layoutParams = WidgetLinearLayout.LayoutParams(size, size).apply {
                marginStart = (6 * resources.displayMetrics.density).toInt()
            }
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#e74c3c"))
                setSize(size, size)
            }
            setImageDrawable(drawable)
        }
        connectionDot = dot

        val customView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            val textView = android.widget.TextView(this@MainActivity).apply {
                text = "Connection"
                setTextColor(binding.tabLayout.tabTextColors)
                textSize = 14f
            }
            addView(textView)
            addView(dot)
        }
        tab.customView = customView
    }

    private fun setupProbeButton() {
        binding.overflowMenuBtn.setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            popup.menuInflater.inflate(R.menu.overflow_menu, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_probe -> {
                        if (isConnected && !axisControlsDisabled()) {
                            val wsUrl = binding.wsUrlInput.text.toString().trim()
                                .removePrefix("ws://").removePrefix("wss://")
                            val intent = Intent(this, ProbeActivity::class.java).apply {
                                putExtra("server_address", wsUrl)
                            }
                            startActivity(intent)
                        } else if (!isConnected) {
                            Toast.makeText(this, "Connect to a server first", Toast.LENGTH_SHORT).show()
                        } else if (isJobRunning()) {
                            Toast.makeText(this, "Cannot probe during job", Toast.LENGTH_SHORT).show()
                        } else if (isAlarmState()) {
                            Toast.makeText(this, "Unlock machine first", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Home machine first", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    R.id.menu_voice_control -> {
                        if (isConnected) {
                            val wsUrl = binding.wsUrlInput.text.toString().trim()
                                .removePrefix("ws://").removePrefix("wss://")
                            val intent = Intent(this, VoiceControlActivity::class.java).apply {
                                putExtra("server_address", wsUrl)
                            }
                            startActivity(intent)
                        } else {
                            Toast.makeText(this, "Connect to a server first", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    R.id.menu_settings -> {
                        showSettingsDialog()
                        true
                    }
                    R.id.menu_about -> {
                        showAboutDialog()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun updateConnectionUI(connected: Boolean) {
        // Update dot color
        connectionDot?.let { dot ->
            val color = if (connected) "#2ecc71" else "#e74c3c"
            (dot.drawable as? GradientDrawable)?.setColor(android.graphics.Color.parseColor(color))
        }
        // Show/hide disabled overlay on controls
        binding.disconnectedOverlay.visibility = if (connected) View.GONE else View.VISIBLE
        // Show/hide open web browser button
        binding.openWebBtn.visibility = if (connected) View.VISIBLE else View.GONE
        // Update overflow menu button color based on connection
        binding.overflowMenuBtn.setTextColor(
            Color.parseColor(if (connected) "#ffffff" else "#7f8c8d")
        )
        // Update homing lock state
        if (!connected) {
            isHomed = false
            homingCycle = 0
            currentActiveState = ""
            senderStatus = ""
            senderConnected = false
        }
        updateHomingLockUI()
    }

    private fun needsHoming(): Boolean {
        return isConnected && homingCycle > 0 && !isHomed
    }

    private fun isAlarmState(): Boolean {
        return isConnected && currentActiveState.startsWith("Alarm")
    }

    private fun isSenderConnecting(): Boolean {
        return isConnected && (senderStatus in listOf("connecting", "disconnected") || !senderConnected)
    }
    
    private fun startUnlockSequence() {
        Toast.makeText(this, "Resetting and unlocking...", Toast.LENGTH_SHORT).show()
        awaitingUnlockMessage = true
        // Send soft reset first
        webSocketManager.sendSoftReset()
        // Set a timeout in case we don't receive the unlock message
        jogHandler.postDelayed({
            if (awaitingUnlockMessage) {
                awaitingUnlockMessage = false
                Log.d(TAG, "Unlock message timeout, sending \$X anyway")
                webSocketManager.sendCommand("\$X")
            }
        }, 3000) // 3 second timeout
    }
    
    private fun handleGrblMessage(message: String) {
        // Check for unlock message after soft reset
        if (awaitingUnlockMessage && message.contains("\$X") && message.contains("unlock")) {
            runOnUiThread {
                awaitingUnlockMessage = false
                Log.d(TAG, "Received unlock message, sending \$X")
                webSocketManager.sendCommand("\$X")
            }
        }
    }
    
    private fun handleAlarmCodeChanged(alarmCode: Int?, alarmDescription: String?) {
        runOnUiThread {
            if (alarmCode != null) {
                Log.d(TAG, "Alarm code received from ncSender: $alarmCode, description: $alarmDescription")
                updateAlarmMessage(alarmCode, alarmDescription)
            }
        }
    }
    
    private fun updateAlarmMessage(alarmCode: Int, serverDescription: String?) {
        // Use server-provided description if available, otherwise fall back to local lookup
        val description = serverDescription ?: getAlarmDescription(alarmCode)
        binding.alarmMessageText.text = "Alarm $alarmCode: $description"
        binding.alarmMessageText.visibility = View.VISIBLE
    }
    
    private fun getAlarmDescription(alarmCode: Int): String {
        return when (alarmCode) {
            1 -> "Alarm 1: Hard limit triggered\nMachine position likely lost. Re-home recommended."
            2 -> "Alarm 2: Soft limit alarm\nG-code motion target exceeds machine travel."
            3 -> "Alarm 3: Reset while in motion\nPosition may be lost. Re-home recommended."
            4 -> "Alarm 4: Probe fail\nProbe not in expected state before starting cycle."
            5 -> "Alarm 5: Probe fail\nProbe did not contact workpiece within travel."
            6 -> "Alarm 6: Homing fail\nReset during active homing cycle."
            7 -> "Alarm 7: Homing fail\nSafety door opened during homing cycle."
            8 -> "Alarm 8: Homing fail\nPull-off travel failed to clear limit switch."
            9 -> "Alarm 9: Homing fail\nCould not find limit switch within search distance."
            10 -> "Alarm 10: Homing fail\nOn dual axis machines, could not find second limit switch."
            else -> "Alarm $alarmCode: Unknown alarm code"
        }
    }

    private fun controlsLocked(): Boolean {
        return needsHoming() || isAlarmState() || isSenderConnecting()
    }
    
    private fun jogDisabled(): Boolean {
        // Jogging is disabled when controls are locked OR a job is running
        return controlsLocked() || isJobRunning()
    }
    
    private fun axisControlsDisabled(): Boolean {
        // Axis zeroing and workspace changes disabled when jogging disabled
        return jogDisabled()
    }

    private fun updateHomingLockUI() {
        val locked = controlsLocked()
        val jogLocked = jogDisabled()
        val axisLocked = axisControlsDisabled()
        val alpha = if (locked) 0.4f else 1.0f
        val jogAlpha = if (jogLocked) 0.4f else 1.0f

        // Dial controls - disabled during job
        binding.jogDial.isEnabled = !jogLocked
        binding.jogDial.alpha = jogAlpha

        // Pad buttons - disabled during job
        binding.xPlusBtn.isEnabled = !jogLocked
        binding.xMinusBtn.isEnabled = !jogLocked
        binding.yPlusBtn.isEnabled = !jogLocked
        binding.yMinusBtn.isEnabled = !jogLocked
        binding.zPlusBtn.isEnabled = !jogLocked
        binding.zMinusBtn.isEnabled = !jogLocked
        binding.xyUpLeftBtn.isEnabled = !jogLocked
        binding.xyUpRightBtn.isEnabled = !jogLocked
        binding.xyDownLeftBtn.isEnabled = !jogLocked
        binding.xyDownRightBtn.isEnabled = !jogLocked

        // Set alpha on pad buttons
        binding.xPlusBtn.alpha = jogAlpha
        binding.xMinusBtn.alpha = jogAlpha
        binding.yPlusBtn.alpha = jogAlpha
        binding.yMinusBtn.alpha = jogAlpha
        binding.zPlusBtn.alpha = jogAlpha
        binding.zMinusBtn.alpha = jogAlpha
        binding.xyUpLeftBtn.alpha = jogAlpha
        binding.xyUpRightBtn.alpha = jogAlpha
        binding.xyDownLeftBtn.alpha = jogAlpha
        binding.xyDownRightBtn.alpha = jogAlpha

        // Axis buttons (zero functionality disabled during job)
        binding.xBtn.alpha = if (axisLocked) 0.4f else 1.0f
        binding.yBtn.alpha = if (axisLocked) 0.4f else 1.0f
        binding.zBtn.alpha = if (axisLocked) 0.4f else 1.0f
        binding.xBtn.isEnabled = !axisLocked
        binding.yBtn.isEnabled = !axisLocked
        binding.zBtn.isEnabled = !axisLocked

        // Workspace spinner - disabled during job
        binding.workspaceSpinner.isEnabled = !axisLocked
        binding.workspaceSpinner.alpha = if (axisLocked) 0.4f else 1.0f

        // Start button - enabled when not in alarm/homing states (can start/resume job)
        // But disabled if controls locked (except when job is running, then it's hidden anyway)
        val startEnabled = !locked || isJobRunning()
        binding.startBtn.isEnabled = startEnabled
        binding.startBtn.alpha = if (startEnabled) 1.0f else 0.4f
        
        // Halt button - always enabled when connected (safety - can always pause/hold)
        binding.haltBtn.isEnabled = isConnected
        binding.haltBtn.alpha = if (isConnected) 1.0f else 0.4f

        // Home button - disabled during job (can't home while running)
        val homeDisabled = isJobRunning()
        binding.homeBtn.isEnabled = !homeDisabled
        binding.homeBtn.alpha = if (homeDisabled) 0.4f else 1.0f

        // Stop buttons stay enabled always (safety)

        // Update machine status banner
        updateMachineStatusBanner()
    }

    /**
     * Coalesces multiple state updates from a single server-state-updated message
     * into one UI refresh. All callbacks (activeState, homing, senderStatus) post
     * to the UI thread independently — this ensures we wait until all have been
     * processed before refreshing the lock UI.
     */
    private fun scheduleLockUiUpdate() {
        if (!lockUiUpdatePending) {
            lockUiUpdatePending = true
            binding.root.post {
                lockUiUpdatePending = false
                updateHomingLockUI()
            }
        }
    }

    private fun updateMachineStatusBanner() {
        val banner = binding.machineStatusBanner
        val statusText = binding.machineStatusText
        val unlockBtn = binding.unlockBtn

        when {
            !isConnected -> {
                // Disconnected state is handled by the overlay, hide banner
                banner.visibility = View.GONE
                binding.alarmMessageText.visibility = View.GONE
            }
            isSenderConnecting() -> {
                banner.visibility = View.VISIBLE
                statusText.text = "Connecting..."
                statusText.setTextColor(Color.parseColor("#3498db"))
                unlockBtn.visibility = View.GONE
                binding.alarmMessageText.visibility = View.GONE
            }
            isAlarmState() -> {
                banner.visibility = View.VISIBLE
                statusText.text = "Requires Unlock"
                statusText.setTextColor(Color.parseColor("#e74c3c"))
                unlockBtn.visibility = View.VISIBLE
                // Show waiting message - will be updated when ALARM:X message arrives
                if (binding.alarmMessageText.visibility == View.GONE) {
                    binding.alarmMessageText.text = "Waiting for alarm details..."
                    binding.alarmMessageText.visibility = View.VISIBLE
                }
            }
            needsHoming() -> {
                banner.visibility = View.VISIBLE
                if (isHoming) {
                    statusText.text = "Homing..."
                    statusText.setTextColor(Color.parseColor("#3498db"))
                } else {
                    statusText.text = "Requires Homing"
                    statusText.setTextColor(Color.parseColor("#f39c12"))
                }
                unlockBtn.visibility = View.GONE
                binding.alarmMessageText.visibility = View.GONE
            }
            else -> {
                banner.visibility = View.GONE
                binding.alarmMessageText.visibility = View.GONE
            }
        }
    }

    private fun setupUI() {
        // Unlock button - sends soft reset, waits for unlock message, then sends $X
        binding.unlockBtn.setOnClickListener {
            if (isConnected) {
                startUnlockSequence()
            }
        }

        // Axis buttons - tap to toggle, hold to zero
        setupAxisHoldToZero(binding.xBtn, "X", "#e74c3c")
        setupAxisHoldToZero(binding.yBtn, "Y", "#2ecc71")
        setupAxisHoldToZero(binding.zBtn, "Z", "#3498db")

        // Connect button
        binding.connectBtn.setOnClickListener {
            if (isConnected) {
                disconnect()
            } else {
                connect()
            }
        }

        // Open ncSender web UI in browser
        binding.openWebBtn.setOnClickListener {
            val wsUrl = binding.wsUrlInput.text.toString().trim()
                .removePrefix("ws://").removePrefix("wss://")
            val host = wsUrl.split(":").firstOrNull() ?: return@setOnClickListener
            val url = "http://$host:8090"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        // Network scan button
        binding.scanBtn.setOnClickListener { startNetworkScan() }

        // Workspace selector
        val workspaceAdapter = binding.workspaceSpinner.adapter
        binding.workspaceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val workspace = parent?.getItemAtPosition(position).toString()
                if (workspace != currentWorkspace && isConnected && !axisControlsDisabled()) {
                    currentWorkspace = workspace
                    saveUserSettings()
                    webSocketManager.sendCommand(workspace)
                    // Request status update
                    binding.root.postDelayed({
                        webSocketManager.sendCommand("?")
                    }, 150)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        // Restore saved workspace selection
        for (i in 0 until workspaceAdapter.count) {
            if (workspaceAdapter.getItem(i).toString() == currentWorkspace) {
                binding.workspaceSpinner.setSelection(i)
                break
            }
        }

        // Jog dial - step jog (under 10 ticks)
        binding.jogDial.setOnJogListener { clicks, direction ->
            playClick()
            if (isConnected && !jogDisabled() && selectedAxis.isNotEmpty()) {
                val now = System.currentTimeMillis()
                if (now - lastDialJogSentAt >= DIAL_MIN_SEND_INTERVAL_MS && !isJogCoolingDown()) {
                    lastDialJogSentAt = now
                    val distance = currentStep * clicks * direction
                    // Use jog:step directly - server atomically prepends 0x85 jog cancel
                    webSocketManager.sendJogCommand(selectedAxis, distance, currentFeedRate)
                }
            }
        }

        // Jog dial - continuous jog (5+ ticks rapid spin)
        binding.jogDial.setOnContinuousJogStartListener { direction ->
            playClick()
            if (isConnected && !jogDisabled() && selectedAxis.isNotEmpty()) {
                startContinuousJog(selectedAxis, direction)
            }
        }
        binding.jogDial.setOnContinuousJogStopListener {
            stopContinuousJog()
        }

        // Apply saved dial points setting
        binding.jogDial.setNumTicks(loadDialPoints())

        // Step size spinner - values in mm (metric) or converted from inches (imperial)
        // Metric: 0.05, 0.1, 1, 10, 100 mm
        // Imperial: 0.001, 0.005, 0.01, 0.1, 1 in (converted to mm: 0.0254, 0.127, 0.254, 2.54, 25.4)
        binding.stepSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentStep = getStepValue(position)
                saveUserSettings()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        // Restore saved step size selection
        val stepIndex = getStepValues().indexOfFirst { it == currentStep }
        if (stepIndex >= 0) binding.stepSpinner.setSelection(stepIndex)

        // Feed rate spinner - values in mm/min (metric) or converted from in/min (imperial)
        // Metric: 100, 500, 1000, 3000, 6000, 8000 mm/min
        // Imperial: 4, 20, 40, 120, 240, 315 in/min (converted to mm/min: ~100, ~500, ~1000, ~3000, ~6000, ~8000)
        binding.feedRateSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentFeedRate = getFeedRateValue(position)
                saveUserSettings()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        // Restore saved feed rate selection
        val feedRateIndex = getFeedRateValues().indexOfFirst { it == currentFeedRate }
        binding.feedRateSpinner.setSelection(if (feedRateIndex >= 0) feedRateIndex else 1)

        // Mode toggle button - shows the OTHER mode name
        binding.modeToggleBtn.setOnClickListener {
            val switchToDial = binding.dialContainer.visibility == View.GONE
            selectMode(switchToDial)
        }
        selectMode(loadJogMode()) // Restore last used mode

        // Home button - hold 3 seconds to trigger homing
        setupHomeButton()

        // Touch pad buttons (hold for continuous jog)
        setupPadButton(binding.xPlusBtn, "X", 1)
        setupPadButton(binding.xMinusBtn, "X", -1)
        setupPadButton(binding.yPlusBtn, "Y", 1)
        setupPadButton(binding.yMinusBtn, "Y", -1)
        setupPadButton(binding.zPlusBtn, "Z", 1)
        setupPadButton(binding.zMinusBtn, "Z", -1)

        // Diagonal buttons (hold for continuous jog)
        setupDiagonalPadButton(binding.xyUpLeftBtn, -1, 1)
        setupDiagonalPadButton(binding.xyUpRightBtn, 1, 1)
        setupDiagonalPadButton(binding.xyDownLeftBtn, -1, -1)
        setupDiagonalPadButton(binding.xyDownRightBtn, 1, -1)
        
        // Stop buttons (both pad and dial screens) - use soft reset for emergency stop
        binding.stopBtn.setOnClickListener {
            if (isConnected) {
                stopContinuousJog()
                webSocketManager.sendSoftReset()
            }
        }
        binding.dialStopBtn.setOnClickListener {
            if (isConnected) {
                stopContinuousJog()
                webSocketManager.sendSoftReset()
            }
        }
        
        // Start button - hold to activate (like home button)
        setupStartButton()
        
        binding.haltBtn.setOnClickListener {
            if (isConnected) {
                // Pause/hold the running job
                if (senderStatus == "running") {
                    webSocketManager.sendSenderPause()
                } else {
                    webSocketManager.sendFeedHold()
                }
            }
        }
        
        // Override controls setup
        setupOverrideControls()
    }
    
    @SuppressLint("ClickableViewAccessibility")
    private fun setupStartButton() {
        val holdDuration = 1000L
        var holdHandler: Handler? = null
        var holdRunnable: Runnable? = null
        var holdStartTime = 0L
        var cycleTriggered = false

        val fillView = binding.startBtnFill

        binding.startBtn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    Log.d("MainActivity", "CYCLE ACTION_DOWN - isConnected: $isConnected, controlsLocked: ${controlsLocked()}, senderStatus: $senderStatus")
                    if (!isConnected || controlsLocked()) {
                        Log.d("MainActivity", "CYCLE blocked - not connected or controls locked")
                        return@setOnTouchListener true
                    }
                    holdStartTime = System.currentTimeMillis()
                    cycleTriggered = false
                    v.isPressed = true
                    holdHandler = Handler(Looper.getMainLooper())

                    // Animate fill bar from left to right
                    val parentWidth = (fillView.parent as View).width
                    val fillRunnable = object : Runnable {
                        override fun run() {
                            val elapsed = System.currentTimeMillis() - holdStartTime
                            val progress = (elapsed.toFloat() / holdDuration).coerceIn(0f, 1f)
                            fillView.layoutParams = fillView.layoutParams.apply {
                                width = (parentWidth * progress).toInt()
                            }
                            fillView.requestLayout()
                            if (progress < 1f) {
                                holdHandler?.postDelayed(this, 16) // ~60fps
                            }
                        }
                    }

                    holdRunnable = Runnable {
                        // Hold duration elapsed - start/resume job
                        cycleTriggered = true
                        Log.d("MainActivity", "Cycle button triggered, senderStatus: $senderStatus, loadedJob: $loadedJobFilename")
                        when (senderStatus) {
                            "idle" -> {
                                val filename = loadedJobFilename
                                if (filename != null) {
                                    webSocketManager.sendSenderStart(filename)
                                } else {
                                    Log.w("MainActivity", "No job loaded to start")
                                }
                            }
                            "paused", "hold" -> webSocketManager.sendSenderResume()
                            else -> {
                                val filename = loadedJobFilename
                                if (filename != null) {
                                    webSocketManager.sendSenderStart(filename)
                                }
                            }
                        }
                        binding.startBtn.text = "Starting..."
                        binding.startBtn.setTextColor(Color.WHITE)
                        fillView.setBackgroundColor(Color.parseColor("#502e7d32"))
                        vibrator?.let {
                            @Suppress("DEPRECATION")
                            it.vibrate(100)
                        }
                    }
                    holdHandler?.post(fillRunnable)
                    holdHandler?.postDelayed(holdRunnable!!, holdDuration)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    holdHandler?.removeCallbacksAndMessages(null)
                    holdHandler = null
                    // Reset button appearance after a short delay
                    binding.startBtn.postDelayed({
                        binding.startBtn.text = "CYCLE\nHold"
                        binding.startBtn.setTextColor(Color.parseColor("#2e7d32"))
                        fillView.setBackgroundColor(Color.parseColor("#302e7d32"))
                        fillView.layoutParams = fillView.layoutParams.apply { width = 0 }
                        fillView.requestLayout()
                    }, if (cycleTriggered) 500 else 0)
                    true
                }
                else -> false
            }
        }
    }
    
    private fun setupOverrideControls() {
        // Feed override slider - exactly like ncSender:
        // Track user interaction to prevent status updates from moving slider while dragging
        binding.feedOverrideSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (isUpdatingSlider) return
                // Round to nearest 10 to match ncSender's step=10
                val rounded = (progress / 10) * 10
                binding.feedOverridePercent.text = "$rounded%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userInteractingWithSlider = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userInteractingWithSlider = false
                if (isUpdatingSlider) return
                // Round to nearest 10 to match ncSender's step=10
                val rawProgress = seekBar?.progress ?: 100
                val targetPercent = (rawProgress / 10) * 10
                seekBar?.progress = targetPercent  // Snap to 10% increment
                adjustFeedOverride(targetPercent)
            }
        })
        
        // Spindle override slider - exactly like ncSender
        binding.spindleOverrideSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (isUpdatingSlider) return
                // Round to nearest 10 to match ncSender's step=10
                val rounded = (progress / 10) * 10
                binding.spindleOverridePercent.text = "$rounded%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userInteractingWithSlider = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userInteractingWithSlider = false
                if (isUpdatingSlider) return
                // Round to nearest 10 to match ncSender's step=10
                val rawProgress = seekBar?.progress ?: 100
                val targetPercent = (rawProgress / 10) * 10
                seekBar?.progress = targetPercent  // Snap to 10% increment
                adjustSpindleOverride(targetPercent)
            }
        })
        
        // Reset buttons - send 0x90 and 0x99 commands like ncSender
        binding.feedResetBtn.setOnClickListener {
            if (isConnected) {
                webSocketManager.sendCommand("0x90")  // FEED_RESET
                previousFeedOverride = 100
                overrideUpdateCooldownUntil = System.currentTimeMillis() + 500
            }
        }
        binding.spindleResetBtn.setOnClickListener {
            if (isConnected) {
                webSocketManager.sendCommand("0x99")  // SPINDLE_RESET
                previousSpindleOverride = 100
                overrideUpdateCooldownUntil = System.currentTimeMillis() + 500
            }
        }
        
        // Stop button in override panel
        binding.overrideStopBtn.setOnClickListener {
            if (isConnected) {
                webSocketManager.sendSoftReset()
            }
        }
    }
    
    // Adjust feed override like ncSender - calculate diff from previous and send 10% commands
    private fun adjustFeedOverride(targetPercent: Int) {
        if (!isConnected) return
        val diff = targetPercent - previousFeedOverride
        if (diff == 0) return
        
        Log.d("MainActivity", "Adjusting feed override from $previousFeedOverride to $targetPercent (diff=$diff)")
        
        // Set cooldown to prevent status updates from reverting slider (like ncSender's feedUpdateTimeout)
        overrideUpdateCooldownUntil = System.currentTimeMillis() + 500
        
        // Send +10% or -10% commands based on difference (ncSender only uses 10% steps)
        val steps = kotlin.math.abs(diff) / 10
        
        repeat(steps) {
            if (diff > 0) {
                webSocketManager.sendCommand("0x91") // +10%
            } else {
                webSocketManager.sendCommand("0x92") // -10%
            }
        }
        
        // Update previous to track what we've sent
        previousFeedOverride = targetPercent
    }
    
    // Adjust spindle override like ncSender - calculate diff from previous and send 10% commands  
    private fun adjustSpindleOverride(targetPercent: Int) {
        if (!isConnected) return
        val diff = targetPercent - previousSpindleOverride
        if (diff == 0) return
        
        Log.d("MainActivity", "Adjusting spindle override from $previousSpindleOverride to $targetPercent (diff=$diff)")
        
        // Set cooldown to prevent status updates from reverting slider (like ncSender's spindleUpdateTimeout)
        overrideUpdateCooldownUntil = System.currentTimeMillis() + 500
        
        // Send +10% or -10% commands based on difference (ncSender only uses 10% steps)
        val steps = kotlin.math.abs(diff) / 10
        
        repeat(steps) {
            if (diff > 0) {
                webSocketManager.sendCommand("0x9A") // +10%
            } else {
                webSocketManager.sendCommand("0x9B") // -10%
            }
        }
        
        // Update previous to track what we've sent
        previousSpindleOverride = targetPercent
    }
    
    private fun isJobRunning(): Boolean {
        return senderStatus == "running" || senderStatus == "hold"
    }
    
    private fun updateOverrideUI() {
        val jobRunning = isJobRunning()
        
        // Show/hide override controls vs jog controls
        binding.overrideContainer.visibility = if (jobRunning) View.VISIBLE else View.GONE
        
        if (!jobRunning) {
            // Restore normal dial/pad visibility based on current mode
            val dialMode = binding.modeToggleBtn.text.toString().contains("Pad")
            binding.dialContainer.visibility = if (dialMode) View.VISIBLE else View.GONE
            binding.padContainer.visibility = if (dialMode) View.GONE else View.VISIBLE
        } else {
            binding.dialContainer.visibility = View.GONE
            binding.padContainer.visibility = View.GONE
        }
        
        // Hide mode toggle row and direction label when job is running
        binding.modeToggleRow.visibility = if (jobRunning) View.GONE else View.VISIBLE
        binding.directionLabel.visibility = if (jobRunning) View.GONE else View.VISIBLE
        
        // Update slider positions and displays - but only when user is NOT interacting
        // AND cooldown has expired (like ncSender's feedUpdateTimeout/spindleUpdateTimeout check)
        val cooldownExpired = System.currentTimeMillis() >= overrideUpdateCooldownUntil
        if (!userInteractingWithSlider && cooldownExpired) {
            isUpdatingSlider = true
            binding.feedOverrideSlider.progress = currentFeedOverride.coerceIn(0, 200)
            binding.spindleOverrideSlider.progress = currentSpindleOverride.coerceIn(0, 200)
            binding.feedOverridePercent.text = "$currentFeedOverride%"
            binding.spindleOverridePercent.text = "$currentSpindleOverride%"
            // Sync previous values with current status (like ncSender does)
            previousFeedOverride = currentFeedOverride
            previousSpindleOverride = currentSpindleOverride
            isUpdatingSlider = false
        }
        
        // Update rate displays (always update these, they don't affect slider interaction)
        binding.feedRateDisplay.text = "${currentMachineFeedRate.toInt()} mm/min"
        binding.spindleSpeedDisplay.text = "${currentSpindleSpeed.toInt()} / ${requestedSpindleSpeed.toInt()} rpm"
    }

    private fun selectMode(dialMode: Boolean) {
        // Button shows the name of the mode you can switch TO
        binding.modeToggleBtn.text = if (dialMode) "\u25A6 Pad" else "\u25CE Dial"
        binding.dialContainer.visibility = if (dialMode) View.VISIBLE else View.GONE
        binding.padContainer.visibility = if (dialMode) View.GONE else View.VISIBLE
        
        // Disable axis button interaction in pad mode
        binding.xBtn.isClickable = dialMode
        binding.yBtn.isClickable = dialMode
        binding.zBtn.isClickable = dialMode
        binding.xBtn.isFocusable = dialMode
        binding.yBtn.isFocusable = dialMode
        binding.zBtn.isFocusable = dialMode
        
        // Unselect axis when switching to pad mode
        if (!dialMode) {
            selectedAxis = ""
            setAxisSelected(false, binding.xBtn, binding.yBtn, binding.zBtn)
            binding.directionLabel.text = "Select Axis"
        }

        // Show overlay and grey out dial when switching to dial mode with no axis selected
        if (dialMode && selectedAxis.isEmpty()) {
            binding.selectAxisOverlay.visibility = View.VISIBLE
            binding.dialGreyOverlay.visibility = View.VISIBLE
        }

        saveJogMode(dialMode)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHomeButton() {
        val holdDuration = 1500L
        var holdHandler: Handler? = null
        var holdRunnable: Runnable? = null
        var holdStartTime = 0L

        val fillView = binding.homeBtnFill

        binding.homeBtn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isConnected) return@setOnTouchListener true
                    holdStartTime = System.currentTimeMillis()
                    v.isPressed = true
                    holdHandler = Handler(Looper.getMainLooper())

                    // Animate fill bar from left to right
                    val parentWidth = (fillView.parent as View).width
                    val fillRunnable = object : Runnable {
                        override fun run() {
                            val elapsed = System.currentTimeMillis() - holdStartTime
                            val progress = (elapsed.toFloat() / holdDuration).coerceIn(0f, 1f)
                            fillView.layoutParams = fillView.layoutParams.apply {
                                width = (parentWidth * progress).toInt()
                            }
                            fillView.requestLayout()
                            if (progress < 1f) {
                                holdHandler?.postDelayed(this, 16) // ~60fps
                            }
                        }
                    }

                    holdRunnable = Runnable {
                        // 3 seconds elapsed - send homing command
                        isHoming = true
                        webSocketManager.sendCommand("\$H")
                        binding.homeBtn.text = "Homing..."
                        binding.homeBtn.setTextColor(Color.WHITE)
                        fillView.setBackgroundColor(Color.parseColor("#5027ae60"))
                        vibrator?.let {
                            @Suppress("DEPRECATION")
                            it.vibrate(100)
                        }
                    }
                    holdHandler?.post(fillRunnable)
                    holdHandler?.postDelayed(holdRunnable!!, holdDuration)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    holdHandler?.removeCallbacksAndMessages(null)
                    holdHandler = null
                    if (!isHoming) {
                        // Only reset if homing wasn't triggered
                        binding.homeBtn.text = "\u2302 Home\nHold"
                        binding.homeBtn.setTextColor(Color.parseColor("#2ecc71"))
                        fillView.layoutParams = fillView.layoutParams.apply { width = 0 }
                        fillView.requestLayout()
                    }
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupAxisHoldToZero(axisBtn: View, axis: String, colorHex: String) {
        val holdDuration = 2000L
        var holdHandler: Handler? = null
        var holdRunnable: Runnable? = null
        var holdStartTime = 0L
        var didZero = false

        // Create a ClipDrawable as foreground overlay for fill animation
        val fillColor = Color.parseColor("#50${colorHex.removePrefix("#")}")
        val colorDrawable = android.graphics.drawable.ColorDrawable(fillColor)
        val clipDrawable = android.graphics.drawable.ClipDrawable(
            colorDrawable, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL
        )
        clipDrawable.level = 0
        axisBtn.foreground = clipDrawable

        axisBtn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    holdStartTime = System.currentTimeMillis()
                    didZero = false
                    holdHandler = Handler(Looper.getMainLooper())

                    // Animate fill from left to right using ClipDrawable level (0-10000)
                    val fillRunnable = object : Runnable {
                        override fun run() {
                            val elapsed = System.currentTimeMillis() - holdStartTime
                            val progress = (elapsed.toFloat() / holdDuration).coerceIn(0f, 1f)
                            clipDrawable.level = (progress * 10000).toInt()
                            if (progress < 1f) {
                                holdHandler?.postDelayed(this, 16)
                            }
                        }
                    }

                    holdRunnable = Runnable {
                        // Hold completed - zero the axis
                        didZero = true
                        if (isConnected && !axisControlsDisabled()) {
                            webSocketManager.sendCommand("G10 L20 P1 ${axis}0")
                        }
                        vibrator?.let {
                            @Suppress("DEPRECATION")
                            it.vibrate(100)
                        }
                        // Flash then reset
                        clipDrawable.level = 10000
                        axisBtn.postDelayed({
                            clipDrawable.level = 0
                        }, 200)
                    }
                    holdHandler?.post(fillRunnable)
                    holdHandler?.postDelayed(holdRunnable!!, holdDuration)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    holdHandler?.removeCallbacksAndMessages(null)
                    holdHandler = null
                    if (!didZero) {
                        clipDrawable.level = 0
                        if (event.action == MotionEvent.ACTION_UP) {
                            toggleAxis(axis)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun jogPad(axis: String, direction: Int) {
        playClick()
        if (isConnected && !jogDisabled()) {
            val distance = currentStep * direction
            jogAxis(axis, distance)
        }
    }

    private fun jogPadDiagonal(xDir: Int, yDir: Int) {
        playClick()
        if (isConnected && !jogDisabled() && !isJogCoolingDown()) {
            webSocketManager.sendJogCancel()
            val xDist = currentStep * xDir
            val yDist = currentStep * yDir
            val feedRate = currentFeedRate
            val command = "\$J=G91 X${String.format("%.3f", xDist)} Y${String.format("%.3f", yDist)} F$feedRate"
            webSocketManager.sendCommand(command)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPadButton(view: View, axis: String, direction: Int) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    isLongPress = false
                    longPressTimer = Runnable {
                        isLongPress = true
                        startContinuousJog(axis, direction)
                    }
                    jogHandler.postDelayed(longPressTimer!!, LONG_PRESS_DELAY_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    longPressTimer?.let { jogHandler.removeCallbacks(it) }
                    longPressTimer = null
                    if (isLongPress) {
                        stopContinuousJog()
                    } else {
                        jogPad(axis, direction)
                    }
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDiagonalPadButton(view: View, xDir: Int, yDir: Int) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    isLongPress = false
                    longPressTimer = Runnable {
                        isLongPress = true
                        startContinuousDiagonalJog(xDir, yDir)
                    }
                    jogHandler.postDelayed(longPressTimer!!, LONG_PRESS_DELAY_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    longPressTimer?.let { jogHandler.removeCallbacks(it) }
                    longPressTimer = null
                    if (isLongPress) {
                        stopContinuousJog()
                    } else {
                        jogPadDiagonal(xDir, yDir)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun generateJogId(): String {
        return "pendant-${System.currentTimeMillis()}-${(Math.random() * 65536).toInt().toString(16)}"
    }

    private fun startContinuousJog(axis: String, direction: Int) {
        if (!isConnected || jogDisabled()) return
        playClick()

        val jogId = generateJogId()
        activeJogId = jogId
        val dirSign = if (direction > 0) "" else "-"
        val feedRate = currentFeedRate
        val command = "\$J=G91 $axis$dirSign${String.format("%.3f", CONTINUOUS_TRAVEL_DISTANCE)} F$feedRate"
        val dirStr = if (direction > 0) "+" else "-"

        webSocketManager.sendJogStart(jogId, command, axis, dirStr, feedRate)
        startHeartbeat(jogId)
    }

    private fun startContinuousDiagonalJog(xDir: Int, yDir: Int) {
        if (!isConnected || jogDisabled()) return
        playClick()

        val jogId = generateJogId()
        activeJogId = jogId
        val feedRate = currentFeedRate
        val xSign = if (xDir > 0) "" else "-"
        val ySign = if (yDir > 0) "" else "-"
        val command = "\$J=G91 X$xSign${String.format("%.3f", CONTINUOUS_TRAVEL_DISTANCE)} Y$ySign${String.format("%.3f", CONTINUOUS_TRAVEL_DISTANCE)} F$feedRate"

        webSocketManager.sendJogStart(jogId, command, "XY", if (xDir > 0) "+" else "-", feedRate)
        startHeartbeat(jogId)
    }

    private fun startHeartbeat(jogId: String) {
        stopHeartbeat()
        heartbeatTimer = object : Runnable {
            override fun run() {
                if (activeJogId == jogId) {
                    webSocketManager.sendJogHeartbeat(jogId)
                    jogHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
                }
            }
        }
        jogHandler.postDelayed(heartbeatTimer!!, HEARTBEAT_INTERVAL_MS)
    }

    private fun stopHeartbeat() {
        heartbeatTimer?.let { jogHandler.removeCallbacks(it) }
        heartbeatTimer = null
    }

    private fun stopContinuousJog() {
        val jogId = activeJogId ?: return
        stopHeartbeat()
        webSocketManager.sendJogStop(jogId)
        webSocketManager.sendJogCancel()
        activeJogId = null
        // Prevent new jog commands while GRBL processes the cancel
        jogCooldownUntil = System.currentTimeMillis() + JOG_COOLDOWN_MS
    }

    private fun toggleAxis(axis: String) {
        if (selectedAxis == axis) {
            // Unselect if already selected
            selectedAxis = ""
            setAxisSelected(false, binding.xBtn, binding.yBtn, binding.zBtn)
            binding.directionLabel.text = "Select Axis"
            binding.selectAxisOverlay.visibility = View.VISIBLE
            binding.dialGreyOverlay.visibility = View.VISIBLE
        } else {
            selectAxis(axis)
        }
    }

    private fun setAxisSelected(selected: Boolean, vararg btns: View) {
        for (btn in btns) {
            btn.isSelected = selected
        }
    }

    private fun selectAxis(axis: String) {
        selectedAxis = axis

        // Update button states
        setAxisSelected(axis == "X", binding.xBtn)
        setAxisSelected(axis == "Y", binding.yBtn)
        setAxisSelected(axis == "Z", binding.zBtn)

        binding.directionLabel.text = "Jog $axis Axis"
        binding.selectAxisOverlay.visibility = View.GONE
        binding.dialGreyOverlay.visibility = View.GONE
    }


    private fun connect() {
        val url = binding.wsUrlInput.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Please enter a WebSocket URL", Toast.LENGTH_SHORT).show()
            return
        }

        var finalUrl = url
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            finalUrl = "ws://$url"
        }

        binding.connectBtn.isEnabled = false
        binding.connectBtn.text = "Connecting..."

        // Also connect via ConnectionManager so other activities share the connection state
        ConnectionManager.connect(this, finalUrl)
        
        webSocketManager.connect(finalUrl, object : WebSocketManager.ConnectionListener {
            override fun onConnected() {
                runOnUiThread {
                    addSavedUrl(finalUrl)
                    isConnected = true
                    updateConnectionUI(true)
                    binding.connectBtn.isEnabled = true
                    binding.connectBtn.isSelected = true
                    binding.connectBtn.text = "Disconnect"
                    // Switch to Controls tab after connecting
                    binding.tabLayout.getTabAt(0)?.select()
                    // Fetch unit preference from ncSender
                    fetchServerSettings(finalUrl)
                }
            }

            override fun onDisconnected() {
                runOnUiThread {
                    isConnected = false
                    updateConnectionUI(false)
                    binding.connectBtn.isEnabled = true
                    binding.connectBtn.isSelected = false
                    binding.connectBtn.text = "Connect"
                    // Also update ConnectionManager
                    ConnectionManager.disconnect()
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error: $error", Toast.LENGTH_SHORT).show()
                    binding.connectBtn.isEnabled = true
                    binding.connectBtn.text = "Connect"
                }
            }

            override fun onMachineStateUpdate(mPos: WebSocketManager.Position?, wco: WebSocketManager.Position?, wcs: String?) {
                runOnUiThread {
                    mPos?.let {
                        machinePosition = Position(it.x, it.y, it.z)
                        recalculateWorkPosition()
                    }
                    wco?.let {
                        workCoordinateOffset = Position(it.x, it.y, it.z)
                        recalculateWorkPosition()
                    }
                    wcs?.let {
                        if (it != currentWorkspace) {
                            currentWorkspace = it
                            val position = resources.getStringArray(R.array.workspaces).indexOf(it)
                            if (position >= 0) {
                                binding.workspaceSpinner.setSelection(position)
                            }
                        }
                    }
                    updatePositionDisplay()
                }
            }

            override fun onActiveStateChanged(state: String) {
                runOnUiThread {
                    Log.d("HomeButton", "State changed: '$state', isHoming=$isHoming, isConnected=$isConnected")
                    currentActiveState = state
                    if (isHoming && (state.startsWith("Idle") || state.startsWith("Alarm"))) {
                        // Homing finished (machine returned to Idle) or failed (Alarm)
                        isHoming = false
                        binding.homeBtn.text = "\u2302 Home\nHold"
                        binding.homeBtn.setTextColor(Color.parseColor("#2ecc71"))
                        binding.homeBtnFill.setBackgroundColor(Color.parseColor("#3027ae60"))
                        binding.homeBtnFill.layoutParams = binding.homeBtnFill.layoutParams.apply { width = 0 }
                        binding.homeBtnFill.requestLayout()
                    }
                    scheduleLockUiUpdate()
                }
            }

            override fun onPinStateChanged(pn: String) {
                // Not needed in MainActivity
            }

            override fun onHomingStateChanged(homed: Boolean, homingCycle: Int) {
                runOnUiThread {
                    isHomed = homed
                    this@MainActivity.homingCycle = homingCycle
                    scheduleLockUiUpdate()
                }
            }

            override fun onSenderStatusChanged(status: String) {
                runOnUiThread {
                    Log.d("MainActivity", "Sender status changed: '$status' (was '$senderStatus')")
                    senderStatus = status
                    scheduleLockUiUpdate()
                    updateOverrideUI()
                }
            }

            override fun onSenderConnectedChanged(connected: Boolean) {
                runOnUiThread {
                    Log.d("MainActivity", "Sender connected changed: $connected (was $senderConnected)")
                    senderConnected = connected
                    scheduleLockUiUpdate()
                }
            }
            
            override fun onOverridesChanged(feedOverride: Int, spindleOverride: Int, feedRate: Float, spindleSpeed: Float, reqSpindleSpeed: Float) {
                runOnUiThread {
                    Log.d("MainActivity", "Overrides received: feed=$feedOverride%, spindle=$spindleOverride%, rate=$feedRate, rpm=$spindleSpeed/$reqSpindleSpeed")
                    currentFeedOverride = feedOverride
                    currentSpindleOverride = spindleOverride
                    currentMachineFeedRate = feedRate
                    currentSpindleSpeed = spindleSpeed
                    requestedSpindleSpeed = reqSpindleSpeed
                    updateOverrideUI()
                }
            }
            
            override fun onJobLoadedChanged(filename: String?) {
                runOnUiThread {
                    Log.d("MainActivity", "Job loaded changed: $filename")
                    loadedJobFilename = filename
                }
            }
            
            override fun onGrblMessage(message: String) {
                handleGrblMessage(message)
            }
            
            override fun onAlarmCodeChanged(alarmCode: Int?, alarmDescription: String?) {
                handleAlarmCodeChanged(alarmCode, alarmDescription)
            }
        })
    }

    private fun disconnect() {
        webSocketManager.disconnect()
        ConnectionManager.disconnect()
    }

    private fun isJogCoolingDown(): Boolean {
        return System.currentTimeMillis() < jogCooldownUntil
    }

    private fun jogAxis(axis: String, distance: Float) {
        if (isJogCoolingDown()) return
        // Cancel any in-progress jog before sending a new one
        webSocketManager.sendJogCancel()
        val feedRate = currentFeedRate
        webSocketManager.sendJogCommand(axis, distance, feedRate)
    }

    private fun recalculateWorkPosition() {
        workspacePosition = Position(
            machinePosition.x - workCoordinateOffset.x,
            machinePosition.y - workCoordinateOffset.y,
            machinePosition.z - workCoordinateOffset.z
        )
    }

    private fun updatePositionDisplay() {
        val isMetric = unitsPreference == "metric"
        if (isMetric) {
            // Metric: show mm with 3 decimal places
            binding.xWPos.text = String.format("%.3f", workspacePosition.x)
            binding.yWPos.text = String.format("%.3f", workspacePosition.y)
            binding.zWPos.text = String.format("%.3f", workspacePosition.z)

            binding.xMPos.text = String.format("%.3f", machinePosition.x)
            binding.yMPos.text = String.format("%.3f", machinePosition.y)
            binding.zMPos.text = String.format("%.3f", machinePosition.z)
        } else {
            // Imperial: convert mm to inches (divide by 25.4), show 4 decimal places
            val mmToInch = 1.0 / 25.4
            binding.xWPos.text = String.format("%.4f", workspacePosition.x * mmToInch)
            binding.yWPos.text = String.format("%.4f", workspacePosition.y * mmToInch)
            binding.zWPos.text = String.format("%.4f", workspacePosition.z * mmToInch)

            binding.xMPos.text = String.format("%.4f", machinePosition.x * mmToInch)
            binding.yMPos.text = String.format("%.4f", machinePosition.y * mmToInch)
            binding.zMPos.text = String.format("%.4f", machinePosition.z * mmToInch)
        }
    }
    
    // Fetch settings from ncSender including unit preference
    private fun fetchServerSettings(wsUrl: String) {
        val httpAddress = wsUrl
            .removePrefix("ws://")
            .removePrefix("wss://")
        
        val url = "http://$httpAddress/api/settings"
        Log.d(TAG, "Fetching settings from: $url")
        
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to fetch settings", e)
            }
            
            override fun onResponse(call: Call, response: Response) {
                try {
                    val responseBody = response.body?.string() ?: return
                    val json = com.google.gson.JsonParser.parseString(responseBody).asJsonObject
                    
                    // Extract unit preference
                    if (json.has("unitsPreference")) {
                        val newUnitsPreference = json.get("unitsPreference").asString
                        runOnUiThread {
                            if (unitsPreference != newUnitsPreference) {
                                unitsPreference = newUnitsPreference
                                Log.d(TAG, "Units preference set to: $unitsPreference")
                                updateUnitsDisplay()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing settings", e)
                }
            }
        })
    }
    
    // Update UI elements based on unit preference
    private fun updateUnitsDisplay() {
        val isMetric = unitsPreference == "metric"
        
        // Update step size spinner
        val stepLabels = if (isMetric) {
            resources.getStringArray(R.array.step_sizes)
        } else {
            resources.getStringArray(R.array.step_sizes_imperial)
        }
        val stepAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, stepLabels)
        stepAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val savedStepPosition = binding.stepSpinner.selectedItemPosition
        binding.stepSpinner.adapter = stepAdapter
        if (savedStepPosition >= 0 && savedStepPosition < stepLabels.size) {
            binding.stepSpinner.setSelection(savedStepPosition)
        }
        
        // Update feed rate spinner  
        val feedLabels = if (isMetric) {
            resources.getStringArray(R.array.feed_rates)
        } else {
            resources.getStringArray(R.array.feed_rates_imperial)
        }
        val feedAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, feedLabels)
        feedAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val savedFeedPosition = binding.feedRateSpinner.selectedItemPosition
        binding.feedRateSpinner.adapter = feedAdapter
        if (savedFeedPosition >= 0 && savedFeedPosition < feedLabels.size) {
            binding.feedRateSpinner.setSelection(savedFeedPosition)
        }
        
        // Update position display
        updatePositionDisplay()
        
        // Show toast about unit change
        val unitName = if (isMetric) "Metric (mm)" else "Imperial (inches)"
        Toast.makeText(this, "Units: $unitName", Toast.LENGTH_SHORT).show()
    }
    
    // Get step size values based on current unit preference (always returns mm values)
    private fun getStepValues(): FloatArray {
        return if (unitsPreference == "metric") {
            floatArrayOf(0.05f, 0.1f, 1f, 10f, 100f)  // mm
        } else {
            // Imperial values converted to mm (1 inch = 25.4 mm)
            floatArrayOf(0.0254f, 0.127f, 0.254f, 2.54f, 25.4f)  // 0.001, 0.005, 0.01, 0.1, 1 inch
        }
    }
    
    private fun getStepValue(position: Int): Float {
        val values = getStepValues()
        return if (position >= 0 && position < values.size) values[position] else values[0]
    }
    
    // Get feed rate values based on current unit preference (always returns mm/min values)
    private fun getFeedRateValues(): IntArray {
        return if (unitsPreference == "metric") {
            intArrayOf(100, 500, 1000, 3000, 6000, 8000)  // mm/min
        } else {
            // Imperial values converted to mm/min (1 inch = 25.4 mm)
            // 4, 20, 40, 120, 240, 315 in/min
            intArrayOf(102, 508, 1016, 3048, 6096, 8001)  // ~4, ~20, ~40, ~120, ~240, ~315 in/min
        }
    }
    
    private fun getFeedRateValue(position: Int): Int {
        val values = getFeedRateValues()
        return if (position >= 0 && position < values.size) values[position] else values[1]
    }

    private fun playClick() {
        // Play click sound from .ogg file
        try {
            soundPool?.play(clickSoundId, 1.0f, 1.0f, 1, 0, 1.0f)
            android.util.Log.d("MainActivity", "Playing click sound")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error playing click sound", e)
        }
        
        // Vibrate
        vibrator?.let {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                it.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(15)
            }
        }
    }
    
    // --- USB Encoder Support ---
    
    private fun setupUsbEncoder() {
        usbEncoderManager = UsbEncoderManager(this).apply {
            setEncoderListener(object : UsbEncoderManager.EncoderListener {
                override fun onEncoderConnected() {
                    encoderConnected = true
                    lastEncoderPositionActedOn = null  // Reset for fresh start
                    Log.d(TAG, "USB Encoder connected")
                    Toast.makeText(this@MainActivity, "USB Encoder connected", Toast.LENGTH_SHORT).show()
                    
                    // Send button configuration to the device
                    sendButtonConfigToEncoder()
                }
                
                override fun onEncoderDisconnected() {
                    encoderConnected = false
                    lastEncoderPositionActedOn = null  // Clear stale position
                    Log.d(TAG, "USB Encoder disconnected")
                    Toast.makeText(this@MainActivity, "USB Encoder disconnected", Toast.LENGTH_SHORT).show()
                }
                
                override fun onEncoderRotation(delta: Int, position: Long) {
                    handleEncoderRotation(delta, position)
                }
                
                override fun onEncoderError(error: String) {
                    Log.e(TAG, "USB Encoder error: $error")
                }
                
                override fun onButtonPressed(pin: Int) {
                    handleButtonEvent(pin, true)
                }
                
                override fun onButtonReleased(pin: Int) {
                    handleButtonEvent(pin, false)
                }
            })
            initialize()
        }
    }
    
    private fun handleButtonEvent(pin: Int, pressed: Boolean) {
        // Get the config for this pin to check if it's an FN modifier
        val config = ButtonConfigManager.getConfigForPin(this, pin)
        
        // Handle FN modifier button state (track press AND release)
        if (config != null && config.idleFunction == ButtonFunction.FN_MODIFIER) {
            isFnHeld = pressed
            Log.d(TAG, "FN button ${if (pressed) "pressed" else "released"}: isFnHeld=$isFnHeld")
            return  // FN button doesn't execute any action itself
        }
        
        if (!pressed) return  // Only act on button press for other functions
        
        // Determine which function to execute based on FN state and job state
        val function = if (isFnHeld && config != null && config.secondaryFunction != ButtonFunction.NONE) {
            // FN is held and secondary function is defined
            config.secondaryFunction
        } else {
            // Normal function based on job state
            ButtonConfigManager.getFunctionForPin(this, pin, isJobRunning())
        }
        
        if (function == null || function == ButtonFunction.NONE || function == ButtonFunction.FN_MODIFIER) return
        
        Log.d(TAG, "Button pressed: pin=$pin, function=${function.id}, fnHeld=$isFnHeld, jobRunning=${isJobRunning()}")
        playClick()
        
        executeButtonFunction(function)
    }
    
    private fun executeButtonFunction(function: ButtonFunction) {
        when (function) {
            // Jog commands
            ButtonFunction.JOG_X_PLUS -> executeJogButton("X", 1)
            ButtonFunction.JOG_X_MINUS -> executeJogButton("X", -1)
            ButtonFunction.JOG_Y_PLUS -> executeJogButton("Y", 1)
            ButtonFunction.JOG_Y_MINUS -> executeJogButton("Y", -1)
            ButtonFunction.JOG_Z_PLUS -> executeJogButton("Z", 1)
            ButtonFunction.JOG_Z_MINUS -> executeJogButton("Z", -1)
            
            // Home commands
            ButtonFunction.HOME_ALL -> webSocketManager.sendCommand("\$H")
            ButtonFunction.HOME_X -> webSocketManager.sendCommand("\$HX")
            ButtonFunction.HOME_Y -> webSocketManager.sendCommand("\$HY")
            ButtonFunction.HOME_Z -> webSocketManager.sendCommand("\$HZ")
            
            // Zero (WCS) commands
            ButtonFunction.ZERO_ALL -> webSocketManager.sendCommand("G10 L20 P1 X0 Y0 Z0")
            ButtonFunction.ZERO_X -> webSocketManager.sendCommand("G10 L20 P1 X0")
            ButtonFunction.ZERO_Y -> webSocketManager.sendCommand("G10 L20 P1 Y0")
            ButtonFunction.ZERO_Z -> webSocketManager.sendCommand("G10 L20 P1 Z0")
            
            // Machine control
            ButtonFunction.FEED_HOLD -> webSocketManager.sendCommand("!")
            ButtonFunction.CYCLE_START -> webSocketManager.sendCommand("~")
            ButtonFunction.STOP -> webSocketManager.sendCommand("\\x18")  // Ctrl+X soft reset
            
            // Spindle/Coolant
            ButtonFunction.SPINDLE_TOGGLE -> webSocketManager.sendCommand("M5")  // Toggle would need state tracking
            ButtonFunction.COOLANT_TOGGLE -> webSocketManager.sendCommand("M9")  // Toggle would need state tracking
            
            // Probing
            ButtonFunction.PROBE_Z -> {
                // Open probe activity
                val intent = Intent(this, ProbeActivity::class.java)
                intent.putExtra("wsUrl", binding.wsUrlInput.text.toString())
                startActivity(intent)
            }
            
            // Axis selection
            ButtonFunction.SELECT_X -> selectAxis("X")
            ButtonFunction.SELECT_Y -> selectAxis("Y")
            ButtonFunction.SELECT_Z -> selectAxis("Z")
            
            // Feed Override commands (realtime)
            ButtonFunction.FEED_OVERRIDE_100 -> webSocketManager.sendCommand("\\x90")  // Feed 100%
            ButtonFunction.FEED_OVERRIDE_PLUS_10 -> webSocketManager.sendCommand("\\x91")  // Feed +10%
            ButtonFunction.FEED_OVERRIDE_MINUS_10 -> webSocketManager.sendCommand("\\x92")  // Feed -10%
            ButtonFunction.FEED_OVERRIDE_PLUS_1 -> webSocketManager.sendCommand("\\x93")  // Feed +1%
            ButtonFunction.FEED_OVERRIDE_MINUS_1 -> webSocketManager.sendCommand("\\x94")  // Feed -1%
            
            // Rapid Override commands (realtime)
            ButtonFunction.RAPID_OVERRIDE_100 -> webSocketManager.sendCommand("\\x95")  // Rapid 100%
            ButtonFunction.RAPID_OVERRIDE_50 -> webSocketManager.sendCommand("\\x96")   // Rapid 50%
            ButtonFunction.RAPID_OVERRIDE_25 -> webSocketManager.sendCommand("\\x97")   // Rapid 25%
            
            // Spindle Override commands (realtime)
            ButtonFunction.SPINDLE_OVERRIDE_100 -> webSocketManager.sendCommand("\\x99")  // Spindle 100%
            ButtonFunction.SPINDLE_OVERRIDE_PLUS_10 -> webSocketManager.sendCommand("\\x9A")  // Spindle +10%
            ButtonFunction.SPINDLE_OVERRIDE_MINUS_10 -> webSocketManager.sendCommand("\\x9B")  // Spindle -10%
            ButtonFunction.SPINDLE_OVERRIDE_PLUS_1 -> webSocketManager.sendCommand("\\x9C")  // Spindle +1%
            ButtonFunction.SPINDLE_OVERRIDE_MINUS_1 -> webSocketManager.sendCommand("\\x9D")  // Spindle -1%
            
            // Jog step size control
            ButtonFunction.JOG_STEP_CYCLE -> cycleJogStep()
            ButtonFunction.JOG_STEP_SMALL -> setJogStep(0.1f)
            ButtonFunction.JOG_STEP_MEDIUM -> setJogStep(1.0f)
            ButtonFunction.JOG_STEP_LARGE -> setJogStep(10.0f)
            
            // Diagonal jog commands
            ButtonFunction.JOG_XY_PLUS_PLUS -> executeDiagonalJog(1, 1)
            ButtonFunction.JOG_XY_PLUS_MINUS -> executeDiagonalJog(1, -1)
            ButtonFunction.JOG_XY_MINUS_PLUS -> executeDiagonalJog(-1, 1)
            ButtonFunction.JOG_XY_MINUS_MINUS -> executeDiagonalJog(-1, -1)
            
            // Machine control
            ButtonFunction.SOFT_RESET -> webSocketManager.sendCommand("\\x18")  // Ctrl+X soft reset
            ButtonFunction.UNLOCK -> webSocketManager.sendCommand("\$X")  // Unlock alarm
            
            // Tool functions
            ButtonFunction.TOOL_LENGTH_SENSOR -> webSocketManager.sendCommand("\$TLS")
            
            // Job control
            ButtonFunction.JOB_START -> startLoadedJob()
            
            // Macros (execute via ncSender M98 macro system)
            ButtonFunction.RUN_MACRO_1 -> executeMacro(1)
            ButtonFunction.RUN_MACRO_2 -> executeMacro(2)
            ButtonFunction.RUN_MACRO_3 -> executeMacro(3)
            ButtonFunction.RUN_MACRO_4 -> executeMacro(4)
            ButtonFunction.RUN_MACRO_5 -> executeMacro(5)
            ButtonFunction.RUN_MACRO_6 -> executeMacro(6)
            ButtonFunction.RUN_MACRO_7 -> executeMacro(7)
            ButtonFunction.RUN_MACRO_8 -> executeMacro(8)
            ButtonFunction.RUN_MACRO_9 -> executeMacro(9)
            
            ButtonFunction.FN_MODIFIER -> { /* Handled separately */ }
            ButtonFunction.NONE -> { /* Do nothing */ }
        }
    }
    
    private fun executeJogButton(axis: String, direction: Int) {
        if (!isConnected || jogDisabled()) return
        
        val distance = currentStep * direction
        webSocketManager.sendJogCommand(axis, distance, currentFeedRate)
    }
    
    private fun executeDiagonalJog(xDir: Int, yDir: Int) {
        if (!isConnected || jogDisabled()) return
        
        // Send two jog commands for diagonal movement
        val xDistance = currentStep * xDir
        val yDistance = currentStep * yDir
        // Use G91 relative mode with combined XY move
        webSocketManager.sendCommand("\$J=G91 X$xDistance Y$yDistance F$currentFeedRate")
    }
    
    private fun cycleJogStep() {
        // Cycle through common step sizes: 0.1 -> 1 -> 10 -> 0.1
        currentStep = when {
            currentStep < 0.5f -> 1.0f
            currentStep < 5.0f -> 10.0f
            else -> 0.1f
        }
        syncStepSpinner()
        Toast.makeText(this, "Step: $currentStep mm", Toast.LENGTH_SHORT).show()
    }
    
    private fun setJogStep(step: Float) {
        currentStep = step
        syncStepSpinner()
        Toast.makeText(this, "Step: $currentStep mm", Toast.LENGTH_SHORT).show()
    }
    
    private fun syncStepSpinner() {
        val stepIndex = getStepValues().indexOfFirst { it == currentStep }
        if (stepIndex >= 0) binding.stepSpinner.setSelection(stepIndex)
        saveUserSettings()
    }
    
    private fun startLoadedJob() {
        // Start the currently loaded job via HTTP API
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val baseUrl = webSocketManager.getHttpBaseUrl()
                val url = URL("$baseUrl/api/gcode-job")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                
                // Send empty JSON body - server will use currently loaded file
                connection.outputStream.use { os ->
                    os.write("{}".toByteArray())
                }
                
                val responseCode = connection.responseCode
                withContext(Dispatchers.Main) {
                    if (responseCode == 200) {
                        Toast.makeText(this@MainActivity, "Job started", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Failed to start job", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error starting job: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun executeMacro(macroId: Int) {
        // Execute M98 macro via HTTP API
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val baseUrl = webSocketManager.getHttpBaseUrl()
                val url = URL("$baseUrl/api/m98-macros/$macroId/execute")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                
                val responseCode = connection.responseCode
                withContext(Dispatchers.Main) {
                    if (responseCode == 200) {
                        Toast.makeText(this@MainActivity, "Macro $macroId executed", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Macro $macroId not found", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error running macro: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun handleEncoderRotation(delta: Int, position: Long) {
        // Firmware now reports actual clicks (not raw pulses), use directly
        if (delta == 0) return
        
        // Sync the on-screen dial to the encoder's absolute position
        // This keeps dial in sync even if some events are dropped when moving fast
        binding.jogDial.syncToEncoderPosition(position)
        
        // Only send jog commands if connected and axis selected
        if (!isConnected || jogDisabled() || selectedAxis.isEmpty()) {
            // Even when not jogging, track position so we don't "catch up" later
            lastEncoderPositionActedOn = position
            return
        }
        
        val now = System.currentTimeMillis()
        if (now - lastEncoderJogSentAt < ENCODER_MIN_SEND_INTERVAL_MS || isJogCoolingDown()) {
            return
        }
        
        lastEncoderJogSentAt = now
        
        // Calculate movement from position difference to catch up on any dropped messages
        val lastPos = lastEncoderPositionActedOn
        val actualDelta = if (lastPos != null) {
            (position - lastPos).toInt()
        } else {
            delta  // First message, use reported delta
        }
        lastEncoderPositionActedOn = position
        
        if (actualDelta == 0) return
        
        // Convert encoder clicks to jog distance
        val direction = if (actualDelta > 0) 1 else -1
        val absClicks = kotlin.math.abs(actualDelta)
        val distance = currentStep * absClicks * direction
        
        playClick()
        webSocketManager.sendJogCommand(selectedAxis, distance, currentFeedRate)
    }

    override fun onDestroy() {
        super.onDestroy()
        scanJob?.cancel()
        // Cancel any pending background disconnect
        backgroundDisconnectRunnable?.let { jogHandler.removeCallbacks(it) }
        backgroundDisconnectRunnable = null
        webSocketManager.disconnect()
        usbEncoderManager?.release()
        usbEncoderManager = null
        soundPool?.release()
        soundPool = null
    }

    // --- Saved URL Management ---

    private fun loadSavedUrls() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val json = prefs.getString(SAVED_URLS_KEY, null)
        if (json != null) {
            try {
                savedUrls = gson.fromJson(json, Array<String>::class.java).toMutableList()
            } catch (e: Exception) {
                savedUrls = mutableListOf()
            }
        } else {
            // Migrate old single-URL key
            prefs.getString(STORAGE_KEY, null)?.let {
                savedUrls = mutableListOf(it)
                saveSavedUrls()
            }
        }
        if (savedUrls.isNotEmpty()) {
            binding.wsUrlInput.setText(savedUrls[0])
        }
        refreshSavedUrlsDropdown()
    }

    private fun saveSavedUrls() {
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
            .putString(SAVED_URLS_KEY, gson.toJson(savedUrls))
            .apply()
    }

    private fun addSavedUrl(url: String) {
        savedUrls.remove(url)
        savedUrls.add(0, url)
        if (savedUrls.size > 10) savedUrls = savedUrls.take(10).toMutableList()
        saveSavedUrls()
        refreshSavedUrlsDropdown()
    }

    private fun removeSavedUrl(url: String) {
        savedUrls.remove(url)
        saveSavedUrls()
        refreshSavedUrlsDropdown()
    }

    private fun refreshSavedUrlsDropdown() {
        val container = binding.savedUrlsContainer
        container.removeAllViews()
        if (savedUrls.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        for ((index, url) in savedUrls.withIndex()) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(4), dp(10))
                setBackgroundColor(Color.parseColor("#2c3e50"))
            }
            val label = TextView(this).apply {
                text = url.removePrefix("ws://").removePrefix("wss://")
                setTextColor(Color.WHITE)
                textSize = 16f
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            }
            label.setOnClickListener {
                binding.wsUrlInput.setText(url)
            }
            val deleteBtn = TextView(this).apply {
                text = "\u2715"
                setTextColor(Color.parseColor("#e74c3c"))
                textSize = 18f
                gravity = Gravity.CENTER
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(40), dp(40))
            }
            deleteBtn.setOnClickListener {
                removeSavedUrl(url)
            }
            row.addView(label)
            row.addView(deleteBtn)
            container.addView(row)
            // Divider between items
            if (index < savedUrls.size - 1) {
                val divider = View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    )
                    setBackgroundColor(Color.parseColor("#34495e"))
                }
                container.addView(divider)
            }
        }
    }

    // --- User Settings Persistence ---

    private fun loadUserSettings() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        currentStep = prefs.getFloat(PREF_STEP_SIZE, 0.05f)
        currentFeedRate = prefs.getInt(PREF_FEED_RATE, 500)
        currentWorkspace = prefs.getString(PREF_WORKSPACE, "G54") ?: "G54"
    }

    private fun saveUserSettings() {
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
            .putFloat(PREF_STEP_SIZE, currentStep)
            .putInt(PREF_FEED_RATE, currentFeedRate)
            .putString(PREF_WORKSPACE, currentWorkspace)
            .apply()
    }

    private fun loadJogMode(): Boolean {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        return prefs.getBoolean(PREF_DIAL_MODE, true) // Default to dial mode
    }

    private fun saveJogMode(dialMode: Boolean) {
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
            .putBoolean(PREF_DIAL_MODE, dialMode)
            .apply()
    }

    private fun loadDialPoints(): Int {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        return prefs.getInt(PREF_DIAL_POINTS, 50) // Default to 50 points
    }

    private fun saveDialPoints(points: Int) {
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
            .putInt(PREF_DIAL_POINTS, points)
            .apply()
    }

    // --- About Dialog ---

    private fun showAboutDialog() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "Unknown"
        }
        
        AlertDialog.Builder(this, R.style.DarkAlertDialog)
            .setTitle("About ncSender Control")
            .setMessage("Version: $version\n\nA mobile pendant for CNC control via ncSender.")
            .setPositiveButton("OK", null)
            .setNeutralButton("Check for Updates") { _, _ ->
                checkForUpdates()
            }
            .setNegativeButton("GitHub") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dmquinny/ncsendercontrolandroid/"))
                startActivity(intent)
            }
            .show()
    }

    // --- Settings Dialog ---

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val dialPointsSpinner = dialogView.findViewById<Spinner>(R.id.dialPointsSpinner)
        val boardTypeSpinner = dialogView.findViewById<Spinner>(R.id.boardTypeSpinner)
        val boardPinCountLabel = dialogView.findViewById<TextView>(R.id.boardPinCountLabel)
        val numButtonsSpinner = dialogView.findViewById<Spinner>(R.id.numButtonsSpinner)
        val configureButtonsBtn = dialogView.findViewById<Button>(R.id.configureButtonsBtn)
        val firmwareInfoLabel = dialogView.findViewById<TextView>(R.id.firmwareInfoLabel)
        val flashFirmwareBtn = dialogView.findViewById<Button>(R.id.flashFirmwareBtn)

        // Set up dial points options
        val dialPointsOptions = arrayOf("50 points", "100 points")
        val dialAdapter = ArrayAdapter(this, R.layout.spinner_item_dark, dialPointsOptions)
        dialAdapter.setDropDownViewResource(R.layout.spinner_dropdown_dark)
        dialPointsSpinner.adapter = dialAdapter

        // Set current dial points selection
        val currentPoints = loadDialPoints()
        dialPointsSpinner.setSelection(if (currentPoints == 100) 1 else 0)

        // Set up board type options
        val boardOptions = BoardType.getDisplayNames().toTypedArray()
        val boardAdapter = ArrayAdapter(this, R.layout.spinner_item_dark, boardOptions)
        boardAdapter.setDropDownViewResource(R.layout.spinner_dropdown_dark)
        boardTypeSpinner.adapter = boardAdapter

        // Set current board type selection
        val currentBoardType = ButtonConfigManager.loadBoardType(this)
        boardTypeSpinner.setSelection(currentBoardType.ordinal)
        boardPinCountLabel.text = "${currentBoardType.availablePins.size} GPIO pins available"
        firmwareInfoLabel.text = "Firmware: ${getFirmwareFilename(currentBoardType)}"

        // Update pin count label and firmware info when board changes
        boardTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val boardType = BoardType.entries[position]
                boardPinCountLabel.text = "${boardType.availablePins.size} GPIO pins available"
                firmwareInfoLabel.text = "Firmware: ${getFirmwareFilename(boardType)}"
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Flash firmware button click
        flashFirmwareBtn.setOnClickListener {
            val selectedBoardType = BoardType.entries[boardTypeSpinner.selectedItemPosition]
            startFirmwareFlash(selectedBoardType)
        }

        // Set up number of buttons options (0-12)
        val buttonOptions = (0..12).map { if (it == 0) "None" else it.toString() }.toTypedArray()
        val buttonAdapter = ArrayAdapter(this, R.layout.spinner_item_dark, buttonOptions)
        buttonAdapter.setDropDownViewResource(R.layout.spinner_dropdown_dark)
        numButtonsSpinner.adapter = buttonAdapter

        // Set current button count selection
        val currentNumButtons = ButtonConfigManager.loadNumButtons(this)
        numButtonsSpinner.setSelection(currentNumButtons)

        // Update configure button visibility based on selection
        configureButtonsBtn.isEnabled = currentNumButtons > 0
        numButtonsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                configureButtonsBtn.isEnabled = position > 0
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Configure buttons button click
        configureButtonsBtn.setOnClickListener {
            val numButtons = numButtonsSpinner.selectedItemPosition
            if (numButtons > 0) {
                // Save board type first so the button config dialog uses correct pins
                val selectedBoardType = BoardType.entries[boardTypeSpinner.selectedItemPosition]
                ButtonConfigManager.saveBoardType(this, selectedBoardType)
                showButtonConfigDialog(numButtons)
            }
        }

        AlertDialog.Builder(this, R.style.DarkAlertDialog)
            .setTitle("Settings")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val selectedPoints = if (dialPointsSpinner.selectedItemPosition == 1) 100 else 50
                saveDialPoints(selectedPoints)
                binding.jogDial.setNumTicks(selectedPoints)

                // Save board type
                val selectedBoardType = BoardType.entries[boardTypeSpinner.selectedItemPosition]
                ButtonConfigManager.saveBoardType(this, selectedBoardType)

                // Save button count
                val numButtons = numButtonsSpinner.selectedItemPosition
                ButtonConfigManager.saveNumButtons(this, numButtons)

                // Send button config to encoder device
                sendButtonConfigToEncoder()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    // --- Firmware Flashing ---
    
    private fun getFirmwareFilename(boardType: BoardType): String {
        return when (boardType) {
            BoardType.RP2040_ZERO -> "encoder_rp2040zero.uf2"
            BoardType.PICO -> "encoder_pico.uf2"
            BoardType.TINY2040 -> "encoder_tiny2040.uf2"
        }
    }
    
    private fun startFirmwareFlash(boardType: BoardType) {
        pendingFirmwareBoardType = boardType
        val filename = getFirmwareFilename(boardType)
        
        // First check if RP2040 is connected in BOOTSEL mode
        val rp2040Device = usbMassStorageManager.findRp2040BootselDevice()
        
        if (rp2040Device != null) {
            // Device found - try direct USB flash
            flashFirmwareViaMassStorage(boardType)
        } else {
            // No device found - show instructions and offer to save to Downloads
            showBootselInstructions(boardType)
        }
    }
    
    private fun flashFirmwareViaMassStorage(boardType: BoardType) {
        val filename = getFirmwareFilename(boardType)
        val assetFilename = "firmware/$filename"
        
        // Show progress dialog
        val progressDialog = AlertDialog.Builder(this, R.style.DarkAlertDialog)
            .setTitle("Flashing Firmware")
            .setMessage("Writing ${boardType.displayName} firmware...\nDo not disconnect the device.")
            .setCancelable(false)
            .create()
        progressDialog.show()
        
        lifecycleScope.launch {
            try {
                val inputStream = assets.open(assetFilename)
                val result = usbMassStorageManager.flashFirmware(inputStream, filename)
                inputStream.close()
                
                progressDialog.dismiss()
                
                when (result) {
                    is UsbMassStorageManager.FlashResult.Success -> {
                        AlertDialog.Builder(this@MainActivity, R.style.DarkAlertDialog)
                            .setTitle("Firmware Flashed!")
                            .setMessage(
                                "Successfully wrote firmware to ${boardType.displayName}.\n\n" +
                                "The device will reboot automatically and be ready to use."
                            )
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    is UsbMassStorageManager.FlashResult.NoDeviceFound -> {
                        showBootselInstructions(boardType)
                    }
                    is UsbMassStorageManager.FlashResult.PermissionDenied -> {
                        AlertDialog.Builder(this@MainActivity, R.style.DarkAlertDialog)
                            .setTitle("Permission Denied")
                            .setMessage(
                                "USB permission was denied.\n\n" +
                                "Please try again and tap 'Allow' when prompted for USB access.\n\n" +
                                "If the permission dialog doesn't appear, unplug and replug the device while in BOOTSEL mode."
                            )
                            .setPositiveButton("Try Again") { _, _ -> flashFirmwareViaMassStorage(boardType) }
                            .setNeutralButton("Save to Downloads") { _, _ -> saveFirmwareToDownloads(boardType) }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    is UsbMassStorageManager.FlashResult.Error -> {
                        AlertDialog.Builder(this@MainActivity, R.style.DarkAlertDialog)
                            .setTitle("Flash Failed")
                            .setMessage(
                                "Failed to write firmware: ${result.message}\n\n" +
                                "Make sure your ${boardType.displayName} is in BOOTSEL mode:\n" +
                                "1. Unplug the USB cable\n" +
                                "2. Hold the BOOTSEL button\n" +
                                "3. While holding, plug in the USB cable\n" +
                                "4. Release BOOTSEL - device should appear as 'RPI-RP2'\n" +
                                "5. Tap 'Try Again'"
                            )
                            .setPositiveButton("Try Again") { _, _ -> flashFirmwareViaMassStorage(boardType) }
                            .setNeutralButton("Save to Downloads") { _, _ -> saveFirmwareToDownloads(boardType) }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                Log.e(TAG, "Firmware flash failed", e)
                AlertDialog.Builder(this@MainActivity, R.style.DarkAlertDialog)
                    .setTitle("Flash Failed")
                    .setMessage(
                        "Error: ${e.message}\n\n" +
                        "Make sure your ${boardType.displayName} is in BOOTSEL mode:\n" +
                        "1. Unplug the USB cable\n" +
                        "2. Hold the BOOTSEL button\n" +
                        "3. While holding, plug in the USB cable\n" +
                        "4. Release BOOTSEL - device should appear as 'RPI-RP2'\n" +
                        "5. Tap 'Try Again'"
                    )
                    .setPositiveButton("Try Again") { _, _ -> flashFirmwareViaMassStorage(boardType) }
                    .setNeutralButton("Save to Downloads") { _, _ -> saveFirmwareToDownloads(boardType) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }
    
    private fun showBootselInstructions(boardType: BoardType) {
        AlertDialog.Builder(this, R.style.DarkAlertDialog)
            .setTitle("BOOTSEL Mode Required")
            .setMessage(
                "To flash firmware, the ${boardType.displayName} must be in BOOTSEL mode:\n\n" +
                "1. UNPLUG the USB cable from your phone\n" +
                "2. Find the BOOTSEL button on your board\n" +
                "3. HOLD the BOOTSEL button down\n" +
                "4. While HOLDING, plug the USB cable into your phone\n" +
                "5. RELEASE the BOOTSEL button\n" +
                "6. Tap 'Flash Now' below\n\n" +
                "The device will appear as 'RPI-RP2' storage when ready."
            )
            .setPositiveButton("Flash Now") { _, _ -> flashFirmwareViaMassStorage(boardType) }
            .setNeutralButton("Save to Downloads") { _, _ -> saveFirmwareToDownloads(boardType) }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun saveFirmwareToDownloads(boardType: BoardType) {
        val filename = getFirmwareFilename(boardType)
        val assetFilename = "firmware/$filename"
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Read firmware from assets
                val inputStream = assets.open(assetFilename)
                val firmwareBytes = inputStream.readBytes()
                inputStream.close()
                
                // Save to Downloads folder
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                val firmwareFile = java.io.File(downloadsDir, filename)
                firmwareFile.writeBytes(firmwareBytes)
                
                withContext(Dispatchers.Main) {
                    // Show success dialog with instructions
                    AlertDialog.Builder(this@MainActivity, R.style.DarkAlertDialog)
                        .setTitle("Firmware Saved")
                        .setMessage(
                            "Firmware saved to Downloads/$filename\n\n" +
                            "To flash manually:\n" +
                            "1. Hold BOOTSEL on ${boardType.displayName}\n" +
                            "2. Connect USB cable, then release BOOTSEL\n" +
                            "3. Open a file manager app\n" +
                            "4. Copy '$filename' from Downloads to the RPI-RP2 drive\n" +
                            "5. The board will reboot automatically\n\n" +
                            "Note: If Android asks to format RPI-RP2, tap Cancel - use a file manager app instead."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save firmware", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to save firmware: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    
    private fun writeFirmwareToUri(uri: Uri) {
        val boardType = pendingFirmwareBoardType ?: return
        val assetFilename = "firmware/${getFirmwareFilename(boardType)}"
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Read firmware from assets
                val inputStream = assets.open(assetFilename)
                val firmwareBytes = inputStream.readBytes()
                inputStream.close()
                
                // Write to selected location
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(firmwareBytes)
                }
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Firmware written! The ${boardType.displayName} will reboot automatically.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write firmware", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to write firmware: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        
        pendingFirmwareBoardType = null
    }

    private fun showButtonConfigDialog(numButtons: Int) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_button_config, null)
        val container = dialogView.findViewById<WidgetLinearLayout>(R.id.buttonsContainer)

        // Load existing configs
        val existingConfigs = ButtonConfigManager.loadButtonConfigs(this).associateBy { it.index }

        // Available GPIO pins for selected board (with analog labels for Tiny2040)
        val availablePins = ButtonConfigManager.getAvailablePins(this)
        val pinDisplayNames = ButtonConfigManager.getPinDisplayNames(this)
        val pinOptions = listOf("--") + pinDisplayNames
        val allFunctions = ButtonFunction.entries.toList()

        // Track selections for each button
        data class ButtonSelection(
            val pin: Spinner,
            var idleFunction: ButtonFunction,
            var runningFunction: ButtonFunction,
            var secondaryFunction: ButtonFunction,
            val idlePicker: TextView,
            val runningPicker: TextView,
            val secondaryPicker: TextView
        )
        val buttonSelections = mutableListOf<ButtonSelection>()

        // Create config row for each button
        for (i in 0 until numButtons) {
            val itemView = layoutInflater.inflate(R.layout.item_button_config, container, false)
            
            val label = itemView.findViewById<TextView>(R.id.buttonLabel)
            val pinSpinner = itemView.findViewById<Spinner>(R.id.pinSpinner)
            val idlePicker = itemView.findViewById<TextView>(R.id.idleFunctionPicker)
            val runningPicker = itemView.findViewById<TextView>(R.id.runningFunctionPicker)
            val secondaryPicker = itemView.findViewById<TextView>(R.id.secondaryFunctionPicker)

            label.text = "Button ${i + 1}"

            // Pin spinner with analog labels
            val pinAdapter = ArrayAdapter(this, R.layout.spinner_item_dark, pinOptions)
            pinAdapter.setDropDownViewResource(R.layout.spinner_dropdown_dark)
            pinSpinner.adapter = pinAdapter

            // Initialize with existing or default values
            var currentIdleFunction = ButtonFunction.NONE
            var currentRunningFunction = ButtonFunction.NONE
            var currentSecondaryFunction = ButtonFunction.NONE
            
            existingConfigs[i]?.let { config ->
                val pinIndex = availablePins.indexOf(config.gpioPin)
                if (pinIndex >= 0) pinSpinner.setSelection(pinIndex + 1) // +1 for "--" option
                currentIdleFunction = config.idleFunction
                currentRunningFunction = config.runningFunction
                currentSecondaryFunction = config.secondaryFunction
            }
            
            idlePicker.text = currentIdleFunction.displayName
            runningPicker.text = currentRunningFunction.displayName
            secondaryPicker.text = currentSecondaryFunction.displayName

            val selection = ButtonSelection(
                pinSpinner, 
                currentIdleFunction, 
                currentRunningFunction,
                currentSecondaryFunction,
                idlePicker,
                runningPicker,
                secondaryPicker
            )
            buttonSelections.add(selection)

            // Click listener for idle function picker
            idlePicker.setOnClickListener {
                showFunctionPickerDialog("Select Idle Function", allFunctions, selection.idleFunction) { chosen ->
                    selection.idleFunction = chosen
                    idlePicker.text = chosen.displayName
                }
            }

            // Click listener for running function picker
            runningPicker.setOnClickListener {
                showFunctionPickerDialog("Select Running Function", allFunctions, selection.runningFunction) { chosen ->
                    selection.runningFunction = chosen
                    runningPicker.text = chosen.displayName
                }
            }

            // Click listener for secondary (Fn+) function picker
            secondaryPicker.setOnClickListener {
                showFunctionPickerDialog("Select Fn+ Function", allFunctions, selection.secondaryFunction) { chosen ->
                    selection.secondaryFunction = chosen
                    secondaryPicker.text = chosen.displayName
                }
            }

            container.addView(itemView)
        }

        AlertDialog.Builder(this, R.style.DarkAlertDialog)
            .setTitle("Configure Buttons")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                // Collect configs
                val configs = buttonSelections.mapIndexedNotNull { index, selection ->
                    val pinIndex = selection.pin.selectedItemPosition
                    val pin = if (pinIndex > 0) {
                        availablePins[pinIndex - 1]
                    } else {
                        availablePins.getOrElse(index) { 2 }
                    }
                    ButtonConfig(index, pin, selection.idleFunction, selection.runningFunction, selection.secondaryFunction)
                }
                ButtonConfigManager.saveButtonConfigs(this, configs)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showFunctionPickerDialog(
        title: String,
        functions: List<ButtonFunction>,
        currentSelection: ButtonFunction,
        onSelected: (ButtonFunction) -> Unit
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_function_picker, null)
        val searchInput = dialogView.findViewById<android.widget.EditText>(R.id.searchInput)
        val listView = dialogView.findViewById<android.widget.ListView>(R.id.functionList)
        
        // Create a filterable list
        val displayNames = functions.map { it.displayName }
        val adapter = ArrayAdapter(this, R.layout.spinner_dropdown_dark, displayNames.toMutableList())
        listView.adapter = adapter
        
        // Scroll to current selection
        val currentIndex = functions.indexOf(currentSelection)
        if (currentIndex >= 0) {
            listView.post { listView.setSelection(currentIndex) }
        }
        
        val dialog = AlertDialog.Builder(this, R.style.DarkAlertDialog)
            .setTitle(title)
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .create()
        
        // Filter as user types
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.lowercase() ?: ""
                adapter.clear()
                val filtered = if (query.isEmpty()) {
                    displayNames
                } else {
                    displayNames.filter { it.lowercase().contains(query) }
                }
                adapter.addAll(filtered)
                adapter.notifyDataSetChanged()
            }
        })
        
        // Select item on click
        listView.setOnItemClickListener { _, _, position, _ ->
            val selectedName = adapter.getItem(position) ?: return@setOnItemClickListener
            val selectedFunction = functions.find { it.displayName == selectedName } ?: return@setOnItemClickListener
            onSelected(selectedFunction)
            dialog.dismiss()
        }
        
        dialog.show()
    }

    private fun sendButtonConfigToEncoder() {
        val pins = ButtonConfigManager.getConfiguredPins(this)
        if (pins.isNotEmpty()) {
            usbEncoderManager?.sendButtonConfig(pins)
        } else {
            usbEncoderManager?.clearButtonConfig()
        }
    }

    // --- Network Scanning ---

    private fun getLocalSubnet(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                for (address in networkInterface.inetAddresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress ?: continue
                        val parts = ip.split(".")
                        if (parts.size == 4) {
                            return "${parts[0]}.${parts[1]}.${parts[2]}"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to get local subnet", e)
        }
        return null
    }

    private fun startNetworkScan() {
        val subnet = getLocalSubnet()
        if (subnet == null) {
            Toast.makeText(this, "Could not determine local network", Toast.LENGTH_SHORT).show()
            return
        }

        binding.scanBtn.isEnabled = false
        binding.scanBtn.text = "Scanning..."
        binding.scanProgress.visibility = View.VISIBLE
        binding.scanResultsContainer.removeAllViews()
        binding.scanResultsContainer.visibility = View.GONE

        val scanClient = OkHttpClient.Builder()
            .connectTimeout(800, TimeUnit.MILLISECONDS)
            .readTimeout(800, TimeUnit.MILLISECONDS)
            .build()

        scanJob = CoroutineScope(Dispatchers.Main).launch {
            val results = mutableListOf<String>()

            withContext(Dispatchers.IO) {
                val deferreds = (1..254).map { i ->
                    async {
                        val ip = "$subnet.$i"
                        try {
                            val request = Request.Builder()
                                .url("http://$ip:8090/api/health")
                                .build()
                            val response = scanClient.newCall(request).execute()
                            if (response.isSuccessful) {
                                val body = response.body?.string() ?: ""
                                if (body.contains("\"status\"") && body.contains("\"ok\"")) {
                                    synchronized(results) { results.add(ip) }
                                }
                            }
                            response.close()
                        } catch (_: Exception) {
                            // Expected for most IPs - timeout or connection refused
                        }
                    }
                }
                deferreds.awaitAll()
            }

            // Back on Main thread
            binding.scanBtn.isEnabled = true
            binding.scanBtn.text = "Scan Network"
            binding.scanProgress.visibility = View.GONE

            if (results.isEmpty()) {
                Toast.makeText(this@MainActivity, "No servers found on $subnet.x:8090", Toast.LENGTH_SHORT).show()
            } else {
                showScanResults(results)
            }
        }
    }

    private fun showScanResults(results: List<String>) {
        val container = binding.scanResultsContainer
        container.removeAllViews()
        container.visibility = View.VISIBLE

        val header = TextView(this).apply {
            text = "Discovered Servers"
            setTextColor(Color.parseColor("#7f8c8d"))
            textSize = 12f
            setPadding(dp(4), dp(4), 0, dp(4))
        }
        container.addView(header)

        for (ip in results) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(Color.parseColor("#2c3e50"))
            }
            val label = TextView(this).apply {
                text = "$ip:8090"
                setTextColor(Color.parseColor("#2ecc71"))
                textSize = 16f
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val useBtn = TextView(this).apply {
                text = "USE"
                setTextColor(Color.parseColor("#3498db"))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            useBtn.setOnClickListener {
                binding.wsUrlInput.setText("$ip:8090")
            }
            row.addView(label)
            row.addView(useBtn)
            container.addView(row)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

data class Position(
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f
)
