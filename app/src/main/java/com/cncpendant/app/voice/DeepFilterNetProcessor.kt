package com.cncpendant.app.voice

import android.content.Context
import android.util.Log
import ai.onnxruntime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer

/**
 * DeepFilterNet processor for real-time audio noise reduction.
 * Uses ONNX Runtime to run the DeepFilterNet model for speech enhancement.
 * 
 * DeepFilterNet removes background noise while preserving speech quality,
 * which significantly improves speech recognition accuracy in noisy environments
 * like machine shops with CNC equipment running.
 * 
 * Model files should be placed in assets/models/deepfilter/
 */
class DeepFilterNetProcessor(private val context: Context) {
    
    companion object {
        private const val TAG = "DeepFilterNet"
        private const val MODEL_DIR = "models/deepfilter"
        private const val MODEL_NAME = "deepfilter3.onnx"  // DeepFilterNet3 model
        
        // DeepFilterNet operates at 48kHz, we need to resample from 16kHz
        const val NATIVE_SAMPLE_RATE = 48000
        const val INPUT_SAMPLE_RATE = 16000  // Whisper's sample rate
        
        // Frame sizes for DeepFilterNet
        const val FRAME_SIZE = 480  // 10ms at 48kHz
        const val HOP_SIZE = 480
        
        private var instanceCount = 0
    }
    
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var modelPath: String? = null
    private var initialized = false
    private var enabled = true
    
    // Internal state buffers (maintained between frames for temporal coherence)
    private var erbState: OnnxTensor? = null
    private var dfState: OnnxTensor? = null
    
    /**
     * Check if DeepFilterNet is available
     */
    fun isAvailable(): Boolean {
        return try {
            val modelFile = getModelFile()
            val exists = modelFile.exists()
            if (!exists) {
                // Check if model exists in assets
                try {
                    context.assets.open("$MODEL_DIR/$MODEL_NAME").close()
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "DeepFilterNet model not found")
                    false
                }
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking model availability", e)
            false
        }
    }
    
    /**
     * Initialize the DeepFilterNet processor
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (initialized) {
            return@withContext true
        }
        
        try {
            // Ensure model is extracted to internal storage
            val modelFile = getModelFile()
            if (!modelFile.exists()) {
                Log.d(TAG, "Extracting DeepFilterNet model from assets...")
                if (!extractModelFromAssets(modelFile)) {
                    Log.e(TAG, "Failed to extract DeepFilterNet model")
                    return@withContext false
                }
            }
            
            modelPath = modelFile.absolutePath
            Log.d(TAG, "Initializing DeepFilterNet with model: $modelPath")
            
            // Create ONNX Runtime environment
            ortEnvironment = OrtEnvironment.getEnvironment()
            
            // Configure session options for mobile performance
            val sessionOptions = OrtSession.SessionOptions().apply {
                // Use NNAPI for hardware acceleration on supported devices
                try {
                    addNnapi()
                    Log.d(TAG, "NNAPI acceleration enabled")
                } catch (e: Exception) {
                    Log.d(TAG, "NNAPI not available, using CPU")
                }
                
                // Optimize for inference
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(2)
            }
            
            // Load model
            ortSession = ortEnvironment?.createSession(modelPath, sessionOptions)
            
            if (ortSession == null) {
                Log.e(TAG, "Failed to create ONNX session")
                return@withContext false
            }
            
            // Initialize state buffers
            initializeStates()
            
            initialized = true
            instanceCount++
            Log.d(TAG, "DeepFilterNet initialized successfully (instance $instanceCount)")
            return@withContext true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing DeepFilterNet", e)
            return@withContext false
        }
    }
    
    /**
     * Initialize internal state tensors
     */
    private fun initializeStates() {
        // State dimensions depend on the specific DeepFilterNet model
        // These are typical values for DeepFilterNet3
        val erbStateShape = longArrayOf(1, 2, 32, 2)  // [batch, channels, freq_bins, real/imag]
        val dfStateShape = longArrayOf(1, 2, 96, 2)   // [batch, channels, df_bins, real/imag]
        
        val env = ortEnvironment ?: return
        
        erbState = OnnxTensor.createTensor(
            env,
            FloatBuffer.allocate(erbStateShape.reduce { a, b -> a * b }.toInt()),
            erbStateShape
        )
        
        dfState = OnnxTensor.createTensor(
            env,
            FloatBuffer.allocate(dfStateShape.reduce { a, b -> a * b }.toInt()),
            dfStateShape
        )
    }
    
