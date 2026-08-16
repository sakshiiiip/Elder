package com.example.elderhelpprototypev01.accessibility

import android.graphics.Rect

/**
 * Represents a single visible UI element extracted from the Android Accessibility node tree.
 *
 * Privacy & Safety Guarantee:
 * Sensitive fields (passwords, PINs, OTPs, CVVs) are automatically flagged with [isSensitive] = true,
 * and their text content is replaced with "[PROTECTED SENSITIVE FIELD]" before being processed by AI.
 */
data class UiElement(
    val id: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val role: String = "UNKNOWN", // e.g. BUTTON, EDIT_TEXT, CHECKBOX, RADIO, TEXT, CARD
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val bounds: Rect = Rect(0, 0, 0, 0),
    val isSensitive: Boolean = false,
    /**
     * Stable prompt index assigned at parse time (1-based, matches "e1", "e2", ... in LLM prompt).
     * -1 means this element was not included in the prompt summary (filtered out).
     * This index is the ONLY reliable way to resolve an LLM-returned targetElementId back to an element.
     * Never re-derive this by re-filtering the element list.
     */
    val promptIndex: Int = -1
) {
    /** Highlighting target identifier helper */
    val label: String
        get() = text.ifBlank { contentDescription.ifBlank { id } }

    /** Returns the prompt ID string (e.g. "e3") if this element is in the prompt, or null otherwise. */
    val promptId: String?
        get() = if (promptIndex > 0) "e$promptIndex" else null
}
