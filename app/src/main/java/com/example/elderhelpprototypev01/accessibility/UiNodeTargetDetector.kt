package com.example.elderhelpprototypev01.accessibility

import android.graphics.Rect
import android.util.Log

/**
 * UiNodeTargetDetector
 *
 * Modular target detector responsible for identifying corresponding clickable / actionable
 * UI nodes from user voice instructions (e.g. "Tap Payments", "Tap Settings", "Open Phone",
 * "Tap Electricity Bill", "What should I do next?").
 *
 * Responsibilities:
 *  - Strips action verbs and conversational prefixes.
 *  - Matches target against visible UiElement nodes using visible text, content description, and synonyms.
 *  - Walks up to the nearest clickable / actionable ancestor so the ENTIRE interactive element
 *    (Button, Card, clickable Layout) is outlined, not just an inner text fragment.
 *  - Never guesses coordinates or returns arbitrary bounding boxes. Returns null when not found.
 */
object UiNodeTargetDetector {

    private const val TAG = "UiNodeTargetDetector"

    private val ACTION_PREFIXES = listOf(
        "tap on the", "tap on", "tap the", "tap",
        "click on the", "click on", "click the", "click",
        "press on the", "press on", "press the", "press",
        "select the", "select", "choose the", "choose",
        "open the", "open", "go to the", "go to",
        "navigate to", "show me the", "show me", "where is the", "where is",
        "help me with", "help me find", "i want to pay", "i want to book", "i want to",
        "please tap", "please click", "please open", "please select"
    )

    private val SYNONYM_DICTIONARY = mapOf(
        "payments" to listOf("payments", "payment", "pay", "pay bills", "pay now", "upi", "bhim", "send money", "transfer"),
        "electricity" to listOf("electricity", "electricity bill", "power", "tata power", "bses", "mseb", "electric", "bijli"),
        "bill" to listOf("bill", "bills", "utility", "pay utility bills", "electricity", "water", "gas", "recharge"),
        "settings" to listOf("settings", "setting", "preference", "preferences", "options", "configure", "gear"),
        "phone" to listOf("phone", "dialer", "call", "dial", "contacts", "keypad", "telephone", "mobile"),
        "doctor" to listOf("doctor", "doctor appointment", "book appointment", "dr", "dr.", "clinic", "hospital", "physician", "consultation"),
        "appointment" to listOf("appointment", "booking", "schedule", "slot", "visit", "date", "calendar"),
        "pension" to listOf("pension", "pension form", "form", "apply", "scholarship", "scheme"),
        "help" to listOf("help", "support", "guide", "emergency", "sos", "assistance"),
        "home" to listOf("home", "main", "dashboard", "back to home"),
        "profile" to listOf("profile", "my account", "details", "user", "personal info"),
        "confirm" to listOf("confirm", "proceed", "continue", "submit", "next", "ok", "yes", "done", "save"),
        "back" to listOf("back", "previous", "return", "go back", "cancel")
    )

    /**
     * Finds the matching UI element from [context] for the given [userInstruction].
     *
     * @param userInstruction Natural voice instruction (e.g., "Tap Payments", "Tap Electricity Bill")
     * @param context Current parsed screen context
     * @return The matched actionable [UiElement], or null if no confident match is found.
     */
    fun findTarget(userInstruction: String, context: ScreenContext): UiElement? {
        if (userInstruction.isBlank() || context.elements.isEmpty()) return null

        val query = extractTargetQuery(userInstruction)
        if (query.isBlank()) return null

        Log.d(TAG, "Resolving target for query '$query' (original: '$userInstruction') across ${context.elements.size} elements")

        // 1. Actionable & visible candidates with non-zero dimensions
        val candidates = context.elements.filter { el ->
            el.visible && el.label.isNotBlank() &&
            el.bounds.width() > 0 && el.bounds.height() > 0 &&
            !el.isSensitive
        }

        if (candidates.isEmpty()) return null

        // 2. Multi-tier resolution
        val matched = findExactMatch(query, candidates)
            ?: findSubstringMatch(query, candidates)
            ?: findSynonymMatch(query, candidates)
            ?: findWordMatch(query, candidates)
            ?: findFuzzyMatch(query, candidates)

        if (matched == null) {
            Log.w(TAG, "No matching UI node found for query '$query'")
            return null
        }

        // 3. Clickable parent elevation: If the matched element is not clickable/editable (e.g. inner text),
        // walk up to the enclosing clickable container (e.g. Button, Card) so the ENTIRE interactive element is targeted.
        val actionableTarget = if (matched.clickable || matched.editable) {
            matched
        } else {
            findClickableAncestor(matched, context.elements) ?: matched
        }

        Log.d(TAG, "Target resolved: '${actionableTarget.label}' (role=${actionableTarget.role}, clickable=${actionableTarget.clickable}, bounds=${actionableTarget.bounds})")
        return actionableTarget
    }

