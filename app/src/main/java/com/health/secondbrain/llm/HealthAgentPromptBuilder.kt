package com.health.secondbrain.llm

import com.health.secondbrain.data.HealthAgentContext

object HealthAgentPromptBuilder {
    fun buildChatPrompt(
        context: HealthAgentContext,
        userMessage: String,
        toolResult: ToolCallResult? = null,
    ): String {
        val toolBlock = toolResult?.let {
            """
            WEB_TOOL_RESULT:
            tool=${it.tool}
            content=${it.content.take(MAX_TOOL_CHARS)}
            """.trimIndent()
        }.orEmpty()

        return """
            <|im_start|>system
            You are HALO, an on-device health interpretation assistant.
            Use only the user-provided HEALTH_CONTEXT_JSON and WEB_TOOL_RESULT.
            Think through the facts before answering, but final output must be concise plain text.
            If recorded_daily_count is 0, say recorded biometrics are not available and do not infer personal risk.
            Never use demo, fake, seed, synthetic, or display-only values for personal conclusions.
            Do not diagnose disease or claim medical certainty.
            If a metric is missing, say it is not available.
            Keep the final answer to 2 to 4 short sentences.
            <|im_end|>
            <|im_start|>user
            /think

            HEALTH_CONTEXT_JSON:
            ${context.toPromptJson()}

            $toolBlock

            USER_MESSAGE:
            $userMessage
            <|im_end|>
            <|im_start|>assistant
        """.trimIndent()
    }

    private const val MAX_TOOL_CHARS = 900
}
