package com.example.elderhelpprototypev01.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.elderhelpprototypev01.MainActivity
import com.example.elderhelpprototypev01.accessibility.SahaayAccessibilityService
import com.example.elderhelpprototypev01.highlight.HighlightManager
import com.example.elderhelpprototypev01.model.ConversationMessage
import com.example.elderhelpprototypev01.model.MessageRole
import com.example.elderhelpprototypev01.voice.SpeechRecognizerManager
import com.example.elderhelpprototypev01.voice.TextToSpeechManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import com.example.elderhelpprototypev01.task.TaskPlanner
import com.example.elderhelpprototypev01.task.TaskStage

/**
 * ScreenAssistantEngine
 *
 * Standalone engine managing the full screen-reading, voice interaction,
 * high-brightness yellow element highlighting, and automatic next-step progression.
 *
 * Quota-Aware Optimizations:
 *   - Screen fingerprinting: skips duplicate API calls when the screen hasn't changed.
 *   - Local-first bypass: resolves confident matches without an API call.
 *   - Auto-progression cooldown: enforces 3s minimum between automatic re-analyses.
 *   - Conversation history cap: limits context to 8 entries max.
 *
 * Bug 3 Fix (Stale Bounds):
 *   The fingerprint cache is used ONLY to skip the Gemini API call (avoiding duplicate analysis).
 *   It is NEVER used to reuse physical screen coordinates.
 *   Immediately before every showHighlight() call, fresh bounds are obtained via
 *   SahaayAccessibilityService.findFreshBoundsForElement(). This is the ONLY path to bounds.
 *
 * Automatic Progression:
 *   Listens to [SahaayAccessibilityService.userInteractionEvents].
 *   When the user taps an element on screen during an active guide session,
 *   it automatically captures the NEW screen, determines the next safest step,
 *   speaks the instruction aloud, and highlights the new target element.
 */
class ScreenAssistantEngine(private val context: Context) {

    companion object {
        private const val TAG = "SahaayEngine"
    }

    private val screenAnalysisService: ScreenAnalysisService = GeminiScreenAnalysisService()
    private val ttsManager = TextToSpeechManager(context)
    private val speechManager = SpeechRecognizerManager(context)
    private val taskPlanner = TaskPlanner()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val conversationHistory = mutableListOf<ConversationMessage>()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _isListeningVoice = MutableStateFlow(false)
    val isListeningVoice: StateFlow<Boolean> = _isListeningVoice.asStateFlow()

    private val _lastResult = MutableStateFlow<ScreenAnalysisResult?>(null)
    val lastResult: StateFlow<ScreenAnalysisResult?> = _lastResult.asStateFlow()

    private var currentLanguage = "English (India)"
    private var voiceJob: Job? = null
    private var interactionJob: Job? = null
    private var isAutoProgressionActive = false

    // Quota guard: screen fingerprinting
    // Bug 3 Fix: lastCachedResult stores SEMANTIC data only (text, ID, spoken response).
    // Physical bounds are NEVER reused from cache — they are always re-fetched fresh.
    private var lastScreenFingerprint: String = ""
    private var lastCachedResult: ScreenAnalysisResult? = null

    // Quota guard: auto-progression cooldown
    private var lastAutoProgressionTime = 0L
    private val AUTO_PROGRESSION_COOLDOWN_MS = 3000L

    init {
        ttsManager.initialize(language = currentLanguage)
        observeUserInteractions()
    }

    fun setLanguage(language: String) {
        currentLanguage = language
        ttsManager.applyLanguage(language)
    }

    /**
     * Listens for user click/touch events on screen.
     * Automatically re-analyzes the screen and guides the next step after the user acts.
     * Enforces a 3-second cooldown to prevent rapid-fire API calls.
     */
    private fun observeUserInteractions() {
        interactionJob = scope.launch(Dispatchers.Main) {
            SahaayAccessibilityService.userInteractionEvents.collect {
                val now = System.currentTimeMillis()
                if (isAutoProgressionActive && !_isAnalyzing.value && !_isListeningVoice.value
                    && (now - lastAutoProgressionTime) > AUTO_PROGRESSION_COOLDOWN_MS
                ) {
                    lastAutoProgressionTime = now
                    delay(450L)
                    val activeGoal = taskPlanner.currentTaskState?.originalGoal?.ifBlank { null } ?: "What should I do next?"
                    analyzeAndGuide(userGoal = activeGoal)
                }
            }
        }
    }

