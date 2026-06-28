package com.health.secondbrain.llm

import android.content.Context
import android.util.Log
import com.health.secondbrain.data.HealthAgentContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.pytorch.executorch.ExecutorchRuntimeException
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmModule
import java.io.File
import java.util.EnumMap

data class AgentResponse(
    val text: String,
    val statusLine: String? = null,
    val usedTool: Boolean = false,
    val visual: AgentVisual? = null,
)

data class AgentVisual(
    val domainId: String,
    val title: String,
    val caption: String,
)

class OnDeviceLlmService(
    context: Context,
    private val generator: QwenLocalGenerator = QwenLocalGenerator(
        context = context.applicationContext,
    ),
    private val toolBridgeClient: ToolBridgeClient = ToolBridgeClient(),
) {
    suspend fun generateChat(
        context: HealthAgentContext,
        userMessage: String,
        onToken: ((String) -> Unit)? = null,
    ): AgentResponse {
        classifyIntent(context, userMessage)?.let { intent ->
            return when (intent.flow) {
                GoldenFlow.Greeting -> AgentResponse(
                    text = buildGreetingAnswer(context),
                    statusLine = "local greeting",
                )
                GoldenFlow.HealthStatus -> AgentResponse(
                    text = buildHealthStatusAnswer(context),
                    statusLine = "local health status",
                    visual = context.toAgentVisual("Current signal graph"),
                )
                GoldenFlow.NextStep -> buildNextStepAnswer(context, userMessage)
                GoldenFlow.LocalInfo -> AgentResponse(
                    text = buildLocalInfoAnswer(context),
                    statusLine = "local context",
                    visual = context.toAgentVisual("Referenced signal graph"),
                )
            }
        }

        val toolResult = if (shouldUseWebSearch(userMessage)) {
            runCatching { toolBridgeClient.webSearch(searchQuery(userMessage)) }
                .getOrElse { error ->
                    return AgentResponse(
                        text = "Web search is not connected right now, so I’m using HALO’s local context only.",
                        statusLine = "web search unavailable",
                        usedTool = true,
                        visual = context.toAgentVisual("Tool requested this graph"),
                    )
                }
        } else {
            null
        }
        val summarizedToolResult = toolResult?.let {
            it.copy(content = summarizeWebContext(it.content).promptText)
        }

        val prompt = HealthAgentPromptBuilder.buildChatPrompt(
            context = context,
            userMessage = userMessage,
            toolResult = summarizedToolResult,
        )

        return runCatching {
            val streamModelOutput = context.hasRecordedBiometrics
            val rawText = generator.generate(
                prompt,
                onToken = onToken.takeIf { streamModelOutput },
            )
            AgentResponse(
                text = cleanModelText(rawText, context),
                statusLine = summarizedToolResult?.let { "searched web with ${it.tool}" }
                    ?: "answered from HALO health context",
                usedTool = summarizedToolResult != null,
                visual = summarizedToolResult?.let { context.toAgentVisual("Tool context graph") },
            )
        }.getOrElse { error ->
            AgentResponse(
                text = "Qwen is unavailable: ${error.safeMessage()}",
                statusLine = "model unavailable",
                usedTool = summarizedToolResult != null,
            )
        }
    }

    private suspend fun buildNextStepAnswer(
        context: HealthAgentContext,
        userMessage: String,
    ): AgentResponse {
        val searchResult = runCatching {
            toolBridgeClient.webSearch(nextStepSearchQuery(context, userMessage))
        }.getOrNull()

        val base = if (context.hasRecordedBiometrics) {
            "${context.organ.displayName} is ${if (context.organ.statusGood) "currently marked stable" else "currently marked as needing attention"} in HALO. ${context.organ.sentenceNextStep}"
        } else {
            "Recorded biometrics are not available yet, so I can’t personalize a ${context.organ.displayName.lowercase()} improvement plan from watch data. For the MVP demo, the visible ${context.organ.displayName.lowercase()} card suggests: ${context.organ.sentenceNextStep}"
        }

        val webLine = searchResult
            ?.let { summarizeWebContext(it.content).displayText }
            ?: "Web search is not connected right now, so this answer is using HALO’s local backend context only."

        return AgentResponse(
            text = "$base $webLine",
            statusLine = searchResult?.let { "golden next step + ${it.tool}" }
                ?: "golden next step; web unavailable",
            usedTool = searchResult != null,
            visual = context.toAgentVisual(
                if (searchResult != null) "Web-backed action graph" else "Local action graph"
            ),
        )
    }

    private fun HealthAgentContext.toAgentVisual(title: String): AgentVisual =
        AgentVisual(
            domainId = organ.id,
            title = title,
            caption = "${organ.displayName} · ${organ.systemLabel}",
        )

    private fun buildGreetingAnswer(context: HealthAgentContext): String =
        "Hi — I’m here with your ${context.organ.displayName.lowercase()} view. Ask for status, what changed, or what to do next."

    private fun buildHealthStatusAnswer(context: HealthAgentContext): String {
        val status = if (context.organ.statusGood) "looks stable" else "needs attention"
        val reasons = context.organ.metrics
            .take(2)
            .joinToString("; ") { metric ->
                "${metric.label} ${metric.value}${metric.deltaText.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}"
            }
            .ifBlank { context.organ.previewSummary }

        return if (context.hasRecordedBiometrics) {
            "Your current ${context.organ.displayName.lowercase()} status $status based on recorded HALO signals. The main signals are $reasons."
        } else {
            "Recorded biometrics are not available yet, so I can’t claim your personal ${context.organ.displayName.lowercase()} status from watch data. For the MVP demo, the ${context.organ.displayName.lowercase()} card $status because it shows $reasons."
        }
    }

    private fun buildLocalInfoAnswer(context: HealthAgentContext): String {
        val sourceLine = if (context.hasRecordedBiometrics) {
            "I’m reading recorded HALO biometrics and clinical context for this ${context.organ.displayName.lowercase()} view."
        } else {
            "Recorded biometrics are not available yet, so this view is using the local HALO demo card and backend component metadata."
        }
        return "$sourceLine I can explain the visible trend graph, summarize status, suggest next steps, or use web search only when you explicitly ask me to search."
    }

    private fun shouldUseWebSearch(message: String): Boolean {
        val features = SemanticFeatures.from(message)
        val explicitSearchScore =
            features.score(WEB_SEARCH_TERMS, 3) +
                features.score(WEB_SEARCH_PHRASES, 4) +
                features.score(EVIDENCE_TERMS, 2)
        return explicitSearchScore >= 3
    }

    private fun searchQuery(message: String): String =
        message
            .replace(Regex("(?i)\\b(search|web search|look up|google|find)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { message }

    private fun nextStepSearchQuery(context: HealthAgentContext, userMessage: String): String =
        "${context.organ.displayName} health evidence based next steps ${userMessage.ifBlank { context.organ.sentenceNextStep }}"

    private fun summarizeWebContext(content: String): WebContextSummary {
        val sources = content
            .lineSequence()
            .mapNotNull { parseWebSource(it) }
            .filterNot { it.domain == "duckduckgo.com" }
            .distinctBy { "${it.title.lowercase()}|${it.domain}" }
            .take(3)
            .toList()

        if (sources.isNotEmpty()) {
            val sourceText = sources.joinToString("; ") { source ->
                "${source.title} (${source.domain})"
            }
            return WebContextSummary(
                displayText = "Web context: I found source results including $sourceText.",
                promptText = "Source results: $sourceText",
            )
        }

        val fallback = content
            .lineSequence()
            .map { stripUrlNoise(it) }
            .firstOrNull { it.isNotBlank() }
            ?.take(MAX_TOOL_SUMMARY_CHARS)
            ?.trim()
            .orEmpty()

        return if (fallback.isBlank()) {
            WebContextSummary(
                displayText = "Web context: the search tool returned results, but no source title was usable.",
                promptText = "Search returned results, but no usable source title.",
            )
        } else {
            WebContextSummary(
                displayText = "Web context: $fallback",
                promptText = "Search summary: $fallback",
            )
        }
    }

    private fun parseWebSource(line: String): WebSource? {
        val match = WEB_RESULT_PATTERN.find(line.trim()) ?: return null
        val title = match.groupValues[1]
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.isNotBlank() } ?: return null
        val url = match.groupValues[2].trim()
        if (isTrackingUrl(url)) return null
        val domain = runCatching {
            java.net.URI(url).host.orEmpty().removePrefix("www.")
        }.getOrDefault("")
        if (domain.isBlank()) return null
        return WebSource(title = title, domain = domain)
    }

    private fun stripUrlNoise(line: String): String =
        line
            .replace(URL_PATTERN, " ")
            .replace(Regex("(?i)\\bResult:\\s*"), " ")
            .replace(Regex("\\(\\s*\\)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun isTrackingUrl(url: String): Boolean {
        val lower = url.lowercase()
        return "duckduckgo.com/y.js" in lower ||
            "ad_domain=" in lower ||
            "click_metadata=" in lower ||
            "ad_provider=" in lower
    }

    private fun classifyIntent(context: HealthAgentContext, message: String): SemanticIntent? {
        val features = SemanticFeatures.from(message)
        if (features.normalized.isBlank()) return null

        val scores = EnumMap<GoldenFlow, Int>(GoldenFlow::class.java).apply {
            GoldenFlow.entries.forEach { put(it, 0) }
        }
        fun add(flow: GoldenFlow, points: Int) {
            scores[flow] = (scores[flow] ?: 0) + points
        }

        val mentionsOrgan = features.hasAny(context.organ.id, context.organ.displayName.lowercase())
        val shortUtterance = features.tokens.size <= 4

        add(GoldenFlow.Greeting, features.score(GREETING_TERMS, if (shortUtterance) 5 else 2))
        add(GoldenFlow.Greeting, features.score(GREETING_PHRASES, 5))

        add(GoldenFlow.HealthStatus, features.score(HEALTH_TERMS, 2))
        add(GoldenFlow.HealthStatus, features.score(STATUS_TERMS, 2))
        add(GoldenFlow.HealthStatus, features.score(STATUS_PHRASES, 4))
        if (mentionsOrgan && features.hasAny(STATUS_TERMS)) add(GoldenFlow.HealthStatus, 3)
        if (features.hasAny(QUESTION_TERMS) && features.hasAny(HEALTH_TERMS)) add(GoldenFlow.HealthStatus, 2)

        add(GoldenFlow.NextStep, features.score(ACTION_TERMS, 2))
        add(GoldenFlow.NextStep, features.score(IMPROVEMENT_TERMS, 3))
        add(GoldenFlow.NextStep, features.score(ACTION_PHRASES, 5))
        if (features.hasAny(QUESTION_TERMS) && features.hasAny(ACTION_TERMS)) add(GoldenFlow.NextStep, 2)

        add(GoldenFlow.LocalInfo, features.score(EXPLAIN_TERMS, 2))
        add(GoldenFlow.LocalInfo, features.score(GRAPH_TERMS, 3))
        add(GoldenFlow.LocalInfo, features.score(LOCAL_INFO_PHRASES, 4))
        if (features.hasAny(QUESTION_TERMS) && (features.hasAny(EXPLAIN_TERMS) || features.hasAny(GRAPH_TERMS))) {
            add(GoldenFlow.LocalInfo, 2)
        }

        val best = scores.maxBy { it.value }
        val threshold = if (best.key == GoldenFlow.Greeting) 5 else 4
        return if (best.value >= threshold) SemanticIntent(best.key, best.value) else null
    }

    private fun Throwable.safeMessage(): String =
        when (this) {
            is ExecutorchRuntimeException -> "ExecuTorch error $errorCode: ${message.orEmpty()}"
            else -> message ?: javaClass.simpleName
        }

    private fun cleanModelText(raw: String, context: HealthAgentContext): String {
        val normalized = extractVisibleAnswer(raw)
            .replace("```", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (looksLikePromptLeak(normalized)) return fallbackAnswer(context)
        if (violatesMissingDataPolicy(normalized, context)) return fallbackAnswer(context)
        if (normalized.isBlank()) {
            return fallbackAnswer(context)
        }

        val sentences = Regex("(?<=[.!?])\\s+")
            .split(collapseConsecutiveRepeats(normalized))
            .filter { it.isNotBlank() }
        val concise = sentences
            .take(MAX_RESPONSE_SENTENCES)
            .joinToString(" ")
            .ifBlank { normalized }

        if (concise.length <= MAX_RESPONSE_CHARS) return concise
        val clipped = concise.take(MAX_RESPONSE_CHARS)
        val lastSpace = clipped.lastIndexOf(' ')
        return clipped.take(if (lastSpace > 120) lastSpace else clipped.length).trimEnd() + "..."
    }

    private fun extractVisibleAnswer(text: String): String {
        val afterAssistant = text.substringAfterLast("<|im_start|>assistant", text)
        val afterLegacyMarker = afterAssistant.substringAfterLast("HALO_RESPONSE:", afterAssistant)
        return afterLegacyMarker
            .replace(Regex("(?is)<think>.*?</think>"), " ")
            .replace(Regex("(?is)<think>.*$"), " ")
            .replace("<|im_end|>", " ")
            .replace("<|endoftext|>", " ")
    }

    private fun looksLikePromptLeak(text: String): Boolean {
        val normalized = text.lowercase()
        return PROMPT_LEAK_MARKERS.any { it in normalized }
    }

    private fun violatesMissingDataPolicy(text: String, context: HealthAgentContext): Boolean {
        if (context.hasRecordedBiometrics) return false
        val normalized = text.lowercase()
        val statesMissingData = MISSING_DATA_PHRASES.any { it in normalized }
        return !statesMissingData
    }

    private fun collapseConsecutiveRepeats(text: String): String {
        val pieces = Regex("(?<=[.!?])\\s+")
            .split(text)
            .filter { it.isNotBlank() }
        if (pieces.size <= 1) return text

        val collapsed = ArrayList<String>(pieces.size)
        var previousKey = ""
        pieces.forEach { sentence ->
            val key = sentence.lowercase().replace(Regex("[^a-z0-9]+"), "")
            if (key.isNotBlank() && key != previousKey) {
                collapsed.add(sentence)
            }
            previousKey = key
        }
        return collapsed.joinToString(" ")
    }

    private fun fallbackAnswer(context: HealthAgentContext): String =
        if (!context.hasRecordedBiometrics) {
            "Recorded biometrics are not available yet, so I cannot infer personal ${context.organ.displayName.lowercase()} risk from watch data. Once heart rate, HRV, sleep, steps, or clinical records are recorded, I can compare them against your backend signals."
        } else {
            "I thought through the available HALO context, but the on-device model did not produce a clean final answer. The backend signals should be reviewed directly for now."
        }

    companion object {
        private val GREETING_TERMS = setOf("hi", "hello", "hey", "yo", "sup", "howdy")
        private val GREETING_PHRASES = setOf(
            "good morning",
            "good afternoon",
            "good evening",
            "what s up",
            "whats up",
        )

        private val HEALTH_TERMS = setOf(
            "health",
            "biometrics",
            "vitals",
            "heart",
            "kidney",
            "sleep",
            "lungs",
            "liver",
            "gut",
            "brain",
            "body",
            "recovery",
            "readiness",
        )
        private val STATUS_TERMS = setOf(
            "status",
            "state",
            "condition",
            "doing",
            "good",
            "bad",
            "healthy",
            "stable",
            "risk",
            "safe",
            "today",
            "current",
            "now",
        )
        private val STATUS_PHRASES = setOf(
            "how am i",
            "how am i doing",
            "am i good",
            "am i bad",
            "good or bad",
            "current health",
            "health status",
            "my status",
        )

        private val ACTION_TERMS = setOf(
            "do",
            "change",
            "try",
            "plan",
            "next",
            "step",
            "recommend",
            "recommendation",
            "advice",
            "help",
            "action",
            "actions",
        )
        private val IMPROVEMENT_TERMS = setOf(
            "improve",
            "improving",
            "better",
            "optimize",
            "recover",
            "recovery",
            "lower",
            "raise",
            "reduce",
            "increase",
        )
        private val ACTION_PHRASES = setOf(
            "what should i do",
            "what do i do",
            "what can i do",
            "next step",
            "next steps",
            "what can you do",
        )

        private val EXPLAIN_TERMS = setOf(
            "explain",
            "mean",
            "meaning",
            "why",
            "because",
            "interpret",
            "summarize",
            "summary",
            "tell",
            "seeing",
        )
        private val GRAPH_TERMS = setOf("graph", "chart", "trend", "line", "bars", "metric", "metrics", "signal", "signals")
        private val LOCAL_INFO_PHRASES = setOf(
            "what is going on",
            "whats going on",
            "what am i looking at",
            "what does this mean",
            "tell me more",
            "what can you tell me",
            "what are you seeing",
        )

        private val WEB_SEARCH_TERMS = setOf("search", "google", "web", "online")
        private val WEB_SEARCH_PHRASES = setOf("search the web", "web search", "look up", "google it")
        private val EVIDENCE_TERMS = setOf("research", "study", "studies", "source", "sources", "guideline", "guidelines", "latest", "recent", "published")
        private val QUESTION_TERMS = setOf("what", "how", "why", "is", "are", "am", "should", "can", "could")
        private val PROMPT_LEAK_MARKERS = listOf(
            "health_context_json",
            "user_message",
            "recorded_daily_count",
            "generated_at",
            "clinical_observations",
            "\"organ\"",
        )
        private val MISSING_DATA_PHRASES = listOf(
            "not available",
            "no recorded",
            "not recorded",
            "do not have",
            "don't have",
            "cannot infer",
            "can't infer",
            "missing",
            "unavailable",
        )
        private val WEB_RESULT_PATTERN = Regex("^Result:\\s*(.*?)\\s*\\((https?://[^\\s)]+).*\\)\\s*$")
        private val URL_PATTERN = Regex("https?://\\S+")
        private const val MAX_TOOL_SUMMARY_CHARS = 360
        private const val MAX_RESPONSE_SENTENCES = 4
        private const val MAX_RESPONSE_CHARS = 560
    }
}

private enum class GoldenFlow {
    Greeting,
    HealthStatus,
    NextStep,
    LocalInfo,
}

private data class SemanticIntent(
    val flow: GoldenFlow,
    val score: Int,
)

private data class WebSource(
    val title: String,
    val domain: String,
)

private data class WebContextSummary(
    val displayText: String,
    val promptText: String,
)

private data class SemanticFeatures(
    val normalized: String,
    val tokens: Set<String>,
) {
    fun hasAny(terms: Set<String>): Boolean =
        terms.any { hasAny(it) }

    fun hasAny(vararg terms: String): Boolean =
        terms.any { term ->
            val normalizedTerm = normalize(term)
            normalizedTerm in tokens || normalizedTerm in normalized
        }

    fun score(terms: Set<String>, points: Int): Int =
        terms.sumOf { term -> if (hasAny(term)) points else 0 }

    companion object {
        fun from(message: String): SemanticFeatures {
            val normalized = normalize(message)
            return SemanticFeatures(
                normalized = normalized,
                tokens = normalized
                    .split(' ')
                    .filter { it.isNotBlank() }
                    .map { stem(it) }
                    .toSet(),
            )
        }

        private fun normalize(text: String): String =
            text.lowercase()
                .replace("'", "")
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        private fun stem(token: String): String =
            when {
                token.length > 5 && token.endsWith("ing") -> token.dropLast(3)
                token.length > 4 && token.endsWith("ed") -> token.dropLast(2)
                token.length > 4 && token.endsWith("s") -> token.dropLast(1)
                else -> token
            }
    }
}

class QwenLocalGenerator(
    private val context: Context,
    private val modelPath: String = QnnEnvironment.MODEL_PATH,
    private val tokenizerPath: String = QnnEnvironment.TOKENIZER_PATH,
) {
    private val mutex = Mutex()

    suspend fun generate(
        prompt: String,
        sequenceLength: Int = DEFAULT_SEQUENCE_LENGTH,
        visibleCharLimit: Int = DEFAULT_VISIBLE_CHAR_LIMIT,
        onToken: ((String) -> Unit)? = null,
    ): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val safePrompt = compactPrompt(prompt)
                val output = StringBuilder()
                var streamedText = ""
                var callbackError: String? = null
                var stoppedForLimit = false
                val activeModule = loadModule()
                val callback = object : LlmCallback {
                    override fun onResult(result: String) {
                        output.append(result)
                        val visible = visibleAnswerPrefix(output.toString())
                        if (onToken != null && visible.length > streamedText.length) {
                            val delta = visible.substring(streamedText.length)
                            streamedText = visible
                            onToken.invoke(delta)
                        }
                        if (shouldStopGeneration(output.length, visible, visibleCharLimit)) {
                            stoppedForLimit = true
                            activeModule.stop()
                        }
                    }

                    override fun onError(errorCode: Int, message: String?) {
                        callbackError = "ExecuTorch error $errorCode: ${message.orEmpty()}"
                    }
                }

                try {
                    Log.i(
                        TAG,
                        "generate promptChars=${safePrompt.length} seqLen=$sequenceLength visibleCharLimit=$visibleCharLimit"
                    )
                    activeModule.generate(
                        safePrompt,
                        sequenceLength,
                        callback,
                        false,
                        TEMPERATURE,
                        0,
                        0,
                    )
                    callbackError?.let { throw IllegalStateException(it) }
                    if (stoppedForLimit) {
                        Log.i(TAG, "generation stopped after reaching HALO output guard")
                    }
                } finally {
                    runCatching { activeModule.close() }
                }

                output.toString().trim().ifBlank {
                    throw IllegalStateException("No tokens were generated by Qwen.")
                }
            }
        }

    private fun visibleAnswerPrefix(raw: String): String {
        val afterAssistant = raw.substringAfterLast("<|im_start|>assistant", raw)
        val afterLegacyMarker = afterAssistant.substringAfterLast("HALO_RESPONSE:", afterAssistant)
        val withoutClosedThinking = afterLegacyMarker.replace(Regex("(?is)<think>.*?</think>"), "")
        val openThinkStart = withoutClosedThinking.indexOf("<think>", ignoreCase = true)
        val withoutOpenThinking = if (openThinkStart >= 0) {
            withoutClosedThinking.take(openThinkStart)
        } else {
            withoutClosedThinking
        }
        val visible = withoutOpenThinking
            .replace("```", "")
            .replace("<|im_end|>", "")
            .replace("<|endoftext|>", "")
            .trimStart()
        if (PROMPT_LEAK_MARKERS.any { it in visible.lowercase() }) return ""
        return collapseVisibleRepeats(visible)
    }

    private fun collapseVisibleRepeats(text: String): String {
        val sentences = Regex("(?<=[.!?])\\s*")
            .split(text)
            .filter { it.isNotBlank() }
        if (sentences.size <= 1) return text

        val collapsed = ArrayList<String>(sentences.size)
        var previousKey = ""
        sentences.forEach { sentence ->
            val key = sentence.lowercase().replace(Regex("[^a-z0-9]+"), "")
            if (key.isNotBlank() && key != previousKey) {
                collapsed.add(sentence.trim())
            }
            previousKey = key
        }
        return collapsed.joinToString(" ")
    }

    private fun shouldStopGeneration(
        rawChars: Int,
        visible: String,
        visibleCharLimit: Int,
    ): Boolean {
        if (rawChars >= MAX_RAW_GENERATED_CHARS) return true
        if (visible.length < visibleCharLimit) return false
        return visible.lastOrNull() in FINAL_PUNCTUATION || visible.length >= HARD_VISIBLE_CHAR_LIMIT
    }

    private fun compactPrompt(prompt: String): String {
        if (prompt.length <= MAX_PROMPT_CHARS) return prompt
        return buildString {
            append(prompt.take(PROMPT_HEAD_CHARS))
            append("\n\n[HALO prompt context compacted to fit on-device sequence budget]\n\n")
            append(prompt.takeLast(PROMPT_TAIL_CHARS))
        }
    }

    private fun loadModule(): LlmModule {
        val model = File(modelPath)
        val tokenizer = File(tokenizerPath)
        if (!model.canRead() || !tokenizer.canRead()) {
            throw IllegalStateException(
                "Expected model at $modelPath and tokenizer at $tokenizerPath"
            )
        }

        QnnEnvironment.bootstrap(context)
        val module = LlmModule(QnnEnvironment.MODEL_TYPE_QNN_LLAMA, modelPath, tokenizerPath, TEMPERATURE)
        return try {
            module.load()
            module
        } catch (error: Throwable) {
            runCatching { module.close() }
            throw error
        }
    }

    companion object {
        private const val TAG = "HaloQwen"
        private const val DEFAULT_SEQUENCE_LENGTH = 1024
        private const val DEFAULT_VISIBLE_CHAR_LIMIT = 420
        private const val HARD_VISIBLE_CHAR_LIMIT = 620
        private const val MAX_RAW_GENERATED_CHARS = 3200
        private const val MAX_PROMPT_CHARS = 1600
        private const val PROMPT_HEAD_CHARS = 950
        private const val PROMPT_TAIL_CHARS = 450
        private const val TEMPERATURE = 0.2f
        private val PROMPT_LEAK_MARKERS = listOf(
            "health_context_json",
            "user_message",
            "recorded_daily_count",
            "generated_at",
            "clinical_observations",
            "\"organ\"",
        )
        private val FINAL_PUNCTUATION = setOf('.', '!', '?')
    }
}
