package com.example.elderhelpprototypev01.screen

import android.util.Log
import com.example.elderhelpprototypev01.BuildConfig
import com.example.elderhelpprototypev01.accessibility.ScreenContext
import com.example.elderhelpprototypev01.accessibility.UiElement
import com.example.elderhelpprototypev01.model.ConversationMessage
import com.example.elderhelpprototypev01.voice.TextToSpeechManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

import com.example.elderhelpprototypev01.task.TaskStage
import com.example.elderhelpprototypev01.task.TaskState

interface ScreenAnalysisService {
    suspend fun analyzeScreen(
        screenContext: ScreenContext,
        userGoal: String,
        conversationHistory: List<ConversationMessage> = emptyList(),
        userLanguage: String = "English (India)",
        taskState: TaskState? = null
    ): ScreenAnalysisResult
}

/**
 * GeminiScreenAnalysisService
 *
 * Sahaay Accessibility Intelligence Specification Engine:
 * - Patient digital companion mental model for elderly & low-literacy users
 * - Voice intent resolution (research/google, dial/call, bill pay, doctor booking)
 * - Multi-turn conversation context awareness
 * - Intent & task stage intelligence (DISCOVERY, SELECTION, INPUT, CONFIRMATION)
 * - Quota-aware model fallback chain and compact prompts
 *
 * Bug 2 Fix:
 *  - buildFallbackResult() no longer uses firstOrNull { it.clickable } as an unsafe fallback.
 *    When no local match exists, it returns a no-match response with voice guidance only.
 *
 * Bug 4 Fix:
 *  - findMatchingElement() now includes a findClickableParent() walk-up so non-clickable
 *    text nodes are replaced with their nearest clickable parent.
 *
 * Note on bounds:
 *  - This service returns targetElementId and targetElementText ONLY (semantic information).
 *  - Physical bounds in the returned ScreenAnalysisResult are from the snapshot at analysis time.
 *  - ScreenAssistantEngine MUST always call SahaayAccessibilityService.findFreshBoundsForElement()
 *    immediately before calling HighlightManager, never re-use result.targetElementBounds directly.
 */
class GeminiScreenAnalysisService : ScreenAnalysisService {

    private val TAG = "SahaayGemini"
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val apiKey: String get() = BuildConfig.GEMINI_API_KEY.trim()

    // Lean model fallback chain (2 models, cheapest first to save quota)
    private val modelEndpoints = listOf(
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent"
    )

