package com.example.elderhelpprototypev01.model

/**
 * Represents a single message in the Sahaay conversation history.
 * Kept in-memory only — no database persistence in V0.1.
 */
data class ConversationMessage(
    val role: MessageRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class MessageRole {
    USER,
    ASSISTANT
}
