package com.example.elderhelpprototypev01.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SahaayAccessibilityService
 *
 * Android AccessibilityService that enables Sahaay to inspect the active UI screen,
 * extract visible elements, and detect user touch/click interaction events.
 *
 * Used strictly for GUIDANCE and HIGHLIGHTING.
 * Does NOT execute autonomous clicks, payments, or sensitive actions.
 *
 * Bug 3 / Bug 5 Fix:
 *  - TYPE_VIEW_SCROLLED is now handled to refresh the screen context and invalidate stale state.
 *  - findFreshBoundsForElement() is the authoritative source for current screen coordinates.
 *    It ALWAYS re-queries the live accessibility tree immediately before a highlight is drawn.
 */
class SahaayAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SahaayA11y"

        private var _instance: SahaayAccessibilityService? = null
        val instance: SahaayAccessibilityService? get() = _instance

        private val _currentScreenContext = MutableStateFlow<ScreenContext?>(null)
        val currentScreenContext: StateFlow<ScreenContext?> = _currentScreenContext.asStateFlow()

        // Emits whenever the user clicks/taps an element or the screen content changes
        private val _userInteractionEvents = MutableSharedFlow<Long>(extraBufferCapacity = 8)
        val userInteractionEvents: SharedFlow<Long> = _userInteractionEvents.asSharedFlow()

        private var lastEventTime = 0L

        fun isServiceEnabled(context: Context): Boolean {
            val prefString = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return prefString.contains(context.packageName)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance = this
        captureCurrentScreenContext()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val now = System.currentTimeMillis()

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                captureCurrentScreenContext(event.packageName?.toString() ?: "")

                // Debounce rapid events within 600ms
                if (now - lastEventTime > 600L &&
                    (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                     event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                ) {
                    lastEventTime = now
                    _userInteractionEvents.tryEmit(now)
                }
            }

            // Bug 5 Fix: Refresh screen context on scroll so fingerprint cache is invalidated.
            // Scroll events change element positions but not labels, so the fingerprint alone
            // would not detect the change. By refreshing the context here, the engine will
            // know the layout has changed and re-query fresh bounds before any highlight.
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Debounce scroll refreshes to avoid excessive re-parsing
                if (now - lastEventTime > 300L) {
                    lastEventTime = now
                    captureCurrentScreenContext(event.packageName?.toString() ?: "")
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        _instance = null
        _currentScreenContext.value = null
        super.onDestroy()
    }

    fun captureCurrentScreenContext(pkgName: String = ""): ScreenContext? {
        val root = rootInActiveWindow ?: return _currentScreenContext.value
        val actualPkgName = if (pkgName.isNotBlank()) pkgName else (root.packageName?.toString() ?: "")

        val context = AccessibilityNodeParser.parseTree(
            rootNode = root,
            packageName = actualPkgName
        )
        _currentScreenContext.value = context
        return context
    }

    /**
     * Finds fresh, current screen bounds for a target element by re-querying the live
     * accessibility tree immediately before the highlight is drawn.
     *
     * Bug 3 Fix: This is the ONLY source of bounds that should be passed to HighlightManager.
     * Never use bounds cached in ScreenAnalysisResult.targetElementBounds.
     *
     * Priority:
     *   1. Match by promptIndex (most reliable — stable ID from parse time)
     *   2. Match by exact text
     *   3. Match by exact contentDescription
     *   Then walk up to nearest clickable/enabled ancestor (Bug 4 Fix).
     *
     * @param promptIndex  The stable eN index from the LLM response (-1 to skip)
     * @param fallbackText Text or contentDescription to match as fallback
     * @return Fresh Rect from getBoundsInScreen(), or null if not reliably found
     */
    fun findFreshBoundsForElement(promptIndex: Int, fallbackText: String): Rect? {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "findFreshBoundsForElement: rootInActiveWindow is null")
            return null
        }

        return try {
            // Re-parse the current live tree
            val freshContext = AccessibilityNodeParser.parseTree(
                rootNode = root,
                packageName = root.packageName?.toString() ?: ""
            )

            // Step 1: Find the semantic target element from the fresh context
            val targetElement: UiElement? = when {
                promptIndex > 0 -> {
                    // Primary: match by stable promptIndex
                    freshContext.elements.firstOrNull { it.promptIndex == promptIndex }
                        ?: run {
                            // Fallback within fresh context: try text match
                            Log.w(TAG, "findFreshBoundsForElement: promptIndex=$promptIndex not found in fresh tree, trying text fallback")
                            findByTextInContext(fallbackText, freshContext)
                        }
                }
                fallbackText.isNotBlank() -> findByTextInContext(fallbackText, freshContext)
                else -> null
            }

            if (targetElement == null) {
                Log.w(TAG, "findFreshBoundsForElement: No element found for promptIndex=$promptIndex text='$fallbackText'")
                return null
            }

            // Step 2: If target is not clickable/actionable, walk up to the nearest clickable ancestor
            // Bug 4 Fix: Never highlight a tiny non-clickable text node when a clickable parent exists
            val actionableElement = if (targetElement.clickable || targetElement.editable) {
                targetElement
            } else {
                findClickableAncestor(targetElement, freshContext.elements) ?: targetElement
            }

            // Step 3: Get fresh bounds from the live AccessibilityNodeInfo
            val freshBounds = findFreshNodeBounds(root, actionableElement)

            Log.d(TAG, """
                ┌─ FRESH BOUNDS LOOKUP ──────────────────────
                │ promptIndex   = $promptIndex
                │ fallbackText  = '$fallbackText'
                │ targetElement = '${targetElement.label}' (${targetElement.role})
                │ targetBounds  = ${targetElement.bounds}
                │ actionable    = '${actionableElement.label}' (${actionableElement.role})
                │ clickable     = ${actionableElement.clickable}
                │ editable      = ${actionableElement.editable}
                │ freshBounds   = $freshBounds
                └────────────────────────────────────────────
            """.trimIndent())

            freshBounds
        } catch (e: Exception) {
            Log.e(TAG, "findFreshBoundsForElement exception: ${e.localizedMessage}")
            null
        }
    }

    /**
     * Finds an element in the given context by text or contentDescription match.
     * Prefers clickable/editable elements. Exact match wins over substring match.
     */
    private fun findByTextInContext(text: String, context: ScreenContext): UiElement? {
        if (text.isBlank()) return null
        val normalized = text.trim().lowercase()

        val candidates = context.elements.filter {
            it.visible && it.bounds.width() > 0 && it.bounds.height() > 0
        }

        // Exact match first (prefer clickable)
        candidates.sortedByDescending { if (it.clickable || it.editable) 1 else 0 }
            .firstOrNull { it.label.trim().lowercase() == normalized }
            ?.let { return it }

        // Substring match
        candidates.sortedByDescending { if (it.clickable || it.editable) 1 else 0 }
            .firstOrNull { it.label.trim().lowercase().contains(normalized) }
            ?.let { return it }

        return null
    }

    /**
     * Finds the nearest clickable/actionable ancestor of [target] by spatial containment.
     * Bug 4 Fix: When "Continue" text node is matched but the Button parent is clickable,
     * this returns the Button so its full bounds are used.
     *
     * Strategy: Look for elements whose bounds fully contain target's bounds and are clickable.
     * Among those, pick the one with the smallest area (tightest containing clickable).
     */
    private fun findClickableAncestor(target: UiElement, allElements: List<UiElement>): UiElement? {
        val tb = target.bounds
        return allElements
            .filter { el ->
                el != target &&
                (el.clickable || el.editable) &&
                el.enabled && el.visible &&
                el.bounds.contains(tb) &&           // ancestor fully contains target
                el.bounds.width() > 0 &&
                el.bounds.height() > 0 &&
                el.bounds.width() < 2000            // exclude full-screen layout overlays
            }
            .minByOrNull { el ->
                // Pick the tightest (smallest area) ancestor
                el.bounds.width() * el.bounds.height()
            }
    }

    /**
     * Re-queries the live accessibility tree to find the current [AccessibilityNodeInfo]
     * matching [target] and returns its fresh getBoundsInScreen() rect.
     *
     * This prevents using stale Rect objects from a previous parse.
     */
    private fun findFreshNodeBounds(root: AccessibilityNodeInfo, target: UiElement): Rect? {
        val result = Rect()
        val found = findNodeInTree(root, target, result)
        return if (found) result else {
            // Fallback: use the bounds from the fresh UiElement (still from this parse cycle)
            Log.w(TAG, "findFreshNodeBounds: live node not found, using fresh-parse bounds for '${target.label}'")
            if (target.bounds.width() > 0 && target.bounds.height() > 0) target.bounds else null
        }
    }

    private fun findNodeInTree(
        node: AccessibilityNodeInfo,
        target: UiElement,
        outBounds: Rect
    ): Boolean {
        val nodeBounds = Rect()
        node.getBoundsInScreen(nodeBounds)

        // Match by viewIdResourceName + label, or just label + bounds overlap
        val nodeText = (node.text?.toString() ?: "").trim()
        val nodeDesc = (node.contentDescription?.toString() ?: "").trim()
        val nodeId = node.viewIdResourceName ?: ""
        val targetLabel = target.label

        val textMatches = nodeText.equals(targetLabel, ignoreCase = true) ||
                          nodeDesc.equals(targetLabel, ignoreCase = true)
        val idMatches = target.id.isNotBlank() && nodeId == target.id
        val boundsMatch = nodeBounds == target.bounds

        if ((textMatches || idMatches) && (boundsMatch || Rect.intersects(nodeBounds, target.bounds))) {
            node.getBoundsInScreen(outBounds)
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childFound = findNodeInTree(child, target, outBounds)
            child.recycle()
            if (childFound) return true
        }
        return false
    }
}
