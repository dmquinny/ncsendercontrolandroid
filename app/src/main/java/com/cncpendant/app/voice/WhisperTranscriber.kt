package com.cncpendant.app.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * WhisperTranscriber uses whisper.cpp via JNI for on-device speech-to-text.
 * 
 * This provides offline, privacy-respecting speech recognition with better
 * accuracy than many cloud-based alternatives.
 * 
 * Model files should be placed in assets/models/whisper/
 * Recommended model: whisper-tiny.en.bin (~75MB) for mobile devices
 */
class WhisperTranscriber(private val context: Context) : SpeechTranscriber {
    
    companion object {
        private const val TAG = "WhisperTranscriber"
        private const val MODEL_DIR = "models/whisper"
        private const val DEFAULT_MODEL = "ggml-tiny.en.bin"  // ~75MB, English only, fast
        
        // Native library loading state
        private var nativeLoaded = false
        private var loadError: String? = null
        
        init {
            try {
                System.loadLibrary("whisper")
                nativeLoaded = true
                Log.d(TAG, "Whisper native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                loadError = "Failed to load whisper native library: ${e.message}"
                Log.e(TAG, loadError!!)
            }
        }
    }
    
    private var contextPtr: Long = 0L
    private var modelPath: String? = null
    private var initialized = false
    
    override fun isAvailable(): Boolean {
        if (!nativeLoaded) {
            Log.w(TAG, "Native library not available: $loadError")
            return false
        }
        
        // Check if model file exists in assets or internal storage
        return try {
            val modelFile = getModelFile()
            val exists = modelFile.exists()
            if (!exists) {
                Log.w(TAG, "Whisper model not found at: ${modelFile.absolutePath}")
            }
            exists
        } catch (e: Exception) {
            Log.e(TAG, "Error checking model availability", e)
            false
        }
    }
    
    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (initialized && contextPtr != 0L) {
            return@withContext true
        }
        
        if (!nativeLoaded) {
            Log.e(TAG, "Cannot initialize: native library not loaded")
            return@withContext false
        }
        
        try {
            // Ensure model is extracted to internal storage
            val modelFile = getModelFile()
            if (!modelFile.exists()) {
                Log.d(TAG, "Extracting model from assets...")
                if (!extractModelFromAssets(modelFile)) {
                    Log.e(TAG, "Failed to extract model from assets")
                    return@withContext false
                }
            }
            
            modelPath = modelFile.absolutePath
            Log.d(TAG, "Initializing Whisper with model: $modelPath")
            
            // Initialize whisper context
            contextPtr = nativeInit(modelPath!!)
            
            if (contextPtr == 0L) {
                Log.e(TAG, "Failed to initialize Whisper context")
                return@withContext false
            }
            
            initialized = true
            Log.d(TAG, "Whisper initialized successfully")
            return@withContext true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Whisper", e)
            return@withContext false
        }
    }
    
    override suspend fun transcribe(audioSamples: FloatArray): String? = withContext(Dispatchers.IO) {
        if (!initialized || contextPtr == 0L) {
            Log.e(TAG, "Whisper not initialized")
            if (!initialize()) {
                return@withContext null
            }
        }
        
        if (audioSamples.isEmpty()) {
            Log.w(TAG, "Empty audio buffer")
            return@withContext null
        }
        
        try {
            Log.d(TAG, "Transcribing ${audioSamples.size} samples...")
            
            val startTime = System.currentTimeMillis()
            val result = nativeTranscribe(contextPtr, audioSamples)
            val elapsed = System.currentTimeMillis() - startTime
            
            Log.d(TAG, "Transcription completed in ${elapsed}ms: \"$result\"")
            return@withContext result?.trim()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during transcription", e)
            return@withContext null
        }
    }
    
    override fun getName(): String = "Whisper (On-Device)"
    
    override fun release() {
        if (contextPtr != 0L) {
            try {
                nativeFree(contextPtr)
            } catch (e: Exception) {
                Log.e(TAG, "Error freeing Whisper context", e)
            }
            contextPtr = 0L
        }
        initialized = false
    }
    
    /**
     * Get the model file path (in app's internal storage)
     */
    private fun getModelFile(): File {
        val modelsDir = File(context.filesDir, "whisper_models")
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
        return File(modelsDir, DEFAULT_MODEL)
    }
    
    /**
     * Extract model from assets to internal storage
     */
    private fun extractModelFromAssets(destFile: File): Boolean {
        return try {
            val assetPath = "$MODEL_DIR/$DEFAULT_MODEL"
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Model extracted to: ${destFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract model from assets", e)
            false
        }
    }
    
    /**
     * Download model from network (for future use)
     * Models can be downloaded from: https://huggingface.co/ggerganov/whisper.cpp
     */
    suspend fun downloadModel(url: String, progressCallback: ((Float) -> Unit)? = null): Boolean {
        // TODO: Implement model download with progress
        Log.d(TAG, "Model download not yet implemented: $url")
        return false
    }
    
    /**
     * Set transcription parameters
     */
    fun setLanguage(language: String) {
        if (contextPtr != 0L) {
            nativeSetLanguage(contextPtr, language)
        }
    }
    
    fun setTranslate(translate: Boolean) {
        if (contextPtr != 0L) {
            nativeSetTranslate(contextPtr, translate)
        }
    }
    
    // JNI Native method declarations
    // These must match the native implementation in whisper_jni.cpp
    
    private external fun nativeInit(modelPath: String): Long
    private external fun nativeFree(contextPtr: Long)
    private external fun nativeTranscribe(contextPtr: Long, samples: FloatArray): String?
    private external fun nativeSetLanguage(contextPtr: Long, language: String)
    private external fun nativeSetTranslate(contextPtr: Long, translate: Boolean)
}
