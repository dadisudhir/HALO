package com.health.secondbrain.llm

import com.health.secondbrain.data.HealthAgentContext

object HealthAgentPromptBuilder {
    fun buildPlanPrompt(
        context: HealthAgentContext,
        userMessage: String,
    ): String {
        return """
            <|im_start|>system
            Route the message. Output one capital letter only.
            A=no_action for greetings, thanks, casual chat.
            B=data_recall for my/current/watch/HALO/graph/card/dashboard/status/baseline/risk/next-step.
            C=web_search for explicit search/web/latest/source/research/study/evidence/guideline requests.
            D=clarify for unclear short typos.
            <|im_end|>
            <|im_start|>user
            /no_think
            organ=${context.organ.id}
            msg=${userMessage.replace(Regex("\\s+"), " ").trim().take(MAX_PLAN_MESSAGE_CHARS)}
            <|im_end|>
            <|im_start|>assistant
        """.trimIndent()
    }

    internal fun buildChatPrompt(
        context: HealthAgentContext,
        userMessage: String,
        plan: AgentPlan,
        toolResult: ToolCallResult? = null,
    ): String {
        val includeHealthContext = plan.needsHealthContext
        val healthBlock = if (includeHealthContext) {
            """
            HALO_SIGNAL_CONTEXT:
            ${compactHealthContext(context)}
            """.trimIndent()
        } else {
            "HALO_SIGNAL_CONTEXT: omitted_for_this_route"
        }
        val toolBlock = toolResult?.let {
            """
            web_result:
            tool=${it.tool}
            content=${it.content.take(MAX_TOOL_CHARS)}
            """.trimIndent()
        }.orEmpty()

        return """
            <|im_start|>system
            You are HALO. Final answer only. Use exact provided values only.
            No thinking tags. No JSON. No extra numbers. Stop after the answer.
            <|im_end|>
            <|im_start|>user
            /no_think
            route=${plan.route.wireName}
            guidance=${routeGuidance(plan)}
            $healthBlock
            $toolBlock
            user_message=${userMessage.replace(Regex("\\s+"), " ").trim().take(MAX_MESSAGE_CHARS)}
            <|im_end|>
            <|im_start|>assistant
            HALO_RESPONSE:
        """.trimIndent()
    }

    private fun compactHealthContext(context: HealthAgentContext): String {
        val organ = context.organ
        val visibleMetrics = organ.metrics
            .joinToString("; ") { metric ->
                val delta = metric.deltaText.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                "${metric.label} ${metric.value}$delta"
            }
            .ifBlank { "none" }
        val activeAlert = context.activeAlert?.let { alert ->
            "type=${alert.alertType}; severity=${alert.severity}; source=${alert.source}; evidence=${alert.evidenceJson.take(700)}"
        } ?: "none"
        val watchEvents = context.recentWatchEvents
            .joinToString(" | ") { "${it.receivedAt}: ${it.summary} (${it.source})" }
            .ifBlank { "none" }
        val signalTimeline = context.signalTimeline
            .joinToString(" | ") { "${it.occurredAt}: ${it.signal}=${it.value}; ${it.description}" }
            .ifBlank { "none" }
        return """
            organ=${organ.displayName}
            system=${organ.systemLabel}
            route_context=current ${organ.displayName.lowercase()} view
            recorded_daily_count=${context.recordedDailySummaries.size}
            has_recorded_biometrics=${context.hasRecordedBiometrics}
            visible_card_context=true
            status_card=${if (organ.statusGood) "stable" else "needs_attention"}
            visible_metrics=$visibleMetrics
            week_summary=${organ.sentenceWeek}
            month_summary=${organ.sentenceMonth}
            next_step=${organ.sentenceNextStep}
            active_alert=$activeAlert
            recent_watch_events=$watchEvents
            signal_timeline=$signalTimeline
            policy=visible card values can be described as HALO UI context only; do not call them live watch-recorded medical conclusions.
        """.trimIndent()
    }

    private fun routeGuidance(plan: AgentPlan): String =
        when (plan.route) {
            AgentRoute.Smalltalk ->
                "Reply conversationally in one short sentence. Do not mention biometrics."

            AgentRoute.Clarify ->
                "Ask one concise clarification question. Do not infer health status."

            AgentRoute.LocalContext ->
                "Explain the visible HALO graph/card. If active_alert is present, ground the answer in its evidence and recent_watch_events. Mention at least one exact value."

            AgentRoute.HealthStatus ->
                "Summarize the HALO card status. If active_alert is present, explain why it fired using its evidence. If status_card=needs_attention, do not say normal or stable. Mention exact visible_metrics or alert values. If recorded_daily_count=0, say this is visible HALO card context, not live watch-recorded medical conclusion."

            AgentRoute.NextStep ->
                "Give one bounded next step from next_step. If active_alert is present, include slow down/check symptoms guidance and use urgent care language only for severe symptoms. Mention it is based on HALO card context if recorded_daily_count=0."

            AgentRoute.GeneralHealth ->
                "Answer from HALO context. Mention exact visible_metrics if present."

            AgentRoute.WebResearch ->
                "Use WEB_TOOL_RESULT source titles/domains if provided. If the tool failed or no web result is provided, say the search context is unavailable."
        }

    private const val MAX_TOOL_CHARS = 900
    private const val MAX_PLAN_MESSAGE_CHARS = 160
    private const val MAX_MESSAGE_CHARS = 160
}