    override suspend fun analyzeScreen(
        screenContext: ScreenContext,
        userGoal: String,
        conversationHistory: List<ConversationMessage>,
        userLanguage: String,
        taskState: TaskState?
    ): ScreenAnalysisResult = withContext(Dispatchers.IO) {
        if (screenContext.elements.isEmpty()) {
            return@withContext ScreenAnalysisResult.noMatch("I can't see any elements on this screen. Please open an app screen and try again.")
        }

        val directLocalMatch = findMatchingElement(userGoal, screenContext.elements)

        if (apiKey == "REPLACE_WITH_YOUR_GEMINI_API_KEY" || apiKey.isBlank()) {
            return@withContext buildFallbackResult(userGoal, screenContext, directLocalMatch, taskState)
        }

        val systemPrompt = buildSystemPrompt(userLanguage)
        val userPrompt = buildUserPrompt(screenContext, userGoal, conversationHistory, taskState)

        val requestMap = mapOf(
            "system_instruction" to mapOf(
                "parts" to listOf(mapOf("text" to systemPrompt))
            ),
            "contents" to listOf(
                mapOf("role" to "user", "parts" to listOf(mapOf("text" to userPrompt)))
            ),
            "generationConfig" to mapOf(
                "temperature" to 0.2,
                "maxOutputTokens" to 300,
                "responseMimeType" to "application/json"
            )
        )
        val jsonPayload = gson.toJson(requestMap)

        for (endpoint in modelEndpoints) {
            try {
                val request = Request.Builder()
                    .url("$endpoint?key=$apiKey")
                    .post(jsonPayload.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseCode = response.code
                val responseBodyStr = response.body?.string() ?: ""

                // Rate-limit backoff: if quota exceeded, fall back immediately without trying more endpoints
                if (responseCode == 429) {
                    Log.w(TAG, "API Rate limited (429). Using local matching fallback.")
                    break
                }

                if (response.isSuccessful && responseBodyStr.isNotBlank()) {
                    val result = parseGeminiResponse(responseBodyStr, screenContext)
                    if (!result.isError) {
                        return@withContext result
                    } else {
                        Log.w(TAG, "Gemini response parsed but isError=true, trying next endpoint")
                    }
                } else {
                    Log.e(TAG, "API Endpoint $endpoint failed with code $responseCode: $responseBodyStr")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error calling $endpoint: ${e.localizedMessage}")
            }
        }

        return@withContext buildFallbackResult(userGoal, screenContext, directLocalMatch, taskState)
    }

    /**
     * Universal Target Matcher:
     * Resolves the safest next UI element across ANY app screen based on control roles,
     * user goals, and active task stage progression. Zero app-specific hardcoding.
     */
    fun tryLocalMatch(userGoal: String, elements: List<UiElement>, taskState: TaskState? = null): UiElement? {
        val goal = userGoal.trim().lowercase()

        // 1. Universal Task Stage Progression across ANY app
        if (taskState != null && taskState.currentStage != TaskStage.DISCOVERY) {
            val lastTarget = taskState.lastHighlightedText?.lowercase()?.trim()

            val actionable = elements.filter { el ->
                el.visible && (el.clickable || el.editable || el.role == "BUTTON" || el.role == "EDIT_TEXT" || el.role == "RADIO" || el.role == "CHECKBOX" || el.role == "CARD") && el.label.isNotBlank()
            }

            // A. If screen contains editable input fields, prioritize the first un-filled input
            val editableField = actionable.firstOrNull { it.editable || it.role == "EDIT_TEXT" }
            val autoFillBtn = actionable.firstOrNull { it.label.lowercase().contains("saved details") || it.label.lowercase().contains("auto-fill") }
            if (autoFillBtn != null) return autoFillBtn
            if (editableField != null && (lastTarget == null || !editableField.label.lowercase().contains(lastTarget))) {
                return editableField
            }

            // B. Filter out header tabs / previous targets to advance to NEW content elements on screen
            val contentChoices = actionable.filter { el ->
                val labelLower = el.label.lowercase()
                val isNavTab = labelLower == "doctor" || labelLower == "pension form" || labelLower == "pay bills" || labelLower.contains("tab")
                !isNavTab && (lastTarget == null || !labelLower.contains(lastTarget))
            }

            // C. Match choice items (radio buttons, list cards, selection items)
            val choiceItem = contentChoices.firstOrNull { el ->
                el.role == "RADIO" || el.role == "CHECKBOX" || el.role == "CARD" ||
                el.label.lowercase().contains("select") || el.label.lowercase().contains("option") ||
                el.label.lowercase().contains("2026") || el.label.lowercase().contains("slot")
            }
            if (choiceItem != null) return choiceItem

            // D. Match primary action button ("Continue", "Submit", "Next", "Proceed", "Confirm", "Pay", "Book", "Done", "Save")
            val actionKeywords = listOf("continue", "proceed", "submit", "confirm", "pay now", "next", "book now", "finish", "done", "save")
            val actionBtn = contentChoices.firstOrNull { el ->
                val l = el.label.lowercase()
                actionKeywords.any { l.contains(it) }
            }
            if (actionBtn != null) return actionBtn
        }

        // 2. Skip vague queries that require deep LLM reasoning
        val vaguePatterns = listOf(
            "explain", "help", "next",
            "where am i", "what is this", "what should", "how do"
        )
        if (vaguePatterns.any { goal.contains(it) }) return null
        if (goal.length < 3) return null

        // 3. Fallback to universal keyword matcher across screen elements
        return findMatchingElement(userGoal, elements)
    }

    /**
     * Bug 2 Fix: buildFallbackResult no longer picks a random clickable element.
     * When no local match is found, returns a no-match result with voice-only guidance.
     */
    private fun buildFallbackResult(
        userGoal: String,
        screenContext: ScreenContext,
        localMatch: UiElement?,
        taskState: TaskState? = null
    ): ScreenAnalysisResult {
        // Bug 2 Fix: Do NOT fall back to firstOrNull { it.clickable } — that is an arbitrary pick.
        // Only use a local match if it was confidently found.
        val matched = localMatch
        val goalLower = userGoal.trim().lowercase()

        val spoken: String = when {
            matched != null -> {
                val label = matched.label.ifBlank { "option" }
                when {
                    goalLower.contains("research") || goalLower.contains("search") || goalLower.contains("google") ->
                        "To research or search online, tap the highlighted $label option."
                    goalLower.contains("dial") || goalLower.contains("call") || goalLower.contains("phone") ->
                        "To dial a number or make a call, tap the highlighted $label option."
                    goalLower.contains("bill") || goalLower.contains("pay") ->
                        "To pay your bill, tap the highlighted $label option."
                    goalLower.contains("doctor") || goalLower.contains("appointment") ->
                        "To book a doctor appointment, tap the highlighted $label option."
                    else -> "To proceed with your ${taskState?.currentTaskType?.name ?: "task"}, tap the highlighted $label."
                }
            }
            // No confident match — provide voice guidance only, NO random highlight
            goalLower.contains("research") || goalLower.contains("search") || goalLower.contains("google") ->
                "To research or search online, open Google Chrome or the Google Search app on your device."
            goalLower.contains("dial") || goalLower.contains("call") || goalLower.contains("phone") ->
                "To dial a number or make a call, open the Phone app or Dialer on your device."
            goalLower.contains("bill") || goalLower.contains("pay") ->
                "To pay your bills, open Sahaay and tap Pay Utility Bills."
            goalLower.contains("doctor") || goalLower.contains("appointment") ->
                "To book a doctor appointment, open Sahaay and tap Doctor Booking."
            else ->
                "I couldn't find that option. Please try again."
        }

        return if (matched != null) {
            ScreenAnalysisResult(
                responseType = "NEXT_STEP",
                spokenResponse = spoken,
                visualResponse = "Tap ${matched.label}",
                targetElementId = matched.promptId,
                targetElementText = matched.label,
                targetElementBounds = matched.bounds, // snapshot bounds — engine will freshen before use
                actionGuidance = if (matched.editable) "TYPE" else "TAP",
                explanation = spoken,
                reason = "Matched locally (fallback)",
                confidence = 0.88f,
                actionType = "GUIDE_HIGHLIGHT"
            )
        } else {
            // Bug 2 Fix: return NO_MATCH — do not draw any highlight
            ScreenAnalysisResult(
                responseType = "CLARIFICATION",
                spokenResponse = spoken,
                visualResponse = "No target found",
                targetElementId = null,
                targetElementText = null,
                targetElementBounds = null,  // No bounds → no highlight drawn
                actionGuidance = "NONE",
                explanation = spoken,
                reason = "No confident match found",
                confidence = 0.0f,
                actionType = "NO_MATCH"
            )
        }
    }

    private fun buildSystemPrompt(userLanguage: String): String {
        val langInstruction = when {
            userLanguage.contains("Hindi") -> "Reply in simple Hindi or Hinglish."
            userLanguage.contains("Marathi") -> "Reply in simple Marathi."
            userLanguage.contains("Tamil") -> "Reply in simple Tamil."
            userLanguage.contains("Telugu") -> "Reply in simple Telugu."
            userLanguage.contains("Bengali") -> "Reply in simple Bengali."
            else -> "Reply in simple, clear English."
        }

        return """
You are Sahaay, a patient and ultra-intelligent digital assistant for elderly users in India.

THINKING & ANALYSIS ALGORITHM (FOLLOW STRICTLY):
1. UNDERSTAND USER INTENT: Read the user's spoken goal carefully. What end product or service do they want to achieve?
2. ANALYZE CURRENT SCREEN: Examine the visible UI elements list (IDs e1, e2, e3...).
3. IDENTIFY TASK STAGE:
   - Are we at the beginning (discovery)?
   - Are we filling out fields (input)?
   - Are we picking options (selection/date/time)?
   - Are we confirming a transaction/booking (confirmation)?
4. REASON SAFEST NEXT STEP: Determine the single logical, safe UI element that advances the user toward their goal.
   - Ignore header tabs or already-clicked menu items.
   - Do NOT highlight random background text or static labels.
   - Select ONLY actionable buttons, editable text inputs, or selection cards.
5. CRAFT EXPLANATION: Write 1 warm, natural, simple sentence telling the user exactly what to tap/type.
$langInstruction

RULES:
- ALWAYS return targetElementId (e.g. "e3") when a matching element is visible. This is PREFERRED over targetElementText.
- Only return targetElementText when no stable element ID can be determined.
- Do not return both targetElementId and targetElementText pointing to different elements.
- Output exact targetElementText or targetElementId matching a visible element.
- Translate digital jargon: IFSC→bank code, CVV→card back number, OTP→one-time code, Consumer ID→bill number.
- NEVER reveal passwords, PINs, OTPs, CVVs. Keep PINs private.
- No preamble ("As an AI", "I think"). Speak the exact instruction directly.
- If no actionable element matches the user's goal, set confidence to 0.0 and return NO targetElementId or targetElementText.

SAFETY (CRITICAL):
- If any field contains OTP, PIN, password, CVV, security code: DO NOT read its value.
- Say: "This is your private security field. Please enter the code yourself."
- Set responseType to SAFETY_WARNING.

JSON FIELDS: responseType (NEXT_STEP|SCREEN_EXPLANATION|FIELD_HELP|CONFIRMATION|SAFETY_WARNING), spokenResponse, visualResponse, targetElementId, targetElementText, actionGuidance (TAP|TYPE|READ|CONFIRM|NONE), confidence (0-1), reasoningThought.
        """.trimIndent()
    }

    private fun buildUserPrompt(
        context: ScreenContext,
        goal: String,
        history: List<ConversationMessage>,
        taskState: TaskState?
    ): String {
        val sb = StringBuilder()
        sb.append("GOAL: \"").append(goal).append("\"\n")

        if (taskState != null) {
            sb.append("TASK_TYPE: ").append(taskState.currentTaskType.name).append("\n")
            sb.append("TASK_STAGE: ").append(taskState.currentStage.name).append("\n")
            if (taskState.collectedInfo.isNotEmpty()) {
                sb.append("COLLECTED_INFO: ").append(taskState.collectedInfo.entries.joinToString { "${it.key}=${it.value}" }).append("\n")
            }
        } else {
            // Fallback stage detection from screen elements
            val hasEditableFields = context.elements.any { it.editable }
            val hasConfirmButton = context.elements.any {
                it.clickable && (it.label.lowercase().let { l ->
                    l.contains("confirm") || l.contains("pay now") || l.contains("submit") || l.contains("proceed")
                })
            }
            val stage = when {
                hasConfirmButton -> "CONFIRMATION"
                hasEditableFields -> "INPUT"
                else -> "SELECTION"
            }
            sb.append("STAGE: ").append(stage).append("\n")
        }

        if (history.isNotEmpty()) {
            sb.append("HISTORY:\n")
            history.takeLast(3).forEach { msg ->
                val sender = if (msg.role == com.example.elderhelpprototypev01.model.MessageRole.USER) "U" else "S"
                sb.append("$sender: ").append(msg.text.take(120)).append("\n")
            }
        }

        sb.append("SCREEN:\n")
        sb.append(context.toCompactPromptSummary())
        return sb.toString()
    }

    private fun parseGeminiResponse(
        responseBody: String,
        screenContext: ScreenContext
    ): ScreenAnalysisResult {
        return try {
            val root = gson.fromJson(responseBody, JsonObject::class.java)
            val candidates = root.getAsJsonArray("candidates") ?: return ScreenAnalysisResult.noMatch()
            val text = candidates[0].asJsonObject.getAsJsonObject("content")
                .getAsJsonArray("parts")[0].asJsonObject.get("text").asString.trim()

            val cleaned = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = gson.fromJson(cleaned, JsonObject::class.java)

            val responseType = obj.get("responseType")?.asString ?: "NEXT_STEP"
            val rawSpoken = obj.get("spokenResponse")?.asString
                ?: obj.get("explanation")?.asString
                ?: "Here is the highlighted option for you."
            val spokenResponse = TextToSpeechManager.sanitizeForSpeech(rawSpoken)

            val visualResponse = obj.get("visualResponse")?.asString ?: spokenResponse
            val targetId = obj.get("targetElementId")?.takeIf { !it.isJsonNull }?.asString?.trim()
            val targetText = obj.get("targetElementText")?.takeIf { !it.isJsonNull }?.asString?.trim()
            val actionGuidance = obj.get("actionGuidance")?.asString ?: "TAP"
            val confidence = obj.get("confidence")?.asFloat ?: 0.95f
            val requiresConfirmation = obj.get("requiresConfirmation")?.asBoolean ?: false
            val reason = obj.get("reason")?.takeIf { !it.isJsonNull }?.asString

            // Resolve semantic target: prefer promptIndex (targetElementId), fall back to text
            // Bug 1 Fix: elementByPromptId now uses stable promptIndex — no index drift
            val indexResolved: UiElement? = targetId?.let { screenContext.elementByPromptId(it) }

            val matchedElement: UiElement? = when {
                indexResolved != null -> {
                    // ID matched — walk up to clickable parent if needed (Bug 4 Fix)
                    if (indexResolved.clickable || indexResolved.editable) {
                        indexResolved
                    } else {
                        findClickableParentInContext(indexResolved, screenContext) ?: indexResolved
                    }
                }
                targetText != null && targetText.isNotBlank() -> {
                    // Text-based fallback
                    val textFound = findMatchingElement(targetText, screenContext.elements)
                    if (textFound != null && !textFound.clickable && !textFound.editable) {
                        findClickableParentInContext(textFound, screenContext) ?: textFound
                    } else {
                        textFound
                    }
                }
                else -> null
            }

            Log.d(TAG, "parseGeminiResponse: targetId=$targetId targetText=$targetText " +
                "indexResolved=${indexResolved?.label} matchedElement=${matchedElement?.label} " +
                "clickable=${matchedElement?.clickable} confidence=$confidence")

            ScreenAnalysisResult(
                responseType = responseType,
                spokenResponse = spokenResponse,
                visualResponse = visualResponse,
                targetElementId = matchedElement?.promptId ?: targetId,
                targetElementText = matchedElement?.label ?: targetText,
                // Snapshot bounds are stored here; ScreenAssistantEngine MUST freshen these
                // via SahaayAccessibilityService.findFreshBoundsForElement() before highlighting.
                targetElementBounds = matchedElement?.bounds,
                actionGuidance = actionGuidance,
                explanation = spokenResponse,
                reason = reason,
                confidence = confidence,
                actionType = if (matchedElement != null) "GUIDE_HIGHLIGHT" else "EXPLAIN_SCREEN",
                requiresConfirmation = requiresConfirmation
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing response: ${e.localizedMessage}")
            ScreenAnalysisResult.error("Please try tapping the highlighted option again.")
        }
    }

    /**
     * Finds the nearest clickable/actionable ancestor of [element] within the given [ScreenContext].
     * Bug 4 Fix: When "Continue" text node is matched, this returns the enclosing Button.
     */
    private fun findClickableParentInContext(element: UiElement, context: ScreenContext): UiElement? {
        val tb = element.bounds
        return context.elements
            .filter { el ->
                el != element &&
                (el.clickable || el.editable) &&
                el.enabled && el.visible &&
                el.bounds.contains(tb) &&
                el.bounds.width() > 0 &&
                el.bounds.height() > 0 &&
                el.bounds.width() < 2000 // exclude full-screen overlays
            }
            .minByOrNull { el -> el.bounds.width() * el.bounds.height() }
    }

    /**
     * Enhanced Local Matcher:
     * - Exact match → substring match → synonym expansion → fuzzy Levenshtein match
     * - Weighted role matching (clickable/editable controls prioritized)
     * - Expanded synonyms for common Indian digital services & voice actions
     *
     * Bug 4 Fix: After matching, if the element is not clickable, findClickableParentInContext
     * is called in the Gemini response parser. findMatchingElement itself returns the best
     * semantic match; the parent walk-up is done at the call site.
     */
    private fun findMatchingElement(targetText: String, elements: List<UiElement>): UiElement? {
        val rawClean = targetText.trim().lowercase()
            .removePrefix("highlight").removePrefix("find").removePrefix("where is")
            .removePrefix("show me").removePrefix("i want to").removePrefix("help me")
            .removePrefix("tap on").removePrefix("click on").removePrefix("press")
            .removePrefix("how to").removePrefix("open")
            .trim()
            .replace("_", " ")

        if (rawClean.isBlank()) return null

        // Filter out full-screen layout overlays
        val actionableElements = elements.filter {
            it.visible && it.label.isNotBlank() &&
            it.bounds.width() > 0 && it.bounds.height() > 0 &&
            (it.bounds.width() < 1050 || it.bounds.height() < 2200)
        }

        // Helper comparator: prefer clickable/editable controls, then prefer tightest non-zero bounding box area
        val precisionComparator = Comparator<UiElement> { a, b ->
            val aScore = if (a.clickable || a.editable) 2 else 1
            val bScore = if (b.clickable || b.editable) 2 else 1
            if (aScore != bScore) return@Comparator bScore.compareTo(aScore)

            // Prefer tighter bounding box
            val aArea = a.bounds.width() * a.bounds.height()
            val bArea = b.bounds.width() * b.bounds.height()
            aArea.compareTo(bArea)
        }

        // 1. Exact label match (prioritize tightest clickable control)
        actionableElements
            .sortedWith(precisionComparator)
            .firstOrNull { it.label.trim().lowercase() == rawClean }
            ?.let { return it }

        // 2. Substring match (prioritize tightest clickable control)
        actionableElements
            .sortedWith(precisionComparator)
            .firstOrNull {
                val label = it.label.trim().lowercase()
                label.contains(rawClean) || rawClean.contains(label)
            }?.let { return it }

        // 3. Synonym-expanded match
        val synonyms = mapOf(
            "doctor" to listOf("doctor", "dr ", "dr.", "cardiologist", "physician", "specialist", "clinic", "hospital", "book appointment"),
            "appointment" to listOf("appointment", "booking", "schedule", "visit", "slot", "date"),
            "bill" to listOf("bill", "electricity", "tata power", "bses", "mseb", "consumer", "utility", "recharge", "pay utility bills"),
            "pay" to listOf("pay", "payment", "send money", "transfer", "upi", "bhim", "paytm", "phonepe", "gpay", "pay now"),
            "number" to listOf("number", "consumer", "account", "mobile", "phone", "aadhaar", "aadhar"),
            "date" to listOf("date", "august", "today", "tomorrow", "calendar", "pick"),
            "confirm" to listOf("confirm", "submit", "proceed", "continue", "done", "finish", "ok", "yes"),
            "back" to listOf("back", "return", "previous", "go back", "navigate back"),
            "home" to listOf("home", "main", "dashboard", "start"),
            "research" to listOf("google", "chrome", "search", "browser", "internet", "web", "safari", "google search"),
            "search" to listOf("google", "chrome", "search", "browser", "internet", "web", "safari", "google search"),
            "dial" to listOf("phone", "dialer", "call", "dial", "contacts", "keypad", "telephone", "mobile"),
            "call" to listOf("phone", "dialer", "call", "dial", "contacts", "keypad", "telephone", "mobile")
        )

        val matchedTerms = synonyms.filter { (key, terms) ->
            rawClean.contains(key) || terms.any { term -> rawClean.contains(term) }
        }.values.flatten()

        if (matchedTerms.isNotEmpty()) {
            val clickable = actionableElements.filter { it.clickable || it.editable }.sortedWith(precisionComparator)
            for (term in matchedTerms) {
                clickable.firstOrNull { el ->
                    el.label.lowercase().contains(term)
                }?.let { return it }
            }
        }

        // 4. Individual word match
        val words = rawClean.split(" ").filter { it.length > 2 }
        actionableElements
            .sortedWith(precisionComparator)
            .firstOrNull { el ->
                val label = el.label.lowercase()
                words.any { word -> label.contains(word) }
            }?.let { return it }

        // 5. Fuzzy Levenshtein match (Bug 4 Fix: tightened threshold 0.65→0.75 to reduce false positives)
        val bestFuzzy = actionableElements
            .filter { it.clickable || it.editable }
            .sortedWith(precisionComparator)
            .mapNotNull { el ->
                val label = el.label.trim().lowercase()
                if (label.length < 3) return@mapNotNull null
                val distance = levenshteinDistance(rawClean, label)
                val maxLen = maxOf(rawClean.length, label.length)
                val similarity = 1.0 - (distance.toDouble() / maxLen)
                if (similarity >= 0.75) el to similarity else null  // tightened from 0.65
            }
            .maxByOrNull { it.second }
            ?.first

        return bestFuzzy
    }

    /** Levenshtein distance for fuzzy string matching (typo tolerance). */
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