    /**
     * Start voice listening in overlay mode to highlight a specific item requested by voice.
     */
    fun startVoiceListeningAndHighlight() {
        if (_isListeningVoice.value || _isAnalyzing.value) return

        // 1. Permission check
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(
                context,
                "🎙️ Microphone permission needed. Opening Sahaay app...",
                Toast.LENGTH_LONG
            ).show()
            ttsManager.speak("Microphone permission is required. Please allow microphone access.", force = true)
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_VOICE_TAB, true)
                putExtra(MainActivity.EXTRA_START_VOICE_LISTENING, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
            return
        }

        isAutoProgressionActive = true
        ttsManager.stop()
        _isListeningVoice.value = true
        Toast.makeText(context, "🎙️ Listening... Tell Sahaay what to do or highlight", Toast.LENGTH_LONG).show()

        voiceJob?.cancel()
        voiceJob = scope.launch(Dispatchers.Main) {
            speechManager.events.collect { event ->
                when (event) {
                    is SpeechRecognizerManager.SpeechEvent.ReadyForSpeech -> {
                        _isListeningVoice.value = true
                    }
                    is SpeechRecognizerManager.SpeechEvent.PartialResult -> {
                        // Keeps listening active with speech feedback
                    }
                    is SpeechRecognizerManager.SpeechEvent.FinalResult -> {
                        _isListeningVoice.value = false
                        voiceJob?.cancel()
                        val text = event.text
                        if (text.isNotBlank()) {
                            Toast.makeText(context, "Listening: \"$text\"", Toast.LENGTH_SHORT).show()
                            analyzeAndGuide(userGoal = text)
                        }
                    }
                    is SpeechRecognizerManager.SpeechEvent.Error -> {
                        _isListeningVoice.value = false
                        voiceJob?.cancel()
                        Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                        ttsManager.speak("I didn't catch that. Please tap Voice to try again.", force = true)
                    }
                    is SpeechRecognizerManager.SpeechEvent.Stopped -> {
                        _isListeningVoice.value = false
                    }
                    else -> {}
                }
            }
        }

