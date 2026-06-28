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
import org.json.JSONObject
import java.io.File

data class AgentResponse(
    val text: String,
    val statusLine: String? = null,
    val usedTool: Boolean = false,
    val visual: AgentVisual? = null,
    val decision: AgentDecision = AgentDecision.Unknown,
)

data class AgentVisual(
    val domainId: String,
    val title: String,
    val caption: String,
)

data class AgentDecision(
    val action: String,
    val route: String,
    val needsHealthContext: Boolean,
    val toolName: String? = null,
    val query: String? = null,
    val answerSource: String,
) {
    companion object {
        val Unknown = AgentDecision(
            action = "unknown",
            route = "unknown",
            needsHealthContext = false,
            answerSource = "unknown",
        )
    }
}

class OnDeviceLlmService(
    private val generator: LocalTextGenerator,
    private val toolClient: AgentToolClient = ToolBridgeClient(),
) {
    constructor(context: Context) : this(
        generator = QwenLocalGenerator(context.applicationContext),
        toolClient = ToolBridgeClient(),
    )

    suspend fun generateChat(
        context: HealthAgentContext,
        userMessage: String,
        onToken: ((String) -> Unit)? = null,
    ): AgentResponse {
        val plan = AgentPlanner.localPlanForGoldenFlow(context, userMessage)
            ?: requestPlan(context, userMessage)
        val toolExecution = if (plan.type == AgentPlanType.ToolRequest) {
            executeApprovedTool(plan)
        } else {
            null
        }
        val finalPlan = toolExecution?.plan ?: plan

        val prompt = HealthAgentPromptBuilder.buildChatPrompt(
            context = context,
            userMessage = userMessage,
            plan = finalPlan,
            toolResult = toolExecution?.promptResult(),
        )

        return runCatching {
            val rawText = generator.generate(
                prompt = prompt,
                sequenceLength = finalSequenceLengthFor(finalPlan),
                visibleCharLimit = finalVisibleCharLimitFor(finalPlan),
                onToken = null,
            )
            val cleaned = cleanModelText(
                raw = rawText,
                context = context,
                plan = finalPlan,
            )
            AgentResponse(
                text = cleaned.text,
                statusLine = statusLineFor(finalPlan, toolExecution, cleaned.answerSource),
                usedTool = toolExecution != null,
                visual = visualForPlan(context, finalPlan, toolExecution?.result),
                decision = finalPlan.toDecision(toolExecution, cleaned.answerSource),
            )
        }.getOrElse { error ->
            AgentResponse(
                text = fallbackAnswer(context, finalPlan),
                statusLine = "classified as ${finalPlan.route.wireName} - local fallback",
                usedTool = toolExecution != null,
                visual = visualForPlan(context, finalPlan, toolExecution?.result),
                decision = finalPlan.toDecision(toolExecution, "local_fallback:${error.safeMessage()}"),
            )
        }
    }

    private fun HealthAgentContext.toAgentVisual(title: String): AgentVisual =
        AgentVisual(
            domainId = organ.id,
            title = title,
            caption = "${organ.displayName} · ${organ.systemLabel}",
        )

    private suspend fun requestPlan(
        context: HealthAgentContext,
        userMessage: String,
    ): AgentPlan {
        val prompt = HealthAgentPromptBuilder.buildPlanPrompt(
            context = context,
            userMessage = userMessage,
        )
        return runCatching {
            val raw = generator.generate(
                prompt = prompt,
                sequenceLength = PLAN_SEQUENCE_LENGTH,
                visibleCharLimit = PLAN_VISIBLE_CHAR_LIMIT,
            )
            parseAgentPlan(raw, context, userMessage)
        }.getOrElse {
            fallbackPlanFromMessage(context, userMessage)
        }
    }

    private suspend fun executeApprovedTool(plan: AgentPlan): ToolExecution? {
        val request = validateToolRequest(plan) ?: return null
        val result = runCatching {
            when (request.tool) {
                "web_search" -> toolClient.webSearch(request.query)
                else -> null
            }
        }
        return result.fold(
            onSuccess = { toolResult ->
                val summarized = toolResult?.copy(content = summarizeWebContext(toolResult.content).promptText)
                ToolExecution(
                    plan = plan,
                    result = summarized,
                    errorMessage = if (summarized == null) "Tool returned no result" else null,
                )
            },
            onFailure = { error ->
                ToolExecution(
                    plan = plan,
                    result = null,
                    errorMessage = error.safeMessage().take(MAX_TOOL_ERROR_CHARS),
                )
            },
        )
    }

    private fun validateToolRequest(plan: AgentPlan): ToolRequest? {
        if (plan.type != AgentPlanType.ToolRequest) return null
        if (plan.tool != "web_search") return null
        val query = plan.query.orEmpty()
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_TOOL_QUERY_CHARS)
        if (query.length < MIN_TOOL_QUERY_CHARS) return null
        if (!query.any { it.isLetter() }) return null
        return ToolRequest(tool = "web_search", query = query)
    }

    private fun visualForPlan(
        context: HealthAgentContext,
        plan: AgentPlan,
        toolResult: ToolCallResult?,
    ): AgentVisual? {
        if (toolResult != null) return context.toAgentVisual("Agent-requested graph")
        return when (plan.route) {
            AgentRoute.HealthStatus -> context.toAgentVisual("Current signal graph")
            AgentRoute.NextStep -> context.toAgentVisual("Action context graph")
            AgentRoute.LocalContext, AgentRoute.GeneralHealth -> context.toAgentVisual("Referenced signal graph")
            AgentRoute.WebResearch -> context.toAgentVisual("Research context graph")
            AgentRoute.Smalltalk, AgentRoute.Clarify -> null
        }
    }

    private fun statusLineFor(
        plan: AgentPlan,
        toolExecution: ToolExecution?,
        answerSource: String,
    ): String {
        val action = when {
            toolExecution?.result != null -> "web_search"
            toolExecution?.errorMessage != null -> "web_search failed"
            plan.needsHealthContext -> "health_context"
            plan.route == AgentRoute.Clarify -> "clarify"
            else -> "local"
        }
        val source = if (answerSource.startsWith("local_fallback")) "local fallback" else "Qwen final"
        return "classified ${plan.route.wireName} - $action - $source"
    }

    private fun finalSequenceLengthFor(plan: AgentPlan): Int =
        when (plan.route) {
            AgentRoute.Smalltalk, AgentRoute.Clarify -> 160
            AgentRoute.HealthStatus, AgentRoute.LocalContext -> 384
            else -> 512
        }

    private fun finalVisibleCharLimitFor(plan: AgentPlan): Int =
        when (plan.route) {
            AgentRoute.Smalltalk, AgentRoute.Clarify -> 90
            AgentRoute.HealthStatus, AgentRoute.LocalContext -> 260
            else -> 360
        }

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

    private fun parseAgentPlan(
        raw: String,
        context: HealthAgentContext,
        userMessage: String,
    ): AgentPlan = AgentPlanner.canonicalize(raw, context, userMessage)

    private fun fallbackPlanFromMessage(
        context: HealthAgentContext,
        userMessage: String,
    ): AgentPlan = AgentPlanner.fallbackPlan(context, userMessage)

    private fun Throwable.safeMessage(): String =
        when (this) {
            is ExecutorchRuntimeException -> "ExecuTorch error $errorCode: ${message.orEmpty()}"
            else -> message ?: javaClass.simpleName
        }

    private fun cleanModelText(
        raw: String,
        context: HealthAgentContext,
        plan: AgentPlan,
    ): CleanedAnswer {
        val normalized = extractVisibleAnswer(raw)
            .replace("```", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .stripRepeatedRoutingTail()
        val fallback = fallbackAnswer(context, plan)
        if (looksLikePromptLeak(normalized)) return CleanedAnswer(fallback, "local_fallback:prompt_leak")
        if (plan.needsHealthContext && violatesMissingDataPolicy(normalized, context)) {
            Log.d("HALO_LLM", "missing_data_policy raw=${raw.take(500)} normalized=${normalized.take(500)}")
            return CleanedAnswer(fallback, "local_fallback:missing_data_policy")
        }
        if (plan.needsHealthContext && shouldRequireDisplaySignal(normalized, context, plan)) {
            return CleanedAnswer(fallback, "local_fallback:missing_display_signal")
        }
        if (!plan.needsHealthContext && mentionsUnavailableHealthData(normalized)) {
            return CleanedAnswer(fallback, "local_fallback:route_context_mismatch")
        }
        if (plan.route == AgentRoute.Smalltalk && looksLikeMedicalBoilerplate(normalized)) {
            return CleanedAnswer(fallback, "local_fallback:smalltalk_boilerplate")
        }
        if (normalized.isBlank()) {
            return CleanedAnswer(fallback, "local_fallback:blank")
        }

        val sentences = Regex("(?<=[.!?])\\s+")
            .split(collapseConsecutiveRepeats(normalized))
            .filter { it.isNotBlank() }
        val concise = sentences
            .take(MAX_RESPONSE_SENTENCES)
            .joinToString(" ")
            .ifBlank { normalized }

        if (concise.length <= MAX_RESPONSE_CHARS) return CleanedAnswer(concise, "qwen_final")
        val clipped = concise.take(MAX_RESPONSE_CHARS)
        val lastSpace = clipped.lastIndexOf(' ')
        return CleanedAnswer(
            text = clipped.take(if (lastSpace > 120) lastSpace else clipped.length).trimEnd() + "...",
            answerSource = "qwen_final",
        )
    }

    private fun extractVisibleAnswer(text: String): String {
        return extractVisibleAnswerText(text)
    }

    private fun looksLikePromptLeak(text: String): Boolean {
        val normalized = text.lowercase()
        return PROMPT_LEAK_MARKERS.any { it in normalized }
    }

    private fun violatesMissingDataPolicy(text: String, context: HealthAgentContext): Boolean {
        if (context.hasRecordedBiometrics) return false
        val normalized = text.lowercase()
        val statesMissingData = MISSING_DATA_PHRASES.any { it in normalized }
        val framesAsDisplayContext = DISPLAY_CONTEXT_PHRASES.any { it in normalized }
        return !statesMissingData && !framesAsDisplayContext
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

    private fun String.stripRepeatedRoutingTail(): String =
        replace(Regex("(?:\\s+\\b[ABCD]\\b){3,}\\s*$"), "")
            .trim()

    private fun mentionsUnavailableHealthData(text: String): Boolean {
        val normalized = text.lowercase()
        return MISSING_DATA_PHRASES.any { it in normalized } &&
            HEALTH_CONTEXT_OUTPUT_TERMS.any { it in normalized }
    }

    private fun looksLikeMedicalBoilerplate(text: String): Boolean {
        val normalized = text.lowercase()
        return MEDICAL_BOILERPLATE_PHRASES.any { it in normalized }
    }

    private fun shouldRequireDisplaySignal(
        text: String,
        context: HealthAgentContext,
        plan: AgentPlan,
    ): Boolean {
        if (context.hasRecordedBiometrics) return false
        if (context.organ.metrics.isEmpty()) return false
        if (plan.route == AgentRoute.Clarify || plan.route == AgentRoute.Smalltalk) return false
        return !mentionsDisplaySignal(text, context)
    }

    private fun mentionsDisplaySignal(
        text: String,
        context: HealthAgentContext,
    ): Boolean {
        val normalized = text.lowercase()
        return context.organ.metrics.any { metric ->
            metric.label.lowercase() in normalized ||
                metric.value.lowercase() in normalized ||
                metric.deltaText.takeIf { it.isNotBlank() }?.lowercase()?.let { it in normalized } == true
        }
    }

    private fun displayMetricSummary(context: HealthAgentContext): String =
        context.organ.metrics
            .take(3)
            .joinToString(" and ") { metric ->
                val delta = metric.deltaText.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                "${metric.label} ${metric.value}$delta"
            }
            .ifBlank { context.organ.previewSummary }

    private fun displayCardStatus(context: HealthAgentContext): String =
        if (context.organ.statusGood) "stable" else "needs attention"

    private fun displayContextQualifier(): String =
        "This is HALO card context, not a live watch-recorded medical conclusion."

    private fun fallbackAnswer(
        context: HealthAgentContext,
        plan: AgentPlan,
    ): String =
        when (plan.route) {
            AgentRoute.Smalltalk ->
                "Hi - I am here with HALO. Ask about your status, this graph, or what to do next."

            AgentRoute.Clarify ->
                "I am not sure what you meant. Ask about your current status, this graph, or a next step."

            AgentRoute.WebResearch ->
                "I could not turn the sourced context into a clean answer. Try the search again or ask for a local HALO summary."

            AgentRoute.NextStep ->
                if (!context.hasRecordedBiometrics) {
                    "HALO suggests this next step for your ${context.organ.displayName.lowercase()} card: ${context.organ.sentenceNextStep} ${displayContextQualifier()}"
                } else {
                    "${context.organ.previewSummary} Next step: ${context.organ.sentenceNextStep}"
                }

            AgentRoute.HealthStatus, AgentRoute.LocalContext, AgentRoute.GeneralHealth ->
                if (!context.hasRecordedBiometrics) {
                    "HALO currently marks your ${context.organ.displayName.lowercase()} card as ${displayCardStatus(context)}. The visible signals are ${displayMetricSummary(context)}. ${displayContextQualifier()}"
                } else {
                    "${context.organ.previewSummary} ${context.organ.sentenceWeek} ${context.organ.sentenceNextStep}"
                }
        }

    companion object {
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
            "unavailable",
        )
        private val HEALTH_CONTEXT_OUTPUT_TERMS = listOf(
            "biometrics",
            "watch data",
            "risk",
            "heart rate",
            "hrv",
            "sleep",
            "steps",
            "recorded",
            "demo",
        )
        private val DISPLAY_CONTEXT_PHRASES = listOf(
            "visible",
            "display context",
            "display-only",
            "halo card",
            "card context",
            "shown",
            "dashboard",
        )
        private val MEDICAL_BOILERPLATE_PHRASES = listOf(
            "medical advice",
            "medical diagnosis",
            "diagnosis",
            "consult a healthcare professional",
            "healthcare professional",
            "safety and privacy",
            "missing data or demo values",
        )
        private val WEB_RESULT_PATTERN = Regex("^Result:\\s*(.*?)\\s*\\((https?://[^\\s)]+).*\\)\\s*$")
        private val URL_PATTERN = Regex("https?://\\S+")
        private val THINK_CLOSE_PATTERN = Regex("(?is)</think>")
        private const val MAX_TOOL_SUMMARY_CHARS = 360
        private const val MAX_TOOL_ERROR_CHARS = 180
        private const val MAX_RESPONSE_SENTENCES = 4
        private const val MAX_RESPONSE_CHARS = 560
        private const val PLAN_SEQUENCE_LENGTH = 192
        private const val PLAN_VISIBLE_CHAR_LIMIT = 24
        private const val MAX_TOOL_QUERY_CHARS = 160
        private const val MIN_TOOL_QUERY_CHARS = 4
        internal fun extractVisibleAnswerForTesting(text: String): String =
            extractVisibleAnswerText(text)

        internal fun canonicalPlanForTesting(
            raw: String,
            context: HealthAgentContext,
            userMessage: String,
        ): AgentPlan = AgentPlanner.canonicalize(raw, context, userMessage)

        private fun extractVisibleAnswerText(text: String): String {
            val afterAssistant = text.substringAfterLast("<|im_start|>assistant", text)
            val afterLegacyMarker = afterAssistant.substringAfterLast("HALO_RESPONSE:", afterAssistant)
            val withoutClosedThinking = afterLegacyMarker.replace(Regex("(?is)<think>.*?</think>"), " ")
            val withoutOrphanThinking = THINK_CLOSE_PATTERN.findAll(withoutClosedThinking)
                .lastOrNull()
                ?.let { withoutClosedThinking.substring(it.range.last + 1) }
                ?: withoutClosedThinking
            return withoutOrphanThinking
                .replace(Regex("(?is)<think>.*$"), " ")
                .replace(Regex("(?is)</?think>"), " ")
                .replace("<|im_end|>", " ")
                .replace("<|endoftext|>", " ")
        }
    }
}

