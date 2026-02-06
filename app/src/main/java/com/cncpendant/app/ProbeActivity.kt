package com.cncpendant.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cncpendant.app.databinding.ActivityProbeBinding
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class ProbeActivity : AppCompatActivity(), ConnectionManager.ConnectionStateListener {

    private lateinit var binding: ActivityProbeBinding
    private val httpClient = OkHttpClient()
    private var serverAddress = ""
    private var isProbing = false
    private var wsManager: WebSocketManager? = null
    private var webViewReady = false
    private var currentActiveState = ""
    private var awaitingUnlockMessage = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // Current selections
    private var currentProbeType = "3d-probe"
    private var currentProbingAxis = "Z"
    private var selectedCorner: String? = null
    private var selectedSide: String? = null
    private var isLoadingSettings = false

    // Probe type display names and values
    private val probeTypes = listOf("3D Probe", "Standard Block", "AutoZero Touch")
    private val probeTypeValues = listOf("3d-probe", "standard-block", "autozero-touch")

    // Probing axis options per probe type
    private val threeDProbeAxes = listOf("Z", "XYZ", "XY", "X", "Y", "Center - Inner", "Center - Outer")
    private val standardBlockAxes = listOf("Z", "XYZ", "XY", "X", "Y")
    private val autoZeroTouchAxes = listOf("Z", "XYZ", "XY", "X", "Y")

    // Bit diameter options for AutoZero Touch
    private val bitDiameterOptions = listOf("Auto", "Tip", "3.175", "5", "6.35")

    // Jog controls
    private val jogStepValues = floatArrayOf(0.1f, 1f, 10f)
    private val jogFeedValues = intArrayOf(500, 1000, 3000)
    private var jogStep = 1f
    private var jogFeed = 1000

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProbeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serverAddress = intent.getStringExtra("server_address") ?: ""
        if (serverAddress.isEmpty()) {
            Toast.makeText(this, "No server address provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupToolbar()
        setupOverlayButtons()
        setupWebView()
        setupProbeTypeSpinner()
        setupProbingAxisSpinner()
        setupStartButton()
        setupBitDiameterSpinner()
        setupJogControls()
        connectWebSocket()

        // Load saved settings (after spinners are set up)
        loadProbeSettings()

        // Initial UI state
        updateSelectionText()
        updateFieldVisibility()
        
        // Register as connection listener and check current state
        ConnectionManager.addListener(this)
        if (ConnectionManager.isConnected()) {
            hideDisconnectedOverlay()
            if (ConnectionManager.isInAlarmState()) {
                showAlarmOverlay(ConnectionManager.getCurrentActiveState())
            }
        } else {
            showDisconnectedOverlay()
        }
    }

    override fun onPause() {
        super.onPause()
        saveProbeSettings()
    }

    override fun onDestroy() {
        super.onDestroy()
        ConnectionManager.removeListener(this)
        wsManager?.disconnect()
        binding.probeWebView.destroy()
    }
    
    // ConnectionManager.ConnectionStateListener implementation
    override fun onConnectionStateChanged(connected: Boolean) {
        runOnUiThread {
            if (connected) {
                Log.d(TAG, "Connection restored")
                hideDisconnectedOverlay()
                if (ConnectionManager.isInAlarmState()) {
                    showAlarmOverlay(ConnectionManager.getCurrentActiveState())
                }
            } else {
                Log.d(TAG, "Connection lost")
                hideAlarmOverlay()
                showDisconnectedOverlay()
            }
        }
    }
    
    override fun onMachineStateUpdate(mPos: WebSocketManager.Position?, wco: WebSocketManager.Position?, wcs: String?) {
        // Position updates not needed for probe activity overlay handling
    }
    
    override fun onActiveStateChanged(state: String) {
        runOnUiThread {
            currentActiveState = state
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
    
    private fun setupOverlayButtons() {
        // Disconnected overlay buttons
        binding.reconnectBtn.setOnClickListener {
            Toast.makeText(this, "Reconnecting...", Toast.LENGTH_SHORT).show()
            ConnectionManager.reconnect(this)
        }
        binding.connectionMenuBtn.setOnClickListener {
            finish()
        }
        
        // Alarm overlay buttons
        binding.unlockBtn.setOnClickListener {
            // Send soft reset first, then wait for unlock message, then send $X
            startUnlockSequence()
        }
        binding.alarmBackBtn.setOnClickListener {
            finish()
        }
    }
    
    private fun showDisconnectedOverlay() {
        binding.disconnectedOverlay.visibility = View.VISIBLE
    }
    
    private fun hideDisconnectedOverlay() {
        binding.disconnectedOverlay.visibility = View.GONE
    }
    
    private fun showAlarmOverlay(alarmState: String) {
        binding.alarmOverlay.visibility = View.VISIBLE
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
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.probeWebView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            setBackgroundColor(android.graphics.Color.TRANSPARENT)

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    consoleMessage?.let {
                        Log.d(TAG, "WebView JS [${it.messageLevel()}]: ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                    }
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    // Serve all assets via fake HTTPS origin so fetch() API works
                    val prefix = "https://probe.local/"
                    if (url.startsWith(prefix)) {
                        val assetPath = "probe/" + url.removePrefix(prefix)
                        Log.d(TAG, "Intercepting asset request: $assetPath")
                        try {
                            val inputStream = assets.open(assetPath)
                            val mimeType = when {
                                assetPath.endsWith(".mtl") -> "text/plain"
                                assetPath.endsWith(".txt") -> "text/plain"
                                assetPath.endsWith(".obj") -> "text/plain"
                                assetPath.endsWith(".html") -> "text/html"
                                assetPath.endsWith(".js") -> "application/javascript"
                                else -> "application/octet-stream"
                            }
                            val headers = mapOf(
                                "Access-Control-Allow-Origin" to "*",
                                "Cache-Control" to "no-cache"
                            )
                            return WebResourceResponse(mimeType, "UTF-8", 200, "OK", headers, inputStream)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to load asset: $assetPath", e)
                        }
                    }
                    return null
                }
            }

            addJavascriptInterface(ProbeJSInterface(), "Android")
            // Load via fake HTTPS origin so all fetch() calls work
            loadUrl("https://probe.local/probe-visualizer.html")
        }
    }

    private fun syncWebViewState() {
        if (!webViewReady) return
        val corner = selectedCorner?.let { "'$it'" } ?: "null"
        val side = selectedSide?.let { "'$it'" } ?: "null"
        binding.probeWebView.evaluateJavascript(
            "setProbeState('$currentProbeType', '$currentProbingAxis', $corner, $side)",
            null
        )
    }

    inner class ProbeJSInterface {
        @JavascriptInterface
        fun onVisualizerReady() {
            runOnUiThread {
                webViewReady = true
                syncWebViewState()
            }
        }

        @JavascriptInterface
        fun onCornerSelected(corner: String) {
            runOnUiThread {
                selectedCorner = corner
                selectedSide = null
                updateSelectionText()
                updateFieldVisibility()
                updateStartButtonState()
            }
        }

        @JavascriptInterface
        fun onSideSelected(side: String) {
            runOnUiThread {
                selectedSide = side
                selectedCorner = null
                updateSelectionText()
                updateFieldVisibility()
                updateStartButtonState()
            }
        }
    }

    private fun connectWebSocket() {
        val host = serverAddress.split(":").firstOrNull() ?: return
        val port = serverAddress.split(":").getOrNull(1) ?: "8000"
        val wsUrl = "ws://$host:$port/ws"

        wsManager = WebSocketManager(this)
        wsManager?.connect(wsUrl, object : WebSocketManager.ConnectionListener {
            override fun onConnected() {
                Log.d(TAG, "Probe status WS connected")
            }
            override fun onDisconnected() {
                Log.d(TAG, "Probe status WS disconnected")
            }
            override fun onError(error: String) {
                Log.e(TAG, "Probe status WS error: $error")
            }
            override fun onMachineStateUpdate(mPos: WebSocketManager.Position?, wco: WebSocketManager.Position?, wcs: String?) {}
            override fun onActiveStateChanged(state: String) {}
            override fun onPinStateChanged(pn: String) {
                runOnUiThread { updateProbePinStatus(pn) }
            }
            override fun onHomingStateChanged(homed: Boolean, homingCycle: Int) {}
            override fun onSenderStatusChanged(status: String) {}
            override fun onSenderConnectedChanged(connected: Boolean) {}
            override fun onOverridesChanged(feedOverride: Int, spindleOverride: Int, feedRate: Float, spindleSpeed: Float, requestedSpindleSpeed: Float) {}
            override fun onJobLoadedChanged(filename: String?) {}
        })
    }

    private fun updateProbePinStatus(pn: String) {
        val isTriggered = pn.contains("P")
        val drawable = binding.probePinDot.background
        if (drawable is android.graphics.drawable.GradientDrawable) {
            drawable.setColor(
                if (isTriggered) android.graphics.Color.parseColor("#e74c3c")
                else android.graphics.Color.parseColor("#2ecc71")
            )
        }
        // Update 3D model LED color to match
        binding.probeWebView.evaluateJavascript("setProbeActive($isTriggered)", null)
    }

    private fun setupToolbar() {
        binding.backBtn.setOnClickListener { finish() }
        binding.probeStopBtn.setOnClickListener { stopProbe() }
    }

    private fun setupProbeTypeSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, probeTypes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.probeTypeSpinner.adapter = adapter
        binding.probeTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentProbeType = probeTypeValues[position]
                if (!isLoadingSettings) {
                    updateAxisSpinnerOptions()
                }
                syncWebViewState()
                updateFieldVisibility()
                if (!isLoadingSettings) {
                    saveProbeSettings()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupProbingAxisSpinner() {
        updateAxisSpinnerOptions()
        binding.probingAxisSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val axes = getAxesForProbeType()
                currentProbingAxis = axes[position]
                selectedCorner = null
                selectedSide = null
                updateSelectionText()
                updateFieldVisibility()
                updateStartButtonState()
                syncWebViewState()
                if (!isLoadingSettings) {
                    saveProbeSettings()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun getAxesForProbeType(): List<String> {
        return when (currentProbeType) {
            "3d-probe" -> threeDProbeAxes
            "standard-block" -> standardBlockAxes
            "autozero-touch" -> autoZeroTouchAxes
            else -> threeDProbeAxes
        }
    }

    private fun updateAxisSpinnerOptions() {
        val axes = getAxesForProbeType()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, axes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.probingAxisSpinner.adapter = adapter
        // Reset to first axis (Z) when probe type changes
        currentProbingAxis = axes[0]
        selectedCorner = null
        selectedSide = null
    }

    private fun setupBitDiameterSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, bitDiameterOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.bitDiameterSpinner.adapter = adapter
        binding.bitDiameterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isLoadingSettings) {
                    saveProbeSettings()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupJogControls() {
        // Toggle jog overlay
        binding.jogToggleBtn.setOnClickListener {
            binding.jogOverlay.visibility = View.VISIBLE
        }

        // Close jog overlay
        binding.jogCloseBtn.setOnClickListener {
            binding.jogOverlay.visibility = View.GONE
        }

        // Step size spinner
        val stepLabels = jogStepValues.map { "${it}mm" }
        val stepAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, stepLabels)
        stepAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.jogStepSpinner.adapter = stepAdapter
        binding.jogStepSpinner.setSelection(1) // Default to 1mm
        binding.jogStepSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                jogStep = jogStepValues[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Feed rate spinner
        val feedLabels = jogFeedValues.map { "$it" }
        val feedAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, feedLabels)
        feedAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.jogFeedSpinner.adapter = feedAdapter
        binding.jogFeedSpinner.setSelection(1) // Default to 1000
        binding.jogFeedSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                jogFeed = jogFeedValues[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Jog buttons
        binding.jogXPlus.setOnClickListener { sendJog("X", 1) }
        binding.jogXMinus.setOnClickListener { sendJog("X", -1) }
        binding.jogYPlus.setOnClickListener { sendJog("Y", 1) }
        binding.jogYMinus.setOnClickListener { sendJog("Y", -1) }
        binding.jogZPlus.setOnClickListener { sendJog("Z", 1) }
        binding.jogZMinus.setOnClickListener { sendJog("Z", -1) }
    }

    private fun sendJog(axis: String, direction: Int) {
        val distance = jogStep * direction
        wsManager?.sendJogCommand(axis, distance, jogFeed)
    }

    private fun updateSelectionText() {
        val text = when {
            selectedCorner != null -> {
                val corner = when (selectedCorner) {
                    "TopLeft" -> "Top Left"
                    "TopRight" -> "Top Right"
                    "BottomLeft" -> "Bottom Left"
                    "BottomRight" -> "Bottom Right"
                    else -> selectedCorner
                }
                "$currentProbingAxis - $corner"
            }
            selectedSide != null -> {
                "$currentProbingAxis - ${selectedSide}"
            }
            currentProbingAxis == "Z" -> "Z - Center"
            currentProbingAxis.startsWith("Center") -> "$currentProbingAxis"
            else -> "Tap a position on the diagram"
        }
        binding.probeSelectionText.text = text
    }

    private fun updateFieldVisibility() {
        val axis = currentProbingAxis
        val type = currentProbeType

        // Hide all first
        binding.toolDiameterRow.visibility = View.GONE
        binding.zThicknessRow.visibility = View.GONE
        binding.xyThicknessRow.visibility = View.GONE
        binding.zPlungeRow.visibility = View.GONE
        binding.zOffsetRow.visibility = View.GONE
        binding.zProbeDistanceRow.visibility = View.GONE
        binding.xDimensionRow.visibility = View.GONE
        binding.yDimensionRow.visibility = View.GONE
        binding.rapidMovementRow.visibility = View.GONE
        binding.probeZFirstRow.visibility = View.GONE
        binding.bitDiameterRow.visibility = View.GONE

        // Rapid movement is always visible for all probe types and axes
        binding.rapidMovementRow.visibility = View.VISIBLE

        when (type) {
            "3d-probe" -> {
                binding.toolDiameterLabel.text = "Diameter (mm)"
                // Diameter is always visible for 3D probe
                binding.toolDiameterRow.visibility = View.VISIBLE

                when (axis) {
                    "Z" -> {
                        binding.zOffsetRow.visibility = View.VISIBLE
                    }
                    "XYZ" -> {
                        binding.zPlungeRow.visibility = View.VISIBLE
                        binding.zOffsetRow.visibility = View.VISIBLE
                    }
                    "XY", "X", "Y" -> {
                        // just diameter + rapid movement
                    }
                    "Center - Inner" -> {
                        binding.zPlungeRow.visibility = View.VISIBLE
                        binding.xDimensionRow.visibility = View.VISIBLE
                        binding.yDimensionRow.visibility = View.VISIBLE
                        binding.zOffsetRow.visibility = View.VISIBLE
                    }
                    "Center - Outer" -> {
                        binding.zPlungeRow.visibility = View.VISIBLE
                        binding.zOffsetRow.visibility = View.VISIBLE
                        binding.xDimensionRow.visibility = View.VISIBLE
                        binding.yDimensionRow.visibility = View.VISIBLE
                        binding.probeZFirstRow.visibility = View.VISIBLE
                    }
                }
            }
            "standard-block" -> {
                binding.toolDiameterLabel.text = "Bit Diameter (mm)"
                // Bit diameter is always visible for standard block
                binding.toolDiameterRow.visibility = View.VISIBLE

                when (axis) {
                    "Z" -> {
                        binding.zThicknessRow.visibility = View.VISIBLE
                    }
                    "XYZ" -> {
                        binding.zThicknessRow.visibility = View.VISIBLE
                        binding.xyThicknessRow.visibility = View.VISIBLE
                        binding.zProbeDistanceRow.visibility = View.VISIBLE
                    }
                    "XY", "X", "Y" -> {
                        binding.xyThicknessRow.visibility = View.VISIBLE
                    }
                }
            }
            "autozero-touch" -> {
                binding.bitDiameterRow.visibility = View.VISIBLE
            }
        }

        updateStartButtonState()
    }

    private fun updateStartButtonState() {
        val needsCorner = currentProbingAxis in listOf("XYZ", "XY")
        val needsSide = currentProbingAxis in listOf("X", "Y")

        val valid = when {
            isProbing -> false
            needsCorner && selectedCorner == null -> false
            needsSide && selectedSide == null -> false
            else -> true
        }

        binding.startProbeBtn.isEnabled = valid

        binding.probeErrorText.visibility = when {
            needsCorner && selectedCorner == null -> {
                binding.probeErrorText.text = "Select a corner on the diagram above"
                View.VISIBLE
            }
            needsSide && selectedSide == null -> {
                binding.probeErrorText.text = "Select a side on the diagram above"
                View.VISIBLE
            }
            else -> View.GONE
        }
    }

    private fun setupStartButton() {
        binding.startProbeBtn.setOnClickListener {
            if (!isProbing) {
                startProbe()
            }
        }
    }

    private fun startProbe() {
        val body = buildProbeRequest() ?: return

        isProbing = true
        binding.startProbeBtn.isEnabled = false
        binding.startProbeBtn.text = "PROBING..."
        binding.startProbeBtn.setTextColor(android.graphics.Color.parseColor("#7f8c8d"))

        if (webViewReady) {
            binding.probeWebView.evaluateJavascript("setProbeActive(true)", null)
        }

        val url = "http://$serverAddress/api/probe/start"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = body.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Probe start failed", e)
                runOnUiThread {
                    isProbing = false
                    binding.startProbeBtn.isEnabled = true
                    binding.startProbeBtn.text = "START PROBE"
                    binding.startProbeBtn.setTextColor(android.graphics.Color.parseColor("#2ecc71"))
                    if (webViewReady) {
                        binding.probeWebView.evaluateJavascript("setProbeActive(false)", null)
                    }
                    Toast.makeText(this@ProbeActivity, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                Log.d(TAG, "Probe response: $responseBody")
                runOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@ProbeActivity, "Probe started", Toast.LENGTH_SHORT).show()
                        binding.root.postDelayed({
                            resetProbeState()
                        }, 2000)
                    } else {
                        isProbing = false
                        binding.startProbeBtn.isEnabled = true
                        binding.startProbeBtn.text = "START PROBE"
                        binding.startProbeBtn.setTextColor(android.graphics.Color.parseColor("#2ecc71"))
                            if (webViewReady) {
                            binding.probeWebView.evaluateJavascript("setProbeActive(false)", null)
                        }
                        Toast.makeText(this@ProbeActivity, "Error: $responseBody", Toast.LENGTH_LONG).show()
                    }
                }
                response.close()
            }
        })
    }

    private fun stopProbe() {
        val url = "http://$serverAddress/api/probe/stop"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = "{}".toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Probe stop failed", e)
                runOnUiThread {
                    Toast.makeText(this@ProbeActivity, "Stop failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                runOnUiThread {
                    resetProbeState()
                    Toast.makeText(this@ProbeActivity, "Probe stopped", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun resetProbeState() {
        isProbing = false
        binding.startProbeBtn.text = "START PROBE"
        binding.startProbeBtn.setTextColor(android.graphics.Color.parseColor("#2ecc71"))
        if (webViewReady) {
            binding.probeWebView.evaluateJavascript("setProbeActive(false)", null)
        }
        updateStartButtonState()
    }

    private fun buildProbeRequest(): JsonObject? {
        val json = JsonObject()
        json.addProperty("probeType", currentProbeType)
        json.addProperty("probingAxis", currentProbingAxis)

        when (currentProbingAxis) {
            "XYZ", "XY" -> {
                if (selectedCorner == null) return null
                json.addProperty("selectedCorner", selectedCorner)
            }
            "X" -> {
                if (selectedSide == null) return null
                json.addProperty("selectedSide", selectedSide)
            }
            "Y" -> {
                if (selectedSide == null) return null
                json.addProperty("selectedSide", selectedSide)
            }
        }

        when (currentProbeType) {
            "3d-probe" -> {
                when (currentProbingAxis) {
                    "Z" -> {
                        json.addProperty("zOffset", getFloatInput(binding.zOffsetInput, 0f))
                    }
                    "XYZ" -> {
                        json.addProperty("toolDiameter", getFloatInput(binding.toolDiameterInput, 6f))
                        json.addProperty("zPlunge", getFloatInput(binding.zPlungeInput, 3f))
                        json.addProperty("zOffset", getFloatInput(binding.zOffsetInput, 0f))
                    }
                    "XY" -> {
                        json.addProperty("toolDiameter", getFloatInput(binding.toolDiameterInput, 6f))
                    }
                    "X", "Y" -> {
                        json.addProperty("toolDiameter", getFloatInput(binding.toolDiameterInput, 6f))
                    }
                    "Center - Inner" -> {
                        json.addProperty("toolDiameter", getFloatInput(binding.toolDiameterInput, 2f))
                        json.addProperty("zPlunge", getFloatInput(binding.zPlungeInput, 3f))
                        json.addProperty("xDimension", getFloatInput(binding.xDimensionInput, 0f))
                        json.addProperty("yDimension", getFloatInput(binding.yDimensionInput, 0f))
                        json.addProperty("rapidMovement", getFloatInput(binding.rapidMovementInput, 2000f))
                    }
                    "Center - Outer" -> {
                        json.addProperty("toolDiameter", getFloatInput(binding.toolDiameterInput, 2f))
                        json.addProperty("zPlunge", getFloatInput(binding.zPlungeInput, 3f))
                        json.addProperty("zOffset", getFloatInput(binding.zOffsetInput, 0f))
                        json.addProperty("xDimension", getFloatInput(binding.xDimensionInput, 0f))
                        json.addProperty("yDimension", getFloatInput(binding.yDimensionInput, 0f))
                        json.addProperty("rapidMovement", getFloatInput(binding.rapidMovementInput, 2000f))
                        json.addProperty("probeZFirst", binding.probeZFirstSwitch.isChecked)
                    }
                }
            }
            "standard-block" -> {
                when (currentProbingAxis) {
                    "Z" -> {
                        json.addProperty("zThickness", getFloatInput(binding.zThicknessInput, 15f))
                    }
                    "XYZ" -> {
                        json.addProperty("bitDiameter", getFloatInput(binding.toolDiameterInput, 6.35f))
                        json.addProperty("xyThickness", getFloatInput(binding.xyThicknessInput, 10f))
                        json.addProperty("zThickness", getFloatInput(binding.zThicknessInput, 15f))
                        json.addProperty("zProbeDistance", getFloatInput(binding.zProbeDistanceInput, 3f))
                    }
                    "XY" -> {
                        json.addProperty("bitDiameter", getFloatInput(binding.toolDiameterInput, 6.35f))
                        json.addProperty("xyThickness", getFloatInput(binding.xyThicknessInput, 10f))
                    }
                    "X", "Y" -> {
                        json.addProperty("bitDiameter", getFloatInput(binding.toolDiameterInput, 6.35f))
                        json.addProperty("xyThickness", getFloatInput(binding.xyThicknessInput, 10f))
                    }
                }
            }
            "autozero-touch" -> {
                val selectedBitPos = binding.bitDiameterSpinner.selectedItemPosition
                val selectedBitDiameter = if (selectedBitPos >= 0 && selectedBitPos < bitDiameterOptions.size) {
                    bitDiameterOptions[selectedBitPos]
                } else {
                    "Auto"
                }
                json.addProperty("selectedBitDiameter", selectedBitDiameter)

                when (currentProbingAxis) {
                    "XYZ", "XY", "X", "Y" -> {
                        json.addProperty("rapidMovement", getFloatInput(binding.rapidMovementInput, 2000f))
                    }
                }
            }
        }

        Log.d(TAG, "Probe request: $json")
        return json
    }

    private fun getFloatInput(editText: android.widget.EditText, default: Float): Float {
        val text = editText.text.toString().trim()
        return text.toFloatOrNull() ?: default
    }

    // --- Probe Settings Persistence ---

    private fun loadProbeSettings() {
        isLoadingSettings = true

        val prefs = getSharedPreferences("probe_prefs", MODE_PRIVATE)
        currentProbeType = prefs.getString(PREF_PROBE_TYPE, "3d-probe") ?: "3d-probe"
        val savedProbingAxis = prefs.getString(PREF_PROBING_AXIS, "Z") ?: "Z"

        // Load input field values
        val toolDiameter = prefs.getFloat(PREF_TOOL_DIAMETER, 6f)
        val zThickness = prefs.getFloat(PREF_Z_THICKNESS, 15f)
        val xyThickness = prefs.getFloat(PREF_XY_THICKNESS, 10f)
        val zPlunge = prefs.getFloat(PREF_Z_PLUNGE, 3f)
        val rapidMovement = prefs.getFloat(PREF_RAPID_MOVEMENT, 2000f)
        val bitDiameterIndex = prefs.getInt(PREF_BIT_DIAMETER_INDEX, 0)

        // Set input field values
        binding.toolDiameterInput.setText(toolDiameter.toString())
        binding.zThicknessInput.setText(zThickness.toString())
        binding.xyThicknessInput.setText(xyThickness.toString())
        binding.zPlungeInput.setText(zPlunge.toString())
        binding.rapidMovementInput.setText(rapidMovement.toInt().toString())

        // Set probe type spinner
        val probeTypeIndex = probeTypeValues.indexOf(currentProbeType)
        if (probeTypeIndex >= 0) {
            binding.probeTypeSpinner.setSelection(probeTypeIndex)
        }

        // Update axis spinner options for the loaded probe type
        updateAxisSpinnerOptions()

        // Restore probing axis selection
        val axes = getAxesForProbeType()
        val axisIndex = axes.indexOf(savedProbingAxis)
        if (axisIndex >= 0) {
            currentProbingAxis = savedProbingAxis
            binding.probingAxisSpinner.setSelection(axisIndex)
        }

        // Bit diameter spinner
        if (bitDiameterIndex in bitDiameterOptions.indices) {
            binding.bitDiameterSpinner.setSelection(bitDiameterIndex)
        }

        isLoadingSettings = false
    }

    private fun saveProbeSettings() {
        getSharedPreferences("probe_prefs", MODE_PRIVATE).edit()
            .putString(PREF_PROBE_TYPE, currentProbeType)
            .putString(PREF_PROBING_AXIS, currentProbingAxis)
            .putFloat(PREF_TOOL_DIAMETER, getFloatInput(binding.toolDiameterInput, 6f))
            .putFloat(PREF_Z_THICKNESS, getFloatInput(binding.zThicknessInput, 15f))
            .putFloat(PREF_XY_THICKNESS, getFloatInput(binding.xyThicknessInput, 10f))
            .putFloat(PREF_Z_PLUNGE, getFloatInput(binding.zPlungeInput, 3f))
            .putFloat(PREF_RAPID_MOVEMENT, getFloatInput(binding.rapidMovementInput, 2000f))
            .putInt(PREF_BIT_DIAMETER_INDEX, binding.bitDiameterSpinner.selectedItemPosition)
            .apply()
    }

    companion object {
        private const val TAG = "ProbeActivity"

        // Preference keys
        private const val PREF_PROBE_TYPE = "probe_type"
        private const val PREF_PROBING_AXIS = "probing_axis"
        private const val PREF_TOOL_DIAMETER = "tool_diameter"
        private const val PREF_Z_THICKNESS = "z_thickness"
        private const val PREF_XY_THICKNESS = "xy_thickness"
        private const val PREF_Z_PLUNGE = "z_plunge"
        private const val PREF_RAPID_MOVEMENT = "rapid_movement"
        private const val PREF_BIT_DIAMETER_INDEX = "bit_diameter_index"
    }
}