    /**
     * Strips leading action phrases (e.g. "Tap ", "Click ", "Open ") to isolate the target label.
     */
    fun extractTargetQuery(instruction: String): String {
        var clean = instruction.trim().lowercase()
            .replace(Regex("[?!.,;:\"\']"), "")
            .trim()

        for (prefix in ACTION_PREFIXES) {
            if (clean.startsWith(prefix)) {
                clean = clean.removePrefix(prefix).trim()
                break
            }
        }
        return clean
    }

    /**
     * Exact label or contentDescription match (case-insensitive).
     */
    private fun findExactMatch(query: String, candidates: List<UiElement>): UiElement? {
        return candidates
            .sortedWith(preferenceComparator)
            .firstOrNull { it.label.trim().lowercase() == query }
    }

    /**
     * Substring match where query contains the label or label contains query.
     */
    private fun findSubstringMatch(query: String, candidates: List<UiElement>): UiElement? {
        return candidates
            .sortedWith(preferenceComparator)
            .firstOrNull {
                val label = it.label.trim().lowercase()
                label.contains(query) || (query.length >= 4 && query.contains(label))
            }
    }

    /**
     * Match based on synonym terms.
     */
    private fun findSynonymMatch(query: String, candidates: List<UiElement>): UiElement? {
        val matchingSynonymGroups = SYNONYM_DICTIONARY.filter { (key, terms) ->
            query.contains(key) || terms.any { term -> query.contains(term) }
        }.values.flatten()

        if (matchingSynonymGroups.isEmpty()) return null

        val prioritized = candidates.sortedWith(preferenceComparator)
        for (term in matchingSynonymGroups) {
            prioritized.firstOrNull { el ->
                el.label.trim().lowercase().contains(term)
            }?.let { return it }
        }

        return null
    }

    /**
     * Match by significant individual words (length > 2).
     */
    private fun findWordMatch(query: String, candidates: List<UiElement>): UiElement? {
        val words = query.split(" ").filter { it.length > 2 }
        if (words.isEmpty()) return null

        return candidates
            .sortedWith(preferenceComparator)
            .firstOrNull { el ->
                val label = el.label.lowercase()
                words.any { word -> label.contains(word) }
            }
    }

    /**
     * Fuzzy Levenshtein string match with strict threshold (>= 0.75).
     */
    private fun findFuzzyMatch(query: String, candidates: List<UiElement>): UiElement? {
        if (query.length < 3) return null

        return candidates
            .filter { it.clickable || it.editable }
            .sortedWith(preferenceComparator)
            .mapNotNull { el ->
                val label = el.label.trim().lowercase()
                if (label.length < 3) return@mapNotNull null
                val distance = levenshteinDistance(query, label)
                val maxLen = maxOf(query.length, label.length)
                val similarity = 1.0 - (distance.toDouble() / maxLen)
                if (similarity >= 0.75) el to similarity else null
            }
            .maxByOrNull { it.second }
            ?.first
    }

    /**
     * Finds the nearest clickable/actionable ancestor enclosing [target] by spatial containment.
     * Selects the tightest bounding box among all containing clickable elements.
     */
    fun findClickableAncestor(target: UiElement, allElements: List<UiElement>): UiElement? {
        val tb = target.bounds
        return allElements
            .filter { el ->
                el != target &&
                (el.clickable || el.editable) &&
                el.enabled && el.visible &&
                el.bounds.contains(tb) &&
                el.bounds.width() > 0 &&
                el.bounds.height() > 0 &&
                el.bounds.width() < 2000 // exclude full-screen root containers
            }
            .minByOrNull { el ->
                el.bounds.width() * el.bounds.height()
            }
    }

    /**
     * Comparator that prefers clickable/editable elements, then selects the tightest area.
     */
    private val preferenceComparator = Comparator<UiElement> { a, b ->
        val aActionable = if (a.clickable || a.editable) 2 else 1
        val bActionable = if (b.clickable || b.editable) 2 else 1
        if (aActionable != bActionable) return@Comparator bActionable.compareTo(aActionable)

        val aArea = a.bounds.width() * a.bounds.height()
        val bArea = b.bounds.width() * b.bounds.height()
        aArea.compareTo(bArea)
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (s1[i - 1] == s2[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + 1)
                }
            }
        }
        return dp[m][n]
    }
}
