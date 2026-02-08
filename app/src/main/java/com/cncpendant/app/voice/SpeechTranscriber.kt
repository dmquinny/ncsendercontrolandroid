package com.cncpendant.app.voice

/**
 * Interface for speech-to-text transcription.
 * Allows swapping between different STT engines (Android, Whisper, etc.)
 */
interface SpeechTranscriber {
    
    /**
     * Check if the transcriber is available on this device
     */
    fun isAvailable(): Boolean
    
    /**
     * Initialize the transcriber. Must be called before transcribe().
     * @return true if initialization successful
     */
    suspend fun initialize(): Boolean
    
    /**
     * Transcribe audio to text
     * @param audioSamples Float array of audio samples (normalized -1.0 to 1.0), 16kHz mono
     * @return Transcribed text, or null if transcription failed
     */
    suspend fun transcribe(audioSamples: FloatArray): String?
    
    /**
     * Get the name of this transcriber for display/logging
     */
    fun getName(): String
    
    /**
     * Release resources
     */
    fun release()
}
