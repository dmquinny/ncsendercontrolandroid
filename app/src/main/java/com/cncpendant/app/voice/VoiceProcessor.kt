package com.cncpendant.app.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

/**
 * VoiceProcessor orchestrates the complete voice processing pipeline:
 * 1. AudioRecorder captures raw audio
 * 2. DeepFilterNet (optional) removes background noise
 * 3. WhisperTranscriber converts speech to text
 * 
 * This provides on-device, offline voice recognition with optional
 * noise reduction for improved accuracy in noisy environments.
 */
class VoiceProcessor(private val context: Context) {
    
    companion object {
        private const val TAG = "VoiceProcessor"
        
        // Processing modes
        const val MODE_ANDROID_ONLY = 0      // Use Android SpeechRecognizer (fallback)
        const val MODE_WHISPER_ONLY = 1      // Use Whisper without noise reduction
        const val MODE_WHISPER_DEEPFILTER = 2  // Use Whisper with DeepFilterNet
    }
    
    // Components
    private val audioRecorder = AudioRecorder()
    private var whisperTranscriber: WhisperTranscriber? = null
    private var deepFilterNet: DeepFilterNetProcessor? = null
    
    private var processorScope: CoroutineScope? = null
    private var isProcessing = false
    
    // Settings
    private var processingMode = MODE_ANDROID_ONLY
    private var deepFilterEnabled = true
    
    // Callbacks
    var onTranscriptionResult: ((String) -> Unit)? = null
    var onProcessingStarted: (() -> Unit)? = null
    var onProcessingComplete: (() -> Unit)? = null
    var onSpeechDetected: (() -> Unit)? = null
    var onSilenceDetected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStatusUpdate: ((String) -> Unit)? = null
    