    /**
     * Process audio through DeepFilterNet for noise reduction
     * 
     * @param audioSamples Input audio samples (16kHz, normalized -1.0 to 1.0)
     * @return Noise-reduced audio samples (same format)
     */
    suspend fun process(audioSamples: FloatArray): FloatArray = withContext(Dispatchers.IO) {
        if (!enabled) {
            return@withContext audioSamples
        }
        
        if (!initialized) {
            Log.w(TAG, "DeepFilterNet not initialized, returning original audio")
            return@withContext audioSamples
        }
        
        if (audioSamples.isEmpty()) {
            return@withContext audioSamples
        }
        
        try {
            val startTime = System.currentTimeMillis()
            
            // Resample from 16kHz to 48kHz
            val upsampled = resample(audioSamples, INPUT_SAMPLE_RATE, NATIVE_SAMPLE_RATE)
            
            // Process in frames
            val outputFrames = mutableListOf<FloatArray>()
            var offset = 0
            
            while (offset + FRAME_SIZE <= upsampled.size) {
                val frame = upsampled.sliceArray(offset until offset + FRAME_SIZE)
                val processedFrame = processFrame(frame)
                outputFrames.add(processedFrame)
                offset += HOP_SIZE
            }
            
            // Handle remaining samples (pad if needed)
            if (offset < upsampled.size) {
                val remaining = upsampled.sliceArray(offset until upsampled.size)
                val paddedFrame = FloatArray(FRAME_SIZE)
                remaining.copyInto(paddedFrame)
                val processedFrame = processFrame(paddedFrame)
                // Only take the non-padded portion
                outputFrames.add(processedFrame.sliceArray(0 until remaining.size))
            }
            
            // Combine frames
            val processedUpsampled = outputFrames.flatMap { it.toList() }.toFloatArray()
            
            // Resample back to 16kHz
            val output = resample(processedUpsampled, NATIVE_SAMPLE_RATE, INPUT_SAMPLE_RATE)
            
            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "Processed ${audioSamples.size} samples in ${elapsed}ms")
            
            return@withContext output
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio", e)
            return@withContext audioSamples  // Return original on error
        }
    }
    
    /**
     * Process a single frame of audio
     */
    private fun processFrame(frame: FloatArray): FloatArray {
        val env = ortEnvironment ?: return frame
        val session = ortSession ?: return frame
        
        // Create input tensor
        val inputShape = longArrayOf(1, frame.size.toLong())
        val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(frame), inputShape)
        
        // Prepare inputs
        val inputs = mutableMapOf<String, OnnxTensor>()
        inputs["input"] = inputTensor
        erbState?.let { inputs["erb_state"] = it }
        dfState?.let { inputs["df_state"] = it }
        
        try {
            // Run inference
            val results = session.run(inputs)
            
            // Get output audio
            val outputTensor = results.get(0) as OnnxTensor
            val output = outputTensor.floatBuffer.array()
            
            // Update states if model provides them
            if (results.size() > 1) {
                erbState?.close()
                erbState = (results.get(1) as? OnnxTensor)
            }
            if (results.size() > 2) {
                dfState?.close()
                dfState = (results.get(2) as? OnnxTensor)
            }
            
            return output.sliceArray(0 until frame.size)
            
        } finally {
            inputTensor.close()
        }
    }
    
    /**
     * Simple linear resampling
     * For production use, consider a higher-quality resampler
     */
    private fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return input
        
        val ratio = toRate.toDouble() / fromRate
        val outputSize = (input.size * ratio).toInt()
        val output = FloatArray(outputSize)
        
        for (i in 0 until outputSize) {
            val srcPos = i / ratio
            val srcIndex = srcPos.toInt()
            val frac = srcPos - srcIndex
            
            if (srcIndex + 1 < input.size) {
                // Linear interpolation
                output[i] = (input[srcIndex] * (1 - frac) + input[srcIndex + 1] * frac).toFloat()
            } else if (srcIndex < input.size) {
                output[i] = input[srcIndex]
            }
        }
        
        return output
    }
    
    /**
     * Enable or disable noise reduction
     */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        Log.d(TAG, "DeepFilterNet ${if (enabled) "enabled" else "disabled"}")
    }
    
    /**
     * Check if noise reduction is enabled
     */
    fun isEnabled(): Boolean = enabled
    
    /**
     * Check if initialized
     */
    fun isInitialized(): Boolean = initialized
    
    /**
     * Get the model file path
     */
    private fun getModelFile(): File {
        val modelsDir = File(context.filesDir, "deepfilter_models")
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
        return File(modelsDir, MODEL_NAME)
    }
    
    /**
     * Extract model from assets to internal storage
     */
    private fun extractModelFromAssets(destFile: File): Boolean {
        return try {
            val assetPath = "$MODEL_DIR/$MODEL_NAME"
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Model extracted to: ${destFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract model: ${e.message}")
            false
        }
    }
    
    /**
     * Reset internal states (call between separate audio sessions)
     */
    fun resetStates() {
        erbState?.close()
        dfState?.close()
        initializeStates()
        Log.d(TAG, "States reset")
    }
    
    /**
     * Release resources
     */
    fun release() {
        erbState?.close()
        dfState?.close()
        ortSession?.close()
        ortEnvironment?.close()
        
        erbState = null
        dfState = null
        ortSession = null
        ortEnvironment = null
        initialized = false
        
        instanceCount--
        Log.d(TAG, "DeepFilterNet released")
    }
}