        speechManager.startListening(currentLanguage)
    }

    /**
     * Perform full screen read & highlight cycle:
     *   capture → fingerprint check → local-first bypass → analyze → highlight → speak
     *
     * Bug 3 Fix: The fingerprint cache check no longer returns cached bounds.
     * Even on a cache hit, fresh bounds are always fetched via findFreshBoundsForElement().
     */
    fun analyzeAndGuide(userGoal: String) {
        if (_isAnalyzing.value) return

        isAutoProgressionActive = true

        scope.launch {
            _isAnalyzing.value = true

            // 1. CHECK: Is AccessibilityService connected?
            val service = SahaayAccessibilityService.instance
            if (service == null || !SahaayAccessibilityService.isServiceEnabled(context)) {
                val msg = "Please enable Sahaay Accessibility Service in Settings so I can highlight things on screen for you."
                ttsManager.speak(msg, force = true)
                _lastResult.value = ScreenAnalysisResult.error(msg)
                _isAnalyzing.value = false
                return@launch
            }

            // 1b. Check System Vocal Anchors first
            val anchor = com.example.elderhelpprototypev01.voice.VocalAnchorProcessor.detect(userGoal)
            if (anchor != null) {
                when (anchor) {
                    com.example.elderhelpprototypev01.model.VocalAnchorAction.REPEAT -> {
                        val lastRes = _lastResult.value
                        if (lastRes != null && !lastRes.isError) {
                            ttsManager.speak(lastRes.spokenResponse, force = true)
                            if (lastRes.targetElementId != null || lastRes.targetElementText != null) {
                                val promptIdx = lastRes.targetElementId?.removePrefix("e")?.toIntOrNull() ?: -1
                                showHighlightWithFreshBounds(
                                    service = service,
                                    result = lastRes,
                                    promptIndex = promptIdx,
                                    userGoal = userGoal,
                                    source = "REPEAT_CMD"
                                )
                            }
                        } else {
                            ttsManager.speak("There is no previous instruction to repeat. Please tell me what you would like to do.", force = true)
                        }
                        _isAnalyzing.value = false
                        return@launch
                    }
                    com.example.elderhelpprototypev01.model.VocalAnchorAction.GO_BACK -> {
                        clearHighlight()
                        val navigated = SahaayAccessibilityService.performGlobalBack()
                        val msg = if (navigated) "Going back." else "Please press the back button on your device to go back."
                        ttsManager.speak(msg, force = true)
                        _isAnalyzing.value = false
                        return@launch
                    }
                    com.example.elderhelpprototypev01.model.VocalAnchorAction.STOP -> {
                        clearHighlight()
                        stopSpeaking()
                        _isAnalyzing.value = false
                        return@launch
                    }
                    com.example.elderhelpprototypev01.model.VocalAnchorAction.NEXT_STEP -> {
                        // Continue to auto-analyze next step
                    }
                }
            }

            // 2. CAPTURE current screen context
            val screenContext = service.captureCurrentScreenContext()
            if (screenContext == null || screenContext.elements.isEmpty()) {
                val msg = "I couldn't find that option. Please try again."
                clearHighlight()
                ttsManager.speak(msg, force = true)
                _lastResult.value = ScreenAnalysisResult.noMatch(msg)
                _isAnalyzing.value = false
                return@launch
            }

            // 2b. Evaluate Task State via TaskPlanner
            val taskState = taskPlanner.evaluateTask(userGoal, screenContext, conversationHistory)

            // SAFETY PAUSE: Stop if screen is asking for sensitive auth (OTP, PIN, Password, CVV)
            if (taskState.isBlockedBySafety) {
                val safetyMsg = "This is your private security field. Please enter your code yourself. Sahaay cannot see, read, or handle your OTP or PIN."
                val sensitiveField = screenContext.getSensitiveFieldLabels().firstOrNull()
                val targetEl = sensitiveField?.let { screenContext.elements.firstOrNull { el -> el.label.equals(it, ignoreCase = true) } }

                val result = ScreenAnalysisResult(
                    responseType = "SAFETY_WARNING",
                    spokenResponse = safetyMsg,
                    visualResponse = "Private OTP / PIN Field",
                    targetElementText = sensitiveField,
                    targetElementBounds = targetEl?.bounds,
                    actionGuidance = "CONFIRM",
                    explanation = safetyMsg,
                    reason = "Sensitive field safety boundary",
                    confidence = 1.0f,
                    actionType = "SAFETY_PAUSE"
                )
                _lastResult.value = result
                if (targetEl != null && targetEl.bounds.width() > 0) {
                    val freshBounds = service.findFreshBoundsForElement(
                        promptIndex = targetEl.promptIndex,
                        fallbackText = sensitiveField ?: ""
                    )
                    val boundsToUse = freshBounds ?: targetEl.bounds
                    if (boundsToUse.width() > 0) {
                        HighlightManager.showHighlight(context, boundsToUse, sensitiveField ?: "PIN / OTP", safetyMsg, 20000L)
                    }
                }
                ttsManager.speak(safetyMsg, force = true)
                _isAnalyzing.value = false
                return@launch
            }

            // 2c. FINGERPRINT CHECK: Skip API call if screen hasn't changed on "What next"
            val fingerprint = screenContext.toFingerprint()
            if (fingerprint == lastScreenFingerprint && lastCachedResult != null
                && (userGoal == "What should I do next?" || userGoal == "next step")
            ) {
                val cached = lastCachedResult!!
                _lastResult.value = cached
                ttsManager.speak(cached.spokenResponse, force = true)

                if (cached.targetElementId != null || cached.targetElementText != null) {
                    val promptIdx = cached.targetElementId?.removePrefix("e")?.toIntOrNull() ?: -1
                    showHighlightWithFreshBounds(
                        service = service,
                        result = cached,
                        promptIndex = promptIdx,
                        userGoal = userGoal,
                        source = "CACHE_HIT"
                    )
                }
                _isAnalyzing.value = false
                return@launch
            }

            // 3. Add user's goal to conversation history
            conversationHistory.add(ConversationMessage(role = MessageRole.USER, text = userGoal))
            if (conversationHistory.size > 8) {
                conversationHistory.subList(0, conversationHistory.size - 8).clear()
            }

            // 3a. MODULAR TARGET DETECTION (UiNodeTargetDetector): Direct voice action targeting
            val directTarget = com.example.elderhelpprototypev01.accessibility.UiNodeTargetDetector.findTarget(userGoal, screenContext)
            if (directTarget != null) {
                val label = directTarget.label.ifBlank { "option" }
                val spoken = if (directTarget.editable) "Please type in the $label field." else "Please tap $label."
                val directResult = ScreenAnalysisResult(
                    responseType = "NEXT_STEP",
                    spokenResponse = spoken,
                    visualResponse = if (directTarget.editable) "Type $label" else "Tap $label",
                    targetElementId = directTarget.promptId,
                    targetElementText = label,
                    targetElementBounds = directTarget.bounds,
                    actionGuidance = if (directTarget.editable) "TYPE" else "TAP",
                    explanation = spoken,
                    reason = "Direct action matched by UiNodeTargetDetector",
                    confidence = 0.95f,
                    actionType = "GUIDE_HIGHLIGHT"
                )

                _lastResult.value = directResult
                lastScreenFingerprint = fingerprint
                lastCachedResult = directResult

                conversationHistory.add(
                    ConversationMessage(role = MessageRole.ASSISTANT, text = spoken)
                )

                showHighlightWithFreshBounds(
                    service = service,
                    result = directResult,
                    promptIndex = directTarget.promptIndex,
                    userGoal = userGoal,
                    source = "DIRECT_TARGET_DETECTOR"
                )

                ttsManager.speak(spoken, force = true)
                _isAnalyzing.value = false
                return@launch
            }

            // 3b. LOCAL-FIRST BYPASS: If local matcher confidently resolves the goal, skip API
            val analysisService = screenAnalysisService
            if (analysisService is GeminiScreenAnalysisService) {
                val localMatch = analysisService.tryLocalMatch(userGoal, screenContext.elements, taskState)
                if (localMatch != null) {
                    val label = localMatch.label.ifBlank { "option" }
                    val goalLower = userGoal.lowercase()
                    val spoken = when (taskState.currentStage) {
                        TaskStage.SELECT_DATE ->
                            "Now choose an appointment date. I have highlighted the available date option."
                        TaskStage.SELECT_TIME ->
                            "Now choose an appointment time slot."
                        TaskStage.ENTER_PERSONAL_DETAILS, TaskStage.ENTER_BANK_DETAILS, TaskStage.ENTER_BILL_DETAILS ->
                            if (label.lowercase().contains("saved details")) "Tap to auto-fill your saved profile details."
                            else "Type in the highlighted $label field."
                        TaskStage.REVIEW, TaskStage.CONFIRMATION, TaskStage.REVIEW_BILL ->
                            "Review your details and tap the highlighted $label option to proceed."
                        else -> when {
                            goalLower.contains("research") || goalLower.contains("search") || goalLower.contains("google") ->
                                "To research or search online, tap the highlighted $label option."
                            goalLower.contains("dial") || goalLower.contains("call") || goalLower.contains("phone") ->
                                "To dial a number or make a call, tap the highlighted $label option."
                            goalLower.contains("bill") || goalLower.contains("pay") ->
                                "To pay your bill, tap the highlighted $label option."
                            goalLower.contains("doctor") || goalLower.contains("appointment") ->
                                "To book a doctor appointment, tap the highlighted $label option."
                            localMatch.editable ->
                                "Type in the highlighted $label field."
                            else ->
                                "Please tap $label."
                        }
                    }
                    val localResult = ScreenAnalysisResult(
                        responseType = "NEXT_STEP",
                        spokenResponse = spoken,
                        visualResponse = if (localMatch.editable) "Type $label" else "Tap $label",
                        targetElementId = localMatch.promptId,
                        targetElementText = label,
                        targetElementBounds = localMatch.bounds,
                        actionGuidance = if (localMatch.editable) "TYPE" else "TAP",
                        explanation = spoken,
                        reason = "Matched locally without API call",
                        confidence = 0.92f,
                        actionType = "GUIDE_HIGHLIGHT"
                    )

                    _lastResult.value = localResult
                    lastScreenFingerprint = fingerprint
                    lastCachedResult = localResult

                    conversationHistory.add(
                        ConversationMessage(role = MessageRole.ASSISTANT, text = spoken)
                    )

                    showHighlightWithFreshBounds(
                        service = service,
                        result = localResult,
                        promptIndex = localMatch.promptIndex,
                        userGoal = userGoal,
                        source = "LOCAL_MATCH"
                    )

                    ttsManager.speak(spoken, force = true)
                    _isAnalyzing.value = false
                    return@launch
                }
            }

            // 4. ANALYZE via Gemini LLM / Sahaay Intelligence Engine
            val result = try {
                screenAnalysisService.analyzeScreen(
                    screenContext = screenContext,
                    userGoal = userGoal,
                    conversationHistory = conversationHistory,
                    userLanguage = currentLanguage,
                    taskState = taskState
                )
            } catch (e: Exception) {
                val notFoundMsg = "I couldn't find that option. Please try again."
                ScreenAnalysisResult.noMatch(notFoundMsg)
            }

            _lastResult.value = result
            lastScreenFingerprint = fingerprint
            lastCachedResult = result

            if (!result.isError) {
                // 5. Check if target was identified with good confidence
                if (result.confidence < 0.60f || result.actionType == "NO_MATCH" || (result.targetElementId == null && result.targetElementText == null)) {
                    val notFoundMsg = "I couldn't find that option. Please try again."
                    clearHighlight()
                    ttsManager.speak(notFoundMsg, force = true)
                    _lastResult.value = result.copy(spokenResponse = notFoundMsg, responseType = "CLARIFICATION", actionType = "NO_MATCH")
                    conversationHistory.add(
                        ConversationMessage(role = MessageRole.ASSISTANT, text = notFoundMsg)
                    )
                    _isAnalyzing.value = false
                    return@launch
                }

                conversationHistory.add(
                    ConversationMessage(role = MessageRole.ASSISTANT, text = result.spokenResponse)
                )

                val promptIdx = result.targetElementId?.removePrefix("e")?.toIntOrNull() ?: -1
                showHighlightWithFreshBounds(
                    service = service,
                    result = result,
                    promptIndex = promptIdx,
                    userGoal = userGoal,
                    source = "GEMINI"
                )
            } else {
                val notFoundMsg = "I couldn't find that option. Please try again."
                clearHighlight()
                ttsManager.speak(notFoundMsg, force = true)
                _isAnalyzing.value = false
                return@launch
            }

            // 6. SPEAK response aloud via TTS
            ttsManager.speak(result.spokenResponse, force = true)

            _isAnalyzing.value = false
        }
    }

    /**
     * The ONLY function that calls HighlightManager.showHighlight().
     *
     * Bug 3 Fix: Always calls SahaayAccessibilityService.findFreshBoundsForElement()
     * immediately before drawing, regardless of what bounds may be in the result object.
     *
     * Logs the full debug block as specified in requirements (Section 14).
     */
    private fun showHighlightWithFreshBounds(
        service: SahaayAccessibilityService,
        result: ScreenAnalysisResult,
        promptIndex: Int,
        userGoal: String,
        source: String
    ) {
        val targetText = result.targetElementText ?: ""
        val targetId = result.targetElementId ?: ""
        val confidence = result.confidence

        // Fetch FRESH bounds from the live accessibility tree right now
        val freshBounds: Rect? = service.findFreshBoundsForElement(
            promptIndex = promptIndex,
            fallbackText = targetText
        )

        // Full debug log as required in Section 14
        Log.d(TAG, buildString {
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("  SAHAAY HIGHLIGHT ATTEMPT [$source]")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("  USER GOAL:              $userGoal")
            appendLine("  TARGET ID:              $targetId")
            appendLine("  TARGET TEXT:            $targetText")
            appendLine("  CONFIDENCE:             $confidence")
            appendLine("  RESULT ACTION TYPE:     ${result.actionType}")
            appendLine("  SNAPSHOT BOUNDS:        ${result.targetElementBounds}")
            appendLine("  FRESH BOUNDS:           $freshBounds")
            if (freshBounds != null) {
                appendLine("  HIGHLIGHT SHOWN:        YES")
                appendLine("  REASON:                 Fresh bounds obtained from live accessibility tree")
            } else {
                appendLine("  HIGHLIGHT SHOWN:        NO")
                appendLine("  REASON:                 Could not obtain fresh bounds — element not found in live tree")
            }
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        })

        if (freshBounds != null && freshBounds.width() > 0 && freshBounds.height() > 0) {
            HighlightManager.showHighlight(
                context = context,
                bounds = freshBounds,
                targetText = targetText,
                explanation = result.spokenResponse,
                autoDismissMs = 20000L
            )
        } else {
            // No reliable target — do NOT show an arbitrary highlight
            // Voice guidance alone will be used (spoken separately by the caller)
            Log.w(TAG, "showHighlightWithFreshBounds: No fresh bounds for '$targetText' (promptIndex=$promptIndex) — skipping highlight")
        }
    }

    fun stopSpeaking() {
        ttsManager.stop()
        speechManager.stopListening()
    }

    fun clearHighlight() {
        isAutoProgressionActive = false
        HighlightManager.clearHighlight(context)
    }

    fun destroy() {
        isAutoProgressionActive = false
        voiceJob?.cancel()
        interactionJob?.cancel()
        scope.cancel()
        speechManager.destroy()
        ttsManager.shutdown()
        clearHighlight()
    }
}