    /**
     * Initialize the voice processor components
     * @param useWhisper Whether to use Whisper for transcription
     * @param useDeepFilter Whether to use DeepFilterNet for noise reduction
     */
    suspend fun initialize(useWhisper: Boolean = true, useDeepFilter: Boolean = true): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    onStatusUpdate?.invoke("Initializing voice processor...")
                }
                
                // Initialize Whisper if requested
                if (useWhisper) {
                    whisperTranscriber = WhisperTranscriber(context)
                    if (whisperTranscriber?.isAvailable() == true) {
                        val whisperInit = whisperTranscriber?.initialize() ?: false
                        if (whisperInit) {
                            Log.d(TAG, "Whisper initialized successfully")
                            processingMode = MODE_WHISPER_ONLY
                        } else {
                            Log.w(TAG, "Whisper initialization failed, will use Android STT")
                            whisperTranscriber = null
                        }
                    } else {
                        Log.w(TAG, "Whisper not available, will use Android STT")
                        whisperTranscriber = null
                    }
                }
                
                // Initialize DeepFilterNet if requested and Whisper is available
                if (useDeepFilter && whisperTranscriber != null) {
                    deepFilterNet = DeepFilterNetProcessor(context)
                    if (deepFilterNet?.isAvailable() == true) {
                        val dfInit = deepFilterNet?.initialize() ?: false
                        if (dfInit) {
                            Log.d(TAG, "DeepFilterNet initialized successfully")
                            processingMode = MODE_WHISPER_DEEPFILTER
                            deepFilterEnabled = true
                        } else {
                            Log.w(TAG, "DeepFilterNet initialization failed")
                            deepFilterNet = null
                        }
                    } else {
                        Log.w(TAG, "DeepFilterNet model not available")
                        deepFilterNet = null
                    }
                }
                
                // Check audio recorder
                if (!audioRecorder.isRecordingAvailable()) {
                    Log.e(TAG, "Audio recording not available")
                    withContext(Dispatchers.Main) {
                        onError?.invoke("Audio recording not available on this device")
                    }
                    return@withContext false
                }
                
                withContext(Dispatchers.Main) {
                    val status = when (processingMode) {
                        MODE_WHISPER_DEEPFILTER -> "Ready (Whisper + DeepFilterNet)"
                        MODE_WHISPER_ONLY -> "Ready (Whisper)"
                        else -> "Ready (Android STT)"
                    }
                    onStatusUpdate?.invoke(status)
                }
                
                Log.d(TAG, "Voice processor initialized, mode: $processingMode")
                return@withContext true
                
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing voice processor", e)
                withContext(Dispatchers.Main) {
                    onError?.invoke("Initialization error: ${e.message}")
                }
                return@withContext false
            }
        }
    }
    
    /**
     * Start listening for voice input
     */
    fun startListening(scope: CoroutineScope): Boolean {
        if (isProcessing) {
            Log.w(TAG, "Already processing")
            return false
        }
        
        if (processingMode == MODE_ANDROID_ONLY) {
            // This mode should use Android's SpeechRecognizer instead
            Log.w(TAG, "VoiceProcessor in Android mode - use SpeechRecognizer directly")
            onError?.invoke("On-device processing not available, use Android STT")
            return false
        }
        
        processorScope = scope
        isProcessing = true
        
        // Set up audio recorder callbacks
        audioRecorder.onSpeechDetected = {
            onSpeechDetected?.invoke()
        }
        
        audioRecorder.onSilenceDetected = {
            onSilenceDetected?.invoke()
            // Stop and process when silence detected
            stopListening()
        }
        
        audioRecorder.onRecordingComplete = { audio ->
            scope.launch {
                processAudio(audio)
            }
        }
        
        audioRecorder.onError = { error ->
            isProcessing = false
            onError?.invoke(error)
        }
        
        // Start recording
        val started = audioRecorder.startRecording(scope)
        if (started) {
            onProcessingStarted?.invoke()
            onStatusUpdate?.invoke("Listening...")
        } else {
            isProcessing = false
        }
        
        return started
    }
    
    /**
     * Stop listening and process the recorded audio
     */
    fun stopListening() {
        audioRecorder.stopRecording()
    }
    
    /**
     * Process recorded audio through the pipeline
     */
    private suspend fun processAudio(audio: FloatArray) {
        if (audio.isEmpty()) {
            Log.w(TAG, "No audio to process")
            isProcessing = false
            onProcessingComplete?.invoke()
            return
        }
        
        try {
            var processedAudio = audio
            
            // Apply DeepFilterNet noise reduction if enabled
            if (processingMode == MODE_WHISPER_DEEPFILTER && deepFilterEnabled) {
                withContext(Dispatchers.Main) {
                    onStatusUpdate?.invoke("Removing noise...")
                }
                
                processedAudio = deepFilterNet?.process(audio) ?: audio
            }
            
            // Transcribe with Whisper
            withContext(Dispatchers.Main) {
                onStatusUpdate?.invoke("Transcribing...")
            }
            
            val transcription = whisperTranscriber?.transcribe(processedAudio)
            
            withContext(Dispatchers.Main) {
                if (transcription != null && transcription.isNotEmpty()) {
                    Log.d(TAG, "Transcription: $transcription")
                    onTranscriptionResult?.invoke(transcription)
                } else {
                    Log.w(TAG, "No transcription result")
                    onError?.invoke("Could not transcribe speech")
                }
                onProcessingComplete?.invoke()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio", e)
            withContext(Dispatchers.Main) {
                onError?.invoke("Processing error: ${e.message}")
                onProcessingComplete?.invoke()
            }
        } finally {
            isProcessing = false
        }
    }
    
    /**
     * Check if currently processing
     */
    fun isCurrentlyProcessing(): Boolean = isProcessing
    
    /**
     * Check if Whisper is available
     */
    fun isWhisperAvailable(): Boolean = whisperTranscriber != null
    
    /**
     * Check if DeepFilterNet is available
     */
    fun isDeepFilterAvailable(): Boolean = deepFilterNet != null
    
    /**
     * Get current processing mode
     */
    fun getProcessingMode(): Int = processingMode
    
    /**
     * Get mode description
     */
    fun getModeDescription(): String = when (processingMode) {
        MODE_WHISPER_DEEPFILTER -> "Whisper + DeepFilterNet (On-Device)"
        MODE_WHISPER_ONLY -> "Whisper (On-Device)"
        else -> "Android Speech Recognition"
    }
    
    /**
     * Enable/disable DeepFilterNet noise reduction
     */
    fun setDeepFilterEnabled(enabled: Boolean) {
        deepFilterEnabled = enabled
        deepFilterNet?.setEnabled(enabled)
        Log.d(TAG, "DeepFilterNet ${if (enabled) "enabled" else "disabled"}")
    }
    
    /**
     * Check if DeepFilterNet is enabled
     */
    fun isDeepFilterEnabled(): Boolean = deepFilterEnabled && deepFilterNet != null
    
    /**
     * Release all resources
     */
    fun release() {
        audioRecorder.release()
        whisperTranscriber?.release()
        deepFilterNet?.release()
        
        whisperTranscriber = null
        deepFilterNet = null
        isProcessing = false
        
        Log.d(TAG, "Voice processor released")
    }
}