private data class CleanedAnswer(
    val text: String,
    val answerSource: String,
)

internal enum class AgentPlanType {
    Final,
    ToolRequest,
}

private data class WebSource(
    val title: String,
    val domain: String,
)

private data class WebContextSummary(
    val displayText: String,
    val promptText: String,
)

internal enum class AgentRoute(
    val wireName: String,
    val defaultNeedsHealthContext: Boolean,
) {
    Smalltalk("smalltalk", false),
    Clarify("clarify", false),
    LocalContext("local_context", true),
    HealthStatus("health_status", true),
    NextStep("next_step", true),
    GeneralHealth("general_health", true),
    WebResearch("web_research", false);

    companion object {
        fun fromWireName(value: String): AgentRoute =
            entries.firstOrNull { it.wireName == value.lowercase() } ?: GeneralHealth
    }
}

internal data class AgentPlan(
    val type: AgentPlanType,
    val route: AgentRoute,
    val needsHealthContext: Boolean = route.defaultNeedsHealthContext,
    val tool: String? = null,
    val query: String? = null,
    val reason: String? = null,
)

internal object AgentPlanner {
    fun localPlanForGoldenFlow(
        context: HealthAgentContext,
        userMessage: String,
    ): AgentPlan? {
        val policy = IntentPolicy.from(context, userMessage)
        if (policy.allowWeb) return null
        return AgentPlan(
            type = AgentPlanType.Final,
            route = policy.route,
            needsHealthContext = policy.needsHealthContext,
            reason = "local_semantic_pre_route",
        )
    }

