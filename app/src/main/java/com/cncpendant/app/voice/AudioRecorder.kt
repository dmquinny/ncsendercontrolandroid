package com.cncpendant.app.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AudioRecorder captures raw audio from the microphone for processing by
 * Whisper and optionally DeepFilterNet.
 * 
 * Audio format: 16kHz, mono, 16-bit PCM (required by Whisper)
 */
class AudioRecorder {
    
    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000  // 16kHz required by Whisper
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_SAMPLE = 2  // 16-bit = 2 bytes
        
        // Buffer sizes
        private const val CHUNK_DURATION_MS = 100  // 100ms chunks
        val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_DURATION_MS / 1000  // 1600 samples per chunk
        val CHUNK_BYTES = CHUNK_SAMPLES * BYTES_PER_SAMPLE  // 3200 bytes per chunk
        
        // Maximum recording duration (30 seconds for commands)
        const val MAX_RECORDING_DURATION_MS = 30000L
        const val MAX_SAMPLES = SAMPLE_RATE * 30  // 30 seconds of samples
    }
    
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false
    
    private val _audioChunks = MutableSharedFlow<FloatArray>(extraBufferCapacity = 100)
    val audioChunks: SharedFlow<FloatArray> = _audioChunks
    
    // Accumulated audio buffer for complete recordings
    private val audioBuffer = mutableListOf<Float>()
    
    // Silence detection
    private var silenceFrames = 0
    private val SILENCE_THRESHOLD = 0.01f  // RMS threshold for silence
    private val SILENCE_FRAMES_FOR_END = 20  // 2 seconds of silence (20 * 100ms)
    
    // Callback for when recording completes
    var onRecordingComplete: ((FloatArray) -> Unit)? = null
    var onSilenceDetected: (() -> Unit)? = null
    var onSpeechDetected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    /**
     * Get the minimum buffer size required for AudioRecord
     */
    fun getMinBufferSize(): Int {
        return AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    }
    
    /**
     * Check if audio recording is available
     */
    fun isRecordingAvailable(): Boolean {
        val minBufferSize = getMinBufferSize()
        return minBufferSize != AudioRecord.ERROR && minBufferSize != AudioRecord.ERROR_BAD_VALUE
    }
    
    /**
     * Start recording audio
     */
    @SuppressLint("MissingPermission")
    fun startRecording(scope: CoroutineScope): Boolean {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return false
        }
        
        val minBufferSize = getMinBufferSize()
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size: $minBufferSize")
            onError?.invoke("Audio recording not available on this device")
            return false
        }
        
        // Use larger buffer to prevent underruns
        val bufferSize = maxOf(minBufferSize * 2, CHUNK_BYTES * 4)
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,  // Optimized for voice
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                onError?.invoke("Failed to initialize audio recorder")
                audioRecord?.release()
                audioRecord = null
                return false
            }
            
            audioBuffer.clear()
            silenceFrames = 0
            isRecording = true
            
            audioRecord?.startRecording()
            
            recordingJob = scope.launch(Dispatchers.IO) {
                recordAudioLoop()
            }
            
            // Auto-stop after max duration
            scope.launch {
                delay(MAX_RECORDING_DURATION_MS)
                if (isRecording) {
                    Log.d(TAG, "Max recording duration reached")
                    stopRecording()
                }
            }
            
            Log.d(TAG, "Recording started")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            onError?.invoke("Error starting recording: ${e.message}")
            audioRecord?.release()
            audioRecord = null
            isRecording = false
            return false
        }
    }
    
    /**
     * Main recording loop - reads audio chunks and emits them
     */
    private suspend fun recordAudioLoop() {
        val buffer = ShortArray(CHUNK_SAMPLES)
        var speechDetected = false
        
        while (isRecording && audioBuffer.size < MAX_SAMPLES) {
            val read = audioRecord?.read(buffer, 0, CHUNK_SAMPLES) ?: 0
            
            if (read > 0) {
                // Convert shorts to floats (normalized to -1.0 to 1.0)
                val floatBuffer = FloatArray(read) { i ->
                    buffer[i] / 32768f
                }
                
                // Calculate RMS for silence detection
                val rms = calculateRMS(floatBuffer)
                
                if (rms > SILENCE_THRESHOLD) {
                    silenceFrames = 0
                    if (!speechDetected) {
                        speechDetected = true
                        withContext(Dispatchers.Main) {
                            onSpeechDetected?.invoke()
                        }
                    }
                } else {
                    silenceFrames++
                    
                    // Only trigger silence callback if we've already detected speech
                    if (speechDetected && silenceFrames >= SILENCE_FRAMES_FOR_END) {
                        Log.d(TAG, "Silence detected after speech, stopping")
                        withContext(Dispatchers.Main) {
                            onSilenceDetected?.invoke()
                        }
                        break
                    }
                }
                
                // Store in buffer
                audioBuffer.addAll(floatBuffer.toList())
                
                // Emit chunk for real-time processing
                _audioChunks.emit(floatBuffer)
            } else if (read < 0) {
                Log.e(TAG, "AudioRecord read error: $read")
                break
            }
        }
        
        // Recording complete
        withContext(Dispatchers.Main) {
            val finalAudio = audioBuffer.toFloatArray()
            Log.d(TAG, "Recording complete: ${finalAudio.size} samples (${finalAudio.size / SAMPLE_RATE.toFloat()}s)")
            onRecordingComplete?.invoke(finalAudio)
        }
    }
    
    /**
     * Calculate RMS (Root Mean Square) of audio buffer
     */
    private fun calculateRMS(buffer: FloatArray): Float {
        if (buffer.isEmpty()) return 0f
        var sum = 0f
        for (sample in buffer) {
            sum += sample * sample
        }
        return kotlin.math.sqrt(sum / buffer.size)
    }
    
    /**
     * Stop recording
     */
    fun stopRecording() {
        if (!isRecording) return
        
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
        
        audioRecord?.release()
        audioRecord = null
        
        Log.d(TAG, "Recording stopped")
    }
    
    /**
     * Get the accumulated audio buffer as a float array
     */
    fun getRecordedAudio(): FloatArray {
        return audioBuffer.toFloatArray()
    }
    
    /**
     * Check if currently recording
     */
    fun isCurrentlyRecording(): Boolean = isRecording
    
    /**
     * Release resources
     */
    fun release() {
        stopRecording()
    }
    
    /**
     * Convert float array to byte array (for file saving or network transmission)
     */
    fun floatArrayToByteArray(floats: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in floats) {
            buffer.putFloat(f)
        }
        return buffer.array()
    }
    
    /**
     * Convert byte array back to float array
     */
    fun byteArrayToFloatArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) {
            floats[i] = buffer.getFloat()
        }
        return floats
    }
}
