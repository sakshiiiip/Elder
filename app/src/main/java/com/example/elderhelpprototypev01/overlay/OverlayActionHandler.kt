package com.example.elderhelpprototypev01.overlay

/**
 * Stub interface for overlay action callbacks.
 *
 * This interface decouples the overlay UI from all future feature implementations.
 * Future steps will connect:
 *   - Voice recognition  → onVoiceRequested()
 *   - AccessibilityService screen reading  → onReadScreenRequested()
 *   - LLM explanation  → onExplainRequested()
 *   - Emergency/help panel  → onHelpRequested()
 *
 * The overlay view calls these methods but does NOT know about the implementation.
 */
interface OverlayActionHandler {
    fun onVoiceRequested()
    fun onReadScreenRequested()
    fun onExplainRequested()
    fun onHelpRequested()
}

/**
 * Default stub implementation that shows placeholder toasts.
 * Replace method bodies when real features are added.
 */
class StubOverlayActionHandler : OverlayActionHandler {
    override fun onVoiceRequested() {
        // Placeholder – voice recognition not yet implemented
    }
    override fun onReadScreenRequested() {
        // Placeholder – screen reading not yet implemented
    }
    override fun onExplainRequested() {
        // Placeholder – LLM explanation not yet implemented
    }
    override fun onHelpRequested() {
        // Placeholder – help panel not yet implemented
    }
}
