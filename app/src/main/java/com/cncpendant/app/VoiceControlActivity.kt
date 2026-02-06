package com.cncpendant.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.cncpendant.app.databinding.ActivityVoiceControlBinding
import com.cncpendant.app.voice.CommandType
import com.cncpendant.app.voice.ParserConfig
import com.cncpendant.app.voice.VoiceCommand
import com.cncpendant.app.voice.VoiceCommandExecutor
import com.cncpendant.app.voice.VoiceCommandParser
import com.cncpendant.app.voice.VoiceEvent
import com.cncpendant.app.voice.VoiceState
import com.cncpendant.app.voice.VoiceStateMachine
import com.google.gson.JsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

class VoiceControlActivity : AppCompatActivity(), ConnectionManager.ConnectionStateListener, TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityVoiceControlBinding
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var serverAddress = ""

    private var isPushToTalk = true
    private var isListening = false
    private var isContinuousMode = false
    private var allowJobStartCommands = false  // Safety toggle for job start voice commands
    
    // Confirmation mode - pending command waiting for "yes"/"confirm" or "no"/"cancel"
    private var pendingCommand: VoiceCommand? = null
    private var awaitingConfirmation = false
    private var requireConfirmation = true  // User setting to require confirmation for all commands
    
    // Unlock sequence state - waiting for unlock message after soft reset
    private var awaitingUnlockMessage = false
    
    // Wake word detection - customizable
    private var wakeWordEnabled = true
    private var wakeWord = "hey cnc"
    private var awaitingCommandAfterWake = false
    private var wakeWordTimeout: Runnable? = null
    
    // Command history for repeat/undo
    private var lastExecutedCommand: VoiceCommand? = null
    private var lastJogMoves: MutableMap<String, Float>? = null  // For undo
    
    // Chained command queue
    private var commandQueue: MutableList<VoiceCommand> = mutableListOf()
    private var isExecutingQueue = false

    // Current settings
    private var currentFeed = 1000
    private var currentStep = 1f
    private var currentWorkspace = "G54"
    private var defaultProbeType = "3d-probe"

    // HTTP client for probe API
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Position data
    private var machineX = 0f
    private var machineY = 0f
    private var machineZ = 0f
    private var workX = 0f
    private var workY = 0f
    private var workZ = 0f
    private var machineStatus = "Unknown"

    // Home location from ncSender settings (affects movement direction interpretation)
    // Values: "back-left", "back-right", "front-left", "front-right"
    private var homeLocation = "back-left"
    
    // Units preference from ncSender settings (metric or imperial)
    private var unitsPreference = "metric"
    private val handler = Handler(Looper.getMainLooper())
    private var pulseAnimator: ObjectAnimator? = null
    
    // Refactored components
    private lateinit var parserConfig: ParserConfig
    private lateinit var commandParser: VoiceCommandParser
    private lateinit var commandExecutor: VoiceCommandExecutor
    private val stateMachine = VoiceStateMachine()

    companion object {
        private const val TAG = "VoiceControl"
        private const val PERMISSION_REQUEST_CODE = 100

        // Preference keys
        private const val PREF_LISTENING_MODE = "voice_listening_mode"
        private const val PREF_DEFAULT_PROBE = "voice_default_probe"
        private const val PREF_ALLOW_JOB_START = "voice_allow_job_start"
        private const val PREF_WAKE_WORD_ENABLED = "voice_wake_word_enabled"
        private const val PREF_WAKE_WORD = "voice_wake_word"
        private const val PREF_REQUIRE_CONFIRMATION = "voice_require_confirmation"
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get server address from intent or from ConnectionManager
        serverAddress = intent.getStringExtra("server_address") ?: ""
        if (serverAddress.isEmpty()) {
            // Try to get from ConnectionManager
            serverAddress = ConnectionManager.getServerAddress()
        }
        if (serverAddress.isEmpty()) {
            Toast.makeText(this, "No server address provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        loadSettings()
        initializeRefactoredComponents()
        setupUI()
        checkPermissionAndSetup()
        
        // Initialize Text-to-Speech
        textToSpeech = TextToSpeech(this, this)
        
        // Register as connection listener and check current state
        ConnectionManager.addListener(this)
        if (ConnectionManager.isConnected()) {
            hideDisconnectedOverlay()
            // Check if in alarm state
            if (ConnectionManager.isInAlarmState()) {
                showAlarmOverlay(ConnectionManager.getCurrentActiveState())
            }
            fetchServerSettings()
        } else {
            showDisconnectedOverlay()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        ConnectionManager.removeListener(this)
    }
    
    // TextToSpeech.OnInitListener implementation
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS language not supported")
            } else {
                ttsReady = true
                Log.d(TAG, "TTS initialized successfully")
                
                // Set up utterance listener to resume listening after TTS completes
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        // Resume listening after TTS finishes speaking
                        if (isContinuousMode && !isListening) {
                            handler.postDelayed({ startListening() }, 300)
                        }
                    }
                    override fun onError(utteranceId: String?) {}
                })
            }
        } else {
            Log.e(TAG, "TTS initialization failed")
        }
    }
    
    // Speak text using TTS
    private fun speak(text: String, pauseListening: Boolean = true) {
        if (!ttsReady) {
            Log.w(TAG, "TTS not ready, cannot speak: $text")
            return
        }
        
        // Pause listening while speaking to avoid feedback
        if (pauseListening && isListening) {
            stopListening()
        }
        
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice_command_${System.currentTimeMillis()}")
    }
    
    // ConnectionManager.ConnectionStateListener implementation
    override fun onConnectionStateChanged(connected: Boolean) {
        runOnUiThread {
            if (connected) {
                Log.d(TAG, "Connection restored")
                hideDisconnectedOverlay()
                // Check if in alarm state
                if (ConnectionManager.isInAlarmState()) {
                    showAlarmOverlay(ConnectionManager.getCurrentActiveState())
                }
                fetchServerSettings()
            } else {
                Log.d(TAG, "Connection lost")
                hideAlarmOverlay()  // Hide alarm when disconnected
                showDisconnectedOverlay()
            }
        }
    }
    
    override fun onMachineStateUpdate(mPos: WebSocketManager.Position?, wco: WebSocketManager.Position?, wcs: String?) {
        runOnUiThread {
            mPos?.let {
                machineX = it.x
                machineY = it.y
                machineZ = it.z
            }
            wco?.let { offset ->
                mPos?.let {
                    workX = it.x - offset.x
                    workY = it.y - offset.y
                    workZ = it.z - offset.z
                }
            }
            wcs?.let {
                currentWorkspace = it
            }
            updateDisplays()
            
            // Sync state to executor for queries
            if (::commandExecutor.isInitialized) {
                commandExecutor.updateMachineState(
                    machineX = machineX,
                    machineY = machineY,
                    machineZ = machineZ,
                    workX = workX,
                    workY = workY,
                    workZ = workZ,
                    feed = currentFeed,
                    step = currentStep,
                    units = unitsPreference
                )
            }
        }
    }
    
    override fun onActiveStateChanged(state: String) {
        runOnUiThread {
            machineStatus = state
            updateDisplays()
            
            // Sync status to executor
            if (::commandExecutor.isInitialized) {
                commandExecutor.updateMachineState(status = state)
            }
            
            // Show/hide alarm overlay based on state
            if (state.startsWith("Alarm")) {
                showAlarmOverlay(state)
            } else {
                hideAlarmOverlay()
            }
        }
    }
    
    override fun onError(error: String) {
        runOnUiThread {
            Log.e(TAG, "Connection error: $error")
        }
    }
    
    override fun onGrblMessage(message: String) {
        // Check for unlock message after soft reset
        if (awaitingUnlockMessage && message.contains("\$X") && message.contains("unlock")) {
            runOnUiThread {
                awaitingUnlockMessage = false
                Log.d(TAG, "Received unlock message, sending \$X")
                ConnectionManager.sendCommand("\$X")
                Toast.makeText(this, "Unlocking...", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    override fun onAlarmCodeChanged(alarmCode: Int?, alarmDescription: String?) {
        runOnUiThread {
            if (alarmCode != null) {
                Log.d(TAG, "Alarm code received from ncSender: $alarmCode, description: $alarmDescription")
                updateAlarmMessage(alarmCode, alarmDescription)
            }
        }
    }
    
    private fun updateAlarmMessage(alarmCode: Int, serverDescription: String?) {
        if (binding.alarmOverlay.visibility == View.VISIBLE) {
            // Use server-provided description if available, otherwise fall back to local lookup
            val description = serverDescription ?: getAlarmDescription(alarmCode)
            binding.alarmMessage.text = "Alarm $alarmCode: $description"
        }
    }
    
    private fun startUnlockSequence() {
        Toast.makeText(this, "Resetting and unlocking...", Toast.LENGTH_SHORT).show()
        awaitingUnlockMessage = true
        // Send soft reset first
        ConnectionManager.sendSoftReset()
        // Set a timeout in case we don't receive the unlock message
        handler.postDelayed({
            if (awaitingUnlockMessage) {
                awaitingUnlockMessage = false
                Log.d(TAG, "Unlock message timeout, sending \$X anyway")
                ConnectionManager.sendCommand("\$X")
            }
        }, 3000) // 3 second timeout
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("voice_prefs", MODE_PRIVATE)
        isPushToTalk = prefs.getBoolean(PREF_LISTENING_MODE, true)
        defaultProbeType = prefs.getString(PREF_DEFAULT_PROBE, "3d-probe") ?: "3d-probe"
        allowJobStartCommands = prefs.getBoolean(PREF_ALLOW_JOB_START, false)
        requireConfirmation = prefs.getBoolean(PREF_REQUIRE_CONFIRMATION, true)
        wakeWordEnabled = prefs.getBoolean(PREF_WAKE_WORD_ENABLED, true)
        wakeWord = prefs.getString(PREF_WAKE_WORD, "hey cnc")?.lowercase() ?: "hey cnc"

        // Load feed and step from main prefs
        val mainPrefs = getSharedPreferences("prefs", MODE_PRIVATE)
        currentFeed = mainPrefs.getInt("cnc_pendant_feed_rate", 1000)
        currentStep = mainPrefs.getFloat("cnc_pendant_step_size", 1f)
        currentWorkspace = mainPrefs.getString("cnc_pendant_workspace", "G54") ?: "G54"
    }
    
    /**
     * Initialize the refactored voice control components (parser, executor, state machine)
     */
    private fun initializeRefactoredComponents() {
        // Create parser config with current settings
        parserConfig = ParserConfig(
            currentFeed = currentFeed,
            currentStep = currentStep,
            defaultProbeType = defaultProbeType,
            homeLocation = homeLocation,
            unitsPreference = unitsPreference,
            allowJobStartCommands = allowJobStartCommands
        )
        
        // Create parser with settings change listener
        commandParser = VoiceCommandParser(parserConfig)
        commandParser.settingsChangeListener = object : VoiceCommandParser.SettingsChangeListener {
            override fun onFeedChanged(feed: Int) {
                currentFeed = feed
                updateDisplays()
            }
            override fun onStepChanged(step: Float) {
                currentStep = step
                updateDisplays()
            }
            override fun onProbeTypeChanged(probeType: String) {
                defaultProbeType = probeType
                updateDisplays()
                saveSettings()
            }
        }
        
        // Create executor with callbacks
        commandExecutor = VoiceCommandExecutor(serverAddress)
        commandExecutor.executionListener = object : VoiceCommandExecutor.ExecutionListener {
            override fun onCommandExecuted(command: VoiceCommand) {
                Log.d(TAG, "Command executed: ${command.description}")
            }
            override fun onQueryResult(query: String, response: String) {
                runOnUiThread {
                    speak(response)
                    binding.parsedCommand.text = response
                }
            }
            override fun onProbeStarted(axis: String) {
                runOnUiThread {
                    Toast.makeText(this@VoiceControlActivity, "Starting probe $axis...", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onProbeCompleted(axis: String, success: Boolean) {
                runOnUiThread {
                    Toast.makeText(this@VoiceControlActivity, "Probe $axis started", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onProbeError(axis: String, error: String) {
                runOnUiThread {
                    Toast.makeText(this@VoiceControlActivity, "Probe failed: $error", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onExecutionError(command: VoiceCommand, error: String) {
                runOnUiThread {
                    Toast.makeText(this@VoiceControlActivity, "Error: $error", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        // Setup state machine listener for logging/debugging
        stateMachine.addStateChangeListener { oldState, newState ->
            Log.d(TAG, "Voice state: ${oldState.description()} -> ${newState.description()}")
        }
    }

    private fun saveSettings() {
        getSharedPreferences("voice_prefs", MODE_PRIVATE).edit()
            .putBoolean(PREF_LISTENING_MODE, isPushToTalk)
            .putString(PREF_DEFAULT_PROBE, defaultProbeType)
            .putBoolean(PREF_ALLOW_JOB_START, allowJobStartCommands)
            .putBoolean(PREF_REQUIRE_CONFIRMATION, requireConfirmation)
            .putBoolean(PREF_WAKE_WORD_ENABLED, wakeWordEnabled)
            .putString(PREF_WAKE_WORD, wakeWord)
            .apply()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUI() {
        // Back button
        binding.backBtn.setOnClickListener { finish() }

        // Settings button
        binding.settingsBtn.setOnClickListener { showSettingsDialog() }

        // Disconnected overlay buttons
        binding.reconnectBtn.setOnClickListener {
            Toast.makeText(this, "Reconnecting...", Toast.LENGTH_SHORT).show()
            reconnectWebSocket()
        }
        binding.connectionMenuBtn.setOnClickListener {
            // Go back to main activity (connection menu)
            finish()
        }

        // Alarm overlay buttons
        binding.unlockBtn.setOnClickListener {
            // Send soft reset first, then wait for unlock message, then send $X
            startUnlockSequence()
        }
        binding.alarmBackBtn.setOnClickListener {
            // Go back to main activity
            finish()
        }

        // Mode toggle
        updateModeUI()
        binding.modePushToTalk.setOnClickListener {
            isPushToTalk = true
            isContinuousMode = false
            stopListening()
            updateModeUI()
            saveSettings()
        }
        binding.modeContinuous.setOnClickListener {
            isPushToTalk = false
            isContinuousMode = true
            updateModeUI()
            saveSettings()
            startListening()
        }

        // Microphone button - push to talk
        binding.micButton.setOnTouchListener { _, event ->
            if (isPushToTalk) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startListening()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        stopListening()
                        true
                    }
                    else -> false
                }
            } else {
                // In continuous mode, tap to toggle
                if (event.action == MotionEvent.ACTION_UP) {
                    if (isListening) {
                        stopListening()
                    } else {
                        startListening()
                    }
                }
                true
            }
        }

        // Stop button - send soft reset (0x18) for emergency stop
        binding.stopBtn.setOnClickListener {
            ConnectionManager.sendSoftReset()
            addCommandToHistory("STOP", "Soft Reset (0x18)", "#e74c3c")
        }

        // Help button
        binding.helpBtn.setOnClickListener { showHelpDialog() }

        // Update displays
        updateDisplays()
    }

    private fun updateModeUI() {
        if (isPushToTalk) {
            binding.modePushToTalk.setBackgroundResource(R.drawable.voice_mode_selected)
            binding.modePushToTalk.setTextColor(Color.WHITE)
            binding.modeContinuous.setBackgroundResource(R.drawable.voice_mode_unselected)
            binding.modeContinuous.setTextColor(Color.parseColor("#7f8c8d"))
            binding.listeningStatus.text = "Hold microphone to speak"
        } else {
            binding.modeContinuous.setBackgroundResource(R.drawable.voice_mode_selected)
            binding.modeContinuous.setTextColor(Color.WHITE)
            binding.modePushToTalk.setBackgroundResource(R.drawable.voice_mode_unselected)
            binding.modePushToTalk.setTextColor(Color.parseColor("#7f8c8d"))
            binding.listeningStatus.text = if (isListening) "Listening..." else "Tap microphone to start"
        }
    }

    private fun updateDisplays() {
        // Format positions based on unit preference
        val isMetric = unitsPreference == "metric"
        val unitLabel = if (isMetric) "mm" else "in"
        val conversionFactor = if (isMetric) 1f else 1f / 25.4f  // Convert mm to inches if imperial
        
        binding.machineX.text = "X: %.3f".format(machineX * conversionFactor)
        binding.machineY.text = "Y: %.3f".format(machineY * conversionFactor)
        binding.machineZ.text = "Z: %.3f".format(machineZ * conversionFactor)
        binding.workX.text = "X: %.3f".format(workX * conversionFactor)
        binding.workY.text = "Y: %.3f".format(workY * conversionFactor)
        binding.workZ.text = "Z: %.3f".format(workZ * conversionFactor)
        binding.workspaceLabel.text = currentWorkspace
        binding.machineStatus.text = machineStatus
        
        // Format feed display based on units
        val feedDisplay = if (isMetric) {
            "F: $currentFeed mm/min"
        } else {
            "F: %.1f in/min".format(currentFeed / 25.4f)
        }
        binding.feedDisplay.text = feedDisplay
        
        // Format step display based on units
        val stepDisplay = if (isMetric) {
            "Step: ${currentStep}mm"
        } else {
            "Step: %.4f in".format(currentStep / 25.4f)
        }
        binding.stepDisplay.text = stepDisplay
        
        binding.probeDisplay.text = "Probe: ${getProbeDisplayName(defaultProbeType)}"

        // Status color
        val statusColor = when (machineStatus.lowercase()) {
            "idle" -> "#2ecc71"
            "run" -> "#3498db"
            "hold" -> "#f39c12"
            "alarm" -> "#e74c3c"
            else -> "#7f8c8d"
        }
        binding.machineStatus.setTextColor(Color.parseColor(statusColor))
    }

    private fun showDisconnectedOverlay() {
        binding.disconnectedOverlay.visibility = View.VISIBLE
        binding.mainContent.alpha = 0.3f
        // Stop listening if active
        stopListening()
        isContinuousMode = false
        isPushToTalk = true
        updateModeUI()
    }

    private fun hideDisconnectedOverlay() {
        binding.disconnectedOverlay.visibility = View.GONE
        binding.mainContent.alpha = 1f
    }

    private fun showAlarmOverlay(alarmState: String) {
        binding.alarmOverlay.visibility = View.VISIBLE
        binding.mainContent.alpha = 0.3f
        // Stop listening if active
        stopListening()
        isContinuousMode = false
        isPushToTalk = true
        updateModeUI()
        
        // Show default message - will be updated when ALARM:X message is received
        binding.alarmMessage.text = "Waiting for alarm details..."
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

    private fun hideAlarmOverlay() {
        binding.alarmOverlay.visibility = View.GONE
        // Only restore content alpha if disconnected overlay is also hidden
        if (binding.disconnectedOverlay.visibility != View.VISIBLE) {
            binding.mainContent.alpha = 1f
        }
    }

    private fun getProbeDisplayName(probeType: String): String {
        return when (probeType) {
            "3d-probe" -> "3D Probe"
            "standard-block" -> "Standard Block"
            "autozero-touch" -> "AutoZero Touch"
            else -> probeType
        }
    }

    private fun checkPermissionAndSetup() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_CODE
            )
        } else {
            setupSpeechRecognizer()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupSpeechRecognizer()
            } else {
                Toast.makeText(this, "Microphone permission required for voice control", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available on this device", Toast.LENGTH_LONG).show()
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                runOnUiThread {
                    isListening = true
                    binding.micButton.isSelected = true
                    binding.listeningStatus.text = "Listening..."
                    startPulseAnimation()
                }
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {
                // Could use this for audio level visualization
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                runOnUiThread {
                    binding.listeningStatus.text = "Processing..."
                }
            }

            override fun onError(error: Int) {
                runOnUiThread {
                    isListening = false
                    binding.micButton.isSelected = false
                    stopPulseAnimation()

                    val errorMessage = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                        SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        else -> "Recognition error"
                    }
                    binding.listeningStatus.text = errorMessage

                    // Restart if in continuous mode
                    if (isContinuousMode && !isPushToTalk) {
                        handler.postDelayed({ startListening() }, 1000)
                    } else {
                        updateModeUI()
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                runOnUiThread {
                    isListening = false
                    binding.micButton.isSelected = false
                    stopPulseAnimation()

                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val spokenText = matches[0]
                        // Strip end trigger words before processing
                        val cleanedText = stripEndTrigger(spokenText)
                        binding.recognizedText.text = "\"$cleanedText\""
                        processCommand(cleanedText)
                    }

                    // Restart if in continuous mode
                    if (isContinuousMode && !isPushToTalk) {
                        handler.postDelayed({ startListening() }, 500)
                    } else {
                        updateModeUI()
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val partialText = matches[0].lowercase()
                    runOnUiThread {
                        binding.recognizedText.text = "\"${matches[0]}\"..."
                        
                        // Check for end trigger words - immediately finalize the command
                        val endTriggers = listOf("execute", "send it", "do it", "run it", "that's all", "that's it", "done", "go", "over")
                        if (endTriggers.any { partialText.endsWith(it) || partialText.endsWith("$it.") }) {
                            // Stop listening and process immediately
                            speechRecognizer?.stopListening()
                        }
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        
        // Auto-start listening if continuous mode is already selected
        if (!isPushToTalk) {
            isContinuousMode = true
            handler.postDelayed({ startListening() }, 500)
        }
    }

    private fun startListening() {
        if (speechRecognizer == null) {
            setupSpeechRecognizer()
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Increase silence timeouts to allow for longer pauses in speech
            // This helps with compound commands like "move left 250 and forward 250 at feed rate 6000"
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition", e)
            Toast.makeText(this, "Error starting voice recognition", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopListening() {
        isListening = false
        binding.micButton.isSelected = false
        stopPulseAnimation()
        speechRecognizer?.stopListening()
        updateModeUI()
    }

    private fun startPulseAnimation() {
        binding.micPulse.alpha = 0.3f
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.micPulse,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.3f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.3f),
            PropertyValuesHolder.ofFloat("alpha", 0.3f, 0f)
        ).apply {
            duration = 1000
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        binding.micPulse.alpha = 0f
        binding.micPulse.scaleX = 1f
        binding.micPulse.scaleY = 1f
    }

    private fun reconnectWebSocket() {
        // Use shared ConnectionManager for reconnection
        ConnectionManager.reconnect(this)
    }

    // Fetch settings from ncSender server (including home location)
    private fun fetchServerSettings() {
        val httpAddress = serverAddress
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
                    
                    // Extract home location
                    if (json.has("homeLocation")) {
                        val newHomeLocation = json.get("homeLocation").asString
                        runOnUiThread {
                            homeLocation = newHomeLocation
                            Log.d(TAG, "Home location set to: $homeLocation")
                        }
                    }
                    
                    // Extract units preference
                    if (json.has("unitsPreference")) {
                        val newUnits = json.get("unitsPreference").asString
                        runOnUiThread {
                            unitsPreference = newUnits
                            Log.d(TAG, "Units preference set to: $unitsPreference")
                            updateDisplays()
                            Toast.makeText(this@VoiceControlActivity, 
                                "Units: ${if (unitsPreference == "metric") "mm" else "inches"}", 
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing settings", e)
                }
            }
        })
    }

    // Get movement direction for forward/back based on home location
    // Forward = toward operator (front of machine)
    // Back = away from operator (back of machine)
    private fun getYDirectionForward(): Float {
        // If home is at back (Y+), forward is Y-
        // If home is at front (Y-), forward is Y+
        return if (homeLocation.startsWith("back")) -1f else 1f
    }
    
    private fun getYDirectionBack(): Float {
        return -getYDirectionForward()
    }
    
    // Get movement direction for left/right based on home location  
    // Left = toward left side of machine
    // Right = toward right side of machine
    private fun getXDirectionLeft(): Float {
        // If home is at left (X-), left is toward home = X-
        // If home is at right (X+), left is away from home = X-
        // Actually, left is always X- regardless of home position
        return -1f
    }
    
    private fun getXDirectionRight(): Float {
        return 1f
    }

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

    // Command Processing
    private fun processCommand(spokenText: String) {
        // Convert number words to digits before parsing
        val normalizedText = convertWordsToNumbers(spokenText.lowercase())
        Log.d(TAG, "Normalized text: $normalizedText (original: $spokenText)")
        
        // Check for custom wake word - triggers listening for next command
        // Generate variations of the wake word for fuzzy matching
        val wakeWordVariations = mutableListOf(wakeWord)
        // Add spaced-out version (e.g., "hey cnc" -> "hey c n c")
        if (wakeWord.contains(" ")) {
            val parts = wakeWord.split(" ")
            if (parts.size == 2 && parts[1].length <= 4) {
                wakeWordVariations.add("${parts[0]} ${parts[1].toCharArray().joinToString(" ")}")
            }
        }
        
        val detectedWakeWord = wakeWordEnabled && wakeWordVariations.any { normalizedText.contains(it) }
        
        if (detectedWakeWord) {
            // Check if there's a command after the wake word
            var afterWake = normalizedText
            wakeWordVariations.forEach { afterWake = afterWake.replace(it, "") }
            afterWake = afterWake.trim()
            
            if (afterWake.isNotEmpty()) {
                // Command included with wake word, process it
                Log.d(TAG, "Wake word with command: $afterWake")
                processCommand(afterWake)
                return
            } else {
                // Just wake word, wait for command
                awaitingCommandAfterWake = true
                val displayWakeWord = wakeWord.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                binding.recognizedText.text = "\"$displayWakeWord\" - Listening..."
                binding.parsedCommand.text = "Waiting for command..."
                binding.parsedCommand.setTextColor(Color.parseColor("#3498db"))
                binding.commandPreview.visibility = View.VISIBLE
                speak("Yes?")
                
                // Set timeout to cancel wake word mode
                wakeWordTimeout?.let { handler.removeCallbacks(it) }
                wakeWordTimeout = Runnable {
                    if (awaitingCommandAfterWake) {
                        awaitingCommandAfterWake = false
                        binding.commandPreview.visibility = View.GONE
                    }
                }
                handler.postDelayed(wakeWordTimeout!!, 10000) // 10 second timeout
                return
            }
        }
        
        // If we heard wake word, clear the flag (we're now processing the follow-up command)
        if (awaitingCommandAfterWake) {
            awaitingCommandAfterWake = false
            wakeWordTimeout?.let { handler.removeCallbacks(it) }
        }
        
        // Check if we're awaiting confirmation for a pending command
        if (awaitingConfirmation && pendingCommand != null) {
            handleConfirmationResponse(normalizedText)
            return
        }
        
        // Check for chained commands (split by "then" or "and then")
        val chainedParts = normalizedText.split(Regex("\\s+then\\s+|\\s+and then\\s+"))
        if (chainedParts.size > 1) {
            Log.d(TAG, "Chained command detected: ${chainedParts.size} parts")
            processChainedCommands(chainedParts)
            return
        }
        
        val command = parseCommand(normalizedText)
        
        // DEBUG: Show detailed parse info
        val debugInfo = commandParser.getLastParseDebugInfo()
        Log.d(TAG, "DEBUG: $debugInfo")
        Log.d(TAG, "DEBUG parseCommand result: type=${command?.type}, desc=${command?.description}, gcode=${command?.gcode}")
        Toast.makeText(this, "NLU: $debugInfo", Toast.LENGTH_LONG).show()

        if (command != null) {
            binding.commandPreview.visibility = View.VISIBLE
            binding.parsedCommand.text = command.description

            // Color based on command type
            val color = when (command.type) {
                CommandType.BLOCKED -> {
                    binding.parsedCommand.setTextColor(Color.parseColor("#e74c3c"))
                    "#e74c3c" // Red for blocked
                }
                CommandType.STOP -> {
                    // Stop commands bypass confirmation for safety (execute immediately)
                    binding.parsedCommand.setTextColor(Color.parseColor("#f39c12"))
                    executeCommand(command)
                    speak("Stopping")
                    addCommandToHistory(spokenText, command.description, "#f39c12")
                    handler.postDelayed({
                        binding.commandPreview.visibility = View.GONE
                        binding.parsedCommand.setTextColor(Color.parseColor("#2ecc71"))
                    }, 3000)
                    return
                }
                CommandType.QUERY -> {
                    // Query commands don't need confirmation
                    binding.parsedCommand.setTextColor(Color.parseColor("#3498db"))
                    executeCommand(command)
                    addCommandToHistory(spokenText, command.description, "#3498db")
                    handler.postDelayed({
                        binding.commandPreview.visibility = View.GONE
                        binding.parsedCommand.setTextColor(Color.parseColor("#2ecc71"))
                    }, 3000)
                    return
                }
                CommandType.SETTING -> {
                    // Setting commands don't need confirmation (already applied)
                    binding.parsedCommand.setTextColor(Color.parseColor("#2ecc71"))
                    executeCommand(command)
                    speak(command.description)
                    addCommandToHistory(spokenText, command.description, "#2ecc71")
                    handler.postDelayed({
                        binding.commandPreview.visibility = View.GONE
                        binding.parsedCommand.setTextColor(Color.parseColor("#2ecc71"))
                    }, 3000)
                    return
                }
                else -> {
                    binding.parsedCommand.setTextColor(Color.parseColor("#2ecc71"))
                    "#2ecc71" // Green for success
                }
            }

            // If confirmation required, ask for confirmation
            if (requireConfirmation) {
                pendingCommand = command
                awaitingConfirmation = true
                binding.parsedCommand.text = "${command.description}\nSay 'yes' to confirm or 'no' to cancel"
                binding.parsedCommand.setTextColor(Color.parseColor("#f39c12"))
                speak("${command.description}. Say yes to confirm.")
                addCommandToHistory(spokenText, "Awaiting confirmation: ${command.description}", "#f39c12")
            } else {
                // Execute immediately without confirmation
                executeCommand(command)
                speak(command.description)
                addCommandToHistory(spokenText, command.description, color)
            
                // Reset color after delay
                handler.postDelayed({
                    binding.commandPreview.visibility = View.GONE
                    binding.parsedCommand.setTextColor(Color.parseColor("#2ecc71"))
                }, 3000)
            }
        } else {
            binding.commandPreview.visibility = View.VISIBLE
            binding.parsedCommand.text = "Unknown command"
            binding.parsedCommand.setTextColor(Color.parseColor("#e74c3c"))
            speak("Command not recognized")
            addCommandToHistory(spokenText, "Not recognized", "#e74c3c")

            handler.postDelayed({
                binding.commandPreview.visibility = View.GONE
                binding.parsedCommand.setTextColor(Color.parseColor("#2ecc71"))
            }, 2000)
        }
    }
    
    // Process chained commands (e.g., "move left 50, then forward 30, then probe z")
    private fun processChainedCommands(parts: List<String>) {
        commandQueue.clear()
        val parsedCommands = mutableListOf<VoiceCommand>()
        
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isNotEmpty()) {
                val command = parseCommand(trimmed)
                if (command != null && command.type != CommandType.BLOCKED) {
                    parsedCommands.add(command)
                } else {
                    // One part failed to parse
                    binding.commandPreview.visibility = View.VISIBLE
                    binding.parsedCommand.text = "Failed to parse: \"$trimmed\""
                    binding.parsedCommand.setTextColor(Color.parseColor("#e74c3c"))
                    speak("Could not understand: $trimmed")
                    return
                }
            }
        }
        
        if (parsedCommands.isEmpty()) return
        
        // Show what will be executed
        val descriptions = parsedCommands.mapIndexed { i, cmd -> "${i+1}. ${cmd.description}" }.joinToString("\n")
        binding.commandPreview.visibility = View.VISIBLE
        binding.parsedCommand.text = "Queued ${parsedCommands.size} commands:\n$descriptions"
        binding.parsedCommand.setTextColor(Color.parseColor("#3498db"))
        
        if (requireConfirmation) {
            commandQueue.addAll(parsedCommands)
            awaitingConfirmation = true
            pendingCommand = VoiceCommand(CommandType.JOG, "Execute ${parsedCommands.size} commands", "CHAIN")
            speak("${parsedCommands.size} commands queued. Say yes to execute.")
            addCommandToHistory("Chained", descriptions, "#3498db")
        } else {
            // Execute immediately
            speak("Executing ${parsedCommands.size} commands")
            addCommandToHistory("Chained", descriptions, "#2ecc71")
            executeChainedCommands(parsedCommands)
        }
    }
    
    private fun executeChainedCommands(commands: List<VoiceCommand>) {
        if (commands.isEmpty()) return
        
        isExecutingQueue = true
        var delay = 0L
        
        for ((index, command) in commands.withIndex()) {
            handler.postDelayed({
                binding.parsedCommand.text = "Executing ${index+1}/${commands.size}: ${command.description}"
                executeCommand(command)
                
                if (index == commands.size - 1) {
                    // Last command
                    handler.postDelayed({
                        isExecutingQueue = false
                        binding.commandPreview.visibility = View.GONE
                    }, 1500)
                }
            }, delay)
            
            // Add delay between commands (adjust based on command type)
            delay += when (command.type) {
                CommandType.JOG, CommandType.MOVE -> 1500L  // Wait for jog to complete
                CommandType.PROBE -> 30000L  // Probe takes longer
                else -> 500L
            }
        }
    }
    
    // Handle yes/no confirmation response
    private fun handleConfirmationResponse(normalizedText: String) {
        val confirmPatterns = listOf("yes", "yeah", "yep", "confirm", "affirmative", "do it", "execute", "ok", "okay", "go", "proceed")
        val cancelPatterns = listOf("no", "nope", "cancel", "abort", "stop", "never mind", "nevermind", "don't", "negative")
        
        when {
            confirmPatterns.any { normalizedText.contains(it) } -> {
                // Confirmed - execute the pending command
                val command = pendingCommand!!
                awaitingConfirmation = false
                pendingCommand = null
                
                // Check if this is a chained command confirmation
                if (commandQueue.isNotEmpty()) {
                    val commands = commandQueue.toList()
                    commandQueue.clear()
                    binding.parsedCommand.text = "Executing ${commands.size} commands..."
                    binding.parsedCommand.setTextColor(Color.parseColor("#2ecc71"))
                    speak("Executing ${commands.size} commands")
                    addCommandToHistory("Confirmed chain", "${commands.size} commands", "#2ecc71")
                    executeChainedCommands(commands)
                    return
                }
                
                binding.parsedCommand.text = "Executing: ${command.description}"
                binding.parsedCommand.setTextColor(Color.parseColor("#2ecc71"))
                executeCommand(command)
                speak("Executing ${command.description}")
                addCommandToHistory("Confirmed", command.description, "#2ecc71")
                
                handler.postDelayed({
                    binding.commandPreview.visibility = View.GONE
                    binding.parsedCommand.setTextColor(Color.parseColor("#2ecc71"))
                }, 3000)
            }
            cancelPatterns.any { normalizedText.contains(it) } -> {
                // Cancelled
                val command = pendingCommand!!
                awaitingConfirmation = false
                pendingCommand = null
                commandQueue.clear()  // Clear any chained commands
                
                binding.parsedCommand.text = "Cancelled: ${command.description}"
                binding.parsedCommand.setTextColor(Color.parseColor("#e74c3c"))
                speak("Command cancelled")
                addCommandToHistory("Cancelled", command.description, "#e74c3c")
                
                handler.postDelayed({
                    binding.commandPreview.visibility = View.GONE
                    binding.parsedCommand.setTextColor(Color.parseColor("#2ecc71"))
                }, 2000)
            }
            else -> {
                // Didn't understand - prompt again
                binding.parsedCommand.text = "Say 'yes' to confirm or 'no' to cancel:\n${pendingCommand?.description}"
                speak("Please say yes or no")
            }
        }
    }
    
    /**
     * Strip end trigger words from spoken text.
     * Users can say "execute", "send it", "done", etc. to finalize their command.
     */
    private fun stripEndTrigger(text: String): String {
        val endTriggers = listOf("execute", "send it", "do it", "run it", "that's all", "that's it", "done", "go", "over")
        var result = text.lowercase()
        for (trigger in endTriggers) {
            if (result.endsWith(trigger)) {
                result = result.removeSuffix(trigger).trim()
                break
            }
            // Also handle with trailing period
            if (result.endsWith("$trigger.")) {
                result = result.removeSuffix("$trigger.").trim()
                break
            }
        }
        return result
    }

    // Common speech recognition misinterpretations and synonyms
    private fun normalizeText(text: String): String {
        var result = text
        
        // Remove commas from numbers (e.g., "6,000" -> "6000")
        // This handles speech recognition that formats numbers with commas
        result = result.replace(Regex("(\\d),(?=\\d{3})"), "$1")
        
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
        
        return result
    }

    private fun parseCommand(text: String): VoiceCommand? {
        // Apply additional normalization for common misrecognitions
        val normalizedText = normalizeText(text)
        Log.d(TAG, "Normalized for parsing: $normalizedText")
        
        // SAFETY: Block any job/run/execute file commands (unless explicitly enabled)
        val isJobStartCommand = normalizedText.contains("run job") || normalizedText.contains("start job") ||
            normalizedText.contains("run file") || normalizedText.contains("start file") ||
            normalizedText.contains("run program") || normalizedText.contains("start program") ||
            normalizedText.contains("execute") || normalizedText.contains("run gcode") ||
            normalizedText.contains("start gcode") || normalizedText.contains("load and run") ||
            normalizedText.contains("begin job") || normalizedText.contains("begin program")
        
        if (isJobStartCommand) {
            if (!allowJobStartCommands) {
                Toast.makeText(this, "⚠️ Job start blocked - enable in settings", Toast.LENGTH_LONG).show()
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
            if (lastExecutedCommand != null) {
                return VoiceCommand(CommandType.REPEAT, "Repeat: ${lastExecutedCommand!!.description}", "REPEAT")
            } else {
                return VoiceCommand(CommandType.BLOCKED, "No command to repeat", "BLOCKED")
            }
        }
        
        // Undo last jog movement
        val undoPatterns = listOf("undo", "go back", "reverse that", "undo that", "take it back")
        if (undoPatterns.any { normalizedText.contains(it) } && !normalizedText.contains("undo ")) {
            if (lastJogMoves != null && lastJogMoves!!.isNotEmpty()) {
                // Reverse the last jog
                val reverseMoves = lastJogMoves!!.mapValues { -it.value }
                val jogParts = reverseMoves.entries.map { "${it.key}${it.value}" }.joinToString(" ")
                val gcode = "\$J=G91 $jogParts F$currentFeed"
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
            return VoiceCommand(CommandType.PROBE, "Probe $axis ($defaultProbeType)", "PROBE:$axis:$defaultProbeType")
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

        // Feed rate commands - only standalone "set feed" commands, NOT inline feed in jog commands
        val movementWords = listOf("jog", "move", "go", "nudge", "shift", "travel", "left", "right", "forward", "back", "up", "down")
        val hasMovementWord = movementWords.any { normalizedText.contains(it) }
        
        val feedSetPatterns = listOf("set feed", "set the feed", "change feed", "feed to", "set speed")
        if (feedSetPatterns.any { normalizedText.contains(it) } || 
            (!hasMovementWord && listOf("feed", "feed rate", "feedrate", "speed").any { normalizedText.contains(it) })) {
            // Extract number that follows feed keyword, not just any number
            val feedMatch = Regex("(?:feed|feed rate|feedrate|speed)\\s*(?:to|rate)?\\s*(\\d+)").find(normalizedText)
            if (feedMatch != null) {
                val feedInput = feedMatch.groupValues[1].toIntOrNull()
                if (feedInput != null && feedInput in 1..50000) {
                    // If imperial, convert in/min to mm/min
                    val isMetric = unitsPreference == "metric"
                    val feedMm = if (isMetric) feedInput else (feedInput * 25.4).toInt()
                    currentFeed = feedMm
                    updateDisplays()
                    val displayUnit = if (isMetric) "mm/min" else "in/min"
                    return VoiceCommand(CommandType.SETTING, "Set feed to $feedInput $displayUnit", "FEED:$feedMm")
                }
            }
        }

        // Step size commands - only standalone "set step" commands, NOT inline step in jog commands  
        val stepSetPatterns = listOf("set step", "set the step", "change step", "step size to", "set increment")
        if (stepSetPatterns.any { normalizedText.contains(it) } ||
            (!hasMovementWord && listOf("step size", "increment", "jog distance").any { normalizedText.contains(it) })) {
            // Extract number that follows step keyword
            val stepMatch = Regex("(?:step|step size|increment)\\s*(?:to|size)?\\s*(\\d+\\.?\\d*)").find(normalizedText)
            if (stepMatch != null) {
                val stepInput = stepMatch.groupValues[1].toFloatOrNull()
                if (stepInput != null && stepInput in 0.001f..100f) {
                    // If imperial, convert inches to mm
                    val isMetric = unitsPreference == "metric"
                    val stepMm = if (isMetric) stepInput else stepInput * 25.4f
                    currentStep = stepMm
                    updateDisplays()
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
                defaultProbeType = newProbeType
                updateDisplays()
                saveSettings()
                return VoiceCommand(CommandType.SETTING, "Probe set to ${getProbeDisplayName(newProbeType)}", "PROBE_TYPE:$newProbeType")
            }
        }

        // Check/query probe type - "what probe" or "check probe" or "current probe"
        val checkProbePatterns = listOf("what probe", "which probe", "check probe", "current probe", "show probe", "probe status")
        if (checkProbePatterns.any { normalizedText.contains(it) }) {
            val probeName = getProbeDisplayName(defaultProbeType)
            Toast.makeText(this, "Current probe: $probeName", Toast.LENGTH_SHORT).show()
            return VoiceCommand(CommandType.SETTING, "Current probe: $probeName", "PROBE_CHECK")
        }

        // Jog/Move commands - supports compound moves like "move left 50 and forward 50"
        val jogPatterns = listOf("jog", "move", "go", "nudge", "shift", "travel")
        if (jogPatterns.any { normalizedText.contains(it) }) {
            val isMachine = normalizedText.contains("machine")

            // Parse axis and value for absolute moves
            val xMatch = Regex("x ?(-?\\d+\\.?\\d*)").find(normalizedText)
            val yMatch = Regex("y ?(-?\\d+\\.?\\d*)").find(normalizedText)
            val zMatch = Regex("z ?(-?\\d+\\.?\\d*)").find(normalizedText)

            // Direction mapping for back-left homed machine:
            // Forward (toward operator) = Y minus, Back (away) = Y plus
            // Left = X minus, Right = X plus
            // Up = Z plus, Down = Z minus
            
            // Parse compound directional moves (e.g., "move left 50 and forward 50")
            val moves = mutableMapOf<String, Float>()
            val isMetric = unitsPreference == "metric"
            
            // First, extract feed and step values so we can exclude them from distance parsing
            val feedOverrideMatch = Regex("(?:at )?feed\\s*(?:rate)?\\s*(\\d+)").find(normalizedText)
            val stepSettingMatch = Regex("step\\s*(\\d+\\.?\\d*)").find(normalizedText)
            val feedValue = feedOverrideMatch?.groupValues?.get(1)
            val stepValue = stepSettingMatch?.groupValues?.get(1)
            
            // Pattern to find direction + optional distance pairs
            // Split on "and" to handle compound commands
            val parts = normalizedText.split(" and ", ",")
            
            for (part in parts) {
                // Find the distance in this part (or use current step)
                // Exclude numbers that belong to feed or step parameters
                val distMatch = Regex("(\\d+\\.?\\d*)\\s*(?:mm|millimeter|in|inch)?").findAll(part)
                    .filter { match ->
                        val value = match.groupValues[1]
                        val matchStart = match.range.first
                        val textBefore = part.substring(0, matchStart).lowercase()
                        // Exclude if this number follows "feed" or "step"
                        val isFeedNumber = textBefore.endsWith("feed ") || textBefore.endsWith("feed rate ")
                        val isStepNumber = textBefore.endsWith("step ")
                        !isFeedNumber && !isStepNumber && value != feedValue && value != stepValue
                    }
                    .firstOrNull()
                var distance = distMatch?.groupValues?.get(1)?.toFloatOrNull() ?: currentStep
                
                // Track if user specified an explicit distance for this part
                val hasExplicitDistance = distMatch != null && distance != currentStep
                
                // If user specified a number without explicit unit, convert based on preference
                // (currentStep is already in mm, but user-spoken numbers should be in their preferred unit)
                if (hasExplicitDistance) {
                    // User said a specific distance - convert if imperial
                    if (!isMetric) {
                        distance *= 25.4f  // Convert inches to mm
                    }
                }
                
                // Check for each direction and accumulate moves
                // Use direction helpers that account for home location
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
                
                // Check for inline feed override: "at feed 6000", "feed 6000", "at feed rate 6000"
                var jogFeed = if (feedOverrideMatch != null) {
                    val feedInput = feedOverrideMatch.groupValues[1].toIntOrNull() ?: currentFeed
                    // Convert if imperial
                    if (isMetric) feedInput else (feedInput * 25.4).toInt()
                } else {
                    currentFeed
                }
                
                // Apply speed modifier
                jogFeed = (jogFeed * speedModifier).toInt().coerceIn(10, 50000)
                
                // Check for inline step setting: "step 10" - this SETS the step size for future commands
                // It does NOT override the distance if user already specified one (e.g., "move right 500")
                var stepSetDesc = ""
                if (stepSettingMatch != null) {
                    val stepInput = stepSettingMatch.groupValues[1].toFloatOrNull()
                    if (stepInput != null) {
                        // Convert if imperial
                        val stepMm = if (isMetric) stepInput else stepInput * 25.4f
                        // Update the step size setting for future commands
                        currentStep = stepMm
                        updateDisplays()
                        val displayUnit = if (isMetric) "mm" else "in"
                        stepSetDesc = ", step→${stepInput}$displayUnit"
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
                return VoiceCommand(CommandType.JOG, "Jog $desc at F$jogFeed$speedDesc$stepSetDesc", gcode, moves.toMap())
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
        val queryPatterns = listOf(
            "what" to "position", "where" to "am", "what's" to "position", "whats" to "position",
            "current" to "position", "tell" to "position", "read" to "position",
            "what" to "status", "machine" to "status", "what's" to "status",
            "what" to "loaded", "what" to "file", "which" to "file",
            "what" to "feed", "current" to "feed", "what's" to "feed",
            "what" to "step", "current" to "step", "what's" to "step"
        )
        
        // Relative position queries - "how far from zero", "distance to origin"
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

    private fun executeCommand(command: VoiceCommand) {
        // Store command for repeat (except repeat/undo themselves)
        if (command.type != CommandType.REPEAT && command.type != CommandType.UNDO && 
            command.type != CommandType.BLOCKED && command.type != CommandType.QUERY) {
            lastExecutedCommand = command
        }
        
        // Store jog moves for undo
        if (command.type == CommandType.JOG && command.jogMoves != null) {
            lastJogMoves = command.jogMoves.toMutableMap()
        }
        
        when (command.type) {
            CommandType.STOP -> {
                ConnectionManager.sendCommand(command.gcode)
            }
            CommandType.RESUME -> {
                ConnectionManager.sendCycleStart()
            }
            CommandType.RESET -> {
                ConnectionManager.sendSoftReset()
            }
            CommandType.HOME, CommandType.UNLOCK, CommandType.WORKSPACE, CommandType.ZERO -> {
                ConnectionManager.sendCommand(command.gcode)
            }
            CommandType.JOG, CommandType.MOVE -> {
                ConnectionManager.sendCommand(command.gcode)
            }
            CommandType.UNDO -> {
                // Undo is a reverse jog - send the command and clear undo history
                ConnectionManager.sendCommand(command.gcode)
                lastJogMoves = null  // Can't undo an undo
            }
            CommandType.REPEAT -> {
                // Re-execute the last command
                lastExecutedCommand?.let { 
                    executeCommand(it)
                }
            }
            CommandType.TOOL_CHANGE -> {
                // Send tool change command
                ConnectionManager.sendCommand(command.gcode)
            }
            CommandType.PROBE -> {
                // Parse probe command and execute via HTTP API
                val parts = command.gcode.split(":")
                if (parts.size >= 3) {
                    val axis = parts[1]
                    val probeType = parts[2]
                    executeProbe(axis, probeType)
                }
            }
            CommandType.SETTING -> {
                // Settings are already applied in parseCommand
            }
            CommandType.BLOCKED -> {
                // Safety block - do nothing, already showed toast
            }
            CommandType.QUERY -> {
                // Handle query commands - speak the result
                executeQuery(command.gcode)
            }
            CommandType.SPINDLE, CommandType.COOLANT -> {
                // Send spindle/coolant G-code
                ConnectionManager.sendCommand(command.gcode)
            }
        }

        handler.postDelayed({
            binding.commandPreview.visibility = View.GONE
        }, 3000)
    }
    
    // Execute query command and speak the result
    private fun executeQuery(queryType: String) {
        val isMetric = unitsPreference == "metric"
        val unitLabel = if (isMetric) "millimeters" else "inches"
        val conversionFactor = if (isMetric) 1f else 1f / 25.4f
        
        when {
            queryType == "QUERY:POSITION" -> {
                val wx = workX * conversionFactor
                val wy = workY * conversionFactor
                val wz = workZ * conversionFactor
                val response = "Work position is X %.2f, Y %.2f, Z %.2f %s".format(wx, wy, wz, unitLabel)
                speak(response)
                binding.parsedCommand.text = response
            }
            queryType == "QUERY:STATUS" -> {
                val response = "Machine status is $machineStatus"
                speak(response)
                binding.parsedCommand.text = response
            }
            queryType == "QUERY:FEED" -> {
                val feedDisplay = if (isMetric) {
                    "$currentFeed millimeters per minute"
                } else {
                    "%.1f inches per minute".format(currentFeed / 25.4f)
                }
                val response = "Feed rate is $feedDisplay"
                speak(response)
                binding.parsedCommand.text = response
            }
            queryType == "QUERY:STEP" -> {
                val stepDisplay = if (isMetric) {
                    "%.2f millimeters".format(currentStep)
                } else {
                    "%.4f inches".format(currentStep / 25.4f)
                }
                val response = "Step size is $stepDisplay"
                speak(response)
                binding.parsedCommand.text = response
            }
            queryType == "QUERY:DISTANCE_WORK" -> {
                // Distance from work zero (current work position)
                val wx = kotlin.math.abs(workX) * conversionFactor
                val wy = kotlin.math.abs(workY) * conversionFactor
                val wz = kotlin.math.abs(workZ) * conversionFactor
                val totalDist = kotlin.math.sqrt(workX*workX + workY*workY + workZ*workZ) * conversionFactor
                val response = "Distance from work zero: X %.2f, Y %.2f, Z %.2f. Total %.2f %s".format(wx, wy, wz, totalDist, unitLabel)
                speak(response)
                binding.parsedCommand.text = response
            }
            queryType == "QUERY:DISTANCE_MACHINE" -> {
                // Distance from machine home
                val mx = kotlin.math.abs(machineX) * conversionFactor
                val my = kotlin.math.abs(machineY) * conversionFactor
                val mz = kotlin.math.abs(machineZ) * conversionFactor
                val totalDist = kotlin.math.sqrt(machineX*machineX + machineY*machineY + machineZ*machineZ) * conversionFactor
                val response = "Distance from machine home: X %.2f, Y %.2f, Z %.2f. Total %.2f %s".format(mx, my, mz, totalDist, unitLabel)
                speak(response)
                binding.parsedCommand.text = response
            }
            queryType.startsWith("QUERY:TRAVEL:") -> {
                // Travel remaining - need machine limits from ncSender
                // For now, report current machine position as an approximation
                // (In a real implementation, you'd fetch machine limits and calculate remaining travel)
                val axis = queryType.substringAfter("QUERY:TRAVEL:")
                val response = when (axis) {
                    "X" -> "X axis at %.2f %s from home".format(kotlin.math.abs(machineX) * conversionFactor, unitLabel)
                    "Y" -> "Y axis at %.2f %s from home".format(kotlin.math.abs(machineY) * conversionFactor, unitLabel)
                    "Z" -> "Z axis at %.2f %s from home".format(kotlin.math.abs(machineZ) * conversionFactor, unitLabel)
                    else -> "Machine at X %.2f, Y %.2f, Z %.2f %s from home".format(
                        kotlin.math.abs(machineX) * conversionFactor,
                        kotlin.math.abs(machineY) * conversionFactor,
                        kotlin.math.abs(machineZ) * conversionFactor,
                        unitLabel
                    )
                }
                speak(response)
                binding.parsedCommand.text = response
            }
        }
    }

    private fun executeProbe(axis: String, probeType: String) {
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
        // The HTTP API runs on the same port as WebSocket, just use http:// instead
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

        Toast.makeText(this, "Starting probe $axis...", Toast.LENGTH_SHORT).show()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Probe start failed", e)
                runOnUiThread {
                    Toast.makeText(this@VoiceControlActivity, "Probe failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                Log.d(TAG, "Probe response: $responseBody")
                runOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@VoiceControlActivity, "Probe $axis started", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@VoiceControlActivity, "Probe error: ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun addCommandToHistory(spoken: String, result: String, color: String) {
        val historyItem = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.parseColor("#1a1a2e"))
        }

        val spokenView = TextView(this).apply {
            text = "\"$spoken\""
            setTextColor(Color.parseColor("#7f8c8d"))
            textSize = 12f
        }

        val resultView = TextView(this).apply {
            text = result
            setTextColor(Color.parseColor(color))
            textSize = 14f
        }

        historyItem.addView(spokenView)
        historyItem.addView(resultView)

        binding.commandHistory.addView(historyItem, 0)

        // Limit history to 10 items
        while (binding.commandHistory.childCount > 10) {
            binding.commandHistory.removeViewAt(binding.commandHistory.childCount - 1)
        }
    }

    private fun showSettingsDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        // Probe Type Section
        val probeLabel = TextView(this).apply {
            text = "Default Probe Type"
            setTextColor(Color.parseColor("#7f8c8d"))
            textSize = 12f
        }
        dialogView.addView(probeLabel)

        val probeTypes = arrayOf("3D Probe", "Standard Block", "AutoZero Touch")
        val probeValues = arrayOf("3d-probe", "standard-block", "autozero-touch")
        val currentProbeIndex = probeValues.indexOf(defaultProbeType).coerceAtLeast(0)

        val probeSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@VoiceControlActivity, 
                android.R.layout.simple_spinner_dropdown_item, probeTypes)
            setSelection(currentProbeIndex)
        }
        dialogView.addView(probeSpinner)

        // Spacer
        dialogView.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 32
            )
        })

        // Job Start Toggle Section
        val jobStartLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val jobStartLabel = TextView(this).apply {
            text = "Allow 'Start Job' voice commands"
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        jobStartLayout.addView(jobStartLabel)

        val jobStartSwitch = android.widget.Switch(this).apply {
            isChecked = allowJobStartCommands
        }
        jobStartLayout.addView(jobStartSwitch)

        dialogView.addView(jobStartLayout)

        // Warning text for job start
        val warningText = TextView(this).apply {
            text = "⚠️ Enabling this allows voice commands like\n'start job' or 'run program' to begin machining."
            setTextColor(Color.parseColor("#e74c3c"))
            textSize = 12f
            setPadding(0, 8, 0, 16)
        }
        dialogView.addView(warningText)

        // Confirmation Toggle Section
        val confirmLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val confirmLabel = TextView(this).apply {
            text = "Require voice confirmation"
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        confirmLayout.addView(confirmLabel)

        val confirmSwitch = android.widget.Switch(this).apply {
            isChecked = requireConfirmation
        }
        confirmLayout.addView(confirmSwitch)

        dialogView.addView(confirmLayout)

        // Info text for confirmation
        val confirmInfo = TextView(this).apply {
            text = "When enabled, say 'yes' or 'confirm' to execute\ncommands, or 'no'/'cancel' to abort."
            setTextColor(Color.parseColor("#7f8c8d"))
            textSize = 12f
            setPadding(0, 8, 0, 16)
        }
        dialogView.addView(confirmInfo)
        
        // Wake Word Section
        val wakeWordLabel = TextView(this).apply {
            text = "Wake Word"
            setTextColor(Color.parseColor("#7f8c8d"))
            textSize = 12f
        }
        dialogView.addView(wakeWordLabel)
        
        val wakeWordInput = android.widget.EditText(this).apply {
            setText(wakeWord.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } })
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#7f8c8d"))
            hint = "e.g., Hey CNC, OK Machine, Computer"
            setBackgroundColor(Color.parseColor("#1a1a2e"))
            setPadding(16, 12, 16, 12)
        }
        dialogView.addView(wakeWordInput)
        
        val wakeWordInfo = TextView(this).apply {
            text = "Say this phrase to activate voice control.\nKeep it short (2-3 words) for best recognition."
            setTextColor(Color.parseColor("#7f8c8d"))
            textSize = 12f
            setPadding(0, 8, 0, 0)
        }
        dialogView.addView(wakeWordInfo)

        AlertDialog.Builder(this)
            .setTitle("Voice Control Settings")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                defaultProbeType = probeValues[probeSpinner.selectedItemPosition]
                allowJobStartCommands = jobStartSwitch.isChecked
                requireConfirmation = confirmSwitch.isChecked
                
                // Save wake word (normalize to lowercase)
                val newWakeWord = wakeWordInput.text.toString().trim().lowercase()
                if (newWakeWord.isNotEmpty()) {
                    wakeWord = newWakeWord
                }
                
                saveSettings()
                
                val status = if (allowJobStartCommands) "enabled" else "disabled"
                Toast.makeText(this, "Settings saved. Job start commands $status.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showHelpDialog() {
        // Build dynamic help text based on current home location
        val forwardDir = if (homeLocation.startsWith("back")) "Y-" else "Y+"
        val backDir = if (homeLocation.startsWith("back")) "Y+" else "Y-"
        val confirmStatus = if (requireConfirmation) "ON - say 'yes' to confirm" else "OFF"
        val wakeStatus = if (wakeWordEnabled) "ON" else "OFF"
        val displayWakeWord = wakeWord.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        
        val helpText = """
            Voice Commands:
            
            Wake Word: "$displayWakeWord" ($wakeStatus)
            Say "$displayWakeWord" followed by any command.

            Movement:
            • "Move left 50" / "Move right 10"
            • "Move forward slowly" / "Move back fast"
            • "Move up carefully" / "Creep right"
            • "Move left 50 and forward 50" (compound)
            • "Move left 50 at feed 6000" (override)
            • "Move left, then forward 30, then probe Z"
            
            Speed Modifiers:
            • "slowly/careful" = 10% feed
            • "creep/crawl" = 5% feed
            • "fast/rapid" = 300% feed
            
            Home: $homeLocation (Forward=$forwardDir, Back=$backDir)

            Queries:
            • "What's my position?"
            • "How far from zero?" / "Distance to origin"
            • "How much travel left on Z?"
            • "What's the status?"

            Repeat/Undo:
            • "Repeat" / "Again" - repeat last command
            • "Undo" - reverse last jog movement

            Tool Change:
            • "Tool change" / "Load tool 3"
            • "M6 T2"

            Settings:
            • "Set feed to 3000"
            • "Set step to 10"
            • "Workspace G55" / "G54"

            Homing:
            • "Home" / "Home all"
            • "Home X" / "Home Y" / "Home Z"

            Probing:
            • "Probe Z" / "Touch off"
            • "Probe XY" / "Probe all"
            • "Set probe to 3D" / "Use standard block"

            Spindle:
            • "Spindle on" / "Spindle on 12000 RPM"
            • "Spindle off"
            • "Spindle clockwise" / "Spindle counterclockwise"

            Coolant:
            • "Coolant on" / "Flood on"
            • "Mist on"
            • "Coolant off"

            Control:
            • "Stop" / "Hold" / "Pause" (immediate)
            • "Resume" / "Continue"
            • "Reset" / "Unlock"

            Zero:
            • "Zero X" / "Zero all" / "Set origin"

            Confirmation: $confirmStatus
            ⚠️ Job start commands blocked by default.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Voice Commands Help")
            .setMessage(helpText)
            .setPositiveButton("OK", null)
            .show()
    }
    
    // CommandType and VoiceCommand are now imported from com.cncpendant.app.voice package
}