    fun canonicalize(
        raw: String,
        context: HealthAgentContext,
        userMessage: String,
    ): AgentPlan {
        val policy = IntentPolicy.from(context, userMessage)
        val candidateJson = listOfNotNull(labelCandidate(raw)) + extractJsonObjects(raw)
        val candidates = candidateJson.mapNotNull { parseCandidate(it) }
        val selected = selectCandidate(candidates, policy)
        return selected?.toPlan(policy) ?: fallbackPlan(context, userMessage)
    }

    fun firstRoutingLabel(raw: String): Char? {
        val visible = OnDeviceLlmService.extractVisibleAnswerForTesting(raw)
            .replace("<|im_end|>", " ")
            .replace("<|endoftext|>", " ")
            .replace(Regex("(?is)</?think>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val text = visible.ifBlank {
            raw.replace("<|im_end|>", " ")
                .replace("<|endoftext|>", " ")
                .replace(Regex("(?is)</?think>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
        return LABEL_PATTERN.find(text.take(80))
            ?.value
            ?.firstOrNull()
    }

    fun fallbackPlan(
        context: HealthAgentContext,
        userMessage: String,
    ): AgentPlan {
        val policy = IntentPolicy.from(context, userMessage)
        return when {
            policy.allowWeb -> AgentPlan(
                type = AgentPlanType.ToolRequest,
                route = AgentRoute.WebResearch,
                needsHealthContext = false,
                tool = "web_search",
                query = policy.query,
            )

            policy.preferNoAction -> AgentPlan(
                type = AgentPlanType.Final,
                route = policy.route,
                needsHealthContext = false,
            )

            policy.preferClarify -> AgentPlan(
                type = AgentPlanType.Final,
                route = AgentRoute.Clarify,
                needsHealthContext = false,
            )

            else -> AgentPlan(
                type = AgentPlanType.Final,
                route = policy.route,
                needsHealthContext = policy.needsHealthContext,
            )
        }
    }

    fun toPredictionJson(plan: AgentPlan): JSONObject =
        JSONObject()
            .put("type", if (plan.type == AgentPlanType.ToolRequest) "tool_request" else "final")
            .put("tool", plan.tool.orEmpty())
            .put("route", plan.route.wireName)
            .put("query", plan.query.orEmpty())
            .put("needs_health_context", plan.needsHealthContext)
            .put("reason", plan.reason.orEmpty())

    private fun selectCandidate(
        candidates: List<PlannerCandidate>,
        policy: IntentPolicy,
    ): PlannerCandidate? {
        if (policy.preferNoAction) {
            return candidates
                .filter { it.action != PlannerAction.WebSearch }
                .maxByOrNull { it.score + if (it.action == PlannerAction.NoAction) 40 else 0 }
                ?.copy(action = PlannerAction.NoAction, route = policy.route, needsHealthContext = false)
                ?: PlannerCandidate(
                    action = PlannerAction.NoAction,
                    route = policy.route,
                    needsHealthContext = false,
                    tool = null,
                    query = null,
                    reason = null,
                    score = 40,
                )
        }

        if (policy.preferClarify) {
            return candidates
                .filter { it.action != PlannerAction.WebSearch }
                .maxByOrNull { it.score + if (it.action == PlannerAction.Clarify) 40 else 0 }
                ?.copy(action = PlannerAction.Clarify, route = AgentRoute.Clarify, needsHealthContext = false)
                ?: PlannerCandidate(
                    action = PlannerAction.Clarify,
                    route = AgentRoute.Clarify,
                    needsHealthContext = false,
                    tool = null,
                    query = null,
                    reason = null,
                    score = 40,
                )
        }

        if (!policy.allowWeb) {
            val nonWeb = candidates
                .filter { it.action != PlannerAction.WebSearch }
                .maxByOrNull { it.score }
                ?: return PlannerCandidate(
                    action = PlannerAction.DataRecall,
                    route = policy.route,
                    needsHealthContext = policy.needsHealthContext,
                    tool = null,
                    query = null,
                    reason = null,
                    score = 20,
                )
            return if (policy.needsHealthContext) {
                nonWeb.copy(
                    action = PlannerAction.DataRecall,
                    route = policy.route,
                    needsHealthContext = true,
                )
            } else {
                nonWeb.copy(
                    action = if (nonWeb.action == PlannerAction.Clarify) PlannerAction.Clarify else PlannerAction.NoAction,
                    route = policy.route,
                    needsHealthContext = false,
                )
            }
        }

        val webCandidate = candidates
            .filter { it.action == PlannerAction.WebSearch || it.hasWebSignal }
            .maxByOrNull { it.score + 40 }
        if (webCandidate != null) {
            return webCandidate.copy(
                action = PlannerAction.WebSearch,
                route = AgentRoute.WebResearch,
                needsHealthContext = false,
                tool = "web_search",
                query = webCandidate.query?.takeIf { it.isNotBlank() } ?: policy.query,
            )
        }

        return PlannerCandidate(
            action = PlannerAction.WebSearch,
            route = AgentRoute.WebResearch,
            needsHealthContext = false,
            tool = "web_search",
            query = policy.query,
            reason = null,
            hasWebSignal = true,
            score = 20,
        )
    }

    private fun fallbackCandidate(policy: IntentPolicy): PlannerCandidate =
        PlannerCandidate(
            action = if (policy.needsHealthContext) PlannerAction.DataRecall else PlannerAction.NoAction,
            route = policy.route,
            needsHealthContext = policy.needsHealthContext,
            tool = null,
            query = null,
            reason = null,
            score = 10,
        )

    private fun PlannerCandidate.toPlan(policy: IntentPolicy): AgentPlan =
        when (action) {
            PlannerAction.WebSearch -> AgentPlan(
                type = AgentPlanType.ToolRequest,
                route = AgentRoute.WebResearch,
                needsHealthContext = false,
                tool = "web_search",
                query = query?.takeIf { it.isNotBlank() } ?: policy.query,
                reason = reason,
            )

            PlannerAction.NoAction -> AgentPlan(
                type = AgentPlanType.Final,
                route = route,
                needsHealthContext = false,
                reason = reason,
            )

            PlannerAction.Clarify -> AgentPlan(
                type = AgentPlanType.Final,
                route = AgentRoute.Clarify,
                needsHealthContext = false,
                reason = reason,
            )

            PlannerAction.DataRecall -> AgentPlan(
                type = AgentPlanType.Final,
                route = route,
                needsHealthContext = needsHealthContext ?: route.defaultNeedsHealthContext,
                reason = reason,
            )
        }

    private fun parseCandidate(jsonText: String): PlannerCandidate? {
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val actionToken = canonicalToken(obj.optString("action"))
        val typeToken = canonicalToken(obj.optString("type"))
        val toolToken = canonicalToken(obj.optString("tool"))
        val routeToken = canonicalToken(obj.optString("route"))
        val reason = obj.optString("reason").takeIf { it.isNotBlank() }
        val query = obj.optString("query").replace(Regex("\\s+"), " ").trim()
            .takeIf { it.isNotBlank() }

        val hasWebSignal = "web_search" in actionToken ||
            typeToken == "tool_request" ||
            "web_search" in toolToken ||
            routeToken in WEB_ROUTE_TOKENS ||
            WEB_REASON_TERMS.any { it in canonicalToken(reason.orEmpty()) }

        val action = when {
            hasWebSignal -> PlannerAction.WebSearch
            actionToken in NO_ACTION_TOKENS || routeToken in NO_ACTION_ROUTE_TOKENS -> PlannerAction.NoAction
            actionToken == "clarify" || routeToken == "clarify" -> PlannerAction.Clarify
            else -> PlannerAction.DataRecall
        }
        val route = routeFromToken(routeToken, action)
        val needsHealthContext = when {
            obj.has("needs_health_context") -> obj.optBoolean("needs_health_context", route.defaultNeedsHealthContext)
            action == PlannerAction.WebSearch || action == PlannerAction.NoAction || action == PlannerAction.Clarify -> false
            else -> route.defaultNeedsHealthContext
        }
        val score = listOf(actionToken, typeToken, toolToken, routeToken)
            .count { it.isNotBlank() } * 8 +
            if (query != null) 10 else 0 +
            if (reason != null) 5 else 0 +
            if (hasWebSignal) 20 else 0

        return PlannerCandidate(
            action = action,
            route = route,
            needsHealthContext = needsHealthContext,
            tool = toolToken.takeIf { it.isNotBlank() },
            query = query,
            reason = reason,
            hasWebSignal = hasWebSignal,
            score = score,
        )
    }

    private fun labelCandidate(raw: String): String? =
        when (firstRoutingLabel(raw)) {
            'A' -> """{"action":"no_action","route":"smalltalk","needs_health_context":false,"query":""}"""
            'B' -> """{"action":"data_recall","route":"local_context","needs_health_context":true,"query":""}"""
            'C' -> """{"action":"web_search","route":"web_research","needs_health_context":false,"query":""}"""
            'D' -> """{"action":"clarify","route":"clarify","needs_health_context":false,"query":""}"""
            else -> null
        }

    private fun routeFromToken(
        routeToken: String,
        action: PlannerAction,
    ): AgentRoute =
        when {
            action == PlannerAction.WebSearch -> AgentRoute.WebResearch
            action == PlannerAction.Clarify -> AgentRoute.Clarify
            routeToken == "smalltalk" -> AgentRoute.Smalltalk
            routeToken == "clarify" -> AgentRoute.Clarify
            routeToken == "local_context" -> AgentRoute.LocalContext
            routeToken == "health_status" || routeToken.startsWith("health_") -> AgentRoute.HealthStatus
            routeToken == "next_step" -> AgentRoute.NextStep
            routeToken == "web_research" || routeToken == "web_search" -> AgentRoute.WebResearch
            else -> AgentRoute.GeneralHealth
        }

    private fun extractJsonObjects(raw: String): List<String> {
        val visible = OnDeviceLlmService.extractVisibleAnswerForTesting(raw)
            .replace("```json", "")
            .replace("```", "")
        val objects = mutableListOf<String>()
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false

        visible.forEachIndexed { index, char ->
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                !inString && char == '{' -> {
                    if (depth == 0) start = index
                    depth += 1
                }
                !inString && char == '}' && depth > 0 -> {
                    depth -= 1
                    if (depth == 0 && start >= 0) {
                        objects += visible.substring(start, index + 1)
                        start = -1
                    }
                }
            }
        }

        return objects
    }

    private fun canonicalToken(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')

    private enum class PlannerAction {
        WebSearch,
        DataRecall,
        NoAction,
        Clarify,
    }

    private data class PlannerCandidate(
        val action: PlannerAction,
        val route: AgentRoute,
        val needsHealthContext: Boolean?,
        val tool: String?,
        val query: String?,
        val reason: String?,
        val hasWebSignal: Boolean = false,
        val score: Int,
    )

    private data class IntentPolicy(
        val allowWeb: Boolean,
        val preferNoAction: Boolean,
        val preferClarify: Boolean,
        val route: AgentRoute,
        val needsHealthContext: Boolean,
        val query: String,
    ) {
        companion object {
            fun from(
                context: HealthAgentContext,
                userMessage: String,
            ): IntentPolicy {
                val features = SemanticFeatures.from(userMessage)
                val tokenCount = features.tokens.size
                val explicitExternal = features.hasAny(EXTERNAL_TERMS)
                val casual = features.hasAny(CASUAL_TERMS)
                val mentionsOrgan = features.hasAny(context.organ.id, context.organ.displayName.lowercase())
                val mentionsHealth = mentionsOrgan || features.hasAny(HEALTH_CONTEXT_TERMS)
                val localReference = features.hasAny(LOCAL_REFERENCE_TERMS)
                val nextStep = features.hasAny(NEXT_STEP_TERMS)
                val graph = features.hasAny(GRAPH_TERMS)
                val status = features.hasAny(STATUS_TERMS)
                val preferNoAction = !explicitExternal && !mentionsHealth &&
                    (casual || (!localReference && tokenCount <= 2 && userMessage.length <= 12))
                val preferClarify = !preferNoAction && !explicitExternal && !mentionsHealth &&
                    tokenCount <= 2 && userMessage.length <= 16
                val route = when {
                    preferNoAction -> AgentRoute.Smalltalk
                    preferClarify -> AgentRoute.Clarify
                    graph || (localReference && !status) -> AgentRoute.LocalContext
                    nextStep -> AgentRoute.NextStep
                    status || mentionsOrgan -> AgentRoute.HealthStatus
                    mentionsHealth -> AgentRoute.GeneralHealth
                    else -> AgentRoute.Smalltalk
                }
                return IntentPolicy(
                    allowWeb = explicitExternal,
                    preferNoAction = preferNoAction,
                    preferClarify = preferClarify,
                    route = route,
                    needsHealthContext = route.defaultNeedsHealthContext,
                    query = userMessage.replace(Regex("\\s+"), " ").trim().take(MAX_QUERY_CHARS),
                )
            }
        }
    }

    private val EXTERNAL_TERMS = setOf(
        "web",
        "search",
        "look up",
        "lookup",
        "internet",
        "online",
        "research",
        "source",
        "source backed",
        "sources",
        "citation",
        "cited",
        "evidence",
        "study",
        "studies",
        "guideline",
        "guidelines",
        "latest",
        "current guidance",
        "recent",
        "cdc",
        "aha",
        "peer reviewed",
        "reliable medical",
    )
    private val HEALTH_CONTEXT_TERMS = setOf(
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
        "status",
        "risk",
        "steps",
        "hrv",
        "rhr",
        "bpm",
        "strain",
        "hydration",
        "blood pressure",
        "glucose",
    )
    private val LOCAL_REFERENCE_TERMS = setOf(
        "my",
        "me",
        "current",
        "today",
        "recorded",
        "watch",
        "dashboard",
        "local",
        "backend",
        "baseline",
        "changed",
        "trend",
        "graph",
        "card",
        "this",
        "these",
        "shown",
        "showing",
    )
    private val NEXT_STEP_TERMS = setOf("next", "improve", "recommend", "should", "action", "plan")
    private val GRAPH_TERMS = setOf("graph", "chart", "card", "looking at", "what does this mean", "red")
    private val STATUS_TERMS = setOf(
        "status",
        "good",
        "bad",
        "risk",
        "normal",
        "worse",
        "better",
        "healthy",
        "unhealthy",
        "healthier",
        "healthiest",
    )
    private val CASUAL_TERMS = setOf(
        "hi",
        "hello",
        "hey",
        "yo",
        "sup",
        "thanks",
        "thank you",
        "whats up",
        "what is up",
        "my dude",
        "good morning",
        "good evening",
    )
    private val WEB_ROUTE_TOKENS = setOf("web_research", "web_search", "search", "research")
    private val WEB_REASON_TERMS = setOf("external", "source", "evidence", "latest", "current")
    private val NO_ACTION_TOKENS = setOf("no_action", "smalltalk", "casual", "none")
    private val NO_ACTION_ROUTE_TOKENS = setOf("smalltalk", "casual")
    private val LABEL_PATTERN = Regex("(?<![A-Za-z])[ABCD](?![A-Za-z])")
    private const val MAX_QUERY_CHARS = 160
}

private data class ToolRequest(
    val tool: String,
    val query: String,
)

private data class ToolExecution(
    val plan: AgentPlan,
    val result: ToolCallResult?,
    val errorMessage: String?,
) {
    fun promptResult(): ToolCallResult? {
        result?.let { return it }
        val message = errorMessage?.takeIf { it.isNotBlank() } ?: return null
        return ToolCallResult(
            tool = plan.tool ?: "tool",
            content = "Tool execution failed: $message",
        )
    }
}

private fun AgentPlan.toDecision(
    toolExecution: ToolExecution?,
    answerSource: String,
): AgentDecision =
    AgentDecision(
        action = when {
            toolExecution?.result != null -> "web_search"
            toolExecution?.errorMessage != null -> "web_search_failed"
            type == AgentPlanType.ToolRequest -> "web_search_requested"
            route == AgentRoute.Clarify -> "clarify"
            route == AgentRoute.Smalltalk -> "no_action"
            needsHealthContext -> "data_recall"
            else -> "no_action"
        },
        route = route.wireName,
        needsHealthContext = needsHealthContext,
        toolName = toolExecution?.result?.tool ?: toolExecution?.plan?.tool ?: tool,
        query = query,
        answerSource = answerSource,
    )

internal data class SemanticFeatures(
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

interface LocalTextGenerator {
    suspend fun generate(
        prompt: String,
        sequenceLength: Int = 1024,
        visibleCharLimit: Int = 420,
        onToken: ((String) -> Unit)? = null,
    ): String
}

class QwenLocalGenerator(
    private val context: Context,
    private val modelPath: String = QnnEnvironment.MODEL_PATH,
    private val tokenizerPath: String = QnnEnvironment.TOKENIZER_PATH,
) : LocalTextGenerator {
    private val mutex = Mutex()

    override suspend fun generate(
        prompt: String,
        sequenceLength: Int,
        visibleCharLimit: Int,
        onToken: ((String) -> Unit)?,
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
                        if (shouldStopGeneration(output.toString(), visible, visibleCharLimit)) {
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
        val withoutOrphanThinking = THINK_CLOSE_PATTERN.findAll(withoutClosedThinking)
            .lastOrNull()
            ?.let { withoutClosedThinking.substring(it.range.last + 1) }
            ?: withoutClosedThinking
        val openThinkStart = withoutOrphanThinking.indexOf("<think>", ignoreCase = true)
        val withoutOpenThinking = if (openThinkStart >= 0) {
            withoutOrphanThinking.take(openThinkStart)
        } else {
            withoutOrphanThinking
        }
        val visible = withoutOpenThinking
            .replace("```", "")
            .replace(Regex("(?is)</?think>"), "")
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
        raw: String,
        visible: String,
        visibleCharLimit: Int,
    ): Boolean {
        if (visibleCharLimit <= LABEL_VISIBLE_CHAR_LIMIT && AgentPlanner.firstRoutingLabel(raw) != null) {
            return true
        }
        if (raw.length >= MAX_RAW_GENERATED_CHARS) return true
        if (looksLikeCompleteJson(visible)) return true
        if (visible.length < visibleCharLimit) return false
        return visible.lastOrNull() in FINAL_PUNCTUATION || visible.length >= HARD_VISIBLE_CHAR_LIMIT
    }

    private fun looksLikeCompleteJson(visible: String): Boolean {
        val trimmed = visible.trim()
        if (trimmed.length < 24) return false
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return false
        return "\"action\"" in trimmed || "\"type\"" in trimmed || "\"route\"" in trimmed
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
        private const val LABEL_VISIBLE_CHAR_LIMIT = 32
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
        private val THINK_CLOSE_PATTERN = Regex("(?is)</think>")
    }
}
