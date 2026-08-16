package com.example.elderhelpprototypev01.accessibility

/**
 * Encapsulates the entire parsed state of the current Android screen.
 * Used as input for AI screen analysis.
 *
 * Bug 1 Fix:
 *  - toCompactPromptSummary() and elementByPromptId() now both use UiElement.promptIndex
 *    (assigned at parse time by AccessibilityNodeParser) instead of independently re-filtering
 *    the elements list. This guarantees that "e7" in the prompt ALWAYS maps to the same element.
 */
data class ScreenContext(
    val packageName: String = "",
    val activityName: String = "",
    val screenTitle: String = "",
    val elements: List<UiElement> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
) {

    /**
     * Generates a lightweight fingerprint of the screen state for change detection.
     * Two screens with the same fingerprint have identical visible elements.
     * Used by [ScreenAssistantEngine] to skip duplicate API calls.
     *
     * NOTE: The fingerprint is based on element labels/roles only, NOT on positions.
     * Therefore it MUST NOT be used to re-use physical screen bounds (stale bounds bug).
     */
    fun toFingerprint(): String {
        val key = StringBuilder()
        key.append(packageName).append("|")
        key.append(screenTitle).append("|")
        val labels = promptElements()
            .map { "${it.role}:${it.label.take(40)}" }
            .sorted()
        key.append(labels.joinToString(","))
        return key.toString().hashCode().toString(16)
    }

    /**
     * Returns the elements that were included in the LLM prompt (promptIndex > 0), in order.
     * This is the single stable list used by both toCompactPromptSummary() and elementByPromptId().
     */
    fun promptElements(): List<UiElement> =
        elements.filter { it.promptIndex > 0 }.sortedBy { it.promptIndex }

    /**
     * Returns a compact, token-efficient text summary of all visible UI elements for the LLM prompt.
     *
     * Format per element: `e1. BUTTON "Pay Now" tap [100,200,300,400]`
     * - Stable element IDs (e1, e2...) come directly from UiElement.promptIndex (set at parse time)
     * - Role flags: `tap` = clickable, `edit` = editable, `off` = disabled
     * - Bounds are comma-separated without spaces
     */
    fun toCompactPromptSummary(): String {
        val sb = StringBuilder()
        sb.append("PKG: ").append(packageName).append("\n")
        if (screenTitle.isNotBlank()) sb.append("TITLE: ").append(screenTitle).append("\n")

        val validElements = promptElements()

        if (validElements.isEmpty()) {
            sb.append("ELEMENTS: None detected\n")
        } else {
            sb.append("ELEMENTS (${validElements.size}):\n")
            validElements.forEach { el ->
                val id = "e${el.promptIndex}"
                sb.append("$id. ${el.role}")
                if (el.label.isNotBlank()) sb.append(" \"").append(el.label.take(60)).append("\"")
                if (el.clickable) sb.append(" tap")
                if (el.editable) sb.append(" edit")
                if (!el.enabled) sb.append(" off")
                if (el.isSensitive) sb.append(" PROTECTED")
                sb.append(" [${el.bounds.left},${el.bounds.top},${el.bounds.right},${el.bounds.bottom}]")
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    /**
     * Returns the [UiElement] for a given prompt ID (e.g., "e5").
     * Uses UiElement.promptIndex for O(1) lookup — no re-filtering, no index drift.
     *
     * Bug 1 Fix: This no longer re-computes distinctBy independently; it uses the
     * stable promptIndex assigned at parse time.
     */
    fun elementByPromptId(promptId: String): UiElement? {
        val idx = promptId.removePrefix("e").toIntOrNull() ?: return null
        return elements.firstOrNull { it.promptIndex == idx }
    }

    /** Check if any element on screen is a sensitive field (OTP, PIN, password, CVV) */
    fun hasSensitiveFields(): Boolean = elements.any { el ->
        val label = el.label.lowercase()
        label.contains("otp") || label.contains("pin") || label.contains("password") ||
        label.contains("cvv") || label.contains("security code") || label.contains("verification")
    }

    /** Get sensitive field labels for safety warnings */
    fun getSensitiveFieldLabels(): List<String> = elements
        .filter { el ->
            val label = el.label.lowercase()
            label.contains("otp") || label.contains("pin") || label.contains("password") ||
            label.contains("cvv") || label.contains("security code") || label.contains("verification")
        }
        .map { it.label }
}
