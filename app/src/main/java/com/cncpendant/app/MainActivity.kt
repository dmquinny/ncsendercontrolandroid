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
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private lateinit var webSocketManager: WebSocketManager
    private var vibrator: Vibrator? = null
    private var soundPool: SoundPool? = null
    private var clickSoundId: Int = 0

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

    override fun onDestroy() {
        super.onDestroy()
        scanJob?.cancel()
        webSocketManager.disconnect()
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
        
        AlertDialog.Builder(this)
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

        // Set up dial points options
        val dialPointsOptions = arrayOf("50 points", "100 points")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, dialPointsOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        dialPointsSpinner.adapter = adapter

        // Set current selection
        val currentPoints = loadDialPoints()
        dialPointsSpinner.setSelection(if (currentPoints == 100) 1 else 0)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val selectedPoints = if (dialPointsSpinner.selectedItemPosition == 1) 100 else 50
                saveDialPoints(selectedPoints)
                binding.jogDial.setNumTicks(selectedPoints)
            }
            .setNegativeButton("Cancel", null)
            .show()
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
