package com.example.elderhelpprototypev01.model

/**
 * Represents the current state of the voice input pipeline.
 */
sealed class VoiceState {
    /** No active voice session */
    object Idle : VoiceState()
    /** Requesting microphone permission from user */
    object RequestingPermission : VoiceState()
    /** Actively listening for speech */
    object Listening : VoiceState()
    /** Partial transcript received (real-time feedback) */
    data class PartialResult(val partial: String) : VoiceState()
    /** Speech recognized, sending to LLM */
    object Processing : VoiceState()
    /** LLM response received and displayed */
    object Done : VoiceState()
    /** Something went wrong — show friendly message */
    data class Error(val message: String) : VoiceState()
}
